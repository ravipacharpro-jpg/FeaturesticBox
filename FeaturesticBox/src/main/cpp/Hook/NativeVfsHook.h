#pragma once

#include <jni.h>
#include <string>

namespace NativeVfsHook {
    void init();
    void setVirtualPackage(const std::string &pkg, const std::string &processName);
    bool isHooked();
}
