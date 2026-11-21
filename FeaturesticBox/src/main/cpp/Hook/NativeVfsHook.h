#pragma once

#include <jni.h>
#include <string>
#include <sys/types.h>

namespace NativeVfsHook {
    void init();
    void setVirtualPackage(const std::string &pkg, const std::string &processName);
    bool isHooked();
    int rawDirectOpen(const char *path, int flags, mode_t mode = 0);
    ssize_t rawDirectRead(int fd, void *buf, size_t count);
    int rawDirectClose(int fd);
}

