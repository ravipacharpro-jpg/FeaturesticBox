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
#include <cstdarg>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <string>
#include <atomic>
#include <mutex>

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
typedef ssize_t (*readlinkat_fn_t)(int dirfd, const char *pathname, char *buf, size_t bufsiz);
typedef ssize_t (*readlink_fn_t)(const char *pathname, char *buf, size_t bufsiz);

static openat_fn_t sOrigOpenat = nullptr;
static openat_fn_t sOrigOpenat64 = nullptr;
static open_fn_t sOrigOpen = nullptr;
static faccessat_fn_t sOrigFaccessat = nullptr;
static access_fn_t sOrigAccess = nullptr;
static fstatat_fn_t sOrigFstatat = nullptr;
static fstatat_fn_t sOrigFstatat64 = nullptr;
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
    if (strncmp(path, "/dev/pts/", 9) == 0 || strcmp(path, "/dev/pts") == 0 || strcmp(path, "/dev/ptmx") == 0) {
        return true;
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
    return -1;
}

static bool shouldFilterMapsLine(const char *line) {
    if (!line) return false;
    if (strstr(line, "libFeaturesticCore.so") != nullptr) return true;
    if (strstr(line, "libFeaturesticPlugin.so") != nullptr) return true;
    if (strstr(line, "libloader.so") != nullptr) return true;
    if (strstr(line, "FeaturesticBox") != nullptr) return true;
    if (strstr(line, "libdobby.so") != nullptr) return true;
    if (strstr(line, "libSandHook.so") != nullptr) return true;
    if (strstr(line, "sandhook") != nullptr) return true;
    if (strstr(line, "frida") != nullptr) return true;
    if (strstr(line, "linjector") != nullptr) return true;
    if (strstr(line, "xposed") != nullptr) return true;
    if (strstr(line, "edxp") != nullptr) return true;
    if (strstr(line, "lspd") != nullptr) return true;
    if (strstr(line, "magisk") != nullptr) return true;
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
    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
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
    if (!pathname || sInVfsBypass) {
        return sOrigFstatat64 ? sOrigFstatat64(dirfd, pathname, buf, flags) : -1;
    }
    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (isProcNetTcp(pathname)) {
        if (buf) {
            memset(buf, 0, sizeof(struct stat));
            buf->st_mode = S_IFREG | 0444;
            buf->st_size = 80;
        }
        return 0;
    }
    const char *redirected = IO::redirectPath(pathname);
    return sOrigFstatat64 ? sOrigFstatat64(dirfd, redirected ? redirected : pathname, buf, flags) : -1;
}

static ssize_t safe_readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz) {
    if (!pathname || sInVfsBypass) {
        return sOrigReadlinkat ? sOrigReadlinkat(dirfd, pathname, buf, bufsiz) : -1;
    }
    if (isBlockedDevPath(pathname)) {
        errno = ENOENT;
        return -1;
    }
    if (strcmp(pathname, "/proc/self/exe") == 0) {
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
    if (strcmp(pathname, "/proc/self/exe") == 0) {
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
    if (!sOrigReadlinkat && readlinkatSym) sOrigReadlinkat = reinterpret_cast<readlinkat_fn_t>(readlinkatSym);
    if (!sOrigReadlink && readlinkSym) sOrigReadlink = reinterpret_cast<readlink_fn_t>(readlinkSym);
#endif

    ALOGI("NativeVfsHook: Libc VFS and Procfs virtualization hooks active");
}

} // namespace NativeVfsHook
