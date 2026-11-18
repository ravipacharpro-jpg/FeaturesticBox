#include "SystemPropertiesHook.h"
#include "IO.h"
#include "BoxCore.h"
#import "JniHook/JniHook.h"
#include "Log.h"
#include <sys/system_properties.h>
#include <mutex>
#include <string>
#include <map>
#include <cstring>

static std::map<std::string, std::string> prop_map;
static std::mutex prop_mutex;

HOOK_JNI(jstring, native_get, JNIEnv *env, jobject obj, jstring key, jstring def) {
    if (env == nullptr) {
        return nullptr;
    }
    if (key == nullptr) {
        return def;
    }

    const char *key_str = env->GetStringUTFChars(key, nullptr);
    if (key_str == nullptr) {
        return def;
    }

    std::string found_value;
    bool has_override = false;
    {
        std::lock_guard<std::mutex> lock(prop_mutex);
        auto ret = prop_map.find(key_str);
        if (ret != prop_map.end()) {
            found_value = ret->second;
            has_override = true;
        }
    }

    env->ReleaseStringUTFChars(key, key_str);

    if (has_override) {
        return env->NewStringUTF(found_value.c_str());
    }

    return orig_native_get(env, obj, key, def);
}

HOOK_JNI(int, __system_property_get, const char *name, char *value) {
    if (!name || !value) {
        return orig___system_property_get(name, value);
    }

    std::string found_value;
    bool has_override = false;
    {
        std::lock_guard<std::mutex> lock(prop_mutex);
        auto ret = prop_map.find(name);
        if (ret != prop_map.end()) {
            found_value = ret->second;
            has_override = true;
        }
    }

    if (has_override) {
        strncpy(value, found_value.c_str(), PROP_VALUE_MAX - 1);
        value[PROP_VALUE_MAX - 1] = '\0';
        return static_cast<int>(strlen(value));
    }

    return orig___system_property_get(name, value);
}

void SystemPropertiesHook::setProp(const char *key, const char *value) {
    if (key != nullptr && value != nullptr) {
        std::lock_guard<std::mutex> lock(prop_mutex);
        prop_map[std::string(key)] = std::string(value);
    }
}

void SystemPropertiesHook::init(JNIEnv *env) {
    {
        std::lock_guard<std::mutex> lock(prop_mutex);
        // Only keep safe bootloader/debuggable security flags; never hardcode fake Xiaomi Mi 10
        // ("umi" / "QKQ1.191117.002") properties that conflict with real native libc properties
        // on OnePlus/OPPO/Samsung devices and trigger libanogs.so environment mismatch detection.
        prop_map["ro.debuggable"] = "0";
        prop_map["ro.secure"] = "1";
        prop_map["ro.boot.verifiedbootstate"] = "green";
        prop_map["ro.boot.flash.locked"] = "1";
        prop_map["ro.boot.vbmeta.device_state"] = "locked";
        prop_map["ro.boot.warranty_bit"] = "0";
        prop_map["sys.oem_unlock_allowed"] = "0";
        prop_map["ro.build.tags"] = "release-keys";
        prop_map["ro.build.type"] = "user";
    }

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