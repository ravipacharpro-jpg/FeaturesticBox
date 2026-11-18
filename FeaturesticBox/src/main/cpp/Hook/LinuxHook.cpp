#include "LinuxHook.h"
#import "JniHook/JniHook.h"
#include "IO.h"
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LinuxHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static inline void releaseRedirectRef(JNIEnv *env, jstring orig, jstring redirected) {
    if (env && redirected && redirected != orig && !env->IsSameObject(orig, redirected)) {
        env->DeleteLocalRef(redirected);
    }
}

HOOK_JNI(jboolean, access, JNIEnv *env, jobject obj, jstring path, jint mode) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jboolean res = orig_access(env, obj, redirect, mode);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(void, chmod, JNIEnv *env, jobject obj, jstring path, jint mode) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_chmod(env, obj, redirect, mode);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, chown, JNIEnv *env, jobject obj, jstring path, jint mode, jint gid) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_chown(env, obj, redirect, mode, gid);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, execv, JNIEnv *env, jobject obj, jstring filename, jobjectArray argv) {
    jstring redirect = filename ? IO::redirectPath(env, filename) : nullptr;
    orig_execv(env, obj, redirect, argv);
    releaseRedirectRef(env, filename, redirect);
}

HOOK_JNI(void, execve, JNIEnv *env, jobject obj, jstring filename, jobjectArray argv, jobjectArray envp) {
    jstring redirect = filename ? IO::redirectPath(env, filename) : nullptr;
    orig_execve(env, obj, redirect, argv, envp);
    releaseRedirectRef(env, filename, redirect);
}

HOOK_JNI(jbyteArray, getxattr, JNIEnv *env, jobject obj, jstring path, jstring name) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jbyteArray res = orig_getxattr(env, obj, redirect, name);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(void, lchown, JNIEnv *env, jobject obj, jstring path, jint uid, jint gid) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_lchown(env, obj, redirect, uid, gid);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, link, JNIEnv *env, jobject obj, jstring oldPath, jstring newPath) {
    jstring redirectOldPath = oldPath ? IO::redirectPath(env, oldPath) : nullptr;
    jstring redirectNewPath = newPath ? IO::redirectPath(env, newPath) : nullptr;
    orig_link(env, obj, redirectOldPath, redirectNewPath);
    releaseRedirectRef(env, oldPath, redirectOldPath);
    releaseRedirectRef(env, newPath, redirectNewPath);
}

HOOK_JNI(jobjectArray, listxattr, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobjectArray res = orig_listxattr(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jobject, lstat, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_lstat(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(void, mkdir, JNIEnv *env, jobject obj, jstring path, jint mode) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_mkdir(env, obj, redirect, mode);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, mkfifo, JNIEnv *env, jobject obj, jstring path, jint mode) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_mkfifo(env, obj, redirect, mode);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(jobject, open, JNIEnv *env, jobject obj, jstring path, jint flags, jint mode) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_open(env, obj, redirect, flags, mode);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jstring, readlink, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jstring res = orig_readlink(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jstring, realpath, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jstring res = orig_realpath(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(void, remove, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_remove(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, removexattr, JNIEnv *env, jobject obj, jstring path, jstring name) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_removexattr(env, obj, redirect, name);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(void, rename, JNIEnv *env, jobject obj, jstring oldPath, jstring newPath) {
    jstring redirectOldPath = oldPath ? IO::redirectPath(env, oldPath) : nullptr;
    jstring redirectNewPath = newPath ? IO::redirectPath(env, newPath) : nullptr;
    orig_rename(env, obj, redirectOldPath, redirectNewPath);
    releaseRedirectRef(env, oldPath, redirectOldPath);
    releaseRedirectRef(env, newPath, redirectNewPath);
}

HOOK_JNI(void, setxattr, JNIEnv *env, jobject obj, jstring path, jstring name, jbyteArray value, jint flags) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    orig_setxattr(env, obj, redirect, name, value, flags);
    releaseRedirectRef(env, path, redirect);
}

HOOK_JNI(jobject, stat, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_stat(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jobject, statvfs, JNIEnv *env, jobject obj, jstring path) {
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_statvfs(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(void, symlink, JNIEnv *env, jobject obj, jstring oldPath, jstring newPath) {
    jstring redirectOldPath = oldPath ? IO::redirectPath(env, oldPath) : nullptr;
    jstring redirectNewPath = newPath ? IO::redirectPath(env, newPath) : nullptr;
    orig_symlink(env, obj, redirectOldPath, redirectNewPath);
    releaseRedirectRef(env, oldPath, redirectOldPath);
    releaseRedirectRef(env, newPath, redirectNewPath);
}

HOOK_JNI(void, unlink, JNIEnv *env, jobject obj, jstring pathname) {
    jstring redirect = pathname ? IO::redirectPath(env, pathname) : nullptr;
    orig_unlink(env, obj, redirect);
    releaseRedirectRef(env, pathname, redirect);
}

void LinuxHook::init(JNIEnv *env) {
    JniHook::HookJniFun(env, "libcore/io/Linux", "access", "(Ljava/lang/String;I)Z",(void *) new_access, (void **) (&orig_access), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "chmod", "(Ljava/lang/String;I)V",(void *) new_chmod, (void **) (&orig_chmod), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "chown", "(Ljava/lang/String;II)V",(void *) new_chown, (void **) (&orig_chown), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "execv", "(Ljava/lang/String;[Ljava/lang/String;)V",(void *) new_execv, (void **) (&orig_execv), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "execve", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",(void *) new_execve, (void **) (&orig_execve), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "getxattr", "(Ljava/lang/String;Ljava/lang/String;)[B",(void *) new_getxattr, (void **) (&orig_getxattr), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "lchown", "(Ljava/lang/String;II)V",(void *) new_lchown, (void **) (&orig_lchown), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "link", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_link, (void **) (&orig_link), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "listxattr", "(Ljava/lang/String;)[Ljava/lang/String;",(void *) new_listxattr, (void **) (&orig_listxattr), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "lstat", "(Ljava/lang/String;)Landroid/system/StructStat;",(void *) new_lstat, (void **) (&orig_lstat), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "mkdir", "(Ljava/lang/String;I)V",(void *) new_mkdir, (void **) (&orig_mkdir), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "mkfifo", "(Ljava/lang/String;I)V",(void *) new_mkfifo, (void **) (&orig_mkfifo), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "open", "(Ljava/lang/String;II)Ljava/io/FileDescriptor;",(void *) new_open, (void **) (&orig_open), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "readlink", "(Ljava/lang/String;)Ljava/lang/String;",(void *) new_readlink, (void **) (&orig_readlink), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "realpath", "(Ljava/lang/String;)Ljava/lang/String;",(void *) new_realpath, (void **) (&orig_realpath), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "remove", "(Ljava/lang/String;)V",(void *) new_remove, (void **) (&orig_remove), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "removexattr", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_removexattr, (void **) (&orig_removexattr), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "rename", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_rename, (void **) (&orig_rename), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "stat", "(Ljava/lang/String;)Landroid/system/StructStat;",(void *) new_stat, (void **) (&orig_stat), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "statvfs", "(Ljava/lang/String;)Landroid/system/StructStatVfs;",(void *) new_statvfs, (void **) (&orig_statvfs), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "symlink", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_symlink, (void **) (&orig_symlink), false);
    JniHook::HookJniFun(env, "libcore/io/Linux", "unlink", "(Ljava/lang/String;)V",(void *) new_unlink, (void **) (&orig_unlink), false);
}