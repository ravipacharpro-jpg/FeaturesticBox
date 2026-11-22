#include "SystemPropertiesHook.h"
#include "IO.h"
#include "BoxCore.h"
#import "JniHook/JniHook.h"
#include "Log.h"
#include "And64InlineHook/And64InlineHook.hpp"
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <map>
#include <cstring>
#include <atomic>

namespace {

static std::map<std::string, std::string> sPropMap;
static std::mutex sPropMutex;
static std::atomic<bool> sNativeHooksInstalled{false};

static int (*sOrigSystemPropertyGet)(const char *, char *) = nullptr;
static const prop_info *(*sOrigSystemPropertyFind)(const char *) = nullptr;

static bool isQemuOrVirtualProp(const char *name) {
    if (name == nullptr || name[0] == '\0') return false;
    if (strstr(name, "qemu") != nullptr
            || strstr(name, "goldfish") != nullptr
            || strstr(name, "ranchu") != nullptr
            || strstr(name, "vbox") != nullptr
            || strstr(name, "androVM") != nullptr
            || strstr(name, "nox") != nullptr
            || strstr(name, "ttVM") != nullptr
            || strstr(name, "genymotion") != nullptr) {
        return true;
    }
    return false;
}

static int safe_system_property_get(const char *name, char *value) {
    if (name == nullptr || name[0] == '\0' || value == nullptr) {
        if (value) value[0] = '\0';
        return 0;
    }

    if (isQemuOrVirtualProp(name)) {
        value[0] = '\0';
        return 0;
    }

    if (strcmp(name, "ro.debuggable") == 0) {
        strcpy(value, "0");
        return 1;
    }
    if (strcmp(name, "ro.secure") == 0) {
        strcpy(value, "1");
        return 1;
    }
    if (strcmp(name, "ro.build.tags") == 0) {
        strcpy(value, "release-keys");
        return 12;
    }
    if (strcmp(name, "ro.build.type") == 0) {
        strcpy(value, "user");
        return 4;
    }
    if (strcmp(name, "ro.boot.flash.locked") == 0) {
        strcpy(value, "1");
        return 1;
    }
    if (strcmp(name, "ro.boot.verifiedbootstate") == 0) {
        strcpy(value, "green");
        return 5;
    }
    if (strcmp(name, "ro.boot.vbmeta.device_state") == 0) {
        strcpy(value, "locked");
        return 6;
    }

    {
        std::lock_guard<std::mutex> lock(sPropMutex);
        auto it = sPropMap.find(name);
        if (it != sPropMap.end()) {
            strncpy(value, it->second.c_str(), PROP_VALUE_MAX - 1);
            value[PROP_VALUE_MAX - 1] = '\0';
            return static_cast<int>(strlen(value));
        }
    }

    if (sOrigSystemPropertyGet != nullptr) {
        return sOrigSystemPropertyGet(name, value);
    }
    return 0;
}

static const prop_info *safe_system_property_find(const char *name) {
    if (name == nullptr || name[0] == '\0') {
        return nullptr;
    }

    if (isQemuOrVirtualProp(name)) {
        return nullptr;
    }

    if (sOrigSystemPropertyFind != nullptr) {
        return sOrigSystemPropertyFind(name);
    }
    return nullptr;
}

HOOK_JNI(jstring, native_get, JNIEnv *env, jobject obj, jstring key, jstring def) {
    if (env == nullptr) return nullptr;
    if (key == nullptr) return def;

    const char *key_str = env->GetStringUTFChars(key, nullptr);
    if (key_str == nullptr) return def;

    if (isQemuOrVirtualProp(key_str)) {
        env->ReleaseStringUTFChars(key, key_str);
        return def;
    }

    if (strcmp(key_str, "ro.debuggable") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("0");
    }
    if (strcmp(key_str, "ro.secure") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("1");
    }
    if (strcmp(key_str, "ro.build.tags") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("release-keys");
    }
    if (strcmp(key_str, "ro.build.type") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("user");
    }
    if (strcmp(key_str, "ro.boot.flash.locked") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("1");
    }
    if (strcmp(key_str, "ro.boot.verifiedbootstate") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("green");
    }
    if (strcmp(key_str, "ro.boot.vbmeta.device_state") == 0) {
        env->ReleaseStringUTFChars(key, key_str);
        return env->NewStringUTF("locked");
    }

    std::string found_value;
    bool has_override = false;
    {
        std::lock_guard<std::mutex> lock(sPropMutex);
        auto it = sPropMap.find(key_str);
        if (it != sPropMap.end()) {
            found_value = it->second;
            has_override = true;
        }
    }

    env->ReleaseStringUTFChars(key, key_str);

    if (has_override) {
        return env->NewStringUTF(found_value.c_str());
    }

    return orig_native_get(env, obj, key, def);
}

} // anonymous namespace

void SystemPropertiesHook::setProp(const char *key, const char *value) {
    if (key != nullptr && value != nullptr) {
        std::lock_guard<std::mutex> lock(sPropMutex);
        sPropMap[std::string(key)] = std::string(value);
    }
}

void SystemPropertiesHook::init(JNIEnv *env) {
    {
        std::lock_guard<std::mutex> lock(sPropMutex);
        // Only keep safe bootloader/debuggable security flags; never hardcode fake Xiaomi Mi 10
        // ("umi" / "QKQ1.191117.002") properties that conflict with real native libc properties
        // on OnePlus/OPPO/Samsung devices and trigger libanogs.so environment mismatch detection.
        sPropMap["ro.debuggable"] = "0";
        sPropMap["ro.secure"] = "1";
        sPropMap["ro.boot.verifiedbootstate"] = "green";
        sPropMap["ro.boot.flash.locked"] = "1";
        sPropMap["ro.boot.vbmeta.device_state"] = "locked";
        sPropMap["ro.boot.warranty_bit"] = "0";
        sPropMap["sys.oem_unlock_allowed"] = "0";
        sPropMap["ro.build.tags"] = "release-keys";
        sPropMap["ro.build.type"] = "user";
    }

    // 1. Hook Java SystemProperties.native_get
    if (env != nullptr) {
        JniHook::HookJniFun(
            env,
            "android/os/SystemProperties",
            "native_get",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            (void *) new_native_get,
            (void **) (&orig_native_get),
            true
        );
    }

    // 2. Hook Native libc __system_property_get and __system_property_find via A64HookFunction
    if (!sNativeHooksInstalled.exchange(true)) {
#if defined(__aarch64__)
        void *getSym = dlsym(RTLD_DEFAULT, "__system_property_get");
        if (getSym != nullptr) {
            A64HookFunction(getSym,
                            reinterpret_cast<void *>(safe_system_property_get),
                            reinterpret_cast<void **>(&sOrigSystemPropertyGet));
            ALOGI("SystemPropertiesHook: Hooked native __system_property_get");
        }
        void *findSym = dlsym(RTLD_DEFAULT, "__system_property_find");
        if (findSym != nullptr) {
            A64HookFunction(findSym,
                            reinterpret_cast<void *>(safe_system_property_find),
                            reinterpret_cast<void **>(&sOrigSystemPropertyFind));
            ALOGI("SystemPropertiesHook: Hooked native __system_property_find");
        }
#endif
    }
}
