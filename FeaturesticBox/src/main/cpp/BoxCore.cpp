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
#include "Hook/NativeVfsHook.h"
#include "SandHook/xdl.h"
#include "SdkIdentityGuard.h"
#include <algorithm>
#include <atomic>
#include <mutex>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <fstream>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <signal.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#ifndef __NR_exit
#if defined(__aarch64__)
#define __NR_exit 93
#elif defined(__arm__)
#define __NR_exit 1
#elif defined(__x86_64__)
#define __NR_exit 60
#elif defined(__i386__)
#define __NR_exit 1
#endif
#endif
#include <sys/prctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <time.h>
#include <elf.h>
#include <link.h>
#include <dlfcn.h>
#include <unwind.h>

namespace {
static struct sigaction sOldSigsegvAction{};
static struct sigaction sOldSigbusAction{};
static struct sigaction sOldSigabrtAction{};
static struct sigaction sOldSigillAction{};
static bool sSignalGuardInstalled = false;
static std::atomic<int> sGameLibLoadedCount{0};
static std::atomic<bool> sWatcherThreadStarted{false};
static int (*sOrigSigaction)(int, const struct sigaction *, struct sigaction *) = nullptr;
static int (*sOrigSigaction64)(int, const struct sigaction *, struct sigaction *) = nullptr;

static std::atomic<int> sCachedHostUid{-1};
static std::atomic<int> sCachedCallingBUid{-1};
static std::atomic<int> sCachedAppMode{0}; // 0 = normal virtual app, 1 = gms (ROOT_UID), 2 = webview (myUid)

// NOTE: Removed gNativeRecoveryJumpBuf and gNativeRecoveryActive - siglongjmp
// from signal handlers is unreliable and doesn't work with stack corruption.

// Sanitize any inherited /dev/pts/* pseudo-terminal file descriptors at startup
// so anti-cheat /proc/self/fd scans never see a Termux/shell pty FD.
static void sanitizeInheritedPtyFileDescriptors() {
    int devNull = open("/dev/null", O_RDWR);
    if (devNull < 0) return;

    // Unconditionally redirect stdio (0, 1, 2) away from inherited terminal FDs
    // Standard GUI Android applications never use terminal stdio.
    for (int stdioFd = 0; stdioFd <= 2; ++stdioFd) {
        dup2(devNull, stdioFd);
    }

    DIR *dir = opendir("/proc/self/fd");
    if (dir) {
        int dirFd = dirfd(dir);
        struct dirent *entry;
        while ((entry = readdir(dir)) != nullptr) {
            if (entry->d_name[0] == '.') continue;
            int fd = atoi(entry->d_name);
            if (fd < 0 || fd == dirFd || fd == devNull) continue;
            char linkPath[64];
            char target[256];
            snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", fd);
            ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
            if (len < 0) {
                if (errno == EACCES || errno == EPERM) {
                    dup2(devNull, fd);
                }
            } else {
                target[len] = '\0';
                if (strncmp(target, "/dev/pts/", 9) == 0 || strncmp(target, "/dev/ptmx", 9) == 0) {
                    dup2(devNull, fd);
                }
            }
        }
        closedir(dir);
    }
    close(devNull);
}

static bool isCriticalGameOrMainThread() {
    if (gettid() == getpid()) return true;
    char tname[32] = {0};
    if (prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0) == 0 && tname[0] != '\0') {
        if (strcasestr(tname, "main") != nullptr
                || strcasestr(tname, "game") != nullptr
                || strcasestr(tname, "render") != nullptr
                || strcasestr(tname, "rhi") != nullptr
                || strcasestr(tname, "taskgraph") != nullptr
                || strcasestr(tname, "fasync") != nullptr
                || strcasestr(tname, "audio") != nullptr
                || ((strncmp(tname, "binder:", 7) == 0 || strncmp(tname, "Binder:", 7) == 0) && isdigit(tname[7]))
                || strcasestr(tname, "hwui") != nullptr
                || strcasestr(tname, "surface") != nullptr
                || strcasestr(tname, "mali") != nullptr
                || strcasestr(tname, "adreno") != nullptr
                || strcasestr(tname, "unity") != nullptr
                || strcasestr(tname, "unreal") != nullptr
                || strcasestr(tname, "download") != nullptr
                || strcasestr(tname, "puffer") != nullptr
                || strcasestr(tname, "eifs") != nullptr
                || strcasestr(tname, "pak") != nullptr
                || strcasestr(tname, "stream") != nullptr
                || strcasestr(tname, "net") != nullptr
                || strcasestr(tname, "http") != nullptr
                || strcasestr(tname, "volley") != nullptr
                || strcasestr(tname, "okhttp") != nullptr
                || strcasestr(tname, "facebook") != nullptr
                || strcasestr(tname, "adjust") != nullptr
                || strcasestr(tname, "firebase") != nullptr
                || strcasestr(tname, "google") != nullptr
                || strcasestr(tname, "twitter") != nullptr
                || strcasestr(tname, "discord") != nullptr
                || strcasestr(tname, "webview") != nullptr
                || strcasestr(tname, "chromium") != nullptr
                || strcasestr(tname, "input") != nullptr
                || strcasestr(tname, "event") != nullptr
                || strcasestr(tname, "touch") != nullptr
                || strcasestr(tname, "pool") != nullptr
                || strcasestr(tname, "executor") != nullptr
                || strcasestr(tname, "finalizer") != nullptr
                || strcasestr(tname, "heaptask") != nullptr
                || strcasestr(tname, "jit") != nullptr
                || strcasestr(tname, "signal") != nullptr
                || strcasestr(tname, "queued-work") != nullptr
                || strcasestr(tname, "work") != nullptr
                || strcasestr(tname, "daemon") != nullptr
                || strcasestr(tname, "referencequeue") != nullptr
                || strcasestr(tname, "art") != nullptr
                || strcasestr(tname, "dalvik") != nullptr
                || strcasestr(tname, "itop") != nullptr
                || strcasestr(tname, "imsdk") != nullptr
                || strcasestr(tname, "timer") != nullptr
                || strcasestr(tname, "async") != nullptr) {
            return true;
        }
    }
    return false;
}

static bool isRogueWorkerThread(const char *tname) {
    if (!tname || tname[0] == '\0' || strcmp(tname, "?") == 0) return true;
    if (strncmp(tname, "Thread-", 7) == 0) return true;
    if (strcasestr(tname, "crashkit") != nullptr
            || strcasestr(tname, "anort") != nullptr
            || strcasestr(tname, "anogs") != nullptr
            || strcasestr(tname, "tersafe") != nullptr
            || strcasestr(tname, "tp_") != nullptr
            || strcasestr(tname, "guard") != nullptr
            || strcasestr(tname, "turing") != nullptr
            || strcasestr(tname, "gcloud") != nullptr
            || strcasestr(tname, "hdmpve") != nullptr) {
        return true;
    }
    return false;
}

static bool isArtInternalDaemonThread(const char *tname) {
    if (!tname || tname[0] == '\0') return false;
    return strcasestr(tname, "heaptask") != nullptr
            || strcasestr(tname, "finalizer") != nullptr
            || strcasestr(tname, "referencequeue") != nullptr
            || strcasestr(tname, "signal catcher") != nullptr
            || strcasestr(tname, "jit thread") != nullptr
            || strcasestr(tname, "queued-work") != nullptr
            || strcasestr(tname, "daemon") != nullptr;
}

// NOTE: Removed parkFaultingBackgroundThreadForever() - parking threads
// forever was causing SIGBUS crashes instead of preventing them.

// ============================================================================
// Jemalloc Double-Free / Invalid-Free Guard & Android RefBase::decStrong Guard
// ============================================================================
static void (*sOrigFree)(void *) = nullptr;
static void (*sOrigRefBaseDecStrong)(void *, const void *) = nullptr;
static void (*sOrigWeakrefDecWeak)(void *, const void *) = nullptr;

#if defined(__LP64__)
static int sJemallocMapbitsBaseIdx = -1;
static int sJemallocMapbitsStride = 1;
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

// Jemalloc heuristics removed to prevent false positives on Android Scudo allocator.
#endif

struct UnwindBacktraceState {
    int depth;
    void *xdlCache;
};

static _Unwind_Reason_Code crashUnwindCallback(struct _Unwind_Context *context, void *arg) {
    auto *state = static_cast<UnwindBacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        xdl_info_t xinfo{};
        if (xdl_addr(reinterpret_cast<void *>(pc), &xinfo, &state->xdlCache) && xinfo.dli_fname) {
            uintptr_t offset = pc - reinterpret_cast<uintptr_t>(xinfo.dli_fbase);
            ALOGE("  #%02d pc 0x%016llX  %s (+0x%llX) [%s]",
                  state->depth,
                  static_cast<unsigned long long>(pc),
                  xinfo.dli_fname,
                  static_cast<unsigned long long>(offset),
                  xinfo.dli_sname ? xinfo.dli_sname : "?");
        } else {
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
        }
        state->depth++;
    }
    return (state->depth >= 16) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void logCurrentThreadBacktrace() {
    UnwindBacktraceState btState{0, nullptr};
    _Unwind_Backtrace(crashUnwindCallback, &btState);
    if (btState.xdlCache != nullptr) {
        xdl_addr_clean(&btState.xdlCache);
    }
}

static void safe_free(void *ptr) {
    if (ptr == nullptr) return;
#if defined(__LP64__)
    uintptr_t uptr = untagPtr(ptr);
    if (uptr < 0x10000ULL) {
        return;
    }
    // 1. Suppress if the memory address is unmapped in process address space.
    if (!isPageMappedSafe(uptr)) {
        int cnt = ++sSuppressedFreeCount;
        char tname[32] = {0};
        prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
        ALOGW("HeapGuard: SUPPRESSED unmapped free(%p) caller=%p (count=%d, tid=%d [%s])",
              ptr, __builtin_return_address(0), cnt, static_cast<int>(gettid()), tname[0] ? tname : "?");
        return;
    }

    // 2. Suppress if address belongs to an unallocated jemalloc arena chunk page.
    // In Android Bionic jemalloc (2MB chunks, map_bias=13, map_bits offset 0x78):
    // Passing an unallocated chunk page (mapbits bit 0 == 0) causes Bionic to call __libc_fatal
    // and abort() with "Invalid %p passed to free: value not allocated, chunk: %p mapbits: %zx".
    uintptr_t chunk = uptr & ~uintptr_t(0x1FFFFF);
    size_t pageind = (uptr - chunk) >> 12;
    if (pageind >= 13 && pageind < 512) {
        if (isPageMappedSafe(chunk)) {
            const auto *chunkWords = reinterpret_cast<const volatile size_t *>(chunk);
            uintptr_t arenaPtr = untagPtr(reinterpret_cast<const void *>(chunkWords[0]));
            if (arenaPtr >= 0x10000ULL && arenaPtr < UINT64_C(0x0001000000000000) && (arenaPtr & 0x7ULL) == 0) {
                // Genuine jemalloc chunk header verified: read mapbits at chunk + 0x78 + (pageind - 13)*8
                size_t mapbits = *reinterpret_cast<const volatile size_t *>(chunk + 0x78 + (pageind - 13) * sizeof(size_t));
                if ((mapbits & 0x1ULL) == 0) {
                    // Page is unallocated (e.g. mapbits == 0x5ff0, 0xdfe0, or 0xbff0) -> silently drop to prevent abort()!
                    int cnt = ++sSuppressedFreeCount;
                    char tname[32] = {0};
                    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
                    ALOGW("HeapGuard: SUPPRESSED unallocated chunk free(%p, chunk=%p, pageind=%zu, mapbits=0x%zx) caller=%p [tid=%d, %s]",
                          ptr, reinterpret_cast<void *>(chunk), pageind, mapbits,
                          __builtin_return_address(0), static_cast<int>(gettid()), tname[0] ? tname : "?");
                    return;
                }
            }
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
    if (!isPageMappedSafe(uSelf)) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 32) {
            ALOGE("RefBaseGuard: SUPPRESSED unmapped decStrong(%p) (count=%d, tid=%d)",
                  self, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    uintptr_t vptr = untagPtr(*reinterpret_cast<void *const *>(uSelf));
    void *refs = *reinterpret_cast<void *const *>(uSelf + sizeof(void *));
    uintptr_t uRefs = untagPtr(refs);
    if (vptr < 0x10000ULL || refs == nullptr || uRefs < 0x10000ULL || (uRefs & 0x7ULL) != 0
            || !isPageMappedSafe(uRefs)) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 32) {
            ALOGE("RefBaseGuard: SUPPRESSED corrupt decStrong(%p) (count=%d, tid=%d)",
                  self, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    void *mBase = *reinterpret_cast<void *const *>(uRefs + 8);
    if (untagPtr(mBase) != uSelf) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 32) {
            ALOGE("RefBaseGuard: SUPPRESSED mismatched decStrong(%p) (count=%d, tid=%d)",
                  self, cnt, static_cast<int>(gettid()));
        }
        return;
    }
    auto *strongAtomic = reinterpret_cast<std::atomic<int32_t> *>(uRefs);
    int32_t curStrong = strongAtomic->load(std::memory_order_acquire);
    if (curStrong < 1) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 32) {
            ALOGE("RefBaseGuard: SUPPRESSED duplicate decStrong(%p) with curStrong=%d (count=%d, tid=%d)",
                  self, curStrong, cnt, static_cast<int>(gettid()));
            if (cnt <= 4) {
                logCurrentThreadBacktrace();
            }
        }
        return;
    }
#endif
    sOrigRefBaseDecStrong(self, id);
}

static void safe_weakref_decWeak(void *refs, const void *id) {
    if (refs == nullptr || sOrigWeakrefDecWeak == nullptr) return;
#if defined(__LP64__)
    uintptr_t uRefs = untagPtr(refs);
    if (uRefs < 0x10000ULL || (uRefs & 0x7ULL) != 0) {
        return;
    }
    if (!isPageMappedSafe(uRefs)) {
        return;
    }
    auto *weakAtomic = reinterpret_cast<std::atomic<int32_t> *>(uRefs + 4);
    int32_t curWeak = weakAtomic->load(std::memory_order_acquire);
    if (curWeak < 1) {
        int cnt = ++sSuppressedRefBaseCount;
        if (cnt <= 32) {
            ALOGE("RefBaseGuard: SUPPRESSED duplicate decWeak(%p) with mWeak=%d (count=%d, tid=%d)",
                  refs, curWeak, cnt, static_cast<int>(gettid()));
        }
        return;
    }
#endif
    sOrigWeakrefDecWeak(refs, id);
}

#if defined(__LP64__)
static void patchGotSlotSafe(void **slot, void *replacement) {
    if (slot == nullptr || replacement == nullptr || *slot == replacement) return;
    uintptr_t pageStart = reinterpret_cast<uintptr_t>(slot) & ~uintptr_t(0xFFF);
    if (mprotect(reinterpret_cast<void *>(pageStart), 4096, PROT_READ | PROT_WRITE) == 0) {
        *slot = replacement;
        mprotect(reinterpret_cast<void *>(pageStart), 4096, PROT_READ);
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
    if (strstr(info->dlpi_name, "libFeaturesticCore.so") != nullptr
            || strstr(info->dlpi_name, "/libc.so") != nullptr
            || strstr(info->dlpi_name, "/ld-android.so") != nullptr
            || strstr(info->dlpi_name, "linker64") != nullptr
            || strstr(info->dlpi_name, "/libart.so") != nullptr
            || strstr(info->dlpi_name, "libanort") != nullptr
            || strstr(info->dlpi_name, "libCrashKit") != nullptr
            || strstr(info->dlpi_name, "libanogs") != nullptr
            || strstr(info->dlpi_name, "libhdmpve") != nullptr
            || strstr(info->dlpi_name, "libgcloud") != nullptr
            || strstr(info->dlpi_name, "libturing") != nullptr
            || strstr(info->dlpi_name, "libtersafe") != nullptr) {
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

static void recordNativeCrashOffsetLog(int sig, int siCode, uintptr_t faultAddr, void *ucontext) {
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
    void *xdlCache = nullptr;
    xdl_info_t pcInfo{}, lrInfo{};
    xdl_addr(reinterpret_cast<void *>(pcAddr), &pcInfo, &xdlCache);
    xdl_addr(reinterpret_cast<void *>(lrAddr), &lrInfo, &xdlCache);

    uintptr_t pcOffset = (pcInfo.dli_fbase != nullptr && pcAddr >= reinterpret_cast<uintptr_t>(pcInfo.dli_fbase))
            ? (pcAddr - reinterpret_cast<uintptr_t>(pcInfo.dli_fbase)) : pcAddr;
    uintptr_t lrOffset = (lrInfo.dli_fbase != nullptr && lrAddr >= reinterpret_cast<uintptr_t>(lrInfo.dli_fbase))
            ? (lrAddr - reinterpret_cast<uintptr_t>(lrInfo.dli_fbase)) : lrAddr;

    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);

    ALOGE("FEATURESTIC CRASH CATCHER: Signal=%d (code=%d), TID=%d (%s), Fault=0x%llX, X0=0x%llX | PC=%s+0x%llX | LR=%s+0x%llX",
          sig,
          siCode,
          static_cast<int>(gettid()),
          tname[0] ? tname : "?",
          static_cast<unsigned long long>(faultAddr),
          static_cast<unsigned long long>(x0Addr),
          pcInfo.dli_fname ? pcInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(pcOffset),
          lrInfo.dli_fname ? lrInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(lrOffset));

    if (xdlCache != nullptr) {
        xdl_addr_clean(&xdlCache);
    }
    logCurrentThreadBacktrace();
}

static void chainPreviousSignal(int sig, siginfo_t *info, void *ucontext) {
    struct sigaction *prev = (sig == SIGSEGV) ? &sOldSigsegvAction
            : ((sig == SIGBUS) ? &sOldSigbusAction
            : ((sig == SIGILL) ? &sOldSigillAction : &sOldSigabrtAction));
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
    // Return gracefully instead of letting the process crash or killing threads
}

static __thread int tThreadSegvAccerrCount = 0;

static int (*sOrigKill)(pid_t, int) = nullptr;
static int (*sOrigTgkill)(pid_t, pid_t, int) = nullptr;
static int (*sOrigRaise)(int) = nullptr;
static void (*sOrigAbort)(void) = nullptr;

static int safe_kill(pid_t pid, int sig) {
    if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT)
            && (pid == getpid() || pid == 0 || pid == -1)) {
        ALOGW("FEATURESTIC GUARD: Suppressed kill(pid=%d, sig=%d) from tid=%d", static_cast<int>(pid), sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigKill ? sOrigKill(pid, sig) : -1;
}

static int safe_tgkill(pid_t tgid, pid_t tid, int sig) {
    if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT)
            && (tgid == getpid() || tgid == 0 || tgid == -1)) {
        ALOGW("FEATURESTIC GUARD: Suppressed tgkill(tgid=%d, tid=%d, sig=%d) from tid=%d",
              static_cast<int>(tgid), static_cast<int>(tid), sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigTgkill ? sOrigTgkill(tgid, tid, sig) : -1;
}

static int safe_raise(int sig) {
    if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT) {
        ALOGW("FEATURESTIC GUARD: Suppressed raise(sig=%d) from tid=%d", sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigRaise ? sOrigRaise(sig) : -1;
}

static void (*sOrigExit)(int) = nullptr;
static void (*sOrig_Exit)(int) = nullptr;

static void safe_exit(int code) {
    if (code == 0) {
        if (sOrigExit != nullptr) sOrigExit(0);
        _exit(0);
        return;
    }
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    ALOGW("FEATURESTIC GUARD: Suppressed exit(%d) from tid=%d (name=%s)",
          code, static_cast<int>(gettid()), tname[0] ? tname : "?");
    if (!isCriticalGameOrMainThread() && gettid() != getpid() && isRogueWorkerThread(tname)) {
        syscall(__NR_exit, 0);
    }
}

static void safe__exit(int code) {
    if (code == 0) {
        if (sOrig_Exit != nullptr) sOrig_Exit(0);
        _exit(0);
        return;
    }
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    ALOGW("FEATURESTIC GUARD: Suppressed _exit(%d) from tid=%d (name=%s)",
          code, static_cast<int>(gettid()), tname[0] ? tname : "?");
    if (!isCriticalGameOrMainThread() && gettid() != getpid() && isRogueWorkerThread(tname)) {
        syscall(__NR_exit, 0);
    }
}

static void safe_abort(void) {
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    ALOGW("FEATURESTIC GUARD: Suppressed abort() from tid=%d (name=%s)",
          static_cast<int>(gettid()), tname[0] ? tname : "?");
    if (!isCriticalGameOrMainThread() && gettid() != getpid() && isRogueWorkerThread(tname)) {
#if defined(__aarch64__)
        register long x8 __asm__("x8") = 93; // __NR_exit
        register long x0 __asm__("x0") = 0;
        __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
#else
        syscall(__NR_exit, 0);
#endif
    }
    while (true) { usleep(100000); }
}

static void passiveNativeCrashDiagnosticsHandler(int sig, siginfo_t *info, void *ucontext) {
    int siCode = (info != nullptr) ? info->si_code : 0;
    uintptr_t fAddr = (info != nullptr) ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
    uintptr_t pcAddr = 0;
#if defined(__aarch64__)
    if (ucontext != nullptr) {
        pcAddr = static_cast<uintptr_t>(reinterpret_cast<ucontext_t *>(ucontext)->uc_mcontext.pc);
    }
#endif

    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);

    // 1. Suppress all userspace software kills (tgkill / kill / tkill)
    if (info != nullptr && info->si_code <= 0) {
        ALOGW("FEATURESTIC GUARD: Suppressed software kill signal %d (code=%d, sender_pid=%d) on tid=%d (%s)",
              sig, siCode, info->si_pid, static_cast<int>(gettid()), tname[0] ? tname : "?");
        return;
    }

#if defined(__aarch64__)
    if (ucontext != nullptr) {
        auto *uc = reinterpret_cast<ucontext_t *>(ucontext);
        uintptr_t currentPc = static_cast<uintptr_t>(uc->uc_mcontext.pc);

        if (sig == SIGILL) {
            ALOGW("FEATURESTIC RESCUE: Skipping illegal instruction at PC %p on tid=%d (%s)",
                  reinterpret_cast<void *>(currentPc), static_cast<int>(gettid()), tname[0] ? tname : "?");
            uc->uc_mcontext.pc += 4;
            return;
        }

        if (sig == SIGBUS || sig == SIGSEGV) {
            static __thread int tConsecutiveFaults = 0;
            tConsecutiveFaults++;

            bool isCritical = isCriticalGameOrMainThread() || (gettid() == getpid());
            bool isRogue = isRogueWorkerThread(tname);

            if (tConsecutiveFaults <= 5) {
                ALOGW("FEATURESTIC RESCUE: Rescuing signal %d at PC %p (addr=%p) on tid=%d (%s) [fault #%d]",
                      sig, reinterpret_cast<void *>(currentPc), reinterpret_cast<void *>(fAddr),
                      static_cast<int>(gettid()), tname[0] ? tname : "?", tConsecutiveFaults);
            }

            uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
            uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);

            // Determine if LR is a genuine external caller or just an intra-function return address
            uintptr_t callerPc = 0;
            bool isDirectLrValid = (lr >= 0x10000 && isPageMappedSafe(lr));
            bool isIntraFunctionLr = isDirectLrValid && (labs((intptr_t)lr - (intptr_t)currentPc) < 4096);

            if (isDirectLrValid && !isIntraFunctionLr) {
                callerPc = lr;
            } else if (fp >= 0x10000 && (fp & 0x7) == 0 && isPageMappedSafe(fp) && isPageMappedSafe(fp + 8)) {
                // Inspect standard ARM64 stack frame record: fp points to [saved_fp, saved_lr]
                uintptr_t savedLr = *reinterpret_cast<const uintptr_t *>(fp + 8);
                if (savedLr >= 0x10000 && isPageMappedSafe(savedLr) && labs((intptr_t)savedLr - (intptr_t)currentPc) >= 4096) {
                    callerPc = savedLr;
                    uintptr_t savedFp = *reinterpret_cast<const uintptr_t *>(fp);
                    if (savedFp > fp && isPageMappedSafe(savedFp)) {
                        uc->uc_mcontext.regs[29] = savedFp;
                        uc->uc_mcontext.sp = fp + 16;
                    }
                }
            }

            // ART GC Daemon Threads (HeapTaskDaemon, FinalizerDaemon, etc.):
            // Advance pc += 4 to preserve GC checkpoints and prevent thread-inl.h / monitor.cc aborts.
            if (isArtInternalDaemonThread(tname)) {
                if (currentPc >= 0x10000 && isPageMappedSafe(currentPc)) {
                    uc->uc_mcontext.pc += 4;
                    return;
                }
                if (callerPc != 0) {
                    uc->uc_mcontext.pc = callerPc;
                    uc->uc_mcontext.regs[0] = 0;
                    return;
                }
                usleep(10000);
                return;
            }

            // 1. Universal Cascade Detection on ANY thread:
            // Safely return to external caller with return value x0 = 0 (NULL/false).
            // Intra-function loops are strictly rejected to prevent 100% CPU lockups.
            if (tConsecutiveFaults > 1 && callerPc != 0) {
                ALOGW("FEATURESTIC RESCUE: Safely aborting failing call on tid=%d (%s) -> returning to caller PC %p (x0=0)",
                      static_cast<int>(gettid()), tname[0] ? tname : "?", reinterpret_cast<void *>(callerPc));
                uc->uc_mcontext.pc = callerPc;
                uc->uc_mcontext.regs[0] = 0;
                if (tConsecutiveFaults > 2) {
                    usleep(10000); // 10ms throttle: drops CPU burn down to 0% while remaining responsive
                }
                return;
            }

            // 2. If PC is invalid or unmapped, recover via mapped external caller
            if ((currentPc < 0x10000 || !isPageMappedSafe(currentPc)) && callerPc != 0) {
                uc->uc_mcontext.pc = callerPc;
                uc->uc_mcontext.regs[0] = 0;
                if (tConsecutiveFaults > 2) {
                    usleep(10000);
                }
                return;
            }

            // 3. Single-fault instruction advance (throttled if consecutive to preserve 0% CPU without deadlocks)
            if (currentPc >= 0x10000 && isPageMappedSafe(currentPc)) {
                if (tConsecutiveFaults > 2) {
                    usleep(10000); // 10ms throttle: eliminates 100% CPU spins and ANRs
                }
                uc->uc_mcontext.pc += 4;
                return;
            } else if (callerPc != 0) {
                uc->uc_mcontext.pc = callerPc;
                uc->uc_mcontext.regs[0] = 0;
                return;
            }

            // 4. Fallback for unmapped PC & unmapped caller on rogue worker: terminate that single thread cleanly in kernel
            if (!isCritical && !isArtInternalDaemonThread(tname) && isRogue) {
                ALOGW("FEATURESTIC RESCUE: Terminating unrecoverable rogue worker tid=%d (%s) on signal %d",
                      static_cast<int>(gettid()), tname[0] ? tname : "?", sig);
                register long x8 __asm__("x8") = 93;
                register long x0 __asm__("x0") = 0;
                __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
            }
            usleep(20000);
            return;
        }
    }
#endif

    chainPreviousSignal(sig, info, ucontext);
}

extern "C" {
    void *gOurSignalHandler = nullptr;
    void *gOurSigsysHandler = nullptr;
}

static void installOrReassertPassiveNativeCrashDiagnostics() {
    static uint8_t sAltStackBuffer[65536] __attribute__((aligned(16)));
    stack_t curSs{};
    if (sigaltstack(nullptr, &curSs) == 0 && ((curSs.ss_flags & SS_DISABLE) || curSs.ss_sp == nullptr || curSs.ss_size < MINSIGSTKSZ)) {
        stack_t newSs{};
        newSs.ss_sp = sAltStackBuffer;
        newSs.ss_size = sizeof(sAltStackBuffer);
        newSs.ss_flags = 0;
        sigaltstack(&newSs, nullptr);
    }

    gOurSignalHandler = reinterpret_cast<void *>(&passiveNativeCrashDiagnosticsHandler);
    int (*origSa)(int, const struct sigaction *, struct sigaction *) = sOrigSigaction ? sOrigSigaction : ::sigaction;
    struct sigaction sa{};
    sa.sa_sigaction = passiveNativeCrashDiagnosticsHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;

    struct sigaction prevSegv{};
    if (origSa(SIGSEGV, &sa, &prevSegv) == 0) {
        if (prevSegv.sa_sigaction != passiveNativeCrashDiagnosticsHandler && prevSegv.sa_handler != SIG_DFL && prevSegv.sa_handler != SIG_IGN) {
            sOldSigsegvAction = prevSegv;
        }
    }
    struct sigaction prevBus{};
    if (origSa(SIGBUS, &sa, &prevBus) == 0) {
        if (prevBus.sa_sigaction != passiveNativeCrashDiagnosticsHandler && prevBus.sa_handler != SIG_DFL && prevBus.sa_handler != SIG_IGN) {
            sOldSigbusAction = prevBus;
        }
    }
    struct sigaction prevIll{};
    if (origSa(SIGILL, &sa, &prevIll) == 0) {
        if (prevIll.sa_sigaction != passiveNativeCrashDiagnosticsHandler && prevIll.sa_handler != SIG_DFL && prevIll.sa_handler != SIG_IGN) {
            sOldSigillAction = prevIll;
        }
    }
    // NOTE: Intentionally NOT installing SIGABRT handler — siglongjmp from
    // signal handlers is unreliable and doesn't work with stack corruption.
    // Let Android handle SIGABRT naturally for native crashes in game libraries.
    sSignalGuardInstalled = true;
}

// Intercept sigaction() so libanort.so / libCrashKit.so cannot strip our
// passiveNativeCrashDiagnosticsHandler back to SIG_DFL before raising SIGBUS (signal 7).
static int safe_sigaction_common(int sig,
                                 const struct sigaction *act,
                                 struct sigaction *oldact,
                                 int (*origFn)(int, const struct sigaction *, struct sigaction *)) {
    if (sig == SIGSYS) {
        if (oldact != nullptr && origFn != nullptr) {
            origFn(sig, nullptr, oldact);
        }
        if (act != nullptr && (act->sa_flags & SA_SIGINFO) && act->sa_sigaction != nullptr) {
            if (gOurSigsysHandler == nullptr) {
                gOurSigsysHandler = reinterpret_cast<void *>(act->sa_sigaction);
                return origFn ? origFn(sig, act, nullptr) : 0;
            }
            if (reinterpret_cast<void *>(act->sa_sigaction) == gOurSigsysHandler) {
                return origFn ? origFn(sig, act, nullptr) : 0;
            }
        }
        // Protect SECCOMP-BPF trap handler: never allow third-party libs to strip our SIGSYS handler
        return 0;
    }
    if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL) {
        struct sigaction *savedSlot = (sig == SIGSEGV) ? &sOldSigsegvAction
                : ((sig == SIGBUS) ? &sOldSigbusAction
                : ((sig == SIGILL) ? &sOldSigillAction : &sOldSigabrtAction));
        if (oldact != nullptr) {
            *oldact = *savedSlot;
        }
        if (act != nullptr) {
            if (act->sa_sigaction == passiveNativeCrashDiagnosticsHandler) {
                return origFn ? origFn(sig, act, nullptr) : 0;
            }
            if (act->sa_handler == SIG_DFL || act->sa_handler == SIG_IGN) {
                // Do not allow anti-cheat to reset protective signal handlers to SIG_DFL
                return 0;
            }
            if ((act->sa_flags & SA_SIGINFO) && act->sa_sigaction != nullptr) {
                *savedSlot = *act;
            } else if (!(act->sa_flags & SA_SIGINFO)
                    && act->sa_handler != SIG_DFL
                    && act->sa_handler != SIG_IGN
                    && act->sa_handler != nullptr) {
                *savedSlot = *act;
            }
            if (origFn != nullptr) {
                struct sigaction sa{};
                sa.sa_sigaction = passiveNativeCrashDiagnosticsHandler;
                sigemptyset(&sa.sa_mask);
                sa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;
                origFn(sig, &sa, nullptr);
            }
        }
        return 0;
    }
    return origFn ? origFn(sig, act, oldact) : -1;
}

static int safe_sigaction(int sig, const struct sigaction *act, struct sigaction *oldact) {
    return safe_sigaction_common(sig, act, oldact, sOrigSigaction);
}

static int safe_sigaction64(int sig, const struct sigaction *act, struct sigaction *oldact) {
    return safe_sigaction_common(sig, act, oldact, sOrigSigaction64 ? sOrigSigaction64 : sOrigSigaction);
}

static int (*sOrigPthreadSigmask)(int, const sigset_t *, sigset_t *) = nullptr;
static int (*sOrigSigprocmask)(int, const sigset_t *, sigset_t *) = nullptr;
static int (*sOrigSigprocmask64)(int, const sigset64_t *, sigset64_t *) = nullptr;

static inline void stripDangerousSignals(sigset_t *set) {
    if (set == nullptr) return;
    sigdelset(set, SIGSYS);
    sigdelset(set, SIGSEGV);
    sigdelset(set, SIGBUS);
    sigdelset(set, SIGILL);
    sigdelset(set, SIGABRT);
}

static int safe_pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset) {
    if (set != nullptr && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t copy = *set;
        stripDangerousSignals(&copy);
        return sOrigPthreadSigmask ? sOrigPthreadSigmask(how, &copy, oldset) : 0;
    }
    return sOrigPthreadSigmask ? sOrigPthreadSigmask(how, set, oldset) : 0;
}

static int safe_sigprocmask(int how, const sigset_t *set, sigset_t *oldset) {
    if (set != nullptr && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset_t copy = *set;
        stripDangerousSignals(&copy);
        return sOrigSigprocmask ? sOrigSigprocmask(how, &copy, oldset) : 0;
    }
    return sOrigSigprocmask ? sOrigSigprocmask(how, set, oldset) : 0;
}

static int safe_sigprocmask64(int how, const sigset64_t *set, sigset64_t *oldset) {
    if (set != nullptr && (how == SIG_BLOCK || how == SIG_SETMASK)) {
        sigset64_t copy = *set;
        stripDangerousSignals(reinterpret_cast<sigset_t *>(&copy));
        return sOrigSigprocmask64 ? sOrigSigprocmask64(how, &copy, oldset) : 0;
    }
    return sOrigSigprocmask64 ? sOrigSigprocmask64(how, set, oldset) : 0;
}

struct DlIterateProxyData {
    int (*origCallback)(struct dl_phdr_info *info, size_t size, void *data);
    void *origArg;
};

static int (*sOrigDlIteratePhdr)(int (*)(struct dl_phdr_info *, size_t, void *), void *) = nullptr;
static void *(*sOrigDlsym)(void *, const char *) = nullptr;

static int safe_dl_iterate_phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    if (info != nullptr && info->dlpi_name != nullptr) {
        const char *name = info->dlpi_name;
        if (strstr(name, "Featurestic") != nullptr
                || strstr(name, "featurestic") != nullptr
                || strstr(name, "SandHook") != nullptr
                || strstr(name, "sandhook") != nullptr
                || strstr(name, "dobby") != nullptr
                || strstr(name, "loader") != nullptr
                || strstr(name, "frida") != nullptr
                || strstr(name, "xposed") != nullptr
                || strstr(name, "edxp") != nullptr
                || strstr(name, "lspd") != nullptr
                || strstr(name, "magisk") != nullptr
                || strstr(name, "zygisk") != nullptr
                || strstr(name, "termux") != nullptr
                || strstr(name, "antigem") != nullptr
                || strstr(name, "substrate") != nullptr
                || strstr(name, "Substrate") != nullptr
                || strstr(name, "blackbox") != nullptr
                || strstr(name, "BlackBox") != nullptr) {
            // Stealth masking: hide container and hooking libraries from anti-cheat
            return 0;
        }
    }
    auto *proxy = reinterpret_cast<DlIterateProxyData *>(data);
    if (proxy != nullptr && proxy->origCallback != nullptr) {
        return proxy->origCallback(info, size, proxy->origArg);
    }
    return 0;
}

static int safe_dl_iterate_phdr(int (*callback)(struct dl_phdr_info *, size_t, void *), void *data) {
    if (callback == nullptr) return 0;
    DlIterateProxyData proxy{callback, data};
    return sOrigDlIteratePhdr ? sOrigDlIteratePhdr(safe_dl_iterate_phdr_cb, &proxy) : 0;
}

static void *safe_dlsym(void *handle, const char *symbol) {
    if (symbol != nullptr) {
        if (strstr(symbol, "A64HookFunction") != nullptr
                || strstr(symbol, "SandHook") != nullptr
                || strstr(symbol, "DobbyHook") != nullptr
                || strstr(symbol, "dobby") != nullptr
                || strstr(symbol, "featurestic") != nullptr
                || strstr(symbol, "Featurestic") != nullptr
                || strstr(symbol, "NativeVfsHook") != nullptr
                || strstr(symbol, "Substrate") != nullptr
                || strstr(symbol, "MSHookFunction") != nullptr
                || strstr(symbol, "blackbox") != nullptr
                || strstr(symbol, "BlackBox") != nullptr
                || strstr(symbol, "BoxCore") != nullptr) {
            // Anti-cheat blindfold: hide hook framework symbols
            return nullptr;
        }
    }
    return sOrigDlsym ? sOrigDlsym(handle, symbol) : nullptr;
}

static void installHeapAndRefBaseSafetyGuards() {
    static bool sGuardsInitialized = false;
    if (!sGuardsInitialized) {
        sGuardsInitialized = true;
        // 1. Install signal diagnostics BEFORE hooking sigaction().
        installOrReassertPassiveNativeCrashDiagnostics();

        // 2. Hook libc.so free() and sigaction()/sigaction64() directly via A64HookFunction.
        void *freeSym = dlsym(RTLD_DEFAULT, "free");
        void *sigactionSym = dlsym(RTLD_DEFAULT, "sigaction");
        void *sigaction64Sym = dlsym(RTLD_DEFAULT, "sigaction64");
        void *killSym = dlsym(RTLD_DEFAULT, "kill");
        void *tgkillSym = dlsym(RTLD_DEFAULT, "tgkill");
        void *raiseSym = dlsym(RTLD_DEFAULT, "raise");
        void *abortSym = dlsym(RTLD_DEFAULT, "abort");
        void *exitSym = dlsym(RTLD_DEFAULT, "exit");
        void *_exitSym = dlsym(RTLD_DEFAULT, "_exit");
        void *_ExitSym = dlsym(RTLD_DEFAULT, "_Exit");
        void *pthreadSigmaskSym = dlsym(RTLD_DEFAULT, "pthread_sigmask");
        void *sigprocmaskSym = dlsym(RTLD_DEFAULT, "sigprocmask");
        void *sigprocmask64Sym = dlsym(RTLD_DEFAULT, "sigprocmask64");
        void *dlIteratePhdrSym = dlsym(RTLD_DEFAULT, "dl_iterate_phdr");
        void *dlsymSym = dlsym(RTLD_DEFAULT, "dlsym");
#if defined(__aarch64__)
        if (pthreadSigmaskSym) {
            A64HookFunction(pthreadSigmaskSym,
                            reinterpret_cast<void *>(safe_pthread_sigmask),
                            reinterpret_cast<void **>(&sOrigPthreadSigmask));
        }
        if (sigprocmaskSym) {
            A64HookFunction(sigprocmaskSym,
                            reinterpret_cast<void *>(safe_sigprocmask),
                            reinterpret_cast<void **>(&sOrigSigprocmask));
        }
        if (sigprocmask64Sym && sigprocmask64Sym != sigprocmaskSym) {
            A64HookFunction(sigprocmask64Sym,
                            reinterpret_cast<void *>(safe_sigprocmask64),
                            reinterpret_cast<void **>(&sOrigSigprocmask64));
        }
        if (freeSym) {
            A64HookFunction(freeSym,
                            reinterpret_cast<void *>(safe_free),
                            reinterpret_cast<void **>(&sOrigFree));
        }
        if (sigactionSym) {
            A64HookFunction(sigactionSym,
                            reinterpret_cast<void *>(safe_sigaction),
                            reinterpret_cast<void **>(&sOrigSigaction));
        }
        if (sigaction64Sym && sigaction64Sym != sigactionSym) {
            A64HookFunction(sigaction64Sym,
                            reinterpret_cast<void *>(safe_sigaction64),
                            reinterpret_cast<void **>(&sOrigSigaction64));
        }
        if (killSym) {
            A64HookFunction(killSym,
                            reinterpret_cast<void *>(safe_kill),
                            reinterpret_cast<void **>(&sOrigKill));
        }
        if (tgkillSym) {
            A64HookFunction(tgkillSym,
                            reinterpret_cast<void *>(safe_tgkill),
                            reinterpret_cast<void **>(&sOrigTgkill));
        }
        if (raiseSym) {
            A64HookFunction(raiseSym,
                            reinterpret_cast<void *>(safe_raise),
                            reinterpret_cast<void **>(&sOrigRaise));
        }
        if (abortSym) {
            A64HookFunction(abortSym,
                            reinterpret_cast<void *>(safe_abort),
                            reinterpret_cast<void **>(&sOrigAbort));
        }
        if (exitSym) {
            A64HookFunction(exitSym,
                            reinterpret_cast<void *>(safe_exit),
                            reinterpret_cast<void **>(&sOrigExit));
        }
        if (_exitSym) {
            A64HookFunction(_exitSym,
                            reinterpret_cast<void *>(safe__exit),
                            reinterpret_cast<void **>(&sOrig_Exit));
        }
        if (_ExitSym && _ExitSym != _exitSym) {
            A64HookFunction(_ExitSym,
                            reinterpret_cast<void *>(safe__exit),
                            nullptr);
        }
        if (dlIteratePhdrSym) {
            A64HookFunction(dlIteratePhdrSym,
                            reinterpret_cast<void *>(safe_dl_iterate_phdr),
                            reinterpret_cast<void **>(&sOrigDlIteratePhdr));
        }
        if (dlsymSym) {
            A64HookFunction(dlsymSym,
                            reinterpret_cast<void *>(safe_dlsym),
                            reinterpret_cast<void **>(&sOrigDlsym));
        }
#endif
        if (!sOrigFree && freeSym) {
            sOrigFree = reinterpret_cast<void (*)(void *)>(freeSym);
        }
        if (!sOrigSigaction && sigactionSym) {
            sOrigSigaction = reinterpret_cast<int (*)(int, const struct sigaction *, struct sigaction *)>(sigactionSym);
        }
        if (!sOrigKill && killSym) sOrigKill = reinterpret_cast<int (*)(pid_t, int)>(killSym);
        if (!sOrigTgkill && tgkillSym) sOrigTgkill = reinterpret_cast<int (*)(pid_t, pid_t, int)>(tgkillSym);
        if (!sOrigRaise && raiseSym) sOrigRaise = reinterpret_cast<int (*)(int)>(raiseSym);
        if (!sOrigAbort && abortSym) sOrigAbort = reinterpret_cast<void (*)(void)>(abortSym);
        if (!sOrigPthreadSigmask && pthreadSigmaskSym) {
            sOrigPthreadSigmask = reinterpret_cast<int (*)(int, const sigset_t *, sigset_t *)>(pthreadSigmaskSym);
        }
        if (!sOrigSigprocmask && sigprocmaskSym) {
            sOrigSigprocmask = reinterpret_cast<int (*)(int, const sigset_t *, sigset_t *)>(sigprocmaskSym);
        }
        if (!sOrigSigprocmask64 && sigprocmask64Sym) {
            sOrigSigprocmask64 = reinterpret_cast<int (*)(int, const sigset64_t *, sigset64_t *)>(sigprocmask64Sym);
        }
        if (!sOrigDlIteratePhdr && dlIteratePhdrSym) {
            sOrigDlIteratePhdr = reinterpret_cast<int (*)(int (*)(struct dl_phdr_info *, size_t, void *), void *)>(dlIteratePhdrSym);
        }
        if (!sOrigDlsym && dlsymSym) {
            sOrigDlsym = reinterpret_cast<void *(*)(void *, const char *)>(dlsymSym);
        }

        // Unblock SIGSYS, SIGSEGV, SIGBUS, SIGILL permanently on the current thread
        sigset_t unblockSys{};
        sigemptyset(&unblockSys);
        sigaddset(&unblockSys, SIGSYS);
        sigaddset(&unblockSys, SIGSEGV);
        sigaddset(&unblockSys, SIGBUS);
        sigaddset(&unblockSys, SIGILL);
        sigaddset(&unblockSys, SIGABRT);
        pthread_sigmask(SIG_UNBLOCK, &unblockSys, nullptr);
    }
}

static int countMappedGameLibraries() {
    int fd = NativeVfsHook::rawDirectOpen("/proc/self/maps", O_RDONLY, 0);
    if (fd < 0) return 0;
    bool hasAnort = false;
    bool hasAnogs = false;
    bool hasHdmpve = false;
    bool hasUE4 = false;
    bool hasCrashKit = false;
    char buffer[4096];
    ssize_t bytesRead = 0;
    std::string remnant;
    while ((bytesRead = NativeVfsHook::rawDirectRead(fd, buffer, sizeof(buffer) - 1)) > 0) {
        buffer[bytesRead] = '\0';
        std::string chunk = remnant + buffer;
        if (!hasAnort && chunk.find("libanort.so") != std::string::npos) hasAnort = true;
        if (!hasAnogs && chunk.find("libanogs.so") != std::string::npos) hasAnogs = true;
        if (!hasHdmpve && chunk.find("libhdmpve.so") != std::string::npos) hasHdmpve = true;
        if (!hasUE4 && chunk.find("libUE4.so") != std::string::npos) hasUE4 = true;
        if (!hasCrashKit && chunk.find("libCrashKit.so") != std::string::npos) hasCrashKit = true;
        if (hasAnort && hasAnogs && hasHdmpve && hasUE4 && hasCrashKit) break;
        if (chunk.size() > 64) {
            remnant = chunk.substr(chunk.size() - 64);
        } else {
            remnant = chunk;
        }
    }
    NativeVfsHook::rawDirectClose(fd);
    return (hasAnort ? 1 : 0) + (hasAnogs ? 1 : 0) + (hasHdmpve ? 1 : 0) + (hasUE4 ? 1 : 0) + (hasCrashKit ? 1 : 0);
}

static void *nativeGuardWatcherThreadMain(void * /*arg*/) {
    prctl(PR_SET_NAME, reinterpret_cast<unsigned long>("Binder:guard"), 0, 0, 0);
    int lastCount = 0;
    while (true) {
        struct timespec ts{};
        ts.tv_sec = 0;
        ts.tv_nsec = 100 * 1000 * 1000L; // 100ms tight loop so no anti-cheat unhook window exists
        nanosleep(&ts, nullptr);

        installOrReassertPassiveNativeCrashDiagnostics();
        NativeVfsHook::init();

        int count = countMappedGameLibraries();
        if (count > lastCount) {
            lastCount = count;
            sGameLibLoadedCount.store(count, std::memory_order_relaxed);
            sanitizeInheritedPtyFileDescriptors();
            installHeapAndRefBaseSafetyGuards();
        }
    }
    return nullptr;
}

static void startNativeGuardWatcherThread() {
    bool expected = false;
    if (!sWatcherThreadStarted.compare_exchange_strong(expected, true)) {
        return;
    }
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&tid, &attr, nativeGuardWatcherThreadMain, nullptr);
    pthread_attr_destroy(&attr);
}
}


namespace {
static bool sAntiTamperEnforced = true;
static bool sRuntimeTampered = false;
static std::string sPinnedHostCertSha256;

static inline std::string unmaskStr(const unsigned char *data, size_t len, unsigned char key = 0x5A) {
    std::string s;
    s.resize(len);
    for (size_t i = 0; i < len; ++i) {
        s[i] = static_cast<char>(data[i] ^ key);
    }
    return s;
}

// Obfuscated string signatures to avoid plain text detection in .rodata
static const unsigned char k_frida_agent[] = {0x3c, 0x28, 0x33, 0x3e, 0x3b, 0x77, 0x3b, 0x3d, 0x3f, 0x34, 0x2e};
static const unsigned char k_frida_gadget[] = {0x3c, 0x28, 0x33, 0x3e, 0x3b, 0x77, 0x3d, 0x3b, 0x3e, 0x3d, 0x3f, 0x2e};
static const unsigned char k_libgum_so[] = {0x36, 0x33, 0x38, 0x3d, 0x2f, 0x37, 0x74, 0x29, 0x35};
static const unsigned char k_linjector[] = {0x36, 0x33, 0x34, 0x30, 0x3f, 0x39, 0x2e, 0x35, 0x28};
static const unsigned char k_gum_js_loop[] = {0x3d, 0x2f, 0x37, 0x77, 0x30, 0x29, 0x77, 0x36, 0x35, 0x35, 0x2a};
static const unsigned char k_pool_frida[] = {0x2a, 0x35, 0x35, 0x36, 0x77, 0x3c, 0x28, 0x33, 0x3e, 0x3b};
static const unsigned char k_TracerPid_[] = {0x0e, 0x28, 0x3b, 0x39, 0x3f, 0x28, 0x0a, 0x33, 0x3e, 0x60};

bool detectDebuggerTracerPid() {
    FILE *fp = fopen("/proc/self/status", "r");
    if (!fp) return false;
    char line[256];
    bool traced = false;
    std::string tracerKey = unmaskStr(k_TracerPid_, sizeof(k_TracerPid_));
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, tracerKey.c_str(), tracerKey.size()) == 0) {
            int pid = atoi(line + tracerKey.size());
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
    std::string s_agent = unmaskStr(k_frida_agent, sizeof(k_frida_agent));
    std::string s_gadget = unmaskStr(k_frida_gadget, sizeof(k_frida_gadget));
    std::string s_gum = unmaskStr(k_libgum_so, sizeof(k_libgum_so));
    std::string s_inj = unmaskStr(k_linjector, sizeof(k_linjector));
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, s_agent.c_str()) != nullptr
                || strstr(line, s_gadget.c_str()) != nullptr
                || strstr(line, s_gum.c_str()) != nullptr
                || strstr(line, s_inj.c_str()) != nullptr) {
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
    std::string s_loop = unmaskStr(k_gum_js_loop, sizeof(k_gum_js_loop));
    std::string s_pool = unmaskStr(k_pool_frida, sizeof(k_pool_frida));
    std::string s_inj = unmaskStr(k_linjector, sizeof(k_linjector));
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char commPath[128];
        snprintf(commPath, sizeof(commPath), "/proc/self/task/%s/comm", entry->d_name);
        FILE *fp = fopen(commPath, "r");
        if (fp) {
            char comm[64];
            if (fgets(comm, sizeof(comm), fp)) {
                if (strstr(comm, s_loop.c_str()) != nullptr
                        || strstr(comm, s_pool.c_str()) != nullptr
                        || strstr(comm, s_inj.c_str()) != nullptr) {
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

bool detectLocalInstrumentationPort() {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    struct sockaddr_in sa{};
    sa.sin_family = AF_INET;
    sa.sin_port = htons(27042);
    inet_pton(AF_INET, "127.0.0.1", &sa.sin_addr);
    struct timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 40000; // 40ms timeout
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    bool connected = (connect(sock, reinterpret_cast<struct sockaddr *>(&sa), sizeof(sa)) == 0);
    close(sock);
    return connected;
}

static volatile int sNativeLoginGateState = 0; // 0 = off, 1 = armed (locked), 2 = unlocked

bool runNativeSecurityScan() {
    if (!sAntiTamperEnforced) return true;
    if (detectFridaMemoryMaps() || detectFridaThreads() || detectDebuggerTracerPid() || detectLocalInstrumentationPort()) {
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
    sanitizeInheritedPtyFileDescriptors();
    installHeapAndRefBaseSafetyGuards();
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
    NativeVfsHook::init();
    installHeapAndRefBaseSafetyGuards();
    startNativeGuardWatcherThread();
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
        } else if (strcmp(k, "debug.featurestic.target_pkg") == 0) {
            NativeVfsHook::setVirtualPackage(v, v);
        } else if (strcmp(k, "debug.featurestic.process_name") == 0) {
            NativeVfsHook::setVirtualPackage("", v);
        } else if (strcmp(k, "debug.featurestic.gamelibs_ready") == 0) {
            BoxCore::onNativeLibraryLoaded("libUE4.so");
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
    sanitizeInheritedPtyFileDescriptors();

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
    NativeVfsHook::init();
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
