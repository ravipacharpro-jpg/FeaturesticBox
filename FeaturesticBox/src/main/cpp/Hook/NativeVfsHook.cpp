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
#define SECCOMP_RET_ALLOW 0x7fff0000
#endif

#ifndef SECCOMP_RET_TRAP
#define SECCOMP_RET_TRAP 0x00030000
#endif

#ifndef AUDIT_ARCH_AARCH64
#define AUDIT_ARCH_AARCH64 0xc00000b7
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
    return false;
}

static inline bool isDirFdBlocked(int dirfd, const char *pathname) {
    if (dirfd < 0 || dirfd == AT_FDCWD) return false;
    if (pathname && pathname[0] == '/') return false;
    char fdPath[64];
    snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", dirfd);
    char targetPath[PATH_MAX];
    ssize_t len = sOrigReadlink ? sOrigReadlink(fdPath, targetPath, sizeof(targetPath) - 1)
                                : readlink(fdPath, targetPath, sizeof(targetPath) - 1);
    if (len > 0) {
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
    if (strstr(line, "libFeaturesticCore.so") != nullptr) return true;
    if (strstr(line, "libFeaturesticPlugin.so") != nullptr) return true;
    if (strstr(line, "libboxcore.so") != nullptr) return true;
    if (strstr(line, "libloader.so") != nullptr) return true;
    if (strstr(line, "FeaturesticBox") != nullptr) return true;
    if (strstr(line, "featurestic") != nullptr) return true;
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
    return false;
}

static int createVirtualProcMapsFd() {
    if (sInVfsBypass) return -1;
    sInVfsBypass = true;

    int realFd = -1;
    if (sOrigOpenat != nullptr) {
        realFd = sOrigOpenat(AT_FDCWD, "/proc/self/maps", O_RDONLY | O_CLOEXEC, 0);
    } else if (sOrigOpen != nullptr) {
        realFd = sOrigOpen("/proc/self/maps", O_RDONLY | O_CLOEXEC, 0);
    }
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

    int realFd = -1;
    if (sOrigOpenat != nullptr) {
        realFd = sOrigOpenat(AT_FDCWD, "/proc/self/status", O_RDONLY | O_CLOEXEC, 0);
    } else if (sOrigOpen != nullptr) {
        realFd = sOrigOpen("/proc/self/status", O_RDONLY | O_CLOEXEC, 0);
    }
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

    int realFd = -1;
    if (sOrigOpenat != nullptr) {
        realFd = sOrigOpenat(AT_FDCWD, "/proc/self/stat", O_RDONLY | O_CLOEXEC, 0);
    } else if (sOrigOpen != nullptr) {
        realFd = sOrigOpen("/proc/self/stat", O_RDONLY | O_CLOEXEC, 0);
    }
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

    int realFd = -1;
    if (sOrigOpenat != nullptr) {
        realFd = sOrigOpenat(AT_FDCWD, "/proc/self/mounts", O_RDONLY | O_CLOEXEC, 0);
    } else if (sOrigOpen != nullptr) {
        realFd = sOrigOpen("/proc/self/mounts", O_RDONLY | O_CLOEXEC, 0);
    }
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
            || isProcComm(pathname) || isProcStat(pathname) || isProcNetTcp(pathname) || isProcMounts(pathname)) {
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
            || isProcComm(pathname) || isProcStat(pathname) || isProcNetTcp(pathname) || isProcMounts(pathname)) {
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
    if (isProcMaps(pathname) || isProcStatus(pathname) || isProcStat(pathname) || isProcMounts(pathname)) {
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
            || isProcNetTcp(pathname) || isProcCmdline(pathname) || isProcComm(pathname)) {
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
                || strstr(tname, "HeapTask") != nullptr) {
            return true;
        }
    }
    return false;
}

static long safe_syscall(long number, long arg1, long arg2, long arg3, long arg4, long arg5, long arg6) {
    switch (number) {
        case 56: // __NR_openat
            return safe_openat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<mode_t>(arg4));
        case 48: // __NR_faccessat
        case 439: // __NR_faccessat2
            return safe_faccessat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<int>(arg4));
        case 79: // __NR_newfstatat / __NR_fstatat64
            return safe_fstatat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), reinterpret_cast<struct stat *>(arg3), static_cast<int>(arg4));
        case 291: // __NR_statx
            return safe_statx(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), static_cast<int>(arg3), static_cast<unsigned int>(arg4), reinterpret_cast<void *>(arg5));
        case 78: // __NR_readlinkat
            return safe_readlinkat(static_cast<int>(arg1), reinterpret_cast<const char *>(arg2), reinterpret_cast<char *>(arg3), static_cast<size_t>(arg4));
        case 129: { // __NR_kill
            pid_t pid = static_cast<pid_t>(arg1);
            int sig = static_cast<int>(arg2);
            if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL) && (pid == getpid() || pid == 0 || pid == -1)) {
                if (!isCriticalGameOrMainThreadHelper()) {
                    char tname[32] = {0};
                    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
                    ALOGW("FEATURESTIC GUARD: Suppressed syscall(__NR_kill, pid=%d, sig=%d) from worker thread (tid=%d, name=%s) -> exiting cleanly",
                          static_cast<int>(pid), sig, static_cast<int>(gettid()), tname[0] ? tname : "?");
                    pthread_exit(nullptr);
                    return 0;
                }
            }
            break;
        }
        case 130: // __NR_tkill
        case 131: { // __NR_tgkill
            pid_t tgid = (number == 131) ? static_cast<pid_t>(arg1) : getpid();
            pid_t tid = (number == 131) ? static_cast<pid_t>(arg2) : static_cast<pid_t>(arg1);
            int sig = (number == 131) ? static_cast<int>(arg3) : static_cast<int>(arg2);
            if ((sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT || sig == SIGKILL) && (tgid == getpid() || tgid == 0 || tgid == -1)) {
                if (!isCriticalGameOrMainThreadHelper()) {
                    char tname[32] = {0};
                    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
                    ALOGW("FEATURESTIC GUARD: Suppressed syscall(__NR_tgkill, tgid=%d, tid=%d, sig=%d) from worker thread (tid=%d, name=%s) -> exiting cleanly",
                          static_cast<int>(tgid), static_cast<int>(tid), sig, static_cast<int>(gettid()), tname[0] ? tname : "?");
                    pthread_exit(nullptr);
                    return 0;
                }
            }
            break;
        }
        case 134: { // __NR_rt_sigaction
            int sig = static_cast<int>(arg1);
            if (sig == SIGSYS) {
                return 0; // Never allow overriding SECCOMP SIGSYS handler
            }
            if (sig == SIGSEGV || sig == SIGBUS || sig == SIGABRT) {
                const auto *act = reinterpret_cast<const struct sigaction *>(arg2);
                if (act != nullptr && (act->sa_handler == SIG_DFL || act->sa_handler == SIG_IGN || act->sa_handler == nullptr)) {
                    return 0;
                }
            }
            break;
        }
        default:
            break;
    }
    return sOrigSyscall ? sOrigSyscall(number, arg1, arg2, arg3, arg4, arg5, arg6) : -1;
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

static bool isReadableUserPointer(const void *p, size_t len = 1) {
    if (!p) return false;
    uintptr_t addr = reinterpret_cast<uintptr_t>(p);
    if (addr < 0x10000ULL || addr >= UINT64_C(0x0001000000000000)) return false;
    uintptr_t pageStart = addr & ~uintptr_t(0xFFF);
    uintptr_t pageEnd = (addr + len) & ~uintptr_t(0xFFF);
    for (uintptr_t page = pageStart; page <= pageEnd; page += 4096) {
        unsigned char vec = 0;
        if (mincore(reinterpret_cast<void *>(page), 4096, &vec) != 0) {
            return false;
        }
    }
    return true;
}

static void sigsys_handler(int /*sig*/, siginfo_t *info, void *ucontext) {
    if (!ucontext) return;
    struct RecursionGuard {
        bool &flag;
        explicit RecursionGuard(bool &f) : flag(f) { flag = true; }
        ~RecursionGuard() { flag = false; }
    };
    static thread_local bool sInSigsys = false;
    if (sInSigsys) return;
    RecursionGuard guard(sInSigsys);

    auto *uc = reinterpret_cast<ucontext_t *>(ucontext);
    long nr = (info && info->si_syscall != 0) ? info->si_syscall : static_cast<long>(uc->uc_mcontext.regs[8]);
    long a0 = static_cast<long>(uc->uc_mcontext.regs[0]);
    long a1 = static_cast<long>(uc->uc_mcontext.regs[1]);
    long a2 = static_cast<long>(uc->uc_mcontext.regs[2]);
    long a3 = static_cast<long>(uc->uc_mcontext.regs[3]);
    long a4 = static_cast<long>(uc->uc_mcontext.regs[4]);

    ALOGI("FEATURESTIC SECCOMP: Trapped raw syscall nr=%ld, pc=%p, tid=%d",
          nr, reinterpret_cast<void *>(uc->uc_mcontext.pc), static_cast<int>(gettid()));

    switch (nr) {
        case 79: // __NR_newfstatat / __NR_fstatat64
        case 291: // __NR_statx
        case 48: // __NR_faccessat
        case 439: // __NR_faccessat2
        case 56: // __NR_openat
        case 78: { // __NR_readlinkat
            const char *pathname = reinterpret_cast<const char *>(a1);
            if (isReadableUserPointer(pathname)) {
                if (isBlockedDevPath(pathname) || isDirFdBlocked(static_cast<int>(a0), pathname)) {
                    // Suppress kernel SELinux audit denial completely
                    uc->uc_mcontext.regs[0] = static_cast<unsigned long>(-ENOENT);
                    uc->uc_mcontext.pc += 4;
                    return;
                }
                if (nr == 56) {
                    if (isProcMaps(pathname)) {
                        int fd = createVirtualProcMapsFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcCmdline(pathname)) {
                        int fd = createVirtualProcCmdlineFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcStatus(pathname)) {
                        int fd = createVirtualProcStatusFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcComm(pathname)) {
                        int fd = createVirtualProcCommFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcStat(pathname)) {
                        int fd = createVirtualProcStatFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcNetTcp(pathname)) {
                        int fd = createVirtualProcNetTcpFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    } else if (isProcMounts(pathname)) {
                        int fd = createVirtualProcMountsFd();
                        uc->uc_mcontext.regs[0] = static_cast<unsigned long>((fd >= 0) ? fd : -ENOENT);
                        uc->uc_mcontext.pc += 4;
                        return;
                    }
                }
            }
            const char *redirected = (isReadableUserPointer(pathname)) ? IO::redirectPath(pathname) : nullptr;
            long realA1 = (redirected != nullptr) ? reinterpret_cast<long>(redirected) : a1;
            long res = raw_real_syscall(nr, a0, realA1, a2, a3, a4, 0x5afe5afe);
            uc->uc_mcontext.regs[0] = static_cast<unsigned long>(res);
            uc->uc_mcontext.pc += 4;
            return;
        }

        case 94: { // __NR_exit_group
            if (!isCriticalGameOrMainThreadHelper()) {
                char tname[32] = {0};
                prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
                ALOGW("FEATURESTIC SECCOMP: Suppressed raw exit_group() from worker thread (tid=%d, name=%s) -> isolating thread",
                      static_cast<int>(gettid()), tname[0] ? tname : "?");
                struct timespec ts{3600, 0};
                while (true) {
                    nanosleep(&ts, nullptr);
                }
            }
            break;
        }

        case 129: // __NR_kill
        case 130: // __NR_tkill
        case 131: { // __NR_tgkill
            pid_t tgid = (nr == 131) ? static_cast<pid_t>(a0) : getpid();
            int sigVal = (nr == 131) ? static_cast<int>(a2) : static_cast<int>(a1);
            if ((sigVal == SIGSEGV || sigVal == SIGBUS || sigVal == SIGABRT || sigVal == SIGKILL)
                    && (tgid == getpid() || tgid == 0 || tgid == -1)) {
                if (!isCriticalGameOrMainThreadHelper()) {
                    char tname[32] = {0};
                    prctl(PR_GET_NAME, reinterpret_cast<unsigned long>(tname), 0, 0, 0);
                    ALOGW("FEATURESTIC SECCOMP: Suppressed raw suicide signal %d from worker thread (tid=%d, name=%s)",
                          sigVal, static_cast<int>(gettid()), tname[0] ? tname : "?");
                    uc->uc_mcontext.regs[0] = 0;
                    uc->uc_mcontext.pc += 4;
                    return;
                }
            }
            break;
        }

        default:
            break;
    }

    long res = raw_real_syscall(nr, a0, a1, a2, a3, a4, 0x5afe5afe);
    uc->uc_mcontext.regs[0] = static_cast<unsigned long>(res);
    uc->uc_mcontext.pc += 4;
}

static bool installSeccompVfsFilter() {
    static const uint32_t kMagicCanary = 0x5afe5afe;

    struct sigaction sa{};
    sa.sa_sigaction = sigsys_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    if (sigaction(SIGSYS, &sa, nullptr) != 0) {
        ALOGE("NativeVfsHook: Failed to install SIGSYS handler: %s", strerror(errno));
        return false;
    }

    struct sock_filter filter[] = {
        // 0: ld arch
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        // 1: jeq arch -> jt=1 (idx 3), jf=0 (idx 2)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
        // 2: ret ALLOW
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        // 3: ld args[5] (offset 56)
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 56),
        // 4: jeq CANARY -> jt=13 (idx 18), jf=0 (idx 5)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, kMagicCanary, 13, 0),
        // 5: ld nr (offset 0)
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        // 6: jeq 79 (__NR_newfstatat) -> jt=10 (idx 17), jf=0 (idx 7)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 79, 10, 0),
        // 7: jeq 291 (__NR_statx) -> jt=9 (idx 17), jf=0 (idx 8)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 291, 9, 0),
        // 8: jeq 48 (__NR_faccessat) -> jt=8 (idx 17), jf=0 (idx 9)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 48, 8, 0),
        // 9: jeq 439 (__NR_faccessat2) -> jt=7 (idx 17), jf=0 (idx 10)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 439, 7, 0),
        // 10: jeq 56 (__NR_openat) -> jt=6 (idx 17), jf=0 (idx 11)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 56, 6, 0),
        // 11: jeq 78 (__NR_readlinkat) -> jt=5 (idx 17), jf=0 (idx 12)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 78, 5, 0),
        // 12: jeq 94 (__NR_exit_group) -> jt=4 (idx 17), jf=0 (idx 13)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 94, 4, 0),
        // 13: jeq 129 (__NR_kill) -> jt=3 (idx 17), jf=0 (idx 14)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 129, 3, 0),
        // 14: jeq 130 (__NR_tkill) -> jt=2 (idx 17), jf=0 (idx 15)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 130, 2, 0),
        // 15: jeq 131 (__NR_tgkill) -> jt=1 (idx 17), jf=0 (idx 16)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 131, 1, 0),
        // 16: ret ALLOW
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        // 17: ret TRAP
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        // 18: ret ALLOW
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };

    struct sock_fprog prog = {
        .len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        .filter = filter,
    };

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        ALOGE("NativeVfsHook: prctl(PR_SET_NO_NEW_PRIVS) failed: %s", strerror(errno));
        return false;
    }

    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) {
        ALOGE("NativeVfsHook: prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER) failed: %s", strerror(errno));
        return false;
    }

    ALOGI("NativeVfsHook: SECCOMP-BPF Raw Syscall Trap successfully active");
    return true;
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

    if (openatSym) {
        A64HookFunction(openatSym,
                        reinterpret_cast<void *>(safe_openat),
                        reinterpret_cast<void **>(&sOrigOpenat));
    }
    if (openat64Sym && openat64Sym != openatSym) {
        A64HookFunction(openat64Sym,
                        reinterpret_cast<void *>(safe_openat64),
                        reinterpret_cast<void **>(&sOrigOpenat64));
    }
    if (openSym && openSym != openatSym) {
        A64HookFunction(openSym,
                        reinterpret_cast<void *>(safe_open),
                        reinterpret_cast<void **>(&sOrigOpen));
    }
    if (faccessatSym) {
        A64HookFunction(faccessatSym,
                        reinterpret_cast<void *>(safe_faccessat),
                        reinterpret_cast<void **>(&sOrigFaccessat));
    }
    if (accessSym && accessSym != faccessatSym) {
        A64HookFunction(accessSym,
                        reinterpret_cast<void *>(safe_access),
                        reinterpret_cast<void **>(&sOrigAccess));
    }
    if (fstatatSym) {
        A64HookFunction(fstatatSym,
                        reinterpret_cast<void *>(safe_fstatat),
                        reinterpret_cast<void **>(&sOrigFstatat));
    }
    if (fstatat64Sym && fstatat64Sym != fstatatSym) {
        A64HookFunction(fstatat64Sym,
                        reinterpret_cast<void *>(safe_fstatat64),
                        reinterpret_cast<void **>(&sOrigFstatat64));
    }
    if (statSym) {
        A64HookFunction(statSym,
                        reinterpret_cast<void *>(safe_stat),
                        reinterpret_cast<void **>(&sOrigStat));
    }
    if (lstatSym) {
        A64HookFunction(lstatSym,
                        reinterpret_cast<void *>(safe_lstat),
                        reinterpret_cast<void **>(&sOrigLstat));
    }
    if (stat64Sym && stat64Sym != statSym) {
        A64HookFunction(stat64Sym,
                        reinterpret_cast<void *>(safe_stat64),
                        reinterpret_cast<void **>(&sOrigStat64));
    }
    if (lstat64Sym && lstat64Sym != lstatSym) {
        A64HookFunction(lstat64Sym,
                        reinterpret_cast<void *>(safe_lstat64),
                        reinterpret_cast<void **>(&sOrigLstat64));
    }
    if (statxSym) {
        A64HookFunction(statxSym,
                        reinterpret_cast<void *>(safe_statx),
                        reinterpret_cast<void **>(&sOrigStatx));
    }
    if (syscallSym) {
        A64HookFunction(syscallSym,
                        reinterpret_cast<void *>(safe_syscall),
                        reinterpret_cast<void **>(&sOrigSyscall));
    }
    if (fstatSym) {
        A64HookFunction(fstatSym,
                        reinterpret_cast<void *>(safe_fstat),
                        reinterpret_cast<void **>(&sOrigFstat));
    }
    if (fstat64Sym && fstat64Sym != fstatSym) {
        A64HookFunction(fstat64Sym,
                        reinterpret_cast<void *>(safe_fstat64),
                        reinterpret_cast<void **>(&sOrigFstat64));
    }
    if (readlinkatSym) {
        A64HookFunction(readlinkatSym,
                        reinterpret_cast<void *>(safe_readlinkat),
                        reinterpret_cast<void **>(&sOrigReadlinkat));
    }
    if (readlinkSym && readlinkSym != readlinkatSym) {
        A64HookFunction(readlinkSym,
                        reinterpret_cast<void *>(safe_readlink),
                        reinterpret_cast<void **>(&sOrigReadlink));
    }

    if (!sOrigOpenat && openatSym) sOrigOpenat = reinterpret_cast<openat_fn_t>(openatSym);
    if (!sOrigOpenat64 && openat64Sym) sOrigOpenat64 = reinterpret_cast<openat_fn_t>(openat64Sym);
    if (!sOrigOpen && openSym) sOrigOpen = reinterpret_cast<open_fn_t>(openSym);
    if (!sOrigFaccessat && faccessatSym) sOrigFaccessat = reinterpret_cast<faccessat_fn_t>(faccessatSym);
    if (!sOrigAccess && accessSym) sOrigAccess = reinterpret_cast<access_fn_t>(accessSym);
    if (!sOrigFstatat && fstatatSym) sOrigFstatat = reinterpret_cast<fstatat_fn_t>(fstatatSym);
    if (!sOrigFstatat64 && fstatat64Sym) sOrigFstatat64 = reinterpret_cast<fstatat_fn_t>(fstatat64Sym);
    if (!sOrigStat && statSym) sOrigStat = reinterpret_cast<stat_fn_t>(statSym);
    if (!sOrigLstat && lstatSym) sOrigLstat = reinterpret_cast<lstat_fn_t>(lstatSym);
    if (!sOrigStat64 && stat64Sym) sOrigStat64 = reinterpret_cast<stat64_fn_t>(stat64Sym);
    if (!sOrigLstat64 && lstat64Sym) sOrigLstat64 = reinterpret_cast<lstat64_fn_t>(lstat64Sym);
    if (!sOrigStatx && statxSym) sOrigStatx = reinterpret_cast<statx_fn_t>(statxSym);
    if (!sOrigSyscall && syscallSym) sOrigSyscall = reinterpret_cast<syscall_fn_t>(syscallSym);
    if (!sOrigFstat && fstatSym) sOrigFstat = reinterpret_cast<fstat_fn_t>(fstatSym);
    if (!sOrigFstat64 && fstat64Sym) sOrigFstat64 = reinterpret_cast<fstat_fn_t>(fstat64Sym);
    if (!sOrigReadlinkat && readlinkatSym) sOrigReadlinkat = reinterpret_cast<readlinkat_fn_t>(readlinkatSym);
    if (!sOrigReadlink && readlinkSym) sOrigReadlink = reinterpret_cast<readlink_fn_t>(readlinkSym);

    installSeccompVfsFilter();
#endif

    ALOGI("NativeVfsHook: Libc VFS and Procfs virtualization hooks active");
}

} // namespace NativeVfsHook
