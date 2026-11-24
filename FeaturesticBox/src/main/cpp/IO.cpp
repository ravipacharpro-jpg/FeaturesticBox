#include "IO.h"
#include "Log.h"
#include <cstring>
#include <cstdlib>
#include <limits.h>
#include <mutex>

jmethodID getAbsolutePathMethodId;
list<IO::RelocateInfo> relocate_rule;
static std::recursive_mutex relocate_rule_mutex;

const char *IO::redirectPath(const char *__path) {
    if (!__path || __path[0] == '\0') return __path;
    if (strstr(__path, "resource-cache") != nullptr || strstr(__path, "@idmap") != nullptr || strstr(__path, ".frro") != nullptr) {
        return "/dev/null";
    }
    // Isolate terminal pseudo-devices (Termux/ADB) so anti-cheat security scans receive ENOENT
    if (strncmp(__path, "/dev/pts", 8) == 0 || strcmp(__path, "/dev/ptmx") == 0) {
        return "/system/nonexistent_device_pts";
    }
    if (strstr(__path, "/SdCard/") != nullptr
            || strstr(__path, "/Sdcard/") != nullptr
            || strstr(__path, "/files/data/") != nullptr
            || strstr(__path, "/files/user_de/") != nullptr
            || strstr(__path, "/files/profiles/") != nullptr
            || strstr(__path, "/files/proc/") != nullptr
            || strstr(__path, "/Download/") != nullptr) {
        return __path;
    }

    thread_local char tls_bufs[4][PATH_MAX];
    thread_local int tls_idx = 0;

    std::lock_guard<std::recursive_mutex> lock(relocate_rule_mutex);
    for (auto iterator = relocate_rule.begin(); iterator != relocate_rule.end(); ++iterator) {
        const IO::RelocateInfo &info = *iterator;
        if (!info.targetPath || !info.relocatePath) continue;
        if (strstr(__path, info.relocatePath) != nullptr) {
            return __path;
        }
        size_t targetLen = strlen(info.targetPath);
        if (targetLen == 0) continue;
        if (strncmp(__path, info.targetPath, targetLen) == 0
                && (__path[targetLen] == '\0' || __path[targetLen] == '/')) {
            size_t relocateLen = strlen(info.relocatePath);
            size_t tailLen = strlen(__path + targetLen);
            if (relocateLen + tailLen + 1 <= PATH_MAX) {
                char *ret = tls_bufs[tls_idx];
                tls_idx = (tls_idx + 1) & 3;
                memcpy(ret, info.relocatePath, relocateLen);
                memcpy(ret + relocateLen, __path + targetLen, tailLen + 1);
                return ret;
            }
        }
    }
    return __path;
}

jstring IO::redirectPath(JNIEnv *env, jstring path) {
    return BoxCore::redirectPathString(env, path);
}

jobject IO::redirectPath(JNIEnv *env, jobject path) {
    return BoxCore::redirectPathFile(env, path);
}

void IO::addRule(const char *targetPath, const char *relocatePath) {
    if (!targetPath || !relocatePath) return;
    std::lock_guard<std::recursive_mutex> lock(relocate_rule_mutex);
    for (auto it = relocate_rule.begin(); it != relocate_rule.end(); ++it) {
        if (it->targetPath && strcmp(it->targetPath, targetPath) == 0) {
            return;
        }
    }
    IO::RelocateInfo info{};
    info.targetPath = strdup(targetPath);
    info.relocatePath = strdup(relocatePath);
    size_t newLen = strlen(targetPath);
    for (auto it = relocate_rule.begin(); it != relocate_rule.end(); ++it) {
        if (it->targetPath && newLen > strlen(it->targetPath)) {
            relocate_rule.insert(it, info);
            return;
        }
    }
    relocate_rule.push_back(info);
}

void IO::init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    if (tmpFile) {
        getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
        env->DeleteLocalRef(tmpFile);
    }
}
