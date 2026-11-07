#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    int api_level;
    unsigned int art_field_size;
    int art_field_flags_offset;
    unsigned int art_method_size;
    int art_method_flags_offset;
    int art_method_native_offset;
    int class_flags_offset;

    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;
} HookEnv;

static const char *GetMethodDesc(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class, HookEnv.get_method_desc_id, javaMethod));
    return desc ? env->GetStringUTFChars(desc, JNI_FALSE) : nullptr;
}

static const char *GetMethodDeclaringClass(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class, HookEnv.get_method_declaring_class_id, javaMethod));
    return desc ? env->GetStringUTFChars(desc, JNI_FALSE) : nullptr;
}

static const char *GetMethodName(JNIEnv *env, jobject javaMethod) {
    auto desc = reinterpret_cast<jstring>(env->CallStaticObjectMethod(HookEnv.method_utils_class, HookEnv.get_method_name_id, javaMethod));
    return desc ? env->GetStringUTFChars(desc, JNI_FALSE) : nullptr;
}

inline static uint32_t GetAccessFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + HookEnv.art_method_flags_offset);
}

inline static bool SetAccessFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + HookEnv.art_method_flags_offset) = flags;
    return true;
}

inline static bool AddAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag | flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool ClearAccessFlag(char *art_method, uint32_t flag) {
    uint32_t old_flag = GetAccessFlags(art_method);
    uint32_t new_flag = old_flag & ~flag;
    return new_flag != old_flag && SetAccessFlags(art_method, new_flag);
}

inline static bool HasAccessFlag(char *art_method, uint32_t flag) {
    uint32_t flags = GetAccessFlags(art_method);
    return (flags & flag) == flag;
}

inline static bool ClearFastNativeFlag(char *art_method) {
    return HookEnv.api_level < __ANDROID_API_P__ && ClearAccessFlag(art_method, kAccFastNative);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID methodId, bool is_static = true) {
    if (!env || !clazz || !methodId) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        if (!executable) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return reinterpret_cast<void *>(methodId);
        }
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        if (!artId) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(executable);
            return reinterpret_cast<void *>(methodId);
        }
        jobject method = env->ToReflectedMethod(clazz, methodId, is_static ? JNI_TRUE : JNI_FALSE);
        if (!method) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            method = env->ToReflectedMethod(clazz, methodId, is_static ? JNI_FALSE : JNI_TRUE);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        void *res = nullptr;
        if (method) {
            res = reinterpret_cast<void *>(env->GetLongField(method, artId));
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(method);
        }
        env->DeleteLocalRef(executable);
        return res ? res : reinterpret_cast<void *>(methodId);
    } else {
        return reinterpret_cast<void *>(methodId);
    }
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (!env || !field) return nullptr;
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        if (!fieldClass) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return env->FromReflectedField(field);
        }
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        void *res = nullptr;
        if (getArtField) {
            res = reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        env->DeleteLocalRef(fieldClass);
        return res;
    } else {
        return env->FromReflectedField(field);
    }
}

bool CheckFlags(void *artMethod) {
    if (!artMethod) return false;
    char *method = static_cast<char *>(artMethod);
    if (!HasAccessFlag(method, kAccNative)) {
        ALOGE("not native method");
        return false;
    }
    ClearFastNativeFlag(method);
    return true;
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun, void **orig_fun, bool is_static) {
    const char *class_name = GetMethodDeclaringClass(env, java_method);
    const char *method_name = GetMethodName(env, java_method);
    const char *sign = GetMethodDesc(env, java_method);
    if (class_name && method_name && sign) {
        HookJniFun(env, class_name, method_name, sign, new_fun, orig_fun, is_static);
    }
}

void JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign, void *new_fun, void **orig_fun, bool is_static) {
    if (!env || !class_name || !method_name || !sign || HookEnv.art_method_native_offset == 0) {
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        env->ExceptionClear();
        return;
    }
    jmethodID method = nullptr;
    if (is_static) {
        method = env->GetStaticMethodID(clazz, method_name, sign);
    } else {
        method = env->GetMethodID(clazz, method_name, sign);
    }
    if (!method) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }
    JNINativeMethod gMethods[] = {
            {method_name, sign, (void *) new_fun},
    };

    auto artMethod = reinterpret_cast<uintptr_t *>(GetArtMethod(env, clazz, method, is_static));
    if (!artMethod || !CheckFlags(artMethod)) {
        env->DeleteLocalRef(clazz);
        return;
    }
    *orig_fun = reinterpret_cast<void *>(artMethod[HookEnv.art_method_native_offset]);
    if (env->RegisterNatives(clazz, gMethods, 1) < 0) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }
    if (HookEnv.api_level == __ANDROID_API_O__ || HookEnv.api_level == __ANDROID_API_O_MR1__) {
        AddAccessFlag((char *) artMethod, kAccFastNative);
    }
    env->DeleteLocalRef(clazz);
}

__attribute__((section (".mytext"))) JNICALL void native_offset(JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext"))) JNICALL void native_offset2(JNIEnv *env, jclass obj) {
}

__attribute__((section (".mytext"))) JNICALL void set_method_accessible(JNIEnv *env, jclass obj, jclass clazz, jobject method) {
    if (!env || !clazz || !method) return;
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(GetArtMethod(env, clazz, methodId, false));
    if (!art_method) return;
    AddAccessFlag(art_method, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section (".mytext"))) JNICALL void set_field_accessible(JNIEnv *env, jclass obj, jclass clazz, jobject field) {
    if (!env || !field) return;
    char *artField = static_cast<char *>(GetFieldMethod(env, field));
    if (!artField) return;
    AddAccessFlag(artField, kAccPublic);
    if (HookEnv.api_level >= __ANDROID_API_Q__) {
        AddAccessFlag(artField, kAccPublicApi);
    }
    ClearAccessFlag(artField, kAccFinal);
}

void registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("com/featurestic/box/jnihook/jni/JniHook");
    if (!clazz) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    JNINativeMethod gMethods[] = {
            {"nativeOffset", "()V", (void *) native_offset},
            {"nativeOffset2", "()V", (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V", (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V", (void *) set_field_accessible},
    };
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    env->DeleteLocalRef(clazz);
}

void JniHook::InitJniHook(JNIEnv *env, int api_level) {
    registerNative(env);
    HookEnv.api_level = api_level;

    jclass clazz = env->FindClass("com/featurestic/box/jnihook/jni/JniHook");
    if (!clazz) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");

    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");

    jobject reflField1 = env->ToReflectedField(clazz, nativeOffsetFieldId, JNI_TRUE);
    jobject reflField2 = env->ToReflectedField(clazz, nativeOffsetField2Id, JNI_TRUE);
    void *nativeOffsetField = GetFieldMethod(env, reflField1);
    void *nativeOffsetField2 = GetFieldMethod(env, reflField2);
    if (reflField1) env->DeleteLocalRef(reflField1);
    if (reflField2) env->DeleteLocalRef(reflField2);

    intptr_t fieldDiff = reinterpret_cast<intptr_t>(nativeOffsetField2) - reinterpret_cast<intptr_t>(nativeOffsetField);
    if (fieldDiff < 0) fieldDiff = -fieldDiff;
    if (fieldDiff <= 0 || fieldDiff > 64) fieldDiff = 16;
    HookEnv.art_field_size = static_cast<unsigned int>(fieldDiff);

    void *nativeOffset = GetArtMethod(env, clazz, nativeOffsetId, true);
    void *nativeOffset2 = GetArtMethod(env, clazz, nativeOffset2Id, true);
    intptr_t methodDiff = reinterpret_cast<intptr_t>(nativeOffset2) - reinterpret_cast<intptr_t>(nativeOffset);
    if (methodDiff < 0) methodDiff = -methodDiff;
    if (methodDiff <= 0 || methodDiff > 64) methodDiff = sizeof(uintptr_t) * 4;
    HookEnv.art_method_size = static_cast<unsigned int>(methodDiff);

    int maxWords = static_cast<int>(HookEnv.art_method_size / sizeof(uintptr_t)) + 2;
    if (maxWords < 4) maxWords = 4;
    if (maxWords > 12) maxWords = 12;

    auto artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    int foundNativeIdx = -1;
    for (int i = 0; i < maxWords; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == native_offset) {
            foundNativeIdx = i;
            break;
        }
    }
    if (foundNativeIdx < 0) {
        // Standard ArtMethod entry_point_from_jni_ slot fallback (slot 2 on 64-bit, slot 3/4 on 32-bit)
        foundNativeIdx = (sizeof(uintptr_t) == 8) ? 2 : 3;
    }
    HookEnv.art_method_native_offset = foundNativeIdx;

    uint32_t baseFlags = kAccPublic | kAccStatic | kAccNative | kAccFinal;
    uint32_t exactFlags = baseFlags;
    if (api_level >= __ANDROID_API_Q__) {
        exactFlags |= kAccPublicApi;
    }
    if (api_level >= __ANDROID_API_S__) {
        exactFlags |= kAccNterpInvokeFastPathFlag;
    }

    char *start = reinterpret_cast<char *>(artMethod);
    int maxIntSlots = static_cast<int>(HookEnv.art_method_size / sizeof(uint32_t));
    if (maxIntSlots < 2 || maxIntSlots > 16) maxIntSlots = 8;

    int matchedIndex = -1;
    for (int i = 1; i < maxIntSlots; ++i) {
        auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
        if (value == exactFlags) {
            matchedIndex = i;
            break;
        }
    }
    if (matchedIndex < 0) {
        for (int i = 1; i < maxIntSlots; ++i) {
            auto value = *(uint32_t *) (start + i * sizeof(uint32_t));
            if ((value & baseFlags) == baseFlags && (value & kAccAbstract) == 0 && (value & kAccInterface) == 0) {
                matchedIndex = i;
                break;
            }
        }
    }
    if (matchedIndex < 0) {
        matchedIndex = 1;
    }
    HookEnv.art_method_flags_offset = matchedIndex * sizeof(uint32_t);
    HookEnv.art_field_flags_offset = 1 * sizeof(int32_t);

    env->DeleteLocalRef(clazz);

    jclass localMethodUtils = env->FindClass("com/featurestic/box/jnihook/MethodUtils");
    if (localMethodUtils) {
        HookEnv.method_utils_class = (jclass) env->NewGlobalRef(localMethodUtils);
        HookEnv.get_method_desc_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDesc", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        HookEnv.get_method_declaring_class_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getDeclaringClass", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        HookEnv.get_method_name_id = env->GetStaticMethodID(HookEnv.method_utils_class, "getMethodName", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
        env->DeleteLocalRef(localMethodUtils);
    }
}
