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
#include <vector>
#include <cstdint>
#include <cstdlib>
#include <string>
#include <fstream>
#include <cstdarg>
#include <sys/stat.h>
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
#include <android/api-level.h>

namespace {
static struct sigaction sOldSigsegvAction{};
static struct sigaction sOldSigbusAction{};
static struct sigaction sOldSigabrtAction{};
static struct sigaction sOldSigillAction{};
static struct sigaction sOldSigquitAction{};
static bool sSignalGuardInstalled = false;
static std::atomic<int> sGameLibLoadedCount{0};
static std::atomic<bool> sWatcherThreadStarted{false};
static int (*sOrigSigaction)(int, const struct sigaction *, struct sigaction *) = nullptr;
static int (*sOrigSigaction64)(int, const struct sigaction *, struct sigaction *) = nullptr;
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
    sigdelset(set, SIGQUIT);
}

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
                if (strstr(target, "/dev/pts") != nullptr || strstr(target, "/dev/ptmx") != nullptr || strstr(target, "devpts") != nullptr || strstr(target, "/pts/") != nullptr) {
                    dup2(devNull, fd);
                }
            }
        }
        closedir(dir);
    }

    // Direct sweep over file descriptors to catch any unlisted or restricted pty FDs
    for (int fd = 3; fd < 1024; ++fd) {
        char linkPath[64];
        char target[256];
        snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", fd);
        ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "/dev/pts") != nullptr || strstr(target, "/dev/ptmx") != nullptr || strstr(target, "devpts") != nullptr || strstr(target, "/pts/") != nullptr) {
                dup2(devNull, fd);
            }
        }
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
                || strcasestr(tname, "slate") != nullptr
                || strcasestr(tname, "shader") != nullptr
                || strcasestr(tname, "texture") != nullptr
                || strcasestr(tname, "pool") != nullptr
                || strcasestr(tname, "worker") != nullptr
                || strcasestr(tname, "task") != nullptr
                || strcasestr(tname, "load") != nullptr
                || strcasestr(tname, "asset") != nullptr
                || strcasestr(tname, "gcloud") != nullptr
                || strcasestr(tname, "hdmpve") != nullptr
                || strcasestr(tname, "timer") != nullptr
                || strcasestr(tname, "async") != nullptr) {
            return true;
        }
    }
    return false;
}

static bool isRogueWorkerThread(const char *tname) {
    if (!tname || tname[0] == '\0') return false;
    if (strcasestr(tname, "crashkit") != nullptr
            || strcasestr(tname, "anort") != nullptr
            || strcasestr(tname, "anogs") != nullptr
            || strcasestr(tname, "tersafe") != nullptr
            || strcasestr(tname, "tp_") != nullptr
            || strcasestr(tname, "guard") != nullptr
            || strcasestr(tname, "turing") != nullptr) {
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
            || strcasestr(tname, "daemon") != nullptr
            || strcasestr(tname, "compiler") != nullptr
            || strcasestr(tname, "profile saver") != nullptr;
}

static bool isArtOrManagedCode(uintptr_t pc, uintptr_t faultAddr, uint32_t instr) {
    // 1. Canonical ARM64 ART implicit suspend check: ldr x21, [x21] (0xf94002b5u)
    if (instr == 0xf94002b5u) {
        return true;
    }

    if (pc < 0x10000) {
        return false;
    }

    // 2. Check loaded module name via xdl_addr
    void *xdlCache = nullptr;
    xdl_info_t pcInfo{};
    bool isManaged = false;
    if (xdl_addr(reinterpret_cast<void *>(pc), &pcInfo, &xdlCache) != 0 && pcInfo.dli_fname != nullptr) {
        const char *fname = pcInfo.dli_fname;
        if (strstr(fname, "libart.so") != nullptr
                || strstr(fname, "libart-compiler.so") != nullptr
                || strstr(fname, "libopenjdk.so") != nullptr
                || strstr(fname, "libsigchain.so") != nullptr
                || strstr(fname, "com.android.art") != nullptr
                || strstr(fname, "com.android.runtime") != nullptr
                || strstr(fname, ".oat") != nullptr
                || strstr(fname, ".odex") != nullptr
                || strstr(fname, ".vdex") != nullptr
                || strstr(fname, ".art") != nullptr
                || strstr(fname, "dalvik") != nullptr) {
            isManaged = true;
        }
    } else {
        // xdl_addr returned 0: PC is in anonymous memory (stack, heap, or JIT code cache)
        // If it's an implicit null check (faultAddr < 4096) or suspend check, treat as ART JIT code
        if (faultAddr < 4096 || instr == 0xf94002b5u) {
            isManaged = true;
        }
    }

    if (xdlCache != nullptr) {
        xdl_addr_clean(&xdlCache);
    }
    return isManaged;
}

static bool isGraphicsOrRenderThread(const char *tname) {
    if (!tname || tname[0] == '\0') return false;
    return strcasestr(tname, "rhi") != nullptr
            || strcasestr(tname, "render") != nullptr
            || strcasestr(tname, "hwui") != nullptr
            || strcasestr(tname, "surface") != nullptr
            || strcasestr(tname, "mali") != nullptr
            || strcasestr(tname, "adreno") != nullptr
            || strcasestr(tname, "vulkan") != nullptr
            || strcasestr(tname, "egl") != nullptr
            || strcasestr(tname, "gles") != nullptr;
}

static uintptr_t sLowMemoryCushionBase = 0x10000ULL;
static size_t sLowMemoryCushionSize = 0x1F0000ULL; // 0x10000 to 0x200000 (1.9375 MB)
static std::atomic<bool> sLowMemoryCushionInitialized{false};

static void initLowMemorySafetyCushion() {
    bool expected = false;
    if (!sLowMemoryCushionInitialized.compare_exchange_strong(expected, true)) {
        return;
    }
#if defined(__LP64__)
    void *res = mmap(reinterpret_cast<void *>(sLowMemoryCushionBase),
                     sLowMemoryCushionSize,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                     -1, 0);
    if (res != MAP_FAILED) {
        memset(res, 0, sLowMemoryCushionSize);
        ALOGI("FEATURESTIC CUSHION: Mapped zeroed safety cushion at %p (size=%zu KB) to absorb low-address faults",
              res, sLowMemoryCushionSize / 1024);
    } else {
        ALOGW("FEATURESTIC CUSHION: Failed to map low memory cushion at %p (errno=%d)",
              reinterpret_cast<void *>(sLowMemoryCushionBase), errno);
    }
#endif
}

// NOTE: Removed parkFaultingBackgroundThreadForever() - parking threads
// forever was causing SIGBUS crashes instead of preventing them.

// ============================================================================
// Jemalloc Double-Free / Invalid-Free Guard & Android RefBase::decStrong Guard
// ============================================================================
static void (*sRealLibcFree)(void *) = nullptr;
static void (*sOrigRefBaseDecStrong)(void *, const void *) = nullptr;
static void (*sOrigWeakrefDecWeak)(void *, const void *) = nullptr;

static inline void initRealLibcFree() {
    if (sRealLibcFree == nullptr) {
        void *sym = dlsym(RTLD_DEFAULT, "free");
        sRealLibcFree = sym ? reinterpret_cast<void (*)(void *)>(sym) : &free;
    }
}

static size_t getSysPageSize() {
    static size_t sPageSize = 0;
    if (sPageSize == 0) {
        long sz = sysconf(_SC_PAGESIZE);
        sPageSize = (sz > 0) ? static_cast<size_t>(sz) : 4096;
    }
    return sPageSize;
}

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

    // 1. Suppress if memory address is unmapped in process address space
    if (!isPageMappedSafe(uptr)) {
        int cnt = ++sSuppressedFreeCount;
        if (cnt <= 32) {
            ALOGW("HeapGuard: SUPPRESSED unmapped free(%p) caller=%p (count=%d, tid=%d)",
                  ptr, __builtin_return_address(0), cnt, static_cast<int>(gettid()));
        }
        return;
    }

    // 2. Jemalloc 2MB chunk verification (Android Bionic jemalloc)
    // In Android Bionic jemalloc (2MB chunks, map_bias=13):
    // When an unallocated page (bit 0 == 0, e.g. mapbits: 0xbff0, 0x5ff0, 0xdfe0)
    // is passed to libc free(), jemalloc aborts with "Invalid %p passed to free: value not allocated".
    uintptr_t chunk = uptr & ~uintptr_t(0x1FFFFF);
    size_t pageind = (uptr - chunk) >> 12;
    if (pageind >= 13 && pageind < 512) {
        if (isPageMappedSafe(chunk)) {
            static const size_t kCandidateOffsets[] = {0x78, 0x70, 0x80, 0x68, 0x60, 0x88};
            for (size_t offset : kCandidateOffsets) {
                uintptr_t mapSlot = chunk + offset + (pageind - 13) * sizeof(size_t);
                if (isPageMappedSafe(mapSlot)) {
                    size_t mapbits = *reinterpret_cast<const volatile size_t *>(mapSlot);
                    if ((mapbits & 0x1ULL) == 0 && mapbits != 0) {
                        // Page is unallocated in jemalloc (e.g. mapbits: 0x5ff0) -> silently drop to eliminate abort!
                        int cnt = ++sSuppressedFreeCount;
                        if (cnt <= 32) {
                            ALOGW("HeapGuard: SUPPRESSED unallocated jemalloc chunk free(%p, chunk=%p, pageind=%zu, mapbits=0x%zx, offset=0x%zx) [tid=%d, count=%d]",
                                  ptr, reinterpret_cast<void *>(chunk), pageind, mapbits, offset,
                                  static_cast<int>(gettid()), cnt);
                        }
                        return;
                    }
                }
            }
        }
    }
#endif

    initRealLibcFree();
    if (sRealLibcFree != nullptr) {
        sRealLibcFree(ptr);
    } else {
        free(ptr);
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
static void safe_free(void *ptr);
static void safe_delete_sized(void *ptr, size_t size);
static int safe_kill(pid_t pid, int sig);
static int safe_tgkill(pid_t tgid, pid_t tid, int sig);
static int safe_raise(int sig);
static int safe_pthread_kill(pthread_t thread, int sig);
static void safe_exit(int code);
static void safe__exit(int code);
static void safe_abort(void);
static void safe_stack_chk_fail(void);
static void safe_android_log_assert(const char *cond, const char *tag, const char *fmt, ...);
static void terminateCallingThreadCleanly(int code);
static long safe_syscall(long number, long a1 = 0, long a2 = 0, long a3 = 0, long a4 = 0, long a5 = 0, long a6 = 0);
static int safe_stat(const char *pathname, struct stat *statbuf);
static int safe_lstat(const char *pathname, struct stat *statbuf);
static int safe_fstat(int fd, struct stat *statbuf);
static int safe_fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags);
static int safe_access(const char *pathname, int mode);
static int safe_faccessat(int dirfd, const char *pathname, int mode, int flags);
static int safe_faccessat2(int dirfd, const char *pathname, int mode, int flags);
static int safe_statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf);
static ssize_t safe_readlink(const char *pathname, char *buf, size_t bufsiz);
static ssize_t safe_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz);
static int safe_open(const char *pathname, int flags, ...);
static int safe_openat(int dirfd, const char *pathname, int flags, ...);
static int safe___open_2(const char *pathname, int flags);
static int safe___openat_2(int dirfd, const char *pathname, int flags);
static DIR *safe_opendir(const char *name);
static FILE *safe_fopen(const char *pathname, const char *mode);
static int safe_sigaction(int sig, const struct sigaction *act, struct sigaction *oldact);
static int safe_sigaction64(int sig, const struct sigaction *act, struct sigaction *oldact);
static int safe_pthread_sigmask(int how, const sigset_t *set, sigset_t *oldset);
static int safe_sigprocmask(int how, const sigset_t *set, sigset_t *oldset);
static int safe_sigprocmask64(int how, const sigset64_t *set, sigset64_t *oldset);
static int safe_system(const char *command);
static FILE *safe_popen(const char *command, const char *type);
static int safe_posix_spawn(pid_t *pid, const char *path, const void *file_actions, const void *attrp, char *const argv[], char *const envp[]);
static int safe_posix_spawnp(pid_t *pid, const char *file, const void *file_actions, const void *attrp, char *const argv[], char *const envp[]);
static int safe_execve(const char *pathname, char *const argv[], char *const envp[]);
static void installOrReassertPassiveNativeCrashDiagnostics();
static void initRealLibcProcessFunctions();
static void initRealLibcFsFunctions();

static void patchGotSlotSafe(void **slot, void *replacement) {
    if (slot == nullptr || replacement == nullptr || *slot == replacement) return;
    size_t pageSize = getSysPageSize();
    uintptr_t pageMask = ~(pageSize - 1);
    uintptr_t pageStart = reinterpret_cast<uintptr_t>(slot) & pageMask;
    if (mprotect(reinterpret_cast<void *>(pageStart), pageSize, PROT_READ | PROT_WRITE) == 0) {
        *slot = replacement;
        // Keep PROT_READ | PROT_WRITE so dynamic linker and library can write other data/GOT slots safely
    }
}

static void processElfRelaTable(uintptr_t baseAddr,
                                const Elf64_Rela *rela,
                                size_t relaCount,
                                const Elf64_Sym *symtab,
                                const char *strtab,
                                bool hookMemoryAllocator) {
    if (!rela || !symtab || !strtab) return;
    initRealLibcProcessFunctions();
    initRealLibcFsFunctions();
    for (size_t i = 0; i < relaCount; ++i) {
        uint32_t symIdx = ELF64_R_SYM(rela[i].r_info);
        if (symIdx == 0) continue;
        const char *symName = strtab + symtab[symIdx].st_name;
        if (!symName || symName[0] == '\0') continue;

        void **gotSlot = reinterpret_cast<void **>(baseAddr + rela[i].r_offset);
        // 1. Scoped memory allocator guard (protects against jemalloc unallocated free aborts; never touch UE4 engine allocator)
        if (hookMemoryAllocator) {
            if (strcmp(symName, "free") == 0
                    || strncmp(symName, "_ZdlPv", 6) == 0
                    || strncmp(symName, "_ZdaPv", 6) == 0) {
                patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_free));
                continue;
            }
        }

        // 2. Process termination & signal interception
        if (strcmp(symName, "exit") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_exit));
        } else if (strcmp(symName, "_exit") == 0 || strcmp(symName, "_Exit") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe__exit));
        } else if (strcmp(symName, "kill") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_kill));
        } else if (strcmp(symName, "tgkill") == 0 || strcmp(symName, "tkill") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_tgkill));
        } else if (strcmp(symName, "raise") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_raise));
        } else if (strcmp(symName, "pthread_kill") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_pthread_kill));
        } else if (strcmp(symName, "abort") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_abort));
        } else if (strcmp(symName, "__stack_chk_fail") == 0 || strcmp(symName, "__stack_chk_fail_local") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_stack_chk_fail));
        } else if (strcmp(symName, "__android_log_assert") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_android_log_assert));
        } else if (strcmp(symName, "syscall") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_syscall));
        } else if (strcmp(symName, "__system_property_get") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_system_property_get));
        } else if (strcmp(symName, "__system_property_find") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_system_property_find));
        } else if (strcmp(symName, "sigaction") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_sigaction));
        } else if (strcmp(symName, "sigaction64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_sigaction64));
        } else if (strcmp(symName, "pthread_sigmask") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_pthread_sigmask));
        } else if (strcmp(symName, "sigprocmask") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_sigprocmask));
        } else if (strcmp(symName, "sigprocmask64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_sigprocmask64));
        }

        // 3. File system, device & pseudo-terminal stealth interception
        else if (strcmp(symName, "stat") == 0 || strcmp(symName, "stat64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_stat));
        } else if (strcmp(symName, "lstat") == 0 || strcmp(symName, "lstat64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_lstat));
        } else if (strcmp(symName, "fstat") == 0 || strcmp(symName, "fstat64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_fstat));
        } else if (strcmp(symName, "fstatat") == 0 || strcmp(symName, "fstatat64") == 0 || strcmp(symName, "newfstatat") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_fstatat));
        } else if (strcmp(symName, "statx") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_statx));
        } else if (strcmp(symName, "access") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_access));
        } else if (strcmp(symName, "faccessat") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_faccessat));
        } else if (strcmp(symName, "faccessat2") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_faccessat2));
        } else if (strcmp(symName, "readlink") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_readlink));
        } else if (strcmp(symName, "readlinkat") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_readlinkat));
        } else if (strcmp(symName, "open") == 0 || strcmp(symName, "open64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_open));
        } else if (strcmp(symName, "openat") == 0 || strcmp(symName, "openat64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_openat));
        } else if (strcmp(symName, "__open_2") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe___open_2));
        } else if (strcmp(symName, "__openat_2") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe___openat_2));
        } else if (strcmp(symName, "opendir") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_opendir));
        } else if (strcmp(symName, "fopen") == 0 || strcmp(symName, "fopen64") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_fopen));
        }

        // 4. Command & process execution neutralization (eliminates shell spawning & posix_spawn signal reset)
        else if (strcmp(symName, "system") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_system));
        } else if (strcmp(symName, "popen") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_popen));
        } else if (strcmp(symName, "posix_spawn") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_posix_spawn));
        } else if (strcmp(symName, "posix_spawnp") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_posix_spawnp));
        } else if (strcmp(symName, "execve") == 0
                || strcmp(symName, "execv") == 0
                || strcmp(symName, "execl") == 0
                || strcmp(symName, "execvp") == 0
                || strcmp(symName, "execlp") == 0) {
            patchGotSlotSafe(gotSlot, reinterpret_cast<void *>(safe_execve));
        }
    }
}

static std::atomic<bool> sHdmpveGotHooked{false};
static std::mutex sHookedGotLibsMutex;
static std::vector<uintptr_t> sHookedGotLibs;

static int hookTargetLibrariesGotCallback(struct dl_phdr_info *info, size_t /*size*/, void * /*data*/) {
    if (!info || !info->dlpi_name || info->dlpi_name[0] == '\0' || !info->dlpi_phdr) {
        return 0;
    }
    const char *name = info->dlpi_name;

    // Never hook Android OS system, framework, or runtime libraries
    if (strncmp(name, "/system/", 8) == 0
            || strncmp(name, "/apex/", 6) == 0
            || strncmp(name, "/vendor/", 8) == 0
            || strncmp(name, "/product/", 9) == 0
            || strncmp(name, "/system_ext/", 12) == 0) {
        return 0;
    }
    if (strstr(name, "libart.so") != nullptr
            || strstr(name, "libc.so") != nullptr
            || strstr(name, "libdl.so") != nullptr
            || strstr(name, "libm.so") != nullptr
            || strstr(name, "liblog.so") != nullptr
            || strstr(name, "linker") != nullptr
            || strstr(name, "libFeaturesticCore.so") != nullptr) {
        return 0;
    }

    bool isHdmpve = (strstr(name, "libhdmpve") != nullptr || strstr(name, "hdmpve") != nullptr);
    bool isUE4 = (strstr(name, "libUE4") != nullptr || strstr(name, "UE4") != nullptr);
    bool isGameTarget = isHdmpve
            || (strstr(name, "libanort") != nullptr)
            || (strstr(name, "libanogs") != nullptr)
            || (strstr(name, "libCrashKit") != nullptr || strstr(name, "CrashKit") != nullptr)
            || (strstr(name, "libBugly") != nullptr)
            || isUE4
            || (strstr(name, "libtersafe") != nullptr || strstr(name, "tersafe") != nullptr)
            || (strstr(name, "libturingmm") != nullptr)
            || (strstr(name, "libapollo") != nullptr || strstr(name, "apollo") != nullptr)
            || (strstr(name, "libgcloud") != nullptr)
            || (strstr(name, "libIMSDK") != nullptr)
            || (strstr(name, "libTDataMaster") != nullptr)
            || (strstr(name, "libpandora") != nullptr)
            || (strstr(name, "libbgmi") != nullptr)
            || (strstr(name, "libDARK") != nullptr)
            || (strstr(name, "FeaturesticPlugin") != nullptr)
            || (strstr(name, "/data/app/") != nullptr)
            || (strstr(name, "/data/data/") != nullptr)
            || (strstr(name, "/data/user/") != nullptr)
            || (strstr(name, ".apk!") != nullptr);

    if (!isGameTarget) {
        return 0;
    }

    bool hookMemoryAllocator = isGameTarget && !isUE4;

    {
        std::lock_guard<std::mutex> lk(sHookedGotLibsMutex);
        for (uintptr_t hookedBase : sHookedGotLibs) {
            if (hookedBase == info->dlpi_addr) return 0;
        }
        sHookedGotLibs.push_back(info->dlpi_addr);
    }

    ALOGI("FeaturesticCore: Scoped GOT hook target detected: %s (base=%p)",
          info->dlpi_name, reinterpret_cast<void *>(info->dlpi_addr));

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
            processElfRelaTable(info->dlpi_addr, jmprel, jmprelSize / sizeof(Elf64_Rela), symtab, strtab, hookMemoryAllocator);
        }
        if (rela && relaSize >= sizeof(Elf64_Rela)) {
            processElfRelaTable(info->dlpi_addr, rela, relaSize / sizeof(Elf64_Rela), symtab, strtab, hookMemoryAllocator);
        }
        if (isHdmpve) {
            sHdmpveGotHooked.store(true, std::memory_order_release);
        }
        ALOGI("FeaturesticCore: Successfully hooked %s GOT entries!", info->dlpi_name);
        return 0;
    }
    return 0;
}

static void tryHookTargetLibrariesGot() {
    dl_iterate_phdr(hookTargetLibrariesGotCallback, nullptr);
}

static void *targetLibsGotWatcherThreadMain(void * /*arg*/) {
    prctl(PR_SET_NAME, reinterpret_cast<unsigned long>("target:gotguard"), 0, 0, 0);
    // Initial ultra-fast scan: every 25ms for first 5s (catches libbgmi/libDARK in JNI_OnLoad)
    for (int i = 0; i < 200; ++i) {
        installOrReassertPassiveNativeCrashDiagnostics();
        tryHookTargetLibrariesGot();
        struct timespec ts{0, 25 * 1000 * 1000L}; // 25ms
        nanosleep(&ts, nullptr);
    }
    // Mid-phase scan: every 250ms for next 10s
    for (int i = 0; i < 40; ++i) {
        installOrReassertPassiveNativeCrashDiagnostics();
        tryHookTargetLibrariesGot();
        struct timespec ts{0, 250 * 1000 * 1000L}; // 250ms
        nanosleep(&ts, nullptr);
    }
    // Continuous watchdog: checks every 1000ms indefinitely throughout game lifetime
    while (true) {
        installOrReassertPassiveNativeCrashDiagnostics();
        tryHookTargetLibrariesGot();
        struct timespec ts{1, 0}; // 1000ms
        nanosleep(&ts, nullptr);
    }
    return nullptr;
}

static void startTargetLibsWatcherThread() {
    static std::atomic<bool> sStarted{false};
    bool expected = false;
    if (sStarted.compare_exchange_strong(expected, true)) {
        pthread_t tid;
        pthread_create(&tid, nullptr, targetLibsGotWatcherThreadMain, nullptr);
        pthread_detach(tid);
    }
}

static inline void startHdmpveWatcherThread() {
    startTargetLibsWatcherThread();
}
static inline void tryHookHdmpveGot() {
    tryHookTargetLibrariesGot();
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

static void passiveNativeCrashDiagnosticsHandler(int sig, siginfo_t *info, void *ucontext);

static void chainPreviousSignal(int sig, siginfo_t *info, void *ucontext) {
    if (sig == SIGQUIT) return; // Suppress SIGQUIT unconditionally
    struct sigaction *prev = (sig == SIGSEGV) ? &sOldSigsegvAction
            : ((sig == SIGBUS) ? &sOldSigbusAction
            : ((sig == SIGILL) ? &sOldSigillAction
            : ((sig == SIGQUIT) ? &sOldSigquitAction : &sOldSigabrtAction)));
    if (prev->sa_flags & SA_SIGINFO) {
        if (prev->sa_sigaction != nullptr
                && reinterpret_cast<void *>(prev->sa_sigaction) != reinterpret_cast<void *>(&chainPreviousSignal)
                && reinterpret_cast<void *>(prev->sa_sigaction) != reinterpret_cast<void *>(&passiveNativeCrashDiagnosticsHandler)) {
            void *targetFn = reinterpret_cast<void *>(prev->sa_sigaction);
            xdl_info_t fnInfo{};
            void *cache = nullptr;
            if (xdl_addr(targetFn, &fnInfo, &cache) != 0 && fnInfo.dli_fname != nullptr) {
                if (strstr(fnInfo.dli_fname, "libsigchain.so") != nullptr
                        || strstr(fnInfo.dli_fname, "libart.so") != nullptr
                        || strstr(fnInfo.dli_fname, "com.android.art") != nullptr) {
                    // DO NOT call libsigchain.so / libart.so: we were called BY libsigchain!
                    // Calling it directly causes an infinite recursion stack overflow crash.
                    if (cache) xdl_addr_clean(&cache);
                    return;
                }
                if (strstr(fnInfo.dli_fname, "CrashKit") != nullptr || strstr(fnInfo.dli_fname, "Bugly") != nullptr) {
                    ALOGW("FEATURESTIC GUARD: Suppressed chaining signal %d to anti-cheat reporter (%s)", sig, fnInfo.dli_fname);
                    if (cache) xdl_addr_clean(&cache);
                    return;
                }
            }
            if (cache) xdl_addr_clean(&cache);
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
static int (*sOrigPthreadKill)(pthread_t, int) = nullptr;
static void (*sOrigAbort)(void) = nullptr;
static void (*sOrigExit)(int) = nullptr;
static void (*sOrig_Exit)(int) = nullptr;
static long (*sOrigSyscall)(long, ...) = nullptr;

static void initRealLibcProcessFunctions() {
    static std::atomic<bool> sInitDone{false};
    if (sInitDone.exchange(true)) return;
    void *k = dlsym(RTLD_DEFAULT, "kill");
    if (k) sOrigKill = reinterpret_cast<int (*)(pid_t, int)>(k);
    void *tk = dlsym(RTLD_DEFAULT, "tgkill");
    if (tk) sOrigTgkill = reinterpret_cast<int (*)(pid_t, pid_t, int)>(tk);
    void *r = dlsym(RTLD_DEFAULT, "raise");
    if (r) sOrigRaise = reinterpret_cast<int (*)(int)>(r);
    void *pk = dlsym(RTLD_DEFAULT, "pthread_kill");
    if (pk) sOrigPthreadKill = reinterpret_cast<int (*)(pthread_t, int)>(pk);
    void *e = dlsym(RTLD_DEFAULT, "exit");
    if (e) sOrigExit = reinterpret_cast<void (*)(int)>(e);
    void *_e = dlsym(RTLD_DEFAULT, "_exit");
    if (_e) sOrig_Exit = reinterpret_cast<void (*)(int)>(_e);
    void *a = dlsym(RTLD_DEFAULT, "abort");
    if (a) sOrigAbort = reinterpret_cast<void (*)(void)>(a);
    void *sc = dlsym(RTLD_DEFAULT, "syscall");
    if (sc) sOrigSyscall = reinterpret_cast<long (*)(long, ...)>(sc);
    void *sa = dlsym(RTLD_DEFAULT, "sigaction");
    if (sa) sOrigSigaction = reinterpret_cast<int (*)(int, const struct sigaction *, struct sigaction *)>(sa);
    void *sa64 = dlsym(RTLD_DEFAULT, "sigaction64");
    if (sa64) sOrigSigaction64 = reinterpret_cast<int (*)(int, const struct sigaction *, struct sigaction *)>(sa64);
    void *spm = dlsym(RTLD_DEFAULT, "sigprocmask");
    if (spm) sOrigSigprocmask = reinterpret_cast<int (*)(int, const sigset_t *, sigset_t *)>(spm);
    void *spm64 = dlsym(RTLD_DEFAULT, "sigprocmask64");
    if (spm64) sOrigSigprocmask64 = reinterpret_cast<int (*)(int, const sigset64_t *, sigset64_t *)>(spm64);
    void *psm = dlsym(RTLD_DEFAULT, "pthread_sigmask");
    if (psm) sOrigPthreadSigmask = reinterpret_cast<int (*)(int, const sigset_t *, sigset_t *)>(psm);
}

static int safe_kill(pid_t pid, int sig) {
    initRealLibcProcessFunctions();
    if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT)
            && (pid == getpid() || pid == 0 || pid == -1)) {
        ALOGW("FEATURESTIC GUARD: Suppressed kill(pid=%d, sig=%d) from tid=%d", static_cast<int>(pid), sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigKill ? sOrigKill(pid, sig) : 0;
}

static int safe_tgkill(pid_t tgid, pid_t tid, int sig) {
    initRealLibcProcessFunctions();
    if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT)
            && (tgid == getpid() || tgid == 0 || tgid == -1)) {
        ALOGW("FEATURESTIC GUARD: Suppressed tgkill(tgid=%d, tid=%d, sig=%d) from tid=%d",
              static_cast<int>(tgid), static_cast<int>(tid), sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigTgkill ? sOrigTgkill(tgid, tid, sig) : 0;
}

static int safe_raise(int sig) {
    initRealLibcProcessFunctions();
    if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGKILL || sig == SIGQUIT) {
        ALOGW("FEATURESTIC GUARD: Suppressed raise(sig=%d) from tid=%d", sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigRaise ? sOrigRaise(sig) : 0;
}

static int safe_pthread_kill(pthread_t thread, int sig) {
    initRealLibcProcessFunctions();
    if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGKILL || sig == SIGQUIT) {
        ALOGW("FEATURESTIC GUARD: Suppressed pthread_kill(thread=%p, sig=%d) from tid=%d",
              reinterpret_cast<void *>(thread), sig, static_cast<int>(gettid()));
        return 0;
    }
    return sOrigPthreadKill ? sOrigPthreadKill(thread, sig) : 0;
}

static inline bool isTerminalOrPtsPath(const char *path);
static inline bool isPtsDirFd(int dirfd);

static long safe_syscall(long number, long a1, long a2, long a3, long a4, long a5, long a6) {
    initRealLibcProcessFunctions();
#if defined(__aarch64__)
    if (number == 94 /* __NR_exit_group */) {
        safe__exit(static_cast<int>(a1));
        return 0;
    }
    if (number == 93 /* __NR_exit */) {
        terminateCallingThreadCleanly(static_cast<int>(a1));
        return 0;
    }
    if (number == 129 /* __NR_kill */) {
        return safe_kill(static_cast<pid_t>(a1), static_cast<int>(a2));
    }
    if (number == 131 /* __NR_tgkill */) {
        return safe_tgkill(static_cast<pid_t>(a1), static_cast<pid_t>(a2), static_cast<int>(a3));
    }
    if (number == 130 /* __NR_tkill */) {
        return safe_tgkill(getpid(), static_cast<pid_t>(a1), static_cast<int>(a2));
    }
    if (number == 134 /* __NR_rt_sigaction */) {
        int sig = static_cast<int>(a1);
        if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL || sig == SIGABRT || sig == SIGSYS || sig == SIGQUIT) {
            struct sigaction *oldact = reinterpret_cast<struct sigaction *>(a3);
            if (oldact != nullptr) {
                *oldact = (sig == SIGSEGV) ? sOldSigsegvAction
                        : ((sig == SIGBUS) ? sOldSigbusAction
                        : ((sig == SIGILL) ? sOldSigillAction
                        : ((sig == SIGQUIT) ? sOldSigquitAction : sOldSigabrtAction)));
            }
            return 0; // Unconditionally prevent overwriting or stripping protective handlers in kernel
        }
    }
    if (number == 135 /* __NR_rt_sigprocmask */) {
        sigset_t *set = reinterpret_cast<sigset_t *>(a2);
        if (set != nullptr && (a1 == SIG_BLOCK || a1 == SIG_SETMASK)) {
            stripDangerousSignals(set);
        }
    }
    if (number == 132 /* __NR_sigaltstack */) {
        if (a1 != 0) {
            const auto *ss = reinterpret_cast<const stack_t *>(a1);
            if ((ss->ss_flags & 2 /* SS_DISABLE */) || ss->ss_sp == nullptr || ss->ss_size < MINSIGSTKSZ) {
                if (a2 != 0 && sOrigSyscall) {
                    return sOrigSyscall(132, 0, a2, 0, 0, 0, 0);
                }
                return 0;
            }
        }
    }
    if (number == 439 /* __NR_faccessat2 */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 291 /* __NR_statx */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 79 /* __NR_newfstatat */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 56 /* __NR_openat */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 48 /* __NR_faccessat */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 78 /* __NR_readlinkat */) {
        const char *p = reinterpret_cast<const char *>(a2);
        if (isTerminalOrPtsPath(p) || (a1 != -100 /* AT_FDCWD */ && isPtsDirFd(static_cast<int>(a1)))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 80 /* __NR_fstat */) {
        if (isPtsDirFd(static_cast<int>(a1))) {
            errno = ENOENT;
            return -1;
        }
    }
    if (number == 221 /* __NR_execve */ || number == 281 /* __NR_execveat */) {
        ALOGW("FEATURESTIC GUARD: Blocked raw syscall execve(%ld)", number);
        errno = EACCES;
        return -1;
    }
#endif
    if (sOrigSyscall) {
        return sOrigSyscall(number, a1, a2, a3, a4, a5, a6);
    }
    return 0;
}

static void terminateCallingThreadCleanly(int code) {
#if defined(__aarch64__)
    register long x8 __asm__("x8") = 93; // __NR_exit: terminate ONLY calling worker thread cleanly
    register long x0 __asm__("x0") = static_cast<long>(code);
    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
#else
    syscall(SYS_exit, code);
#endif
}

static void safe_exit(int code) {
    initRealLibcProcessFunctions();
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    pid_t tid = gettid();
    pid_t pid = getpid();
    ALOGW("FEATURESTIC GUARD: Intercepted exit(%d) from tid=%d (name=%s)",
          code, static_cast<int>(tid), tname[0] ? tname : "?");
    if (tid != pid && !isCriticalGameOrMainThread() && !isGraphicsOrRenderThread(tname) && !isArtInternalDaemonThread(tname)) {
        ALOGW("FEATURESTIC GUARD: Terminating worker thread calling exit(%d) tid=%d (%s) cleanly",
              code, static_cast<int>(tid), tname[0] ? tname : "?");
        terminateCallingThreadCleanly(code);
        return;
    }
    ALOGW("FEATURESTIC GUARD: Suppressed critical process exit(%d) from tid=%d (%s) to protect game container",
          code, static_cast<int>(tid), tname[0] ? tname : "?");
}

static void safe__exit(int code) {
    initRealLibcProcessFunctions();
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    pid_t tid = gettid();
    pid_t pid = getpid();
    ALOGW("FEATURESTIC GUARD: Intercepted _exit(%d) from tid=%d (name=%s)",
          code, static_cast<int>(tid), tname[0] ? tname : "?");
    if (tid != pid && !isCriticalGameOrMainThread() && !isGraphicsOrRenderThread(tname) && !isArtInternalDaemonThread(tname)) {
        ALOGW("FEATURESTIC GUARD: Terminating worker thread calling _exit(%d) tid=%d (%s) cleanly",
              code, static_cast<int>(tid), tname[0] ? tname : "?");
        terminateCallingThreadCleanly(code);
        return;
    }
    ALOGW("FEATURESTIC GUARD: Suppressed critical process _exit(%d) from tid=%d (%s) to protect game container",
          code, static_cast<int>(tid), tname[0] ? tname : "?");
}

static void safe_abort(void) {
    initRealLibcProcessFunctions();
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    pid_t tid = gettid();
    pid_t pid = getpid();
    ALOGW("FEATURESTIC GUARD: Intercepted abort() from tid=%d (name=%s)",
          static_cast<int>(tid), tname[0] ? tname : "?");
    if (tid != pid && !isArtInternalDaemonThread(tname)) {
        ALOGW("FEATURESTIC GUARD: Terminating worker thread calling abort() tid=%d (%s) cleanly",
              static_cast<int>(tid), tname[0] ? tname : "?");
        terminateCallingThreadCleanly(0);
        return;
    }
    ALOGW("FEATURESTIC GUARD: Suppressed critical process abort() from tid=%d (%s) to protect game container",
          static_cast<int>(tid), tname[0] ? tname : "?");
}

static void (*sOrigStackChkFail)(void) = nullptr;

static void safe_stack_chk_fail(void) {
    char tname[32] = {0};
    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
    ALOGW("FEATURESTIC GUARD: Intercepted __stack_chk_fail() from tid=%d (%s)",
          static_cast<int>(gettid()), tname[0] ? tname : "?");
    if (gettid() != getpid()) {
        ALOGW("FEATURESTIC GUARD: Terminating rogue thread stack failure tid=%d (%s) cleanly",
              static_cast<int>(gettid()), tname[0] ? tname : "?");
        terminateCallingThreadCleanly(0);
        return;
    }
    return;
}

static int (*sOrigOpenat)(int, const char *, int, mode_t) = nullptr;
static int (*sOrigOpen)(const char *, int, mode_t) = nullptr;
static int (*sOrigStat)(const char *, struct stat *) = nullptr;
static int (*sOrigLstat)(const char *, struct stat *) = nullptr;
static int (*sOrigFstatat)(int, const char *, struct stat *, int) = nullptr;
static int (*sOrigFstat)(int, struct stat *) = nullptr;
static int (*sOrigAccess)(const char *, int) = nullptr;
static int (*sOrigFaccessat)(int, const char *, int, int) = nullptr;
static int (*sOrigFaccessat2)(int, const char *, int, int) = nullptr;
static int (*sOrigStatx)(int, const char *, int, unsigned int, void *) = nullptr;
static ssize_t (*sOrigReadlink)(const char *, char *, size_t) = nullptr;
static ssize_t (*sOrigReadlinkat)(int, const char *, char *, size_t) = nullptr;
static DIR *(*sOrigOpendir)(const char *) = nullptr;
static FILE *(*sOrigFopen)(const char *, const char *) = nullptr;

static void initRealLibcFsFunctions() {
    static std::atomic<bool> sInitDone{false};
    if (sInitDone.exchange(true)) return;
    void *oat = dlsym(RTLD_DEFAULT, "openat");
    if (oat) sOrigOpenat = reinterpret_cast<int (*)(int, const char *, int, mode_t)>(oat);
    void *o = dlsym(RTLD_DEFAULT, "open");
    if (o) sOrigOpen = reinterpret_cast<int (*)(const char *, int, mode_t)>(o);
    void *fst = dlsym(RTLD_DEFAULT, "fstatat");
    if (!fst) fst = dlsym(RTLD_DEFAULT, "fstatat64");
    if (!fst) fst = dlsym(RTLD_DEFAULT, "newfstatat");
    if (fst) sOrigFstatat = reinterpret_cast<int (*)(int, const char *, struct stat *, int)>(fst);
    void *fst1 = dlsym(RTLD_DEFAULT, "fstat");
    if (!fst1) fst1 = dlsym(RTLD_DEFAULT, "fstat64");
    if (fst1) sOrigFstat = reinterpret_cast<int (*)(int, struct stat *)>(fst1);
    void *st = dlsym(RTLD_DEFAULT, "stat");
    if (!st) st = dlsym(RTLD_DEFAULT, "stat64");
    if (st) sOrigStat = reinterpret_cast<int (*)(const char *, struct stat *)>(st);
    void *lst = dlsym(RTLD_DEFAULT, "lstat");
    if (!lst) lst = dlsym(RTLD_DEFAULT, "lstat64");
    if (lst) sOrigLstat = reinterpret_cast<int (*)(const char *, struct stat *)>(lst);
    void *acc = dlsym(RTLD_DEFAULT, "access");
    if (acc) sOrigAccess = reinterpret_cast<int (*)(const char *, int)>(acc);
    void *facc = dlsym(RTLD_DEFAULT, "faccessat");
    if (facc) sOrigFaccessat = reinterpret_cast<int (*)(int, const char *, int, int)>(facc);
    void *fa2 = dlsym(RTLD_DEFAULT, "faccessat2");
    if (fa2) sOrigFaccessat2 = reinterpret_cast<int (*)(int, const char *, int, int)>(fa2);
    void *sx = dlsym(RTLD_DEFAULT, "statx");
    if (sx) sOrigStatx = reinterpret_cast<int (*)(int, const char *, int, unsigned int, void *)>(sx);
    void *rl = dlsym(RTLD_DEFAULT, "readlink");
    if (rl) sOrigReadlink = reinterpret_cast<ssize_t (*)(const char *, char *, size_t)>(rl);
    void *rla = dlsym(RTLD_DEFAULT, "readlinkat");
    if (rla) sOrigReadlinkat = reinterpret_cast<ssize_t (*)(int, const char *, char *, size_t)>(rla);
    void *od = dlsym(RTLD_DEFAULT, "opendir");
    if (od) sOrigOpendir = reinterpret_cast<DIR *(*)(const char *)>(od);
    void *fo = dlsym(RTLD_DEFAULT, "fopen");
    if (fo) sOrigFopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(fo);
}

static inline bool isTerminalOrPtsPath(const char *path) {
    if (path == nullptr || path[0] == '\0') return false;
    if (strncmp(path, "/dev/pts", 8) == 0
            || strcmp(path, "/dev/ptmx") == 0
            || strncmp(path, "/dev/tty", 8) == 0
            || strcmp(path, "/dev/console") == 0
            || strstr(path, "devpts") != nullptr
            || strstr(path, "dev/pts") != nullptr
            || strstr(path, "/pts/") != nullptr
            || strncmp(path, "pts/", 4) == 0
            || strcmp(path, "pts") == 0) {
        return true;
    }
    return false;
}

static inline bool isPtsDirFd(int dirfd) {
    if (dirfd < 0 || dirfd == AT_FDCWD) return false;
    char linkPath[64];
    char target[128];
    snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", dirfd);
    ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
    if (len > 0) {
        target[len] = '\0';
        if (strstr(target, "/dev/pts") != nullptr || strstr(target, "devpts") != nullptr || strstr(target, "/dev/ptmx") != nullptr || strstr(target, "/pts/") != nullptr) return true;
    }
    return false;
}

static int safe_stat(const char *pathname, struct stat *statbuf) {
    if (isTerminalOrPtsPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFstatat) {
        return sOrigFstatat(AT_FDCWD, redirected ? redirected : pathname, statbuf, 0);
    }
    if (sOrigStat) {
        return sOrigStat(redirected ? redirected : pathname, statbuf);
    }
    return -1;
}

static int safe_lstat(const char *pathname, struct stat *statbuf) {
    if (isTerminalOrPtsPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFstatat) {
        return sOrigFstatat(AT_FDCWD, redirected ? redirected : pathname, statbuf, AT_SYMLINK_NOFOLLOW);
    }
    if (sOrigLstat) {
        return sOrigLstat(redirected ? redirected : pathname, statbuf);
    }
    return -1;
}

static int safe_fstatat(int dirfd, const char *pathname, struct stat *statbuf, int flags) {
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFstatat) {
        return sOrigFstatat(dirfd, redirected ? redirected : pathname, statbuf, flags);
    }
    return -1;
}

static int safe_fstat(int fd, struct stat *statbuf) {
    if (isPtsDirFd(fd)) {
        errno = ENOENT;
        return -1;
    }
    initRealLibcFsFunctions();
    if (sOrigFstat) {
        return sOrigFstat(fd, statbuf);
    }
    return -1;
}

static int safe_access(const char *pathname, int mode) {
    if (isTerminalOrPtsPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigAccess) {
        return sOrigAccess(redirected ? redirected : pathname, mode);
    }
    return -1;
}

static int safe_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFaccessat) {
        return sOrigFaccessat(dirfd, redirected ? redirected : pathname, mode, flags);
    }
    return -1;
}

static int safe_faccessat2(int dirfd, const char *pathname, int mode, int flags) {
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFaccessat2) {
        return sOrigFaccessat2(dirfd, redirected ? redirected : pathname, mode, flags);
    }
    if (sOrigFaccessat) {
        return sOrigFaccessat(dirfd, redirected ? redirected : pathname, mode, flags);
    }
    return -1;
}

static int safe_statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf) {
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigStatx) {
        return sOrigStatx(dirfd, redirected ? redirected : pathname, flags, mask, statxbuf);
    }
    return -1;
}

static ssize_t safe_readlink(const char *pathname, char *buf, size_t bufsiz) {
    if (isTerminalOrPtsPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigReadlink) {
        return sOrigReadlink(redirected ? redirected : pathname, buf, bufsiz);
    }
    return -1;
}

static ssize_t safe_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigReadlinkat) {
        return sOrigReadlinkat(dirfd, redirected ? redirected : pathname, buf, bufsiz);
    }
    return -1;
}

static int safe_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    if (isTerminalOrPtsPath(pathname) || (dirfd != AT_FDCWD && isPtsDirFd(dirfd))) {
        errno = ENOENT;
        return -1;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigOpenat) {
        return sOrigOpenat(dirfd, redirected ? redirected : pathname, flags, mode);
    }
    return -1;
}

static int safe_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    return safe_openat(AT_FDCWD, pathname, flags, mode);
}

static int safe___openat_2(int dirfd, const char *pathname, int flags) {
    return safe_openat(dirfd, pathname, flags, 0);
}

static int safe___open_2(const char *pathname, int flags) {
    return safe_openat(AT_FDCWD, pathname, flags, 0);
}

static DIR *safe_opendir(const char *name) {
    if (isTerminalOrPtsPath(name)) {
        errno = ENOENT;
        return nullptr;
    }
    initRealLibcFsFunctions();
    return sOrigOpendir ? sOrigOpendir(name) : nullptr;
}

static FILE *safe_fopen(const char *pathname, const char *mode) {
    if (isTerminalOrPtsPath(pathname)) {
        errno = ENOENT;
        return nullptr;
    }
    const char *redirected = IO::redirectPath(pathname);
    initRealLibcFsFunctions();
    if (sOrigFopen) {
        return sOrigFopen(redirected ? redirected : pathname, mode);
    }
    return nullptr;
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

    // Suppress SIGQUIT (Signal 3) from screenshot gestures or ANR traces
    if (sig == SIGQUIT) {
        ALOGW("FEATURESTIC GUARD: Suppressed SIGQUIT (Signal 3) on tid=%d (%s)",
              static_cast<int>(gettid()), tname[0] ? tname : "?");
        return;
    }

    // 1. Suppress all userspace software kills (tgkill / kill / tkill)
    if (info != nullptr && info->si_code <= 0) {
        ALOGW("FEATURESTIC GUARD: Suppressed software kill signal %d (code=%d, sender_pid=%d) on tid=%d (%s)",
              sig, siCode, info->si_pid, static_cast<int>(gettid()), tname[0] ? tname : "?");
        if (sig == SIGABRT && gettid() != getpid() && !isArtInternalDaemonThread(tname)) {
            ALOGW("FEATURESTIC GUARD: Cleanly terminating aborted worker thread tid=%d (%s)",
                  static_cast<int>(gettid()), tname[0] ? tname : "?");
            terminateCallingThreadCleanly(0);
        }
        return;
    }

    // 2. ART Internal GC & Daemon Threads (HeapTaskDaemon, FinalizerDaemon, ReferenceQueueDaemon, JIT, etc.):
    // ART actively uses SIGSEGV for Concurrent Mark-Compact (CMC) GC read barriers, userfaultfd, and safepoints.
    // Advancing PC or suppressing signals on ART threads corrupts ART mutexes (mutex.cc) and permanently deadlocks the runtime!
    // Simply return so ART's own FaultManager handles it naturally without chaining recursion.
    if (isArtInternalDaemonThread(tname)) {
        return;
    }

    // 3. Log crash offset diagnostics for every real fault
    recordNativeCrashOffsetLog(sig, siCode, fAddr, ucontext);

    // 4. Special dedicated handling for SIGBUS (Bus error):
    // Anti-cheat intentionally dereferences misaligned or unmapped memory to raise SIGBUS.
    // Never allow SIGBUS to terminate the process!
    if (sig == SIGBUS) {
        if (!isArtInternalDaemonThread(tname) && gettid() != getpid()) {
            ALOGW("FEATURESTIC RESCUE: Safely terminating rogue worker thread tid=%d (%s) on signal %d cleanly",
                  static_cast<int>(gettid()), tname[0] ? tname : "?", sig);
            terminateCallingThreadCleanly(0);
            return;
        }
#if defined(__aarch64__)
        if (ucontext != nullptr) {
            auto *uc = reinterpret_cast<ucontext_t *>(ucontext);
            uintptr_t currentPc = static_cast<uintptr_t>(uc->uc_mcontext.pc);
            ALOGW("FEATURESTIC RESCUE: Rescuing SIGBUS on critical/main thread PC %p (faultAddr=%p) tid=%d (%s)",
                  reinterpret_cast<void *>(currentPc), reinterpret_cast<void *>(fAddr),
                  static_cast<int>(gettid()), tname[0] ? tname : "?");
            if (currentPc >= 0x10000 && isPageMappedSafe(currentPc)) {
                uint32_t instr = *reinterpret_cast<const uint32_t *>(currentPc);
                int rt = instr & 0x1F;
                if (rt < 31) {
                    uc->uc_mcontext.regs[rt] = sLowMemoryCushionBase;
                }
                uc->uc_mcontext.pc += 4;
                return;
            }
            uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
            if (lr >= 0x10000 && isPageMappedSafe(lr)) {
                uc->uc_mcontext.pc = lr;
                uc->uc_mcontext.regs[0] = 0;
                return;
            }
            uc->uc_mcontext.pc += 4;
            return;
        }
#endif
        return;
    }

    // 5. Worker Thread Isolation for SIGSEGV, SIGILL:
    // If a non-critical worker/anti-cheat thread faults, terminate ONLY this thread cleanly using kernel __NR_exit
    if (sig == SIGSEGV || sig == SIGILL) {
        if (!isCriticalGameOrMainThread() && !isArtInternalDaemonThread(tname) && gettid() != getpid()) {
            ALOGW("FEATURESTIC RESCUE: Safely terminating rogue worker thread tid=%d (%s) on signal %d cleanly",
                  static_cast<int>(gettid()), tname[0] ? tname : "?", sig);
            terminateCallingThreadCleanly(0);
            return;
        }
    }

#if defined(__aarch64__)
    if (ucontext != nullptr) {
        auto *uc = reinterpret_cast<ucontext_t *>(ucontext);
        uintptr_t currentPc = static_cast<uintptr_t>(uc->uc_mcontext.pc);

        if (sig == SIGILL) {
            static __thread int tConsecutiveIllFaults = 0;
            static __thread uint64_t tLastIllTimeMs = 0;
            struct timespec ts{};
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t nowMs = (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
            if (nowMs - tLastIllTimeMs > 2000) tConsecutiveIllFaults = 0;
            tLastIllTimeMs = nowMs;
            tConsecutiveIllFaults++;

            if (tConsecutiveIllFaults <= 3) {
                ALOGW("FEATURESTIC RESCUE: Skipping illegal instruction at PC %p on tid=%d (%s)",
                      reinterpret_cast<void *>(currentPc), static_cast<int>(gettid()), tname[0] ? tname : "?");
                uc->uc_mcontext.pc += 4;
                return;
            }

            // Too many consecutive illegal instructions — abort call safely or terminate rogue thread
            uintptr_t callerPc = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
            if (callerPc >= 0x10000 && isPageMappedSafe(callerPc)) {
                ALOGW("FEATURESTIC RESCUE: Safely aborting illegal instruction loop on tid=%d (%s) -> caller %p",
                      static_cast<int>(gettid()), tname[0] ? tname : "?", reinterpret_cast<void *>(callerPc));
                uc->uc_mcontext.pc = callerPc;
                uc->uc_mcontext.regs[0] = 0;
                return;
            }

            if (!isCriticalGameOrMainThread() && gettid() != getpid()) {
                ALOGW("FEATURESTIC RESCUE: Terminating runaway illegal instruction thread tid=%d (%s) cleanly",
                      static_cast<int>(gettid()), tname[0] ? tname : "?");
                terminateCallingThreadCleanly(0);
                return;
            }
            return;
        }

        // 3. Inspect PC & instruction: If fault is in ART runtime, boot image, OAT, ODEX,
        // or JIT generated code, or is an implicit suspend/null check,
        // let ART's FaultManager handle it naturally!
        // Never step over PC or forge returns inside ART or compiled Java bytecode!
        uint32_t currentInstr = (currentPc >= 0x10000 && isPageMappedSafe(currentPc))
                ? *reinterpret_cast<const uint32_t *>(currentPc) : 0;
        if (isArtOrManagedCode(currentPc, fAddr, currentInstr)) {
            return;
        }

        if (sig == SIGBUS || sig == SIGSEGV) {
            static __thread int tConsecutiveFaults = 0;
            static __thread int tTotalFaults = 0;
            static __thread uint64_t tLastFaultTimeMs = 0;
            static __thread uintptr_t tLastFaultPc = 0;
            static __thread int tSamePcCount = 0;

            struct timespec ts{};
            clock_gettime(CLOCK_MONOTONIC, &ts);
            uint64_t nowMs = (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
            if (nowMs - tLastFaultTimeMs > 2000) {
                tConsecutiveFaults = 0;
            }
            tLastFaultTimeMs = nowMs;
            tConsecutiveFaults++;
            tTotalFaults++;

            if (currentPc == tLastFaultPc) {
                tSamePcCount++;
            } else {
                tLastFaultPc = currentPc;
                tSamePcCount = 1;
            }

            bool isCritical = isCriticalGameOrMainThread() || isGraphicsOrRenderThread(tname) || (gettid() == getpid());
            bool isRogue = isRogueWorkerThread(tname);

            // Rogue worker thread stuck in an infinite cascade loop (> 4 faults in 2s or > 8 lifetime faults):
            // Quiesce into low-power kernel sleep so OS thread remains valid for ART GC without burning CPU!
            if ((tConsecutiveFaults > 4 || tTotalFaults > 8) && !isCritical && !isArtInternalDaemonThread(tname) && gettid() != getpid()) {
                ALOGW("FEATURESTIC RESCUE: Terminating runaway worker thread tid=%d (%s) after %d cascade faults cleanly",
                      static_cast<int>(gettid()), tname[0] ? tname : "?", tConsecutiveFaults);
                terminateCallingThreadCleanly(0);
                return;
            }

            if (tConsecutiveFaults <= 5) {
                ALOGW("FEATURESTIC RESCUE: Rescuing signal %d at PC %p (addr=%p) on tid=%d (%s) [fault #%d, samePcCount=%d]",
                      sig, reinterpret_cast<void *>(currentPc), reinterpret_cast<void *>(fAddr),
                      static_cast<int>(gettid()), tname[0] ? tname : "?", tConsecutiveFaults, tSamePcCount);
            }

            // CASE 1: Active mapped function (currentPc is valid executable code in process)
            // NEVER forge returns to callerPc from inside an active function!
            // Doing so destroys the stack frame, corrupts frame pointer/canary, and triggers __stack_chk_fail!
            if (currentPc >= 0x10000 && isPageMappedSafe(currentPc)) {
                uint32_t instr = *reinterpret_cast<const uint32_t *>(currentPc);
                uint32_t op0 = (instr >> 25) & 0xF;
                bool isLoadStore = (op0 == 4 || op0 == 6 || op0 == 12 || op0 == 13);

                // 1A. Low-memory null-pointer dereference repair:
                // In ARM64, the base register Rn is encoded in bits [9:5] for ALL load and store variants.
                // If Rn points to null or unmapped memory, redirect Rn to our 2MB zeroed cushion
                // and RE-EXECUTE the instruction without moving PC!
                if (isLoadStore) {
                    int rn = (instr >> 5) & 0x1F;
                    if (rn < 31 && tSamePcCount <= 2) {
                        uintptr_t rnVal = static_cast<uintptr_t>(uc->uc_mcontext.regs[rn]);
                        if (rnVal < 0x10000 || !isPageMappedSafe(rnVal)) {
                            ALOGW("FEATURESTIC RESCUE: Redirecting base reg x%d (%p) to safety cushion %p on tid=%d (%s) PC %p",
                                  rn, reinterpret_cast<void *>(rnVal),
                                  reinterpret_cast<void *>(sLowMemoryCushionBase),
                                  static_cast<int>(gettid()), tname[0] ? tname : "?",
                                  reinterpret_cast<void *>(currentPc));
                            uc->uc_mcontext.regs[rn] = sLowMemoryCushionBase;
                            return; // Re-execute instruction naturally with zeroed memory cushion!
                        }
                    }
                }

                // 1B. If instruction still faults after repair or isn't a simple Rn dereference:
                // Step past the instruction safely (pc += 4)
                if (tConsecutiveFaults > 3) {
                    usleep(5000); // 5ms throttle to eliminate 100% CPU spins without deadlocks
                }

                // If it is a load instruction, set destination register(s) to safety cushion
                // so subsequent dependent instructions don't fault on garbage/null!
                int rt = instr & 0x1F;
                uintptr_t safeVal = sLowMemoryCushionBase;
                if (rt < 31) {
                    uc->uc_mcontext.regs[rt] = safeVal;
                }
                // Check for LDP (Load Pair): bits [29:28] == 10, bit 22 == 1 -> also populate Rt2 (bits 14:10)
                if ((instr & 0x3A400000) == 0x28400000) {
                    int rt2 = (instr >> 10) & 0x1F;
                    if (rt2 < 31) {
                        uc->uc_mcontext.regs[rt2] = safeVal;
                    }
                }

                uc->uc_mcontext.pc += 4;
                return;
            }

            // CASE 2: Unmapped or null PC (currentPc < 0x10000 or unmapped):
            // The thread attempted an indirect call (blr) to a null or invalid function pointer.
            // Because the callee function was never entered, no stack frame was created:
            // sp and fp are still the CALLER'S stack frame!
            // Recover safely by returning to callerPc (lr) with return value x0 = 0!
            uintptr_t lr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
            uintptr_t fp = static_cast<uintptr_t>(uc->uc_mcontext.regs[29]);
            uintptr_t callerPc = 0;
            if (lr >= 0x10000 && isPageMappedSafe(lr)) {
                callerPc = lr;
            } else if (fp >= 0x10000 && (fp & 0x7) == 0 && isPageMappedSafe(fp) && isPageMappedSafe(fp + 8)) {
                uintptr_t savedLr = *reinterpret_cast<const uintptr_t *>(fp + 8);
                if (savedLr >= 0x10000 && isPageMappedSafe(savedLr)) {
                    callerPc = savedLr;
                }
            }

            if (callerPc != 0) {
                uintptr_t retVal = isGraphicsOrRenderThread(tname) ? sLowMemoryCushionBase : 0;
                ALOGW("FEATURESTIC RESCUE: Returning to caller PC %p (x0=%p) from unmapped PC %p on tid=%d (%s)",
                      reinterpret_cast<void *>(callerPc), reinterpret_cast<void *>(retVal),
                      reinterpret_cast<void *>(currentPc), static_cast<int>(gettid()), tname[0] ? tname : "?");
                uc->uc_mcontext.pc = callerPc;
                uc->uc_mcontext.regs[0] = retVal;
                return;
            }

            // Fallback for completely unrecoverable rogue worker thread:
            if (!isCritical && !isArtInternalDaemonThread(tname) && isRogue) {
                ALOGW("FEATURESTIC RESCUE: Terminating unrecoverable rogue worker tid=%d (%s) on signal %d cleanly",
                      static_cast<int>(gettid()), tname[0] ? tname : "?", sig);
                terminateCallingThreadCleanly(0);
                return;
            }

            // Critical thread fallback: avoid freeze by stepping past
            uc->uc_mcontext.pc += 4;
            return;
        }
    }
#endif

    if (gettid() != getpid() && !isArtInternalDaemonThread(tname)) {
        ALOGW("FEATURESTIC RESCUE: Safely terminating rogue worker thread tid=%d (%s) on signal %d cleanly",
              static_cast<int>(gettid()), tname[0] ? tname : "?", sig);
        terminateCallingThreadCleanly(0);
        return;
    }

    chainPreviousSignal(sig, info, ucontext);
}

extern "C" {
    void *gOurSignalHandler = nullptr;
    void *gOurSigsysHandler = nullptr;
}

static void installOrReassertPassiveNativeCrashDiagnostics() {
    initRealLibcProcessFunctions();
    struct sigaction sa{};
    sa.sa_sigaction = passiveNativeCrashDiagnosticsHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;

    sigaction(SIGBUS, &sa, &sOldSigbusAction);
    sigaction(SIGILL, &sa, &sOldSigillAction);
    sigaction(SIGSEGV, &sa, &sOldSigsegvAction);
    sigaction(SIGABRT, &sa, &sOldSigabrtAction);
    sigaction(SIGQUIT, &sa, &sOldSigquitAction);
    sSignalGuardInstalled = true;

    sigset_t unblockSet;
    sigemptyset(&unblockSet);
    sigaddset(&unblockSet, SIGBUS);
    sigaddset(&unblockSet, SIGILL);
    sigaddset(&unblockSet, SIGSEGV);
    sigaddset(&unblockSet, SIGABRT);
    sigaddset(&unblockSet, SIGSYS);
    sigaddset(&unblockSet, SIGQUIT);
    pthread_sigmask(SIG_UNBLOCK, &unblockSet, nullptr);
    static std::atomic<bool> sLogged{false};
    if (!sLogged.exchange(true)) {
        ALOGI("FEATURESTIC GUARD: Installed passive native crash diagnostics for SIGSEGV/SIGBUS/SIGILL/SIGABRT/SIGQUIT");
    }
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
    if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL || sig == SIGQUIT) {
        if (oldact != nullptr) {
            *oldact = (sig == SIGSEGV) ? sOldSigsegvAction
                    : ((sig == SIGBUS) ? sOldSigbusAction
                    : ((sig == SIGILL) ? sOldSigillAction
                    : ((sig == SIGQUIT) ? sOldSigquitAction : sOldSigabrtAction)));
        }
        if (act != nullptr && (act->sa_flags & SA_SIGINFO) && act->sa_sigaction == passiveNativeCrashDiagnosticsHandler) {
            return origFn ? origFn(sig, act, nullptr) : 0;
        }
        // Protect all handlers: never allow anti-cheat / crash reporters to hijack or reset them!
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

static int safe_system(const char *command) {
    if (command == nullptr) {
        return 1;
    }
    ALOGI("FeaturesticCore: Intercepted and neutralized unsafe system command: %s", command);
    installOrReassertPassiveNativeCrashDiagnostics();
    return 0;
}

static FILE *safe_popen(const char *command, const char *type) {
    ALOGW("FeaturesticCore: Intercepted and blocked unsafe popen command: %s", command ? command : "(null)");
    errno = EACCES;
    return nullptr;
}

static int safe_posix_spawn(pid_t *pid, const char *path, const void * /*file_actions*/,
                            const void * /*attrp*/, char *const argv[], char *const envp[]) {
    ALOGW("FeaturesticCore: Intercepted and blocked unsafe posix_spawn: %s", path ? path : "(null)");
    errno = EACCES;
    return EACCES;
}

static int safe_posix_spawnp(pid_t *pid, const char *file, const void * /*file_actions*/,
                             const void * /*attrp*/, char *const argv[], char *const envp[]) {
    ALOGW("FeaturesticCore: Intercepted and blocked unsafe posix_spawnp: %s", file ? file : "(null)");
    errno = EACCES;
    return EACCES;
}

static int safe_execve(const char *pathname, char *const argv[], char *const envp[]) {
    ALOGW("FeaturesticCore: Intercepted and blocked unsafe execve: %s", pathname ? pathname : "(null)");
    errno = EACCES;
    return -1;
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

static void (*sOrigAndroidLogAssert)(const char *, const char *, const char *, ...) = nullptr;

static void safe_android_log_assert(const char *cond, const char *tag, const char *fmt, ...) {
    ALOGW("FEATURESTIC GUARD: Intercepted __android_log_assert(tag='%s', cond='%s')",
          tag ? tag : "", cond ? cond : "");
    // NEVER call abort() on assertion failure! Return safely so rendering & teardown continue!
}

static void installHeapAndRefBaseSafetyGuards() {
#if defined(__LP64__)
    tryHookHdmpveGot();
#endif
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
    // Disabled: Background thread polling memory and rewriting GOT tables triggers anti-cheat scans
    return;
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
                || strstr(libPath, "libCrashKit.so") != nullptr
                || strstr(libPath, "libbgmi") != nullptr
                || strstr(libPath, "libDARK") != nullptr
                || strstr(libPath, "FeaturesticPlugin") != nullptr) {
            sGameLibLoadedCount.fetch_add(1, std::memory_order_relaxed);
        }
#if defined(__LP64__)
        tryHookTargetLibrariesGot();
#endif
    }
    installOrReassertPassiveNativeCrashDiagnostics();
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

    initLowMemorySafetyCushion();
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

    initLowMemorySafetyCushion();
    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.initialized = false;
    sCachedHostUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);
    sanitizeInheritedPtyFileDescriptors();
    installOrReassertPassiveNativeCrashDiagnostics();
#if defined(__LP64__)
    startHdmpveWatcherThread();
#endif

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
    initLowMemorySafetyCushion();
    JNIEnv *env;
    VMEnv.vm = vm;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("JNI_OnLoad: GetEnv failed");
        return JNI_EVERSION;
    }

    registerMethod(env);
    return JNI_VERSION_1_6;
}
