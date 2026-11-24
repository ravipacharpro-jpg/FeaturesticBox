#include <IO.h>
#include "UnixFileSystemHook.h"
#include "JniHook/JniHook.h"
#include "BaseHook.h"
#include <cstring>

static bool isDevPtsPath(JNIEnv *env, jstring pathStr) {
    if (!env || !pathStr) return false;
    const char *str = env->GetStringUTFChars(pathStr, nullptr);
    if (!str) return false;
    bool blocked = (strstr(str, "dev/pts") != nullptr
            || strstr(str, "dev/ptmx") != nullptr
            || strstr(str, "devpts") != nullptr
            || strstr(str, "/pts/") != nullptr
            || strncmp(str, "pts/", 4) == 0
            || strcmp(str, "/dev/pts") == 0
            || strncmp(str, "/dev/tty", 8) == 0
            || strcmp(str, "/dev/console") == 0);
    env->ReleaseStringUTFChars(pathStr, str);
    return blocked;
}

static bool isDevPtsFile(JNIEnv *env, jobject fileObj) {
    if (!env || !fileObj) return false;
    jclass fileClass = env->GetObjectClass(fileObj);
    if (!fileClass) return false;
    jmethodID getPath = env->GetMethodID(fileClass, "getPath", "()Ljava/lang/String;");
    if (!getPath) {
        env->DeleteLocalRef(fileClass);
        return false;
    }
    jstring pathStr = (jstring) env->CallObjectMethod(fileObj, getPath);
    env->DeleteLocalRef(fileClass);
    if (!pathStr) return false;
    bool blocked = isDevPtsPath(env, pathStr);
    env->DeleteLocalRef(pathStr);
    return blocked;
}

static inline void releaseRedirectObj(JNIEnv *env, jobject orig, jobject redirected) {
    if (!env || !redirected || redirected == orig) return;
    jthrowable pending = env->ExceptionOccurred();
    if (pending) {
        env->ExceptionClear();
    }
    if (!env->IsSameObject(orig, redirected)) {
        env->DeleteLocalRef(redirected);
    }
    if (pending) {
        env->Throw(pending);
        env->DeleteLocalRef(pending);
    }
}

HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    if (path && isDevPtsPath(env, path)) {
        return path;
    }
    jstring redirect = IO::redirectPath(env, path);
    jstring res = orig_canonicalize0(env, obj, redirect);
    releaseRedirectObj(env, path, redirect);
    return res;
}

HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    if (abspath && isDevPtsPath(env, abspath)) {
        return 0; // BA_EXISTS = 0x01; 0 indicates does not exist
    }
    jstring redirect = IO::redirectPath(env, abspath);
    jint res = orig_getBooleanAttributes0(env, obj, redirect);
    releaseRedirectObj(env, abspath, redirect);
    return res;
}

HOOK_JNI(jint, getBooleanAttributes0File, JNIEnv *env, jobject obj, jobject file) {
    if (file && isDevPtsFile(env, file)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, file);
    jint res = orig_getBooleanAttributes0File(env, obj, redirect);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jboolean, checkAccess0, JNIEnv *env, jobject obj, jobject file, jint access) {
    if (file && isDevPtsFile(env, file)) {
        return JNI_FALSE;
    }
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_checkAccess0(env, obj, redirect, access);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    if (path && isDevPtsFile(env, path)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, path);
    jlong res = orig_getLastModifiedTime0(env, obj, redirect);
    releaseRedirectObj(env, path, redirect);
    return res;
}

HOOK_JNI(jlong, getLength0, JNIEnv *env, jobject obj, jobject file) {
    if (file && isDevPtsFile(env, file)) {
        return 0;
    }
    jobject redirect = IO::redirectPath(env, file);
    jlong res = orig_getLength0(env, obj, redirect);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setPermission0(env, obj, redirect, access, enable, owneronly);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    jboolean res = orig_createFileExclusively0(env, obj, redirect);
    releaseRedirectObj(env, path, redirect);
    return res;
}

HOOK_JNI(jboolean, delete0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_delete0(env, obj, redirect);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    if (file && isDevPtsFile(env, file)) {
        return nullptr;
    }
    jobject redirect = IO::redirectPath(env, file);
    jobjectArray res = orig_list0(env, obj, redirect);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    jboolean res = orig_createDirectory0(env, obj, redirect);
    releaseRedirectObj(env, path, redirect);
    return res;
}

HOOK_JNI(jboolean, rename0, JNIEnv *env, jobject obj, jobject f1, jobject f2) {
    jobject r1 = IO::redirectPath(env, f1);
    jobject r2 = IO::redirectPath(env, f2);
    jboolean res = orig_rename0(env, obj, r1, r2);
    releaseRedirectObj(env, f1, r1);
    releaseRedirectObj(env, f2, r2);
    return res;
}

HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jlong time) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setLastModifiedTime0(env, obj, redirect, time);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    jboolean res = orig_setReadOnly0(env, obj, redirect);
    releaseRedirectObj(env, file, redirect);
    return res;
}

HOOK_JNI(jlong, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    jlong res = orig_getSpace0(env, obj, redirect, t);
    releaseRedirectObj(env, file, redirect);
    return res;
}

void UnixFileSystemHook::init(JNIEnv *env) {
    const char *className = "java/io/UnixFileSystem";
    JniHook::HookJniFun(env, className, "canonicalize0", "(Ljava/lang/String;)Ljava/lang/String;", (void *) new_canonicalize0, (void **) (&orig_canonicalize0), false);
    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/lang/String;)I", (void *) new_getBooleanAttributes0, (void **) (&orig_getBooleanAttributes0), false);
    JniHook::HookJniFun(env, className, "getBooleanAttributes0", "(Ljava/io/File;)I", (void *) new_getBooleanAttributes0File, (void **) (&orig_getBooleanAttributes0File), false);
    JniHook::HookJniFun(env, className, "checkAccess0", "(Ljava/io/File;I)Z", (void *) new_checkAccess0, (void **) (&orig_checkAccess0), false);
    JniHook::HookJniFun(env, className, "getLastModifiedTime0", "(Ljava/io/File;)J", (void *) new_getLastModifiedTime0, (void **) (&orig_getLastModifiedTime0), false);
    JniHook::HookJniFun(env, className, "getLength0", "(Ljava/io/File;)J", (void *) new_getLength0, (void **) (&orig_getLength0), false);
    JniHook::HookJniFun(env, className, "setPermission0", "(Ljava/io/File;IZZ)Z", (void *) new_setPermission0, (void **) (&orig_setPermission0), false);
    JniHook::HookJniFun(env, className, "createFileExclusively0", "(Ljava/lang/String;)Z", (void *) new_createFileExclusively0, (void **) (&orig_createFileExclusively0), false);
    JniHook::HookJniFun(env, className, "delete0", "(Ljava/io/File;)Z", (void *) new_delete0, (void **) (&orig_delete0), false);
    JniHook::HookJniFun(env, className, "list0", "(Ljava/io/File;)[Ljava/lang/String;", (void *) new_list0, (void **) (&orig_list0), false);
    JniHook::HookJniFun(env, className, "createDirectory0", "(Ljava/io/File;)Z", (void *) new_createDirectory0, (void **) (&orig_createDirectory0), false);
    JniHook::HookJniFun(env, className, "rename0", "(Ljava/io/File;Ljava/io/File;)Z", (void *) new_rename0, (void **) (&orig_rename0), false);
    JniHook::HookJniFun(env, className, "setLastModifiedTime0", "(Ljava/io/File;J)Z", (void *) new_setLastModifiedTime0, (void **) (&orig_setLastModifiedTime0), false);
    JniHook::HookJniFun(env, className, "setReadOnly0", "(Ljava/io/File;)Z", (void *) new_setReadOnly0, (void **) (&orig_setReadOnly0), false);
    JniHook::HookJniFun(env, className, "getSpace0", "(Ljava/io/File;I)J", (void *) new_getSpace0, (void **) (&orig_getSpace0), false);
}
