#include "NativeVfsHook.h"
#include <IO.h>
#include <Log.h>
#include <Hook/And64InlineHook/And64InlineHook.hpp>

#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/prctl.h>
#include <signal.h>
#include <pthread.h>
#include <cstdarg>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <string>
#include <atomic>
#include <mutex>

#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>
#include <sys/mman.h>
#include <ucontext.h>

#ifndef PR_SET_NO_NEW_PRIVS
#define PR_SET_NO_NEW_PRIVS 38
#endif

#ifndef PR_SET_SECCOMP
#define PR_SET_SECCOMP 22
#endif

#ifndef SECCOMP_MODE_FILTER
#define SECCOMP_MODE_FILTER 2
#endif

#ifndef SECCOMP_RET_ALLOW
#define SECCOMP_RET_ALLOW 0x7fff0000U
#endif

#ifndef SECCOMP_RET_TRAP
#define SECCOMP_RET_TRAP 0x00030000U
#endif

#ifndef SECCOMP_RET_ERRNO
#define SECCOMP_RET_ERRNO 0x00050000U
#endif

#ifndef AUDIT_ARCH_AARCH64
#define AUDIT_ARCH_AARCH64 0xc00000b7U
#endif

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#elif defined(__arm__)
#define __NR_memfd_create 385
#endif
#endif

extern "C" {
    extern void *gOurSignalHandler;
    extern void *gOurSigsysHandler;
}

namespace {

static std::mutex sPkgMutex;
static std::string sVirtualPackageName = "com.pubg.imobile";
static std::string sVirtualProcessName = "com.pubg.imobile";
static std::atomic<bool> sHooksInstalled{false};

static thread_local bool sInVfsBypass = false;

typedef int (*openat_fn_t)(int dirfd, const char *pathname, int flags, mode_t mode);
typedef int (*open_fn_t)(const char *pathname, int flags, mode_t mode);
typedef int (*faccessat_fn_t)(int dirfd, const char *pathname, int mode, int flags);
typedef int (*access_fn_t)(const char *pathname, int mode);
typedef int (*fstatat_fn_t)(int dirfd, const char *pathname, struct stat *buf, int flags);
typedef int (*stat_fn_t)(const char *pathname, struct stat *buf);
typedef int (*fstat_fn_t)(int fd, struct stat *buf);
typedef int (*lstat_fn_t)(const char *pathname, struct stat *buf);
typedef int (*stat64_fn_t)(const char *pathname, struct stat *buf);
typedef int (*lstat64_fn_t)(const char *pathname, struct stat *buf);
typedef int (*statx_fn_t)(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf);
typedef long (*syscall_fn_t)(long number, long arg1, long arg2, long arg3, long arg4, long arg5, long arg6);
typedef ssize_t (*readlinkat_fn_t)(int dirfd, const char *pathname, char *buf, size_t bufsiz);
typedef ssize_t (*readlink_fn_t)(const char *pathname, char *buf, size_t bufsiz);

static openat_fn_t sOrigOpenat = nullptr;
static openat_fn_t sOrigOpenat64 = nullptr;
static open_fn_t sOrigOpen = nullptr;
static faccessat_fn_t sOrigFaccessat = nullptr;
static access_fn_t sOrigAccess = nullptr;
static fstatat_fn_t sOrigFstatat = nullptr;
static fstatat_fn_t sOrigFstatat64 = nullptr;
static stat_fn_t sOrigStat = nullptr;
static fstat_fn_t sOrigFstat = nullptr;
static fstat_fn_t sOrigFstat64 = nullptr;
static lstat_fn_t sOrigLstat = nullptr;
static stat64_fn_t sOrigStat64 = nullptr;
static lstat64_fn_t sOrigLstat64 = nullptr;
static statx_fn_t sOrigStatx = nullptr;
static syscall_fn_t sOrigSyscall = nullptr;
static readlinkat_fn_t sOrigReadlinkat = nullptr;
static readlink_fn_t sOrigReadlink = nullptr;

static bool isProcSelfOrPid(const char *path, const char *suffix) {
    if (!path || !suffix) return false;
    char selfPath[64];
    snprintf(selfPath, sizeof(selfPath), "/proc/self/%s", suffix);
    if (strcmp(path, selfPath) == 0) return true;

    if (strncmp(path, "/proc/", 6) == 0) {
        const char *p = path + 6;
        while (*p >= '0' && *p <= '9') p++;
        if (*p == '/' && strcmp(p + 1, suffix) == 0) {
            pid_t pid = atoi(path + 6);
            if (pid == getpid()) return true;
        }
    }
    return false;
}

static inline bool isProcMaps(const char *path) {
    return isProcSelfOrPid(path, "maps");
}

static inline bool isProcCmdline(const char *path) {
    return isProcSelfOrPid(path, "cmdline");
}

static inline bool isProcStatus(const char *path) {
    return isProcSelfOrPid(path, "status");
}

static inline bool isProcComm(const char *path) {
    return isProcSelfOrPid(path, "comm");
}

static inline bool isProcStat(const char *path) {
    return isProcSelfOrPid(path, "stat");
}

#if defined(__aarch64__)
static inline long raw_real_syscall(long number, long a0 = 0, long a1 = 0, long a2 = 0, long a3 = 0, long a4 = 0, long a5 = 0x5afe5afe) {
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = a0;
    register long x1 __asm__("x1") = a1;
    register long x2 __asm__("x2") = a2;
    register long x3 __asm__("x3") = a3;
    register long x4 __asm__("x4") = a4;
    register long x5 __asm__("x5") = a5;
    __asm__ __volatile__(
        "svc #0\n"
        : "+r"(x0)
        : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
        : "memory"
    );
    return x0;
}

static inline int raw_openat(int dirfd, const char *path, int flags, mode_t mode = 0) {
    return static_cast<int>(raw_real_syscall(56, dirfd, reinterpret_cast<long>(path), flags, mode, 0, 0x5afe5afe));
}

static inline ssize_t raw_readlinkat(int dirfd, const char *path, char *buf, size_t bufsiz) {
    return static_cast<ssize_t>(raw_real_syscall(78, dirfd, reinterpret_cast<long>(path), reinterpret_cast<long>(buf), bufsiz, 0, 0x5afe5afe));
}
#else
static inline long raw_real_syscall(long number, long a0 = 0, long a1 = 0, long a2 = 0, long a3 = 0, long a4 = 0, long a5 = 0) {
    return syscall(number, a0, a1, a2, a3, a4, a5);
}
static inline int raw_openat(int dirfd, const char *path, int flags, mode_t mode = 0) {
    return openat(dirfd, path, flags, mode);
}
static inline ssize_t raw_readlinkat(int dirfd, const char *path, char *buf, size_t bufsiz) {
    return readlinkat(dirfd, path, buf, bufsiz);
}
#endif

static inline bool isRootBinary(const char *path) {
    if (!path) return false;
    const char *lastSlash = strrchr(path, '/');
    const char *fileName = lastSlash ? lastSlash + 1 : path;
    if (strcmp(fileName, "su") == 0 || strcmp(fileName, "daemonsu") == 0
            || strcmp(fileName, "supersu") == 0 || strcmp(fileName, "superuser") == 0
            || strcmp(fileName, "busybox") == 0 || strcmp(fileName, "magisk") == 0
            || strcmp(fileName, "magiskinit") == 0 || strcmp(fileName, "ksu") == 0
            || strcmp(fileName, "apatch") == 0) {
        return true;
    }
    if (strstr(path, "/magisk") != nullptr || strstr(path, "/kernelsu") != nullptr
            || strstr(path, "/data/adb/ksu") != nullptr || strstr(path, "/data/adb/ap") != nullptr
            || strstr(path, "/data/adb/magisk") != nullptr) {
        return true;
    }
    return false;
}

static inline bool isBlockedDevPath(const char *path) {
    if (!path) return false;
    if (strstr(path, "dev/pts") != nullptr || strstr(path, "dev/ptmx") != nullptr
            || strcmp(path, "/dev/pts") == 0 || strcmp(path, "pts") == 0) {
        return true;
    }
    if (strstr(path, "/pts/") != nullptr || strncmp(path, "pts/", 4) == 0) {
        return true;
    }
    if (strstr(path, "qemu") != nullptr || strstr(path, "goldfish") != nullptr || strstr(path, "vport") != nullptr) {
        return true;
    }
    if (isRootBinary(path)) {
        return true;
    }
    if (strstr(path, "termux") != nullptr || strstr(path, "antigem") != nullptr
            || strstr(path, "xposed") != nullptr || strstr(path, "lsposed") != nullptr
            || strstr(path, "edxposed") != nullptr) {
        return true;
    }
    if (strstr(path, "/mem") != nullptr && strncmp(path, "/proc/", 6) == 0) {
        return true;
    }
    if (strstr(path, "/pagemap") != nullptr && strncmp(path, "/proc/", 6) == 0) {
        return true;
    }
    if (strstr(path, "/wchan") != nullptr || strstr(path, "/syscall") != nullptr) {
        return true;
    }
    return false;
}

static inline bool isDirFdBlocked(int dirfd, const char *pathname) {
    if (dirfd < 0 || dirfd == AT_FDCWD) return false;
    if (pathname && pathname[0] == '/') return false;
    char fdPath[64];
    snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", dirfd);
    char targetPath[PATH_MAX];
    ssize_t len = raw_readlinkat(AT_FDCWD, fdPath, targetPath, sizeof(targetPath) - 1);
    if (len < 0) {
        if (errno == EACCES || errno == EPERM) {
            return true;
        }
    } else if (len > 0) {
        targetPath[len] = '\0';
        if (isBlockedDevPath(targetPath)) {
            return true;
        }
    }
    return false;
}

static inline bool isProcNetTcp(const char *path) {
    if (!path) return false;
    if (strcmp(path, "/proc/net/tcp") == 0 || strcmp(path, "/proc/net/tcp6") == 0
            || isProcSelfOrPid(path, "net/tcp") || isProcSelfOrPid(path, "net/tcp6")) {
        return true;
    }
    return false;
}

static inline bool isProcMounts(const char *path) {
    if (!path) return false;
    if (strcmp(path, "/proc/mounts") == 0 || isProcSelfOrPid(path, "mounts")) {
        return true;
    }
    return false;
}

static inline bool isProcEnviron(const char *path) {
    return isProcSelfOrPid(path, "environ");
}

static inline bool isProcNetUnix(const char *path) {
    if (!path) return false;
    return (strcmp(path, "/proc/net/unix") == 0 || isProcSelfOrPid(path, "net/unix"));
}

static int createAnonymousMemFd(const char *name) {
#if defined(__NR_memfd_create)
    int fd = static_cast<int>(syscall(__NR_memfd_create, name ? name : "vproc", MFD_CLOEXEC));
    if (fd >= 0) return fd;
#endif
    char tmpPath[128];
    snprintf(tmpPath, sizeof(tmpPath), "/data/data/com.featurestic.loader/cache/.%s_XXXXXX", name ? name : "vm");
    int tmpFd = mkstemp(tmpPath);
    if (tmpFd >= 0) {
        unlink(tmpPath);
        return tmpFd;
    }
    return -1;
}

static bool shouldFilterMapsLine(const char *line) {
    if (!line) return false;
    // CRITICAL: NEVER filter out game libraries or plugin libraries!
    if (strstr(line, "libbgmi.so") != nullptr) return false;
    if (strstr(line, "libDARK.so") != nullptr) return false;
    if (strstr(line, "libUE4.so") != nullptr) return false;
    if (strstr(line, "libanogs.so") != nullptr) return false;

    if (strstr(line, "libFeaturesticCore.so") != nullptr) return true;
    if (strstr(line, "libboxcore.so") != nullptr) return true;
    if (strstr(line, "libloader.so") != nullptr) return true;
    if (strstr(line, "libdobby.so") != nullptr) return true;
    if (strstr(line, "libSandHook.so") != nullptr) return true;
    if (strstr(line, "sandhook") != nullptr) return true;
    if (strstr(line, "frida") != nullptr) return true;
    if (strstr(line, "linjector") != nullptr) return true;
    if (strstr(line, "xposed") != nullptr) return true;
    if (strstr(line, "edxp") != nullptr) return true;
    if (strstr(line, "lspd") != nullptr) return true;
    if (strstr(line, "magisk") != nullptr) return true;
    if (strstr(line, "zygisk") != nullptr) return true;
    if (strstr(line, "shamiko") != nullptr) return true;
    if (strstr(line, "riru") != nullptr) return true;
    if (strstr(line, "kernelsu") != nullptr) return true;
    if (strstr(line, "apatch") != nullptr) return true;
    if (strstr(line, "dev/pts") != nullptr) return true;
    if (strstr(line, "antigem") != nullptr) return true;
    if (strstr(line, "termux") != nullptr) return true;
    if (strstr(line, "FeaturesticBox") != nullptr) return true;
    if (strstr(line, "featurestic") != nullptr) return true;
    return false;
}

static int createVirtualProcMapsFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = raw_openat(AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (realFd < 0) {
        sInVfsBypass = false;
        return -1;
    }

    int outFd = createAnonymousMemFd("v_maps");
    if (outFd < 0) {
        close(realFd);
        sInVfsBypass = false;
        return -1;
    }

    std::string currentTargetPkg;
    {
        std::lock_guard<std::mutex> lock(sPkgMutex);
        currentTargetPkg = sVirtualPackageName;
    }

    char inBuf[4096];
    char lineBuf[2048];
    size_t linePos = 0;
    ssize_t bytesRead = 0;

    while ((bytesRead = read(realFd, inBuf, sizeof(inBuf))) > 0) {
        for (ssize_t i = 0; i < bytesRead; ++i) {
            char c = inBuf[i];
            if (linePos < sizeof(lineBuf) - 1) {
                lineBuf[linePos++] = c;
            }
            if (c == '\n') {
                lineBuf[linePos] = '\0';

                // Neutralize anonymous RWX pages (trampolines/hooks) to standard r-xp
                char *rwxPtr = strstr(lineBuf, " rwxp ");
                if (rwxPtr != nullptr) {
                    rwxPtr[2] = '-';
                }

                if (!shouldFilterMapsLine(lineBuf)) {
                    // Rewrite container virtual path to make it look native
                    char *sub = strstr(lineBuf, "/com.featurestic.loader/virtual");
                    if (sub != nullptr) {
                        char rewritten[2048];
                        char *pathStart = strchr(lineBuf, '/');
                        if (pathStart && pathStart <= sub) {
                            const char *virtAfter = sub + strlen("/com.featurestic.loader/virtual");
                            size_t headLen = pathStart - lineBuf;
                            memcpy(rewritten, lineBuf, headLen);
                            size_t restLen = strlen(virtAfter);
                            if (headLen + restLen < sizeof(rewritten)) {
                                memcpy(rewritten + headLen, virtAfter, restLen + 1);
                                write(outFd, rewritten, strlen(rewritten));
                            } else {
                                write(outFd, lineBuf, linePos);
                            }
                        } else {
                            write(outFd, lineBuf, linePos);
                        }
                    } else if (strstr(lineBuf, "com.featurestic.loader") != nullptr) {
                        // Replace loader package name with target package name
                        std::string lineStr(lineBuf);
                        size_t pos = 0;
                        while ((pos = lineStr.find("com.featurestic.loader", pos)) != std::string::npos) {
                            lineStr.replace(pos, strlen("com.featurestic.loader"), currentTargetPkg);
                            pos += currentTargetPkg.length();
                        }
                        write(outFd, lineStr.c_str(), lineStr.length());
                    } else {
                        write(outFd, lineBuf, linePos);
                    }
                }
                linePos = 0;
            }
        }
    }
    if (linePos > 0) {
        lineBuf[linePos] = '\0';
        char *rwxPtr = strstr(lineBuf, " rwxp ");
        if (rwxPtr != nullptr) {
            rwxPtr[2] = '-';
        }
        if (!shouldFilterMapsLine(lineBuf)) {
            write(outFd, lineBuf, linePos);
        }
    }

    close(realFd);
    lseek(outFd, 0, SEEK_SET);
    sInVfsBypass = false;
    return outFd;
}

static int createVirtualProcCmdlineFd() {
    int outFd = createAnonymousMemFd("v_cmdline");
    if (outFd < 0) return -1;

    std::string pkg;
    {
        std::lock_guard<std::mutex> lock(sPkgMutex);
        pkg = sVirtualPackageName;
    }
    // cmdline is null-terminated
    write(outFd, pkg.c_str(), pkg.length() + 1);
    lseek(outFd, 0, SEEK_SET);
    return outFd;
}

static int createVirtualProcCommFd() {
    int outFd = createAnonymousMemFd("v_comm");
    if (outFd < 0) return -1;

    std::string commName;
    {
        std::lock_guard<std::mutex> lock(sPkgMutex);
        commName = sVirtualProcessName;
    }
    char commBuf[32];
    snprintf(commBuf, sizeof(commBuf), "%.15s\n", commName.c_str());
    write(outFd, commBuf, strlen(commBuf));
    lseek(outFd, 0, SEEK_SET);
    return outFd;
}

static int createVirtualProcStatusFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = raw_openat(AT_FDCWD, "/proc/self/status", O_RDONLY | O_CLOEXEC);
    if (realFd < 0) {
        sInVfsBypass = false;
        return -1;
    }

    int outFd = createAnonymousMemFd("v_status");
    if (outFd < 0) {
        close(realFd);
        sInVfsBypass = false;
        return -1;
    }

    std::string commName;
    {
        std::lock_guard<std::mutex> lock(sPkgMutex);
        commName = sVirtualProcessName;
    }

    char inBuf[4096];
    char lineBuf[512];
    size_t linePos = 0;
    ssize_t bytesRead = 0;

    while ((bytesRead = read(realFd, inBuf, sizeof(inBuf))) > 0) {
        for (ssize_t i = 0; i < bytesRead; ++i) {
            char c = inBuf[i];
            if (linePos < sizeof(lineBuf) - 1) {
                lineBuf[linePos++] = c;
            }
            if (c == '\n') {
                lineBuf[linePos] = '\0';
                if (strncmp(lineBuf, "Name:\t", 6) == 0) {
                    char fakeName[64];
                    snprintf(fakeName, sizeof(fakeName), "Name:\t%.15s\n", commName.c_str());
                    write(outFd, fakeName, strlen(fakeName));
                } else if (strncmp(lineBuf, "TracerPid:\t", 11) == 0) {
                    write(outFd, "TracerPid:\t0\n", 13);
                } else {
                    write(outFd, lineBuf, linePos);
                }
                linePos = 0;
            }
        }
    }
    if (linePos > 0) {
        lineBuf[linePos] = '\0';
        write(outFd, lineBuf, linePos);
    }

    close(realFd);
    lseek(outFd, 0, SEEK_SET);
    sInVfsBypass = false;
    return outFd;
}

static int createVirtualProcStatFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = raw_openat(AT_FDCWD, "/proc/self/stat", O_RDONLY | O_CLOEXEC);
    if (realFd < 0) {
        sInVfsBypass = false;
        return -1;
    }

    int outFd = createAnonymousMemFd("v_stat");
    if (outFd < 0) {
        close(realFd);
        sInVfsBypass = false;
        return -1;
    }

    std::string commName;
    {
        std::lock_guard<std::mutex> lock(sPkgMutex);
        commName = sVirtualProcessName;
    }

    char inBuf[1024];
    ssize_t n = read(realFd, inBuf, sizeof(inBuf) - 1);
    close(realFd);
    sInVfsBypass = false;

    if (n > 0) {
        inBuf[n] = '\0';
        char *openParen = strchr(inBuf, '(');
        char *closeParen = strrchr(inBuf, ')');
        if (openParen && closeParen && closeParen > openParen) {
            std::string outStat;
            outStat.append(inBuf, openParen - inBuf + 1);
            outStat.append(commName.substr(0, 15));
            outStat.append(closeParen);
            write(outFd, outStat.c_str(), outStat.length());
        } else {
            write(outFd, inBuf, n);
        }
    }

    lseek(outFd, 0, SEEK_SET);
    return outFd;
}

static int createVirtualProcNetTcpFd() {
    int outFd = createAnonymousMemFd("v_tcp");
    if (outFd < 0) return -1;
    const char *header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
    write(outFd, header, strlen(header));
    lseek(outFd, 0, SEEK_SET);
    return outFd;
}

static int createVirtualProcMountsFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = raw_openat(AT_FDCWD, "/proc/self/mounts", O_RDONLY | O_CLOEXEC);
    if (realFd < 0) {
        sInVfsBypass = false;
        return -1;
    }

    int outFd = createAnonymousMemFd("v_mounts");
    if (outFd < 0) {
        close(realFd);
        sInVfsBypass = false;
        return -1;
    }

    char inBuf[4096];
    char lineBuf[1024];
    size_t linePos = 0;
    ssize_t bytesRead = 0;

    while ((bytesRead = read(realFd, inBuf, sizeof(inBuf))) > 0) {
        for (ssize_t i = 0; i < bytesRead; ++i) {
            char c = inBuf[i];
            if (linePos < sizeof(lineBuf) - 1) {
                lineBuf[linePos++] = c;
            }
            if (c == '\n') {
                lineBuf[linePos] = '\0';
                if (strstr(lineBuf, "antigravity") == nullptr
                        && strstr(lineBuf, "termux") == nullptr
                        && strstr(lineBuf, "magisk") == nullptr
                        && strstr(lineBuf, "daemonsu") == nullptr
                        && strstr(lineBuf, "xposed") == nullptr
                        && strstr(lineBuf, "sandhook") == nullptr
                        && strstr(lineBuf, "featurestic") == nullptr
                        && strstr(lineBuf, "blackbox") == nullptr) {
                    write(outFd, lineBuf, linePos);
                }
                linePos = 0;
            }
        }
    }
    if (linePos > 0) {
        lineBuf[linePos] = '\0';
        write(outFd, lineBuf, linePos);
    }

    close(realFd);
    lseek(outFd, 0, SEEK_SET);
    sInVfsBypass = false;
    return outFd;
}

static int createVirtualProcEnvironFd() {
    int outFd = createAnonymousMemFd("v_environ");
    if (outFd < 0) return -1;
    // Clean Android environment strings separated by '\0'
    const char cleanEnv[] =
        "ANDROID_ROOT=/system\0"
        "ANDROID_DATA=/data\0"
        "EXTERNAL_STORAGE=/sdcard\0"
        "ASEC_MOUNTPOINT=/mnt/asec\0"
        "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin\0";
    write(outFd, cleanEnv, sizeof(cleanEnv));
    lseek(outFd, 0, SEEK_SET);
    return outFd;
}

static int createVirtualProcNetUnixFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = raw_openat(AT_FDCWD, "/proc/net/unix", O_RDONLY | O_CLOEXEC);
    if (realFd < 0) {
        sInVfsBypass = false;
        return -1;
    }

    int outFd = createAnonymousMemFd("v_unix");
    if (outFd < 0) {
        close(realFd);
        sInVfsBypass = false;
        return -1;
    }

    char inBuf[4096];
    char lineBuf[1024];
    size_t linePos = 0;
    ssize_t bytesRead = 0;

    while ((bytesRead = read(realFd, inBuf, sizeof(inBuf))) > 0) {
        for (ssize_t i = 0; i < bytesRead; ++i) {
            char c = inBuf[i];
            if (linePos < sizeof(lineBuf) - 1) {
                lineBuf[linePos++] = c;
            }
            if (c == '\n') {
                lineBuf[linePos] = '\0';
                if (strstr(lineBuf, "magisk") == nullptr
                        && strstr(lineBuf, "zygisk") == nullptr
                        && strstr(lineBuf, "termux") == nullptr
                        && strstr(lineBuf, "antigem") == nullptr
                        && strstr(lineBuf, "featurestic") == nullptr
                        && strstr(lineBuf, "sandhook") == nullptr) {
                    write(outFd, lineBuf, linePos);
                }
                linePos = 0;
            }
        }
    }
    if (linePos > 0) {
        lineBuf[linePos] = '\0';
        write(outFd, lineBuf, linePos);
    }

    close(realFd);
    lseek(outFd, 0, SEEK_SET);
    sInVfsBypass = false;
    return outFd;
}

// ==================== Hook Implementations ====================

static int safe_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    if (!pathname || sInVfsBypass) {
        return sOrigOpenat ? sOrigOpenat(dirfd, pathname, flags, mode) : -1;
    }

    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }

    if (isProcMaps(pathname)) {
        int fd = createVirtualProcMapsFd();
        if (fd >= 0) return fd;
    } else if (isProcCmdline(pathname)) {
        int fd = createVirtualProcCmdlineFd();
        if (fd >= 0) return fd;
    } else if (isProcStatus(pathname)) {
        int fd = createVirtualProcStatusFd();
        if (fd >= 0) return fd;
    } else if (isProcComm(pathname)) {
        int fd = createVirtualProcCommFd();
        if (fd >= 0) return fd;
    } else if (isProcStat(pathname)) {
        int fd = createVirtualProcStatFd();
        if (fd >= 0) return fd;
    } else if (isProcNetTcp(pathname)) {
        int fd = createVirtualProcNetTcpFd();
        if (fd >= 0) return fd;
    } else if (isProcMounts(pathname)) {
        int fd = createVirtualProcMountsFd();
        if (fd >= 0) return fd;
    } else if (isProcEnviron(pathname)) {
        int fd = createVirtualProcEnvironFd();
        if (fd >= 0) return fd;
    } else if (isProcNetUnix(pathname)) {
        int fd = createVirtualProcNetUnixFd();
        if (fd >= 0) return fd;
    }

    const char *redirected = IO::redirectPath(pathname);
    return sOrigOpenat ? sOrigOpenat(dirfd, redirected ? redirected : pathname, flags, mode) : -1;
}

static int safe_openat64(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    if (!pathname || sInVfsBypass) {
        return sOrigOpenat64 ? sOrigOpenat64(dirfd, pathname, flags, mode) : -1;
    }

    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }

    if (isProcMaps(pathname)) {
        int fd = createVirtualProcMapsFd();
        if (fd >= 0) return fd;
    } else if (isProcCmdline(pathname)) {
        int fd = createVirtualProcCmdlineFd();
        if (fd >= 0) return fd;
    } else if (isProcStatus(pathname)) {
        int fd = createVirtualProcStatusFd();
        if (fd >= 0) return fd;
    } else if (isProcComm(pathname)) {
        int fd = createVirtualProcCommFd();
        if (fd >= 0) return fd;
    } else if (isProcStat(pathname)) {
        int fd = createVirtualProcStatFd();
        if (fd >= 0) return fd;
    } else if (isProcNetTcp(pathname)) {
        int fd = createVirtualProcNetTcpFd();
        if (fd >= 0) return fd;
    } else if (isProcMounts(pathname)) {
        int fd = createVirtualProcMountsFd();
        if (fd >= 0) return fd;
    } else if (isProcEnviron(pathname)) {
        int fd = createVirtualProcEnvironFd();
        if (fd >= 0) return fd;
    } else if (isProcNetUnix(pathname)) {
        int fd = createVirtualProcNetUnixFd();
        if (fd >= 0) return fd;
    }

    const char *redirected = IO::redirectPath(pathname);
    return sOrigOpenat64 ? sOrigOpenat64(dirfd, redirected ? redirected : pathname, flags, mode) : -1;
}

static int safe_open(const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) || (flags & O_TMPFILE)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    if (!pathname || sInVfsBypass) {
        return sOrigOpen ? sOrigOpen(pathname, flags, mode) : -1;
    }

    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
    }

    if (isProcMaps(pathname)) {
        int fd = createVirtualProcMapsFd();
        if (fd >= 0) return fd;
    } else if (isProcCmdline(pathname)) {
        int fd = createVirtualProcCmdlineFd();
        if (fd >= 0) return fd;
    } else if (isProcStatus(pathname)) {
        int fd = createVirtualProcStatusFd();
        if (fd >= 0) return fd;
    } else if (isProcComm(pathname)) {
        int fd = createVirtualProcCommFd();
        if (fd >= 0) return fd;
    } else if (isProcStat(pathname)) {
        int fd = createVirtualProcStatFd();
        if (fd >= 0) return fd;
    } else if (isProcNetTcp(pathname)) {
        int fd = createVirtualProcNetTcpFd();
        if (fd >= 0) return fd;
    } else if (isProcMounts(pathname)) {
        int fd = createVirtualProcMountsFd();
        if (fd >= 0) return fd;
    } else if (isProcEnviron(pathname)) {
        int fd = createVirtualProcEnvironFd();
        if (fd >= 0) return fd;
    } else if (isProcNetUnix(pathname)) {
        int fd = createVirtualProcNetUnixFd();
        if (fd >= 0) return fd;
    }

    const char *redirected = IO::redirectPath(pathname);
    return sOrigOpen ? sOrigOpen(redirected ? redirected : pathname, flags, mode) : -1;
}

static int safe_faccessat(int dirfd, const char *pathname, int mode, int flags) {
    if (!pathname || sInVfsBypass) {
        return sOrigFaccessat ? sOrigFaccessat(dirfd, pathname, mode, flags) : -1;
    }
    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcMaps(pathname) || isProcCmdline(pathname) || isProcStatus(pathname)
            || isProcComm(pathname) || isProcStat(pathname) || isProcNetTcp(pathname) || isProcMounts(pathname)
            || isProcEnviron(pathname) || isProcNetUnix(pathname)) {
        if ((mode & W_OK) != 0) {
            errno = EACCES;
            return -1;
        }
        return 0;
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigFaccessat ? sOrigFaccessat(dirfd, redirected ? redirected : pathname, mode, flags) : -1;
}

static int safe_access(const char *pathname, int mode) {
    if (!pathname || sInVfsBypass) {
        return sOrigAccess ? sOrigAccess(pathname, mode) : -1;
    }
    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcMaps(pathname) || isProcCmdline(pathname) || isProcStatus(pathname)
            || isProcComm(pathname) || isProcStat(pathname) || isProcNetTcp(pathname) || isProcMounts(pathname)
            || isProcEnviron(pathname) || isProcNetUnix(pathname)) {
        if ((mode & W_OK) != 0) {
            errno = EACCES;
            return -1;
        }
        return 0;
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigAccess ? sOrigAccess(redirected ? redirected : pathname, mode) : -1;
}

static int safe_fstatat(int dirfd, const char *pathname, struct stat *buf, int flags) {
    if (!pathname || sInVfsBypass) {
        return sOrigFstatat ? sOrigFstatat(dirfd, pathname, buf, flags) : -1;
    }
    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcMaps(pathname) || isProcStatus(pathname) || isProcStat(pathname) || isProcMounts(pathname)
            || isProcEnviron(pathname) || isProcNetUnix(pathname)) {
        if (buf) {
            memset(buf, 0, sizeof(struct stat));
            buf->st_mode = S_IFREG | 0444;
            buf->st_size = 0;
        }
        return 0;
    }
    if (isProcNetTcp(pathname)) {
        if (buf) {
            memset(buf, 0, sizeof(struct stat));
            buf->st_mode = S_IFREG | 0444;
            buf->st_size = 80;
        }
        return 0;
    }
    if (isProcCmdline(pathname)) {
        if (buf) {
            memset(buf, 0, sizeof(struct stat));
            buf->st_mode = S_IFREG | 0444;
            std::lock_guard<std::mutex> lock(sPkgMutex);
            buf->st_size = sVirtualPackageName.length() + 1;
        }
        return 0;
    }
    if (isProcComm(pathname)) {
        if (buf) {
            memset(buf, 0, sizeof(struct stat));
            buf->st_mode = S_IFREG | 0444;
            std::lock_guard<std::mutex> lock(sPkgMutex);
            buf->st_size = sVirtualProcessName.length() + 1;
        }
        return 0;
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigFstatat ? sOrigFstatat(dirfd, redirected ? redirected : pathname, buf, flags) : -1;
}

static int safe_fstatat64(int dirfd, const char *pathname, struct stat *buf, int flags) {
    return safe_fstatat(dirfd, pathname, buf, flags);
}

static int safe_stat(const char *pathname, struct stat *buf) {
    return safe_fstatat(AT_FDCWD, pathname, buf, 0);
}

static int safe_lstat(const char *pathname, struct stat *buf) {
    return safe_fstatat(AT_FDCWD, pathname, buf, AT_SYMLINK_NOFOLLOW);
}

static int safe_stat64(const char *pathname, struct stat *buf) {
    return safe_fstatat(AT_FDCWD, pathname, buf, 0);
}

static int safe_lstat64(const char *pathname, struct stat *buf) {
    return safe_fstatat(AT_FDCWD, pathname, buf, AT_SYMLINK_NOFOLLOW);
}

static int safe_fstat(int fd, struct stat *buf) {
    if (fd < 0 || sInVfsBypass) {
        return sOrigFstat ? sOrigFstat(fd, buf) : -1;
    }
    if (isDirFdBlocked(fd, nullptr)) {
        errno = ENOENT;
        return -1;
    }
    return sOrigFstat ? sOrigFstat(fd, buf) : -1;
}

static int safe_fstat64(int fd, struct stat *buf) {
    return safe_fstat(fd, buf);
}

static int safe_statx(int dirfd, const char *pathname, int flags, unsigned int mask, void *statxbuf) {
    if (!pathname || sInVfsBypass) {
        return sOrigStatx ? sOrigStatx(dirfd, pathname, flags, mask, statxbuf) : -1;
    }
    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcMaps(pathname) || isProcStatus(pathname) || isProcStat(pathname) || isProcMounts(pathname)
            || isProcNetTcp(pathname) || isProcCmdline(pathname) || isProcComm(pathname)
            || isProcEnviron(pathname) || isProcNetUnix(pathname)) {
        if (statxbuf) {
            memset(statxbuf, 0, 256);
        }
        return 0;
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigStatx ? sOrigStatx(dirfd, redirected ? redirected : pathname, flags, mask, statxbuf) : -1;
}

static ssize_t safe_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (!pathname || sInVfsBypass) {
        return sOrigReadlinkat ? sOrigReadlinkat(dirfd, pathname, buf, bufsiz) : -1;
    }
    if (isBlockedDevPath(pathname) || isDirFdBlocked(dirfd, pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcSelfOrPid(pathname, "exe")) {
        const char *fakeExe = "/system/bin/app_process64";
        size_t len = strlen(fakeExe);
        if (len > bufsiz) len = bufsiz;
        if (buf && bufsiz > 0) {
            memcpy(buf, fakeExe, len);
        }
        return static_cast<ssize_t>(len);
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigReadlinkat ? sOrigReadlinkat(dirfd, redirected ? redirected : pathname, buf, bufsiz) : -1;
}

static ssize_t safe_readlink(const char *pathname, char *buf, size_t bufsiz) {
    if (!pathname || sInVfsBypass) {
        return sOrigReadlink ? sOrigReadlink(pathname, buf, bufsiz) : -1;
    }
    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcSelfOrPid(pathname, "exe")) {
        const char *fakeExe = "/system/bin/app_process64";
        size_t len = strlen(fakeExe);
        if (len > bufsiz) len = bufsiz;
        if (buf && bufsiz > 0) {
            memcpy(buf, fakeExe, len);
        }
        return static_cast<ssize_t>(len);
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigReadlink ? sOrigReadlink(redirected ? redirected : pathname, buf, bufsiz) : -1;
}

static bool isCriticalGameOrMainThreadHelper() {
    if (gettid() == getpid()) return true;
    char tname[32] = {0};
    if (prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0) == 0 && tname[0] != '\0') {
        if (strstr(tname, "MainThread") != nullptr
                || strstr(tname, "GameThread") != nullptr
                || strstr(tname, "RenderThread") != nullptr
                || strstr(tname, "RHIThread") != nullptr
                || strstr(tname, "TaskGraph") != nullptr
                || strstr(tname, "FAsync") != nullptr
                || strstr(tname, "AudioThread") != nullptr
                || strstr(tname, "Binder:") != nullptr
                || strstr(tname, "RenderEngine") != nullptr
                || strstr(tname, "hwui") != nullptr
                || strstr(tname, "Finalizer") != nullptr
                || strstr(tname, "HeapTask") != nullptr
                || strstr(tname, "mali") != nullptr
                || strstr(tname, "Mali") != nullptr
                || strstr(tname, "Surface") != nullptr
                || strstr(tname, "Unity") != nullptr
                || strstr(tname, "Unreal") != nullptr
                || strstr(tname, "Download") != nullptr
                || strstr(tname, "Puffer") != nullptr
                || strstr(tname, "Eifs") != nullptr
                || strstr(tname, "Pak") != nullptr
                || strstr(tname, "Stream") != nullptr
                || strstr(tname, "Net") != nullptr
                || strstr(tname, "HTTP") != nullptr
                || strstr(tname, "Volley") != nullptr
                || strstr(tname, "OkHttp") != nullptr) {
            return true;
        }
    }
    return false;
}

static long safe_syscall(long number, long arg1, long arg2, long arg3, long arg4, long arg5, long arg6) {
    // STEP 1 A/B TEST: pass signal/exit/prctl/ptrace syscalls straight to kernel
    if (number == 93 || number == 94 || number == 129 || number == 130 ||
        number == 131 || number == 132 || number == 134 || number == 135 ||
        number == 167 || number == 117) {
        return sOrigSyscall ? sOrigSyscall(number, arg1, arg2, arg3, arg4, arg5, arg6) : -1;
    }
    switch (number) {
        case 56: // __NR_openat
            return safe_openat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<mode_t>(arg4));
        case 48: // __NR_faccessat
        case 439: // __NR_faccessat2
            return safe_faccessat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<int>(arg4));
        case 79: // __NR_newfstatat / __NR_fstatat64
            return safe_fstatat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), reinterpret_cast<struct stat *>(arg3), static_cast<int>(arg4));
        case 80: // __NR_fstat
            return safe_fstat(static_cast<int>(arg1), reinterpret_cast<struct stat *>(arg2));
        case 291: // __NR_statx
            return safe_statx(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<unsigned int>(arg4), reinterpret_cast<void *>(arg5));
        case 78: // __NR_readlinkat
            return safe_readlinkat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), reinterpret_cast<char *>(arg3), static_cast<size_t>(arg4));
        case 93: { // __NR_exit: cleanly terminate only the calling thread in kernel
#if defined(__aarch64__)
            register long x8 __asm__("x8") = 93;
            register long x0 __asm__("x0") = arg1;
            __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
            return 0;
#else
            return sOrigSyscall ? sOrigSyscall(93, arg1, 0, 0, 0, 0, 0) : 0;
#endif
        }
        case 94: { // __NR_exit_group: would terminate entire process
            char tname[32] = {0};
            prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
            ALOGW("FEATURESTIC GUARD: Suppressed syscall exit_group(%ld) from tid=%d (%s) to protect game container",
                  static_cast<long>(arg1), static_cast<int>(gettid()), tname[0] ? tname : "?");
            if (gettid() != getpid() && !isCriticalGameOrMainThreadHelper()) {
                // Cleanly terminate only this rogue background thread in kernel without killing process
#if defined(__aarch64__)
                register long x8 __asm__("x8") = 93;
                register long x0 __asm__("x0") = 0;
                __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8) : "memory", "cc");
#else
                if (sOrigSyscall) sOrigSyscall(93, 0, 0, 0, 0, 0, 0);
#endif
            }
            return 0;
        }
        case 129: { // __NR_kill
            pid_t pid = static_cast<pid_t>(arg1);
            int sig = static_cast<int>(arg2);
            if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT) && (pid == getpid() || pid == 0 || pid == -1)) {
                return 0;
            }
            break;
        }
        case 130: // __NR_tkill
        case 131: { // __NR_tgkill
            pid_t tgid = (number == 131) ? static_cast<pid_t>(arg1) : getpid();
            int sig = (number == 131) ? static_cast<int>(arg3) : static_cast<int>(arg2);
            if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL || sig == SIGSYS || sig == SIGILL || sig == SIGTERM || sig == SIGQUIT) && (tgid == getpid() || tgid == 0 || tgid == -1)) {
                return 0;
            }
            break;
        }
        case 132: { // __NR_sigaltstack
            if (arg1 != 0) {
                const auto *ss = reinterpret_cast<const stack_t *>(arg1);
                // Prevent anti-cheat from disabling alternate signal stack (SS_DISABLE = 2) or passing invalid pointer
                if ((ss->ss_flags & 2 /* SS_DISABLE */) || ss->ss_sp == nullptr || ss->ss_size < MINSIGSTKSZ) {
                    if (arg2 != 0) {
#if defined(__aarch64__)
                        register long x8 __asm__("x8") = 132;
                        register long x0 __asm__("x0") = 0;
                        register long x1 __asm__("x1") = arg2;
                        __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1) : "memory", "cc");
                        return x0;
#else
                        return sOrigSyscall ? sOrigSyscall(132, 0, arg2, 0, 0, 0, 0) : 0;
#endif
                    }
                    return 0; // Suppress disabling alternate signal stack
                }
            }
            break;
        }
        case 134: { // __NR_rt_sigaction
            int sig = static_cast<int>(arg1);
            if (sig == SIGSYS || sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGILL) {
                void *ourHandler = (sig == SIGSYS && gOurSigsysHandler != nullptr) ? gOurSigsysHandler : gOurSignalHandler;
                if (ourHandler != nullptr) {
                    struct {
                        void *sa_handler;
                        unsigned long sa_flags;
                        void (*sa_restorer)(void);
                        unsigned long sa_mask;
                    } ksa{};
                    ksa.sa_handler = ourHandler;
                    ksa.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;
                    ksa.sa_restorer = nullptr;
                    ksa.sa_mask = 0;
#if defined(__aarch64__)
                    register long x8 __asm__("x8") = 134; // __NR_rt_sigaction
                    register long x0 __asm__("x0") = sig;
                    register long x1 __asm__("x1") = reinterpret_cast<long>(&ksa);
                    register long x2 __asm__("x2") = arg3;
                    register long x3 __asm__("x3") = 8;
                    __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
                    return x0;
#else
                    return sOrigSyscall ? sOrigSyscall(134, sig, reinterpret_cast<uintptr_t>(&ksa), arg3, 8, 0, 0) : 0;
#endif
                }
                return 0; // Never allow overriding protective signal handlers via raw syscalls!
            }
            break;
        }
        case 135: { // __NR_rt_sigprocmask
            int how = static_cast<int>(arg1);
            uintptr_t setPtr = static_cast<uintptr_t>(arg2);
            uintptr_t oldsetPtr = static_cast<uintptr_t>(arg3);
            size_t sigsetsize = static_cast<size_t>(arg4);
            if (setPtr != 0 && (how == 0 /* SIG_BLOCK */ || how == 2 /* SIG_SETMASK */)) {
                // Strip fatal hardware & trap signals from mask so kernel never drops synchronous hardware signals!
                uint64_t mask = *reinterpret_cast<const uint64_t *>(setPtr);
                constexpr uint64_t DANGEROUS_MASK = (1ULL << (SIGSEGV - 1)) |
                                                    (1ULL << (SIGBUS - 1))  |
                                                    (1ULL << (SIGSYS - 1))  |
                                                    (1ULL << (SIGILL - 1))  |
                                                    (1ULL << (SIGABRT - 1));
                mask &= ~DANGEROUS_MASK;
#if defined(__aarch64__)
                register long x8 __asm__("x8") = 135; // __NR_rt_sigprocmask
                register long x0 __asm__("x0") = how;
                register long x1 __asm__("x1") = reinterpret_cast<long>(&mask);
                register long x2 __asm__("x2") = oldsetPtr;
                register long x3 __asm__("x3") = sigsetsize ? sigsetsize : sizeof(uint64_t);
                __asm__ __volatile__("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
                return x0;
#else
                return sOrigSyscall ? sOrigSyscall(135, how, reinterpret_cast<uintptr_t>(&mask), oldsetPtr, sigsetsize ? sigsetsize : 8, 0, 0) : 0;
#endif
            }
            break;
        }
        case 167: { // __NR_prctl
            int option = static_cast<int>(arg1);
            if (option == 22 /* PR_SET_SECCOMP */) {
                ALOGW("FEATURESTIC GUARD: Suppressed syscall prctl(PR_SET_SECCOMP) from tid=%d", static_cast<int>(gettid()));
                return 0;
            }
            break;
        }
        case 117: { // __NR_ptrace
            if (arg1 == 0 /* PTRACE_TRACEME */) {
                return 0;
            }
            break;
        }
        default:
            break;
    }
    return sOrigSyscall ? sOrigSyscall(number, arg1, arg2, arg3, arg4, arg5, arg6) : -1;
}

#if defined(__aarch64__)
static void seccompSigsysHandler(int sig, siginfo_t *info, void *ucontext) {
    if (sig != SIGSYS || !ucontext) return;
    static thread_local bool sInSigsys = false;
    if (sInSigsys) return;
    struct Guard {
        bool &b;
        explicit Guard(bool &v) : b(v) { b = true; }
        ~Guard() { b = false; }
    } g(sInSigsys);

    auto *uc = reinterpret_cast<ucontext_t *>(ucontext);
    long nr = (info && info->si_syscall != 0) ? info->si_syscall : static_cast<long>(uc->uc_mcontext.regs[8]);
    long a0 = static_cast<long>(uc->uc_mcontext.regs[0]);
    long a1 = static_cast<long>(uc->uc_mcontext.regs[1]);
    long a2 = static_cast<long>(uc->uc_mcontext.regs[2]);
    long a3 = static_cast<long>(uc->uc_mcontext.regs[3]);

    // Advance PC past the 4-byte svc #0 instruction on ARM64
    uc->uc_mcontext.pc += 4;
    sigdelset(&uc->uc_sigmask, SIGSYS);

    switch (nr) {
        case 94: { // __NR_exit_group
            char tname[32] = {0};
            prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
            ALOGW("FEATURESTIC SECCOMP: Intercepted inline exit_group(%ld) tid=%d (%s)",
                  a0, static_cast<int>(gettid()), tname[0] ? tname : "?");
            if (gettid() != getpid() && !isCriticalGameOrMainThreadHelper()) {
                raw_real_syscall(93 /* __NR_exit */, 0, 0, 0, 0, 0, 0x5afe5afe);
            }
            uc->uc_mcontext.regs[0] = 0;
            return;
        }
        case 129: { // __NR_kill
            pid_t pid = static_cast<pid_t>(a0);
            if (pid == getpid() || pid == 0 || pid == -1) {
                ALOGW("FEATURESTIC SECCOMP: Suppressed inline kill(pid=%d, sig=%ld) tid=%d",
                      static_cast<int>(pid), a1, static_cast<int>(gettid()));
                uc->uc_mcontext.regs[0] = 0;
                return;
            }
            uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                    raw_real_syscall(129, a0, a1, 0, 0, 0, 0x5afe5afe));
            return;
        }
        case 130: // __NR_tkill
        case 131: { // __NR_tgkill
            pid_t tgid = (nr == 131) ? static_cast<pid_t>(a0) : getpid();
            long sigNum = (nr == 131) ? a2 : a1;
            if (tgid == getpid() || tgid == 0 || tgid == -1) {
                ALOGW("FEATURESTIC SECCOMP: Suppressed inline %s(tgid=%d, tid=%ld, sig=%ld) from tid=%d",
                      (nr == 131) ? "tgkill" : "tkill",
                      static_cast<int>(tgid), (nr == 131) ? a1 : a0, sigNum, static_cast<int>(gettid()));
                uc->uc_mcontext.regs[0] = 0;
                return;
            }
            uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                    raw_real_syscall(nr, a0, a1, a2, 0, 0, 0x5afe5afe));
            return;
        }
        case 132: { // __NR_sigaltstack
            if (a0 != 0) {
                const auto *ss = reinterpret_cast<const stack_t *>(a0);
                if ((ss->ss_flags & 2 /* SS_DISABLE */) || ss->ss_sp == nullptr || ss->ss_size < MINSIGSTKSZ) {
                    if (a1 != 0) {
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                                raw_real_syscall(132, 0, a1, 0, 0, 0, 0x5afe5afe));
                    } else {
                        uc->uc_mcontext.regs[0] = 0;
                    }
                    return;
                }
            }
            uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                    raw_real_syscall(132, a0, a1, 0, 0, 0, 0x5afe5afe));
            return;
        }
        case 134: { // __NR_rt_sigaction
            if (a1 == 0) {
                uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                        raw_real_syscall(134, a0, 0, a2, a3 ? a3 : 8, 0, 0x5afe5afe));
                return;
            }
            void *handler = *reinterpret_cast<void *const *>(a1);
            if (a2 != 0) {
                raw_real_syscall(134, a0, 0, a2, a3 ? a3 : 8, 0, 0x5afe5afe);
            }
            ALOGW("FEATURESTIC SECCOMP: Blocked inline rt_sigaction(sig=%ld, handler=%p) from tid=%d",
                  a0, handler, static_cast<int>(gettid()));
            uc->uc_mcontext.regs[0] = 0;
            return;
        }
        case 135: { // __NR_rt_sigprocmask
            if (a1 != 0 && (a0 == 0 /* SIG_BLOCK */ || a0 == 2 /* SIG_SETMASK */)) {
                uint64_t mask = *reinterpret_cast<const uint64_t *>(a1);
                constexpr uint64_t DANGEROUS_MASK = (1ULL << (SIGSEGV - 1)) |
                                                    (1ULL << (SIGBUS - 1))  |
                                                    (1ULL << (SIGSYS - 1))  |
                                                    (1ULL << (SIGILL - 1))  |
                                                    (1ULL << (SIGABRT - 1)) |
                                                    (1ULL << (SIGQUIT - 1));
                mask &= ~DANGEROUS_MASK;
                uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                        raw_real_syscall(135, a0, reinterpret_cast<long>(&mask), a2, a3 ? a3 : 8, 0, 0x5afe5afe));
                return;
            }
            uc->uc_mcontext.regs[0] = static_cast<unsigned long>(
                    raw_real_syscall(135, a0, a1, a2, a3 ? a3 : 8, 0, 0x5afe5afe));
            return;
        }
        default:
            break;
    }
}

static bool installSeccompVfsFilter() {
    // STEP 1 A/B TEST: disable SECCOMP-BPF signal guard
    return false;
    static std::atomic<bool> sInstalled{false};
    if (sInstalled.exchange(true)) return true;

    // Zero-SIGSYS SECCOMP-BPF signal guard using SECCOMP_RET_ERRNO | 0:
    // Silently returns 0 (success) in-kernel for inline svc #0 attempts to overwrite
    // SIGSEGV/SIGBUS/SIGILL/SIGABRT or send fatal kill/tkill/tgkill signals.
    // Never raises SIGSYS (zero Signal 31 / pthread_create conflict).
    constexpr uint32_t RET_SILENT_OK = SECCOMP_RET_ERRNO | 0U;
    struct sock_filter filter[] = {
        /* 00 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4),
        /* 01 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0xc00000b7U, 1, 0),
        /* 02 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

        /* 03 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 56),           // args[5] magic
        /* 04 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0x5afe5afeU, 0, 1),
        /* 05 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

        /* 06 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),            // nr
        /* 07 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 134, 3,  0),  // __NR_rt_sigaction -> 11
        /* 08 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 129, 11, 0),  // __NR_kill -> 20
        /* 09 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 130, 10, 0),  // __NR_tkill -> 20
        /* 10 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 131, 11, 19), // __NR_tgkill -> 22 else 30

        /* 11 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 24),           // rt_sigaction act lo
        /* 12 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0,   0,  2),  // if lo==0 check hi else 15
        /* 13 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 28),           // rt_sigaction act hi
        /* 14 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0,   15, 0),  // act==NULL -> 30 ALLOW
        /* 15 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 16),           // rt_sigaction sig
        /* 16 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 11,  12, 0),  // SIGSEGV -> 29
        /* 17 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 7,   11, 0),  // SIGBUS -> 29
        /* 18 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 6,   10, 0),  // SIGABRT -> 29
        /* 19 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 4,   9,  10), // SIGILL -> 29 else 30

        /* 20 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 24),           // kill/tkill args[1]
        /* 21 */ BPF_JUMP(BPF_JMP | BPF_JA, 1, 0, 0),              // -> 23
        /* 22 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 32),           // tgkill args[2]
        /* 23 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 11,  5,  0),  // SIGSEGV -> 29
        /* 24 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 7,   4,  0),  // SIGBUS -> 29
        /* 25 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 6,   3,  0),  // SIGABRT -> 29
        /* 26 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 4,   2,  0),  // SIGILL -> 29
        /* 27 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 9,   1,  0),  // SIGKILL -> 29
        /* 28 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 15,  0,  1),  // SIGTERM -> 29 else 30
        /* 29 */ BPF_STMT(BPF_RET | BPF_K, RET_SILENT_OK),
        /* 30 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog prog{};
    prog.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;

    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
        ALOGW("NativeVfsHook: Failed to install zero-SIGSYS SECCOMP guard (errno=%d)", errno);
        return false;
    }
    ALOGI("NativeVfsHook: Zero-SIGSYS SECCOMP-BPF signal guard active!");
    return true;
}
#else
static bool installSeccompVfsFilter() {
    return false;
}
#endif

} // anonymous namespace

namespace NativeVfsHook {

void setVirtualPackage(const std::string &pkg, const std::string &processName) {
    std::lock_guard<std::mutex> lock(sPkgMutex);
    if (!pkg.empty()) sVirtualPackageName = pkg;
    if (!processName.empty()) sVirtualProcessName = processName;
    ALOGI("NativeVfsHook: Configured virtual package=%s, process=%s",
          sVirtualPackageName.c_str(), sVirtualProcessName.c_str());
}

bool isHooked() {
    return sHooksInstalled.load(std::memory_order_relaxed);
}

void init() {
    if (sHooksInstalled.exchange(true)) {
        return;
    }

#if defined(__aarch64__)
    // CRITICAL STEALTH ARCHITECTURE:
    // DO NOT INLINE-HOOK LIBC FUNCTIONS (openat, syscall, fstatat, etc.)!
    // Inline hooking libc.so functions overwrites the .text instructions of libc.so,
    // which triggers Tencent Anti-Cheat (ACE / TP / Anogs) post-login memory integrity hash verification
    // and causes silent process termination (exit_group / SIGKILL).
    // All virtual file routing is cleanly handled at the Java/JNI layer (LinuxHook.cpp)
    // and scoped ELF GOT hooks for libhdmpve.so.
    void *openatSym = dlsym(RTLD_DEFAULT, "openat");
    void *openat64Sym = dlsym(RTLD_DEFAULT, "openat64");
    void *openSym = dlsym(RTLD_DEFAULT, "open");
    void *faccessatSym = dlsym(RTLD_DEFAULT, "faccessat");
    void *accessSym = dlsym(RTLD_DEFAULT, "access");
    void *fstatatSym = dlsym(RTLD_DEFAULT, "fstatat");
    void *fstatat64Sym = dlsym(RTLD_DEFAULT, "fstatat64");
    void *statSym = dlsym(RTLD_DEFAULT, "stat");
    void *lstatSym = dlsym(RTLD_DEFAULT, "lstat");
    void *stat64Sym = dlsym(RTLD_DEFAULT, "stat64");
    void *lstat64Sym = dlsym(RTLD_DEFAULT, "lstat64");
    void *statxSym = dlsym(RTLD_DEFAULT, "statx");
    void *syscallSym = dlsym(RTLD_DEFAULT, "syscall");
    void *fstatSym = dlsym(RTLD_DEFAULT, "fstat");
    void *fstat64Sym = dlsym(RTLD_DEFAULT, "fstat64");
    void *readlinkatSym = dlsym(RTLD_DEFAULT, "readlinkat");
    void *readlinkSym = dlsym(RTLD_DEFAULT, "readlink");

    if (openatSym) sOrigOpenat = reinterpret_cast<openat_fn_t>(openatSym);
    if (openat64Sym) sOrigOpenat64 = reinterpret_cast<openat_fn_t>(openat64Sym);
    if (openSym) sOrigOpen = reinterpret_cast<open_fn_t>(openSym);
    if (faccessatSym) sOrigFaccessat = reinterpret_cast<faccessat_fn_t>(faccessatSym);
    if (accessSym) sOrigAccess = reinterpret_cast<access_fn_t>(accessSym);
    if (fstatatSym) sOrigFstatat = reinterpret_cast<fstatat_fn_t>(fstatatSym);
    if (fstatat64Sym) sOrigFstatat64 = reinterpret_cast<fstatat_fn_t>(fstatat64Sym);
    if (statSym) sOrigStat = reinterpret_cast<stat_fn_t>(statSym);
    if (lstatSym) sOrigLstat = reinterpret_cast<lstat_fn_t>(lstatSym);
    if (stat64Sym) sOrigStat64 = reinterpret_cast<stat64_fn_t>(stat64Sym);
    if (lstat64Sym) sOrigLstat64 = reinterpret_cast<lstat64_fn_t>(lstat64Sym);
    if (statxSym) sOrigStatx = reinterpret_cast<statx_fn_t>(statxSym);
    if (syscallSym) sOrigSyscall = reinterpret_cast<syscall_fn_t>(syscallSym);
    if (fstatSym) sOrigFstat = reinterpret_cast<fstat_fn_t>(fstatSym);
    if (fstat64Sym) sOrigFstat64 = reinterpret_cast<fstat_fn_t>(fstat64Sym);
    if (readlinkatSym) sOrigReadlinkat = reinterpret_cast<readlinkat_fn_t>(readlinkatSym);
    if (readlinkSym) sOrigReadlink = reinterpret_cast<readlink_fn_t>(readlinkSym);

    ALOGI("NativeVfsHook: Libc inline hooks safely disabled: zero tampering on libc.so text segment for Tencent Anti-Cheat");
    installSeccompVfsFilter();
#endif
}

int rawDirectOpen(const char *path, int flags, mode_t mode) {
#if defined(__aarch64__)
    return raw_openat(AT_FDCWD, path, flags, mode);
#else
    return open(path, flags, mode);
#endif
}

ssize_t rawDirectRead(int fd, void *buf, size_t count) {
#if defined(__aarch64__)
    return static_cast<ssize_t>(raw_real_syscall(63 /* __NR_read */, fd, reinterpret_cast<long>(buf), count, 0, 0, 0x5afe5afe));
#else
    return read(fd, buf, count);
#endif
}

int rawDirectClose(int fd) {
#if defined(__aarch64__)
    return static_cast<int>(raw_real_syscall(57 /* __NR_close */, fd, 0, 0, 0, 0, 0x5afe5afe));
#else
    return close(fd);
#endif
}

} // namespace NativeVfsHook
