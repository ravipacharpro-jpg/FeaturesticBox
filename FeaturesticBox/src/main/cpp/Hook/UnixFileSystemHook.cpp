#include <IO.h>
#include "UnixFileSystemHook.h"
#include "JniHook/JniHook.h"
#include "BaseHook.h"

HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_canonicalize0(env, obj, redirect);
}

HOOK_JNI(jint, getBooleanAttributes0, JNIEnv *env, jobject obj, jstring abspath) {
    jstring redirect = IO::redirectPath(env, abspath);
    return orig_getBooleanAttributes0(env, obj, redirect);
}

HOOK_JNI(jint, getBooleanAttributes0File, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getBooleanAttributes0File(env, obj, redirect);
}

HOOK_JNI(jboolean, checkAccess0, JNIEnv *env, jobject obj, jobject file, jint access) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_checkAccess0(env, obj, redirect, access);
}

HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_getLastModifiedTime0(env, obj, redirect);
}

HOOK_JNI(jlong, getLength0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getLength0(env, obj, redirect);
}

HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setPermission0(env, obj, redirect, access, enable, owneronly);
}

HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = IO::redirectPath(env, path);
    return orig_createFileExclusively0(env, obj, redirect);
}

HOOK_JNI(jboolean, delete0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_delete0(env, obj, redirect);
}

HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_list0(env, obj, redirect);
}

HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    jobject redirect = IO::redirectPath(env, path);
    return orig_createDirectory0(env, obj, redirect);
}

HOOK_JNI(jboolean, rename0, JNIEnv *env, jobject obj, jobject f1, jobject f2) {
    jobject r1 = IO::redirectPath(env, f1);
    jobject r2 = IO::redirectPath(env, f2);
    return orig_rename0(env, obj, r1, r2);
}

HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jlong time) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setLastModifiedTime0(env, obj, redirect, time);
}

HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_setReadOnly0(env, obj, redirect);
}

HOOK_JNI(jlong, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    jobject redirect = IO::redirectPath(env, file);
    return orig_getSpace0(env, obj, redirect, t);
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
