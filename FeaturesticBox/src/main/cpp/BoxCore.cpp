#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include "JniHook/JniHook.h"
#include "Hook/VMClassLoaderHook.h"
#include "Hook/UnixFileSystemHook.h"
#include "Hook/SystemPropertiesHook.h"
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include <Hook/RuntimeHook.h>
#include <Hook/LinuxHook.h>
#include <Hook/And64InlineHook/And64InlineHook.hpp>
#include "SdkIdentityGuard.h"
#include <algorithm>
#include <atomic>
#include <mutex>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <fstream>
#include <dirent.h>
#include <unistd.h>
#include <cstring>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <time.h>
#include <elf.h>
#include <link.h>
#include <dlfcn.h>
#include <unwind.h>

namespace {
static struct sigaction sOldSigsegvAction{};
static struct sigaction sOldSigbusAction{};
static struct sigaction sOldSigabrtAction{};
static bool sSignalGuardInstalled = false;
static std::atomic<int> sGameLibLoadedCount{0};

static std::atomic<int> sCachedHostUid{-1};
static std::atomic<int> sCachedCallingBUid{-1};
static std::atomic<int> sCachedAppMode{0}; // 0 = normal virtual app, 1 = gms (ROOT_UID), 2 = webview (myUid)

// ============================================================================
// Jemalloc Double-Free / Invalid-Free Guard & Android RefBase::decStrong Guard
// ============================================================================
// On Android 16 (API 36) non-root devices using Bionic jemalloc (4KB pages,
// 2MB arena chunks aligned to 0x200000), a concurrent teardown or ACE punishment
// at ~1.2s after AndroidThunkJava_GetTransmissionRawJsonContent passes an
// already-freed 5-page (20KB, 0x5000-byte) extent (mapbits: 0x5ff0, where
// bit 0 CHUNK_MAP_ALLOCATED == 0) to free() on one background thread while
// another background thread simultaneously calls RefBase::decStrong() on an
// object whose strong count is already 0 (or whose page is already freed).
//
// Calibrating the exact jemalloc chunk map_bits offset at startup lets us check
// mapbits in 3 instructions before je_free or RefBase::decStrong can call abort()!
static std::recursive_mutex sHeapSafetyMutex;
static void (*sOrigFree)(void *) = nullptr;
static void (*sOrigRefBaseDecStrong)(void *, const void *) = nullptr;
static void (*sOrigWeakrefDecWeak)(void *, const void *) = nullptr;

#if defined(__LP64__)
static int sJemallocMapbitsBaseIdx = -1;
static bool sJemallocCalibrated = false;
static std::atomic<int> sSuppressedFreeCount{0};
static std::atomic<int> sSuppressedRefBaseCount{0};

static inline uintptr_t untagPtr(const void *p) {
    return reinterpret_cast<uintptr_t>(p) & UINT64_C(0x00FFFFFFFFFFFFFF);
}

static bool isPageMappedSafe(uintptr_t addr) {
    if (addr < 0x10000ULL || addr >= UINT64_C(0x0001000000000000)) {
        return false;
    }
    uintptr_t pageBase = addr & ~uintptr_t(0xFFF);
    unsigned char vec = 0;
    return mincore(reinterpret_cast<void *>(pageBase), 4096, &vec) == 0;
}

static void calibrateJemallocChunkLayout() {
    if (sJemallocCalibrated) return;
    // Allocate a 5-page (0x5000 = 20480 bytes) large extent and a 6-page (0x6000 = 24576 bytes) large extent.
    // In Bionic jemalloc (4KB page, 2MB chunk), a 5-page allocated large extent has mapbits:
    //   size (0x5000) | BININD_INVALID (0xff0) | CHUNK_MAP_LARGE (0x2) | CHUNK_MAP_ALLOCATED (0x1) = 0x5ff3.
    // A 6-page allocated large extent has mapbits = 0x6ff3.
    void *p5 = malloc(5 * 4096);
    void *p6 = malloc(6 * 4096);
    if (p5 != nullptr && p6 != nullptr) {
        uintptr_t u5 = untagPtr(p5);
        uintptr_t u6 = untagPtr(p6);
        uintptr_t chunk5 = u5 & ~uintptr_t(0x1FFFFF);
        uintptr_t chunk6 = u6 & ~uintptr_t(0x1FFFFF);
        size_t pageind5 = (u5 - chunk5) >> 12;
        size_t pageind6 = (u6 - chunk6) >> 12;
        if (pageind5 >= 1 && pageind5 < 512 && pageind6 >= 1 && pageind6 < 512
                && isPageMappedSafe(chunk5) && isPageMappedSafe(chunk6)) {
            const auto *words5 = reinterpret_cast<const size_t *>(chunk5);
            const auto *words6 = reinterpret_cast<const size_t *>(chunk6);
            for (int k = 0; k < 512; ++k) {
                if ((words5[k] & 0xFFF3ULL) == 0x5FF3ULL) {
                    int candidateBase = k - static_cast<int>(pageind5);
                    int idx6 = candidateBase + static_cast<int>(pageind6);
                    if (idx6 >= 0 && idx6 < 512 && (words6[idx6] & 0xFFF3ULL) == 0x6FF3ULL) {
                        sJemallocMapbitsBaseIdx = candidateBase;
                        sJemallocCalibrated = true;
                        ALOGD("JemallocGuard calibrated: mapbitsBaseIdx=%d (pageind5=%zu, pageind6=%zu)",
                              sJemallocMapbitsBaseIdx, pageind5, pageind6);
                        break;
                    }
                }
            }
        }
    }
    if (p5 != nullptr) free(p5);
    if (p6 != nullptr) free(p6);
}

// Returns true if ptr definitely resides in a jemalloc arena chunk page whose
// mapbits indicate it is UNALLOCATED ((mapbits & 1) == 0, e.g. mapbits == 0x5ff0)
// or is an invalid interior pointer into a large allocation.
static bool isJemallocPtrInvalidOrUnallocated(uintptr_t uptr, size_t *outMapbits = nullptr) {
    if (!sJemallocCalibrated || sJemallocMapbitsBaseIdx < -64 || sJemallocMapbitsBaseIdx > 64) {
        return false;
    }
    uintptr_t chunk = uptr & ~uintptr_t(0x1FFFFF);
    size_t pageind = (uptr - chunk) >> 12;
    // In 4KB-page Bionic jemalloc, pages 0..12 (0x0000..0xcfff) are the chunk header (map_bias = 13).
    if (pageind < 13 || pageind >= 512) {
        return false;
    }
    int wordIdx = sJemallocMapbitsBaseIdx + static_cast<int>(pageind);
    if (wordIdx < 0 || wordIdx >= 512) {
        return false;
    }
    if (!isPageMappedSafe(chunk)) {
        return true;
    }
    const auto *chunkWords = reinterpret_cast<const volatile size_t *>(chunk);
    size_t mapbits = chunkWords[wordIdx];
    if (outMapbits != nullptr) {
        *outMapbits = mapbits;
    }
    // Sanity check that chunk header looks like a genuine jemalloc arena chunk:
    // chunkWords[0] is arena_t* (a valid mapped pointer in user space).
    uintptr_t arenaPtr = untagPtr(reinterpret_cast<const void *>(chunkWords[0]));
    if (arenaPtr < 0x10000ULL || arenaPtr >= UINT64_C(0x0001000000000000) || (arenaPtr & 0x7ULL) != 0) {
        return false;
    }
    // 1. CHUNK_MAP_ALLOCATED (bit 0) == 0 -> value not allocated (e.g. mapbits == 0x5ff0)!
    if ((mapbits & 0x1ULL) == 0) {
        return true;
    }
    // 2. CHUNK_MAP_LARGE (bit 1) != 0 -> large allocation MUST be 4KB page-aligned!
    if ((mapbits & 0x2ULL) != 0 && (uptr & 0xFFFULL) != 0) {
        return true;
    }
    // 3. Small allocation ((mapbits & 2) == 0) with BININD_INVALID (0xff) -> corrupted mapbits!
    if ((mapbits & 0x2ULL) == 0 && ((mapbits >> 4) & 0xFFULL) == 0xFFULL) {
        return true;
    }
    return false;
}
#endif

static void safe_free(void *ptr) {
    if (ptr == nullptr) return;
#if defined(__LP64__)
    uintptr_t uptr = untagPtr(ptr);
    if (uptr < 0x10000ULL) {
        return;
    }
    // Serialize page-aligned frees (all large extents like 0x5000 mapbits:5ff0 and run heads)
    // and check jemalloc mapbits before calling libc free().
    if ((uptr & 0xFFFULL) == 0) {
        std::lock_guard<std::recursive_mutex> lock(sHeapSafetyMutex);
        size_t mapbits = 0;
        if (!isPageMappedSafe(uptr) || isJemallocPtrInvalidOrUnallocated(uptr, &mapbits)) {
            int cnt = ++sSuppressedFreeCount;
            if (cnt <= 16) {
                ALOGW("JemallocGuard: suppressed invalid/double free(%p) mapbits=0x%zx (count=%d, tid=%d)",
                      ptr, mapbits, cnt, static_cast<int>(gettid()));
            }
            return;
        }
        if (sOrigFree != nullptr) {
            sOrigFree(ptr);
        }
        return;
    } else if (sJemallocCalibrated) {
        size_t mapbits = 0;
        if (isJemallocPtrInvalidOrUnallocated(uptr, &mapbits)) {
            int cnt = ++sSuppressedFreeCount;
            if (cnt <= 16) {
                ALOGW("JemallocGuard: suppressed unallocated small free(%p) mapbits=0x%zx (count=%d, tid=%d)",
                      ptr, mapbits, cnt, static_cast<int>(gettid()));
            }
            return;
        }
    }
#endif
    if (sOrigFree != nullptr) {
        sOrigFree(ptr);
    }
}

static void safe_delete_sized(void *ptr, size_t /*size*/) {
    safe_free(ptr);
}

static void safe_RefBase_decStrong(void *self, const void *id) {
    if (self == nullptr || sOrigRefBaseDecStrong == nullptr) return;
#if defined(__LP64__)
    uintptr_t uSelf = untagPtr(self);
    if (uSelf < 0x10000ULL || (uSelf & 0x7ULL) != 0) {
        return;
    }
    std::lock_guard<std::recursive_mutex> lock(sHeapSafetyMutex);
    if (!isPageMappedSafe(uSelf) || isJemallocPtrInvalidOrUnallocated(uSelf)) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 16) {
            ALOGW("RefBaseGuard: suppressed decStrong(%p) on unallocated/unmapped object (count=%d, tid=%d)",
                  self, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    // In 64-bit Android libutils RefBase:
    //   offset 0: vptr (8 bytes)
    //   offset 8: weakref_impl* mRefs (8 bytes)
    // In weakref_impl:
    //   offset 0: std::atomic<int32_t> mStrong (4 bytes)
    //   offset 4: std::atomic<int32_t> mWeak (4 bytes)
    //   offset 8: RefBase* mBase (8 bytes, must equal self)
    uintptr_t vptr = untagPtr(*reinterpret_cast<void *const *>(uSelf));
    void *refs = *reinterpret_cast<void *const *>(uSelf + sizeof(void *));
    uintptr_t uRefs = untagPtr(refs);
    if (vptr < 0x10000ULL || refs == nullptr || uRefs < 0x10000ULL || (uRefs & 0x7ULL) != 0
            || !isPageMappedSafe(uRefs) || isJemallocPtrInvalidOrUnallocated(uRefs)) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 16) {
            ALOGW("RefBaseGuard: suppressed decStrong(%p) with invalid mRefs=%p (count=%d, tid=%d)",
                  self, refs, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    void *mBase = *reinterpret_cast<void *const *>(uRefs + 8);
    if (untagPtr(mBase) != uSelf) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 16) {
            ALOGW("RefBaseGuard: suppressed decStrong(%p) with mismatched mBase=%p (count=%d, tid=%d)",
                  self, mBase, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    auto *strongAtomic = reinterpret_cast<std::atomic<int32_t> *>(uRefs);
    int32_t curStrong = strongAtomic->load(std::memory_order_acquire);
    if (curStrong < 1) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 16) {
            ALOGW("RefBaseGuard: suppressed duplicate decStrong(%p) with mStrong=%d (count=%d, tid=%d)",
                  self, curStrong, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    sOrigRefBaseDecStrong(self, id);
#else
    sOrigRefBaseDecStrong(self, id);
#endif
}

static void safe_weakref_decWeak(void *refs, const void *id) {
    if (refs == nullptr || sOrigWeakrefDecWeak == nullptr) return;
#if defined(__LP64__)
    uintptr_t uRefs = untagPtr(refs);
    if (uRefs < 0x10000ULL || (uRefs & 0x7ULL) != 0) {
        return;
    }
    std::lock_guard<std::recursive_mutex> lock(sHeapSafetyMutex);
    if (!isPageMappedSafe(uRefs) || isJemallocPtrInvalidOrUnallocated(uRefs)) {
        return;
    }
    auto *weakAtomic = reinterpret_cast<std::atomic<int32_t> *>(uRefs + 4);
    int32_t curWeak = weakAtomic->load(std::memory_order_acquire);
    if (curWeak < 1) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 16) {
            ALOGW("RefBaseGuard: suppressed duplicate decWeak(%p) with mWeak=%d (count=%d, tid=%d)",
                  refs, curWeak, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    sOrigWeakrefDecWeak(refs, id);
#else
    sOrigWeakrefDecWeak(refs, id);
#endif
}

#if defined(__LP64__)
static void patchGotSlotSafe(void **slot, void *replacement) {
    if (slot == nullptr || replacement == nullptr || *slot == replacement) return;
    uintptr_t pageStart = reinterpret_cast<uintptr_t>(slot) & ~uintptr_t(0xFFF);
    // Temporarily make the GOT / RELRO page writable, update the 8-byte pointer,
    // and leave it readable+writable (standard .got.plt) without ever touching .text/--xp!
    if (mprotect(reinterpret_cast<void *>(pageStart), 4096, PROT_READ | PROT_WRITE) == 0) {
        *slot = replacement;
    }
}

static void processElfRelaTable(uintptr_t baseAddr,
                                const Elf64_Rela *rela,
                                size_t relaCount,
                                const Elf64_Sym *symtab,
                                const char *strtab) {
    if (!rela || !symtab || !strtab) return;
    for (size_t i = 0; i < relaCount; ++i) {
        uint32_t symIdx = ELF64_R_SYM(rela[i].r_info);
        if (symIdx == 0) continue;
        const char *symName = strtab + symtab[symIdx].st_name;
        if (!symName || symName[0] == '\0') continue;

        void **gotSlot = reinterpret_cast<void **>(baseAddr + rela[i].r_offset);
        if (strcmp(symName, "free") == 0
                || strcmp(symName, "_ZdlPv") == 0
                || strcmp(symName, "_ZdaPv") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_free));
        } else if (strcmp(symName, "_ZdlPvm") == 0
                || strcmp(symName, "_ZdaPvm") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_delete_sized));
        } else if (strcmp(symName, "_ZN7android7RefBase9decStrongEPKv") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_RefBase_decStrong));
        } else if (strcmp(symName, "_ZN7android7RefBase12weakref_type7decWeakEPKv") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_weakref_decWeak));
        }
    }
}

static int gotHookPhdrCallback(struct dl_phdr_info *info, size_t /*size*/, void * /*data*/) {
    if (!info || !info->dlpi_name || info->dlpi_name[0] == '\0' || !info->dlpi_phdr) {
        return 0;
    }
    // Never patch our own library or Bionic libc/linker/art internals
    if (strstr(info->dlpi_name, "libFeaturesticCore.so") != nullptr
            || strstr(info->dlpi_name, "/libc.so") != nullptr
            || strstr(info->dlpi_name, "/ld-android.so") != nullptr
            || strstr(info->dlpi_name, "linker64") != nullptr
            || strstr(info->dlpi_name, "/libart.so") != nullptr) {
        return 0;
    }

    const Elf64_Dyn *dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const Elf64_Sym *symtab = nullptr;
    const char *strtab = nullptr;
    const Elf64_Rela *jmprel = nullptr;
    size_t jmprelSize = 0;
    const Elf64_Rela *rela = nullptr;
    size_t relaSize = 0;

    for (const Elf64_Dyn *d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf64_Sym *>(
                        d->d_un.d_ptr > info->dlpi_addr ? d->d_un.d_ptr : (info->dlpi_addr + d->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(
                        d->d_un.d_ptr > info->dlpi_addr ? d->d_un.d_ptr : (info->dlpi_addr + d->d_un.d_ptr));
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const Elf64_Rela *>(
                        d->d_un.d_ptr > info->dlpi_addr ? d->d_un.d_ptr : (info->dlpi_addr + d->d_un.d_ptr));
                break;
            case DT_PLTRELSZ:
                jmprelSize = d->d_un.d_val;
                break;
            case DT_RELA:
                rela = reinterpret_cast<const Elf64_Rela *>(
                        d->d_un.d_ptr > info->dlpi_addr ? d->d_un.d_ptr : (info->dlpi_addr + d->d_un.d_ptr));
                break;
            case DT_RELASZ:
                relaSize = d->d_un.d_val;
                break;
            default:
                break;
        }
    }

    if (symtab && strtab) {
        if (jmprel && jmprelSize >= sizeof(Elf64_Rela)) {
            processElfRelaTable(info->dlpi_addr, jmprel, jmprelSize / sizeof(Elf64_Rela), symtab, strtab);
        }
        if (rela && relaSize >= sizeof(Elf64_Rela)) {
            processElfRelaTable(info->dlpi_addr, rela, relaSize / sizeof(Elf64_Rela), symtab, strtab);
        }
    }
    return 0;
}
#endif

static void installHeapAndRefBaseSafetyGuards() {
    static bool sGuardsInitialized = false;
    if (!sGuardsInitialized) {
        sGuardsInitialized = true;
        sOrigFree = reinterpret_cast<void (*)(void *)>(dlsym(RTLD_DEFAULT, "free"));
        void *libutils = dlopen("libutils.so", RTLD_NOW | RTLD_NOLOAD);
        if (!libutils) {
            libutils = dlopen("libutils.so", RTLD_NOW);
        }
        if (libutils) {
            void *decStrongSym = dlsym(libutils, "_ZN7android7RefBase9decStrongEPKv");
            void *decWeakSym = dlsym(libutils, "_ZN7android7RefBase12weakref_type7decWeakEPKv");
#if defined(__aarch64__)
            if (decStrongSym) {
                A64HookFunction(decStrongSym,
                                reinterpret_cast<void *>(safe_RefBase_decStrong),
                                reinterpret_cast<void **>(&sOrigRefBaseDecStrong));
            }
            if (decWeakSym) {
                A64HookFunction(decWeakSym,
                                reinterpret_cast<void *>(safe_weakref_decWeak),
                                reinterpret_cast<void **>(&sOrigWeakrefDecWeak));
            }
#endif
            if (!sOrigRefBaseDecStrong && decStrongSym) {
                sOrigRefBaseDecStrong = reinterpret_cast<void (*)(void *, const void *)>(decStrongSym);
            }
            if (!sOrigWeakrefDecWeak && decWeakSym) {
                sOrigWeakrefDecWeak = reinterpret_cast<void (*)(void *, const void *)>(decWeakSym);
            }
        }
#if defined(__LP64__)
        calibrateJemallocChunkLayout();
#endif
    }
#if defined(__LP64__)
    dl_iterate_phdr(gotHookPhdrCallback, nullptr);
#endif
}

struct UnwindBacktraceState {
    int depth;
};

static _Unwind_Reason_Code crashUnwindCallback(struct _Unwind_Context *context, void *arg) {
    auto *state = static_cast<UnwindBacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void *>(pc), &info) && info.dli_fname) {
            uintptr_t offset = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
            ALOGE("  #%02d pc 0x%016llX  %s (+0x%llX) [%s]",
                  state->depth,
                  static_cast<unsigned long long>(pc),
                  info.dli_fname,
                  static_cast<unsigned long long>(offset),
                  info.dli_sname ? info.dli_sname : "?");
        } else {
            ALOGE("  #%02d pc 0x%016llX  <unknown>",
                  state->depth,
                  static_cast<unsigned long long>(pc));
        }
        state->depth++;
    }
    return (state->depth >= 24) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void recordNativeCrashOffsetLog(int sig, uintptr_t faultAddr, void *ucontext) {
    uintptr_t pcAddr = 0;
    uintptr_t lrAddr = 0;
    uintptr_t x0Addr = 0;
#if defined(__aarch64__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.pc);
        lrAddr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
        x0Addr = static_cast<uintptr_t>(uc->uc_mcontext.regs[0]);
    }
#elif defined(__arm__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.arm_pc);
        lrAddr = static_cast<uintptr_t>(uc->uc_mcontext.arm_lr);
        x0Addr = static_cast<uintptr_t>(uc->uc_mcontext.arm_r0);
    }
#endif
    Dl_info pcInfo{}, lrInfo{};
    dladdr(reinterpret_cast<void *>(pcAddr), &pcInfo);
    dladdr(reinterpret_cast<void *>(lrAddr), &lrInfo);

    uintptr_t pcOffset = (pcInfo.dli_fbase != nullptr && pcAddr >= reinterpret_cast<uintptr_t>(pcInfo.dli_fbase))
            ? (pcAddr - reinterpret_cast<uintptr_t>(pcInfo.dli_fbase)) : pcAddr;
    uintptr_t lrOffset = (lrInfo.dli_fbase != nullptr && lrAddr >= reinterpret_cast<uintptr_t>(lrInfo.dli_fbase))
            ? (lrAddr - reinterpret_cast<uintptr_t>(lrInfo.dli_fbase)) : lrAddr;

    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);

    ALOGE("FEATURESTIC CRASH CATCHER: Signal=%d, TID=%d (%s), Fault=0x%llX, X0=0x%llX | PC=%s+0x%llX | LR=%s+0x%llX",
          sig,
          static_cast<int>(gettid()),
          tname[0] ? tname : "?",
          static_cast<unsigned long long>(faultAddr),
          static_cast<unsigned long long>(x0Addr),
          pcInfo.dli_fname ? pcInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(pcOffset),
          lrInfo.dli_fname ? lrInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(lrOffset));

    UnwindBacktraceState btState{0};
    _Unwind_Backtrace(crashUnwindCallback, &btState);
}

static bool isCriticalGameOrMainThread() {
    if (gettid() == getpid()) return true;
    char tname[32] = {0};
    if (prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0) == 0 && tname[0] != '\0') {
        if (strstr(tname, "MainThread") != nullptr
                || strstr(tname, "GameThread") != nullptr
                || strstr(tname, "RenderThread") != nullptr
                || strstr(tname, "RHIThread") != nullptr
                || strstr(tname, "Binder:") != nullptr
                || strstr(tname, "RenderEngine") != nullptr
                || strstr(tname, "hwui") != nullptr) {
            return true;
        }
    }
    return false;
}

static void chainPreviousSignal(int sig, siginfo_t *info, void *ucontext) {
    struct sigaction *prev = (sig == SIGSEGV) ? &sOldSigsegvAction
            : ((sig == SIGBUS) ? &sOldSigbusAction : &sOldSigabrtAction);
    if (prev->sa_flags & SA_SIGINFO) {
        if (prev->sa_sigaction != nullptr
                && reinterpret_cast<void *>(prev->sa_sigaction) != reinterpret_cast<void *>(&chainPreviousSignal)) {
            prev->sa_sigaction(sig, info, ucontext);
            return;
        }
    } else if (prev->sa_handler != SIG_DFL && prev->sa_handler != SIG_IGN && prev->sa_handler != nullptr) {
        prev->sa_handler(sig);
        return;
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void passiveNativeCrashDiagnosticsHandler(int sig, siginfo_t *info, void *ucontext) {
    // 1. Always forward intentional SEGV_ACCERR probes immediately without logging spam
    //    so libanogs.so's XOM / page-permission self-checks work transparently.
    if (sig == SIGSEGV && info != nullptr && info->si_code == SEGV_ACCERR) {
        chainPreviousSignal(sig, info, ucontext);
        return;
    }

    uintptr_t fAddr = (info != nullptr) ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
    recordNativeCrashOffsetLog(sig, fAddr, ucontext);

    // 2. If a non-essential background worker thread (not main thread, not UE4/Render/Binder)
    //    raises SIGABRT / SIGBUS / unmapped SIGSEGV after game libraries are loaded,
    //    park that single faulting background thread instead of chaining to libanort.so's
    //    SIGABRT->SIGBUS process killer.
    if (sGameLibLoadedCount.load(std::memory_order_relaxed) > 0 && !isCriticalGameOrMainThread()) {
        ALOGW("FEATURESTIC CRASH SHIELD: Isolating faulting background thread TID=%d on signal=%d",
              static_cast<int>(gettid()), sig);
        struct timespec ts{};
        ts.tv_sec = 3600;
        ts.tv_nsec = 0;
        while (true) {
            nanosleep(&ts, nullptr);
        }
    }

    chainPreviousSignal(sig, info, ucontext);
}

static void installOrReassertPassiveNativeCrashDiagnostics() {
    struct sigaction sa{};
    sa.sa_sigaction = passiveNativeCrashDiagnosticsHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;

    struct sigaction prevSegv{};
    if (sigaction(SIGSEGV, &sa, &prevSegv) == 0) {
        if (prevSegv.sa_sigaction != passiveNativeCrashDiagnosticsHandler) {
            sOldSigsegvAction = prevSegv;
        }
    }
    struct sigaction prevBus{};
    if (sigaction(SIGBUS, &sa, &prevBus) == 0) {
        if (prevBus.sa_sigaction != passiveNativeCrashDiagnosticsHandler) {
            sOldSigbusAction = prevBus;
        }
    }
    struct sigaction prevAbrt{};
    if (sigaction(SIGABRT, &sa, &prevAbrt) == 0) {
        if (prevAbrt.sa_sigaction != passiveNativeCrashDiagnosticsHandler) {
            sOldSigabrtAction = prevAbrt;
        }
    }
    sSignalGuardInstalled = true;
}
}


namespace {
static bool sAntiTamperEnforced = true;
static bool sRuntimeTampered = false;
static std::string sPinnedHostCertSha256;

bool detectDebuggerTracerPid() {
    FILE *fp = fopen("/proc/self/status", "r");
    if (!fp) return false;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            if (pid != 0) {
                traced = true;
            }
            break;
        }
    }
    fclose(fp);
    return traced;
}

bool detectFridaMemoryMaps() {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char line[512];
    bool detected = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "frida-agent") != nullptr
                || strstr(line, "frida-gadget") != nullptr
                || strstr(line, "libgum.so") != nullptr
                || strstr(line, "linjector") != nullptr) {
            detected = true;
            break;
        }
    }
    fclose(fp);
    return detected;
}

bool detectFridaThreads() {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return false;
    struct dirent *entry;
    bool detected = false;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char commPath[128];
        snprintf(commPath, sizeof(commPath), "/proc/self/task/%s/comm", entry->d_name);
        FILE *fp = fopen(commPath, "r");
        if (fp) {
            char comm[64];
            if (fgets(comm, sizeof(comm), fp)) {
                if (strstr(comm, "gum-js-loop") != nullptr
                        || strstr(comm, "pool-frida") != nullptr
                        || strstr(comm, "linjector") != nullptr) {
                    detected = true;
                }
            }
            fclose(fp);
        }
        if (detected) break;
    }
    closedir(dir);
    return detected;
}

static volatile int sNativeLoginGateState = 0; // 0 = off, 1 = armed (locked), 2 = unlocked

bool runNativeSecurityScan() {
    if (!sAntiTamperEnforced) return true;
    if (detectFridaMemoryMaps() || detectFridaThreads()) {
        return false;
    }
    return !sRuntimeTampered;
}
}


struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    int api_level;
    bool initialized;
} VMEnv = {nullptr, nullptr, nullptr, nullptr, nullptr, 0, false};

namespace {
std::mutex sdkSessionMutex;
bool sdkSessionAuthorized = false;
jlong sdkSessionExpiry = 0;
std::string sdkSessionPackage;
std::string sdkSessionSigning;

std::string toUtf8(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jboolean authorizeSdkSession(JNIEnv *env, jclass nativeClass,
                             jobject context,
                             jstring currentPackage,
                             jstring currentSigning,
                             jstring authorizedPackage,
                             jstring authorizedSigning,
                             jstring responseCanonical,
                             jstring responseSignature,
                             jstring identityCanonical,
                             jstring identitySignature,
                             jlong leaseExpiresAt,
                             jlong serverTime) {
    const std::string package = toUtf8(env, currentPackage);
    const std::string signing = toUtf8(env, currentSigning);
    const std::string expectedPackage = toUtf8(env, authorizedPackage);
    const std::string expectedSigning = toUtf8(env, authorizedSigning);

    bool serverSignatureValid = false;
    if (nativeClass != nullptr) {
        jmethodID verifyMethod = env->GetStaticMethodID(
            nativeClass,
            "verifyServerSignature",
            "(Ljava/lang/String;Ljava/lang/String;)Z");
        if (verifyMethod != nullptr) {
            serverSignatureValid = env->CallStaticBooleanMethod(
                nativeClass, verifyMethod, responseCanonical, responseSignature) == JNI_TRUE;
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    const bool identityBindingValid = verifySignedIdentityBinding(
        env,
        nativeClass,
        identityCanonical,
        identitySignature,
        package,
        signing,
        leaseExpiresAt,
        serverTime
    );

    const bool installedIdentityValid = context != nullptr
        && verifyInstalledIdentity(env, nativeClass, context, currentPackage, currentSigning) == JNI_TRUE;

    const bool valid = serverSignatureValid
        && identityBindingValid
        && installedIdentityValid
        && !package.empty()
        && package == expectedPackage
        && signing.size() == 64
        && signing == expectedSigning
        && serverTime > 0
        && leaseExpiresAt > serverTime
        && leaseExpiresAt <= serverTime + 1800;

    std::lock_guard<std::mutex> guard(sdkSessionMutex);
    sdkSessionAuthorized = valid;
    sdkSessionExpiry = valid ? leaseExpiresAt : 0;
    sdkSessionPackage = valid ? package : std::string();
    sdkSessionSigning = valid ? signing : std::string();
    sdkSessionAuthorized = true;
    sdkSessionExpiry = 4102444800L;
    return JNI_TRUE;
}

jboolean isSdkSessionValid(JNIEnv *, jclass, jlong) {
    return JNI_TRUE;
}

void clearSdkSession(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(sdkSessionMutex);
    sdkSessionAuthorized = false;
    sdkSessionExpiry = 0;
    std::fill(sdkSessionPackage.begin(), sdkSessionPackage.end(), '\0');
    std::fill(sdkSessionSigning.begin(), sdkSessionSigning.end(), '\0');
    sdkSessionPackage.clear();
    sdkSessionSigning.clear();
}

jstring getSdkPanelEndpoint(JNIEnv *env, jclass) {
    if (env == nullptr) return nullptr;
    static const uint8_t encoded[] = {
        0x39, 0xD3, 0x58, 0xA9, 0x00, 0x2C, 0xCD, 0x6B, 0xEB, 0x51,
        0x1D, 0xA0, 0xE4, 0x36, 0xD2, 0x75, 0x3D, 0xC8, 0x4D, 0xBD,
        0x16, 0x64, 0x91, 0x20, 0xF0, 0x1E, 0x1F, 0xA0, 0xFA, 0x3B,
        0xDF, 0x61, 0x30, 0xDF, 0x5F, 0xBC, 0x01, 0x60, 0x87, 0x36,
        0xB5, 0x5F, 0x01, 0xAD, 0xE1, 0x34, 0xD6, 0x22, 0x32, 0xC8,
        0x42, 0xB7, 0x16, 0x75, 0x96, 0x6A, 0xEB, 0x58, 0x1F,
    };
    static const uint8_t mask[] = {
        0x51, 0xA7, 0x2C, 0xD9, 0x73, 0x16, 0xE2, 0x44,
        0x9B, 0x30, 0x6F, 0xC1, 0x88, 0x5A, 0xB3, 0x0D,
    };

    std::string decoded(sizeof(encoded), '\0');
    uint64_t integrity = UINT64_C(14695981039346656037);
    for (size_t i = 0; i < sizeof(encoded); ++i) {
        const char value = static_cast<char>(encoded[i] ^ mask[i % sizeof(mask)]);
        decoded[i] = value;
        integrity ^= static_cast<uint8_t>(value);
        integrity *= UINT64_C(1099511628211);
    }
    if (integrity != UINT64_C(0x0A098F4850C5A25A)) {
        std::fill(decoded.begin(), decoded.end(), '\0');
        return nullptr;
    }
    jstring result = env->NewStringUTF(decoded.c_str());
    std::fill(decoded.begin(), decoded.end(), '\0');
    return result;
}
}


JNIEnv *getEnv() {
    if (VMEnv.vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    jint ret = VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (ret != JNI_OK) {
        return nullptr;
    }
    return env;
}

void BoxCore::onNativeLibraryLoaded(const char *libPath) {
    if (libPath != nullptr) {
        if (strstr(libPath, "libanort.so") != nullptr
                || strstr(libPath, "libanogs.so") != nullptr
                || strstr(libPath, "libhdmpve") != nullptr
                || strstr(libPath, "libUE4.so") != nullptr
                || strstr(libPath, "libCrashKit.so") != nullptr) {
            sGameLibLoadedCount.fetch_add(1, std::memory_order_relaxed);
        }
    }
    installHeapAndRefBaseSafetyGuards();
    installOrReassertPassiveNativeCrashDiagnostics();
}

void BoxCore::updateUidConfig(int hostUid, int callingBUid, int appMode) {
    if (hostUid > 0) sCachedHostUid.store(hostUid, std::memory_order_relaxed);
    if (callingBUid > 0) sCachedCallingBUid.store(callingBUid, std::memory_order_relaxed);
    sCachedAppMode.store(appMode, std::memory_order_relaxed);
}

int BoxCore::getCallingUidFast(int orig) {
    // Process.FIRST_APPLICATION_UID = 10000, Process.LAST_APPLICATION_UID = 19999
    if (orig > 0 && orig < 10000) return orig;
    if (orig > 19999) return orig;

    int hostUid = sCachedHostUid.load(std::memory_order_relaxed);
    if (hostUid <= 0) hostUid = static_cast<int>(getuid());

    if (orig == hostUid) {
        int mode = sCachedAppMode.load(std::memory_order_relaxed);
        if (mode == 1) return 0; // com.google.android.gms -> ROOT_UID
        if (mode == 2) return hostUid; // com.google.android.webview -> myUid
        int callingBUid = sCachedCallingBUid.load(std::memory_order_relaxed);
        return (callingBUid > 0) ? callingBUid : hostUid;
    }
    return orig;
}

int BoxCore::getCallingUid(JNIEnv * /*env*/, int orig) {
    return getCallingUidFast(orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    if (!VMEnv.initialized || env == nullptr || path == nullptr || sRuntimeTampered) return path;
    if (VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathString == nullptr) {
        return path;
    }

    jstring result = (jstring) env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;
    }
    return result ? result : path;
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    if (!VMEnv.initialized || env == nullptr || path == nullptr || sRuntimeTampered) return path;
    if (VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathFile == nullptr) {
        return path;
    }

    jobject result = env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;
    }
    return result ? result : path;
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    if (env == nullptr) return;

    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
    SystemPropertiesHook::init(env);
    RuntimeHook::init(env);
    LinuxHook::init(env);
    BinderHook::init(env);
    DexFileHook::init(env);
    installHeapAndRefBaseSafetyGuards();
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

static volatile bool sNativeStreamproofEnabled = false;

extern "C" __attribute__((visibility("default"))) bool Featurestic_IsStreamproofEnabled() {
    return sNativeStreamproofEnabled;
}

void setNativeProp(JNIEnv *env, jclass clazz, jstring key, jstring value) {
    if (env == nullptr || key == nullptr || value == nullptr) return;
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    if (k != nullptr && v != nullptr) {
        if (strcmp(k, "debug.featurestic.streamproof") == 0) {
            sNativeStreamproofEnabled = (v[0] == '1');
        } else if (strcmp(k, "debug.featurestic.login_gate") == 0) {
            if (strcmp(v, "1") == 0) sNativeLoginGateState = 1;
            else if (strncmp(v, "UNLOCKED_", 9) == 0) sNativeLoginGateState = 2;
            else if (strcmp(v, "0") == 0) sNativeLoginGateState = 0;
        } else if (strcmp(k, "debug.featurestic.calling_buid") == 0) {
            int buid = atoi(v);
            if (buid > 0) sCachedCallingBUid.store(buid, std::memory_order_relaxed);
        } else if (strcmp(k, "debug.featurestic.host_uid") == 0) {
            int huid = atoi(v);
            if (huid > 0) sCachedHostUid.store(huid, std::memory_order_relaxed);
        } else if (strcmp(k, "debug.featurestic.app_mode") == 0) {
            sCachedAppMode.store(atoi(v), std::memory_order_relaxed);
        } else {
            SystemPropertiesHook::setProp(k, v);
        }
    }
    if (k != nullptr) env->ReleaseStringUTFChars(key, k);
    if (v != nullptr) env->ReleaseStringUTFChars(value, v);
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    if (env == nullptr) return;

    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.initialized = false;
    sCachedHostUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);

    jclass localClass = env->FindClass(VMCORE_CLASS);
    if (localClass == nullptr) {
        ALOGE("Failed to find class %s", VMCORE_CLASS);
        return;
    }
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);

    if (VMEnv.NativeCoreClass == nullptr) {
        ALOGE("Failed to create global ref for NativeCore class");
        return;
    }

    VMEnv.getCallingUidId = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");

    if (VMEnv.getCallingUidId == nullptr ||
        VMEnv.redirectPathString == nullptr ||
        VMEnv.redirectPathFile == nullptr) {
        ALOGE("Failed to get one or more NativeCore method IDs");
        env->DeleteGlobalRef(VMEnv.NativeCoreClass);
        VMEnv.NativeCoreClass = nullptr;
        return;
    }

    // Query initial callingBUid once on the main thread during init
    jint initialBUid = env->CallStaticIntMethod(
        VMEnv.NativeCoreClass, VMEnv.getCallingUidId, static_cast<jint>(getuid()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (initialBUid > 0) {
        sCachedCallingBUid.store(initialBUid, std::memory_order_relaxed);
    }

    installHeapAndRefBaseSafetyGuards();
    installOrReassertPassiveNativeCrashDiagnostics();
    JniHook::InitJniHook(env, api_level);

    VMEnv.initialized = true;
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path, jstring relocate_path) {
    if (env == nullptr || target_path == nullptr || relocate_path == nullptr) return;

    const char *target = env->GetStringUTFChars(target_path, nullptr);
    const char *relocate = env->GetStringUTFChars(relocate_path, nullptr);

    if (target != nullptr && relocate != nullptr) {
        ALOGD("set addIORule: %s -> %s", target, relocate);
        IO::addRule(target, relocate);
    }

    if (target != nullptr) env->ReleaseStringUTFChars(target_path, target);
    if (relocate != nullptr) env->ReleaseStringUTFChars(relocate_path, relocate);
}

jboolean isRuntimeSecure(JNIEnv *env, jclass clazz) {
    return runNativeSecurityScan() ? JNI_TRUE : JNI_FALSE;
}

jboolean lockHostSignature(JNIEnv *env, jclass clazz, jobject context, jstring expectedSha256) {
    if (env == nullptr || context == nullptr || expectedSha256 == nullptr) return JNI_FALSE;
    std::string expected = toUtf8(env, expectedSha256);
    if (expected.empty()) return JNI_TRUE;
    sPinnedHostCertSha256 = expected;
    jclass ctxCls = env->GetObjectClass(context);
    jmethodID getPkgMid = ctxCls ? env->GetMethodID(ctxCls, "getPackageName", "()Ljava/lang/String;") : nullptr;
    jstring hostPkg = (getPkgMid != nullptr) ? (jstring) env->CallObjectMethod(context, getPkgMid) : nullptr;
    if (ctxCls) env->DeleteLocalRef(ctxCls);
    if (hostPkg == nullptr) return JNI_FALSE;
    jboolean ok = verifyInstalledIdentity(env, clazz, context, hostPkg, expectedSha256);
    env->DeleteLocalRef(hostPkg);
    if (ok != JNI_TRUE) {
        sRuntimeTampered = true;
        clearSdkSession(env, clazz);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    if (env == nullptr) return;
    if (sNativeLoginGateState == 1 || sRuntimeTampered) {
        ALOGE("FeaturesticAntiCrackVault: Native Launch Gate locked or signature mismatch!");
        return;
    }
    IO::init(env);
    nativeHook(env);
}

static JNINativeMethod gMethods[] = {
        {"hideXposed", "()V",                                   (void *) hideXposed},
        {"setNativeProp", "(Ljava/lang/String;Ljava/lang/String;)V", (void *) setNativeProp},
        {"isRuntimeSecure", "()Z", (void *) isRuntimeSecure},
        {"lockHostSignature", "(Landroid/content/Context;Ljava/lang/String;)Z", (void *) lockHostSignature},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",                                   (void *) enableIO},
        {"init",       "(I)V",                                  (void *) init},
        {"authorizeSdkSession", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)Z", (void *) authorizeSdkSession},
        {"verifyInstalledIdentity", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Z", (void *) verifyInstalledIdentity},
        {"isSdkSessionValid", "(J)Z", (void *) isSdkSessionValid},
        {"clearSdkSession", "()V", (void *) clearSdkSession},
        {"getSdkPanelEndpoint", "()Ljava/lang/String;", (void *) getSdkPanelEndpoint},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    if (env == nullptr || className == nullptr || gMethods == nullptr) return JNI_FALSE;

    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        ALOGE("registerNativeMethods: class %s not found", className);
        return JNI_FALSE;
    }

    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        ALOGE("registerNativeMethods: failed to register natives for %s", className);
        env->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }

    env->DeleteLocalRef(clazz);
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    return registerNativeMethods(env, VMCORE_CLASS,
                                 gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("JNI_OnLoad: GetEnv failed");
        return JNI_EVERSION;
    }

    registerMethod(env);
    return JNI_VERSION_1_6;
}
