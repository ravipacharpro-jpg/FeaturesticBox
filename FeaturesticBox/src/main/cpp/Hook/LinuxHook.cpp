#include "LinuxHook.h"
#import "JniHook/JniHook.h"
#include "IO.h"
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "LinuxHook"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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

static bool isDevPtsFd(JNIEnv *env, jobject javaFd) {
    if (!env || !javaFd) return false;
    jclass fdClass = env->GetObjectClass(javaFd);
    if (!fdClass) return false;
    jfieldID descField = env->GetFieldID(fdClass, "descriptor", "I");
    if (!descField) {
        env->ExceptionClear();
        env->DeleteLocalRef(fdClass);
        return false;
    }
    int fd = env->GetIntField(javaFd, descField);
    env->DeleteLocalRef(fdClass);
    if (fd < 0) return false;
    char linkPath[64];
    char target[128];
    snprintf(linkPath, sizeof(linkPath), "/proc/self/fd/%d", fd);
    ssize_t len = readlink(linkPath, target, sizeof(target) - 1);
    if (len > 0) {
        target[len] = '\0';
        if (strstr(target, "/dev/pts") != nullptr || strstr(target, "/dev/ptmx") != nullptr || strstr(target, "devpts") != nullptr || strstr(target, "/pts/") != nullptr) {
            return true;
        }
    }
    return false;
}

static void throwErrnoException(JNIEnv *env, const char *fnName, int err) {
    if (!env) return;
    jclass clazz = env->FindClass("android/system/ErrnoException");
    if (clazz != nullptr) {
        jmethodID init = env->GetMethodID(clazz, "<init>", "(Ljava/lang/String;I)V");
        if (init != nullptr) {
            jstring name = env->NewStringUTF(fnName);
            jthrowable ex = (jthrowable) env->NewObject(clazz, init, name, err);
            if (ex != nullptr) {
                env->Throw(ex);
                env->DeleteLocalRef(ex);
            }
            if (name != nullptr) {
                env->DeleteLocalRef(name);
            }
        }
        env->DeleteLocalRef(clazz);
    }
}

static inline void releaseRedirectRef(JNIEnv *env, jstring orig, jstring redirected) {
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

HOOK_JNI(jboolean, access, JNIEnv *env, jobject obj, jstring path, jint mode) {
    if (path && isDevPtsPath(env, path)) {
        return JNI_FALSE;
    }
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
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "lstat", 2 /* ENOENT */);
        return nullptr;
    }
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
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "open", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_open(env, obj, redirect, flags, mode);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jstring, readlink, JNIEnv *env, jobject obj, jstring path) {
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "readlink", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jstring res = orig_readlink(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jstring, realpath, JNIEnv *env, jobject obj, jstring path) {
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "realpath", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jstring res = orig_realpath(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jobject, fstat, JNIEnv *env, jobject obj, jobject javaFd) {
    if (javaFd && isDevPtsFd(env, javaFd)) {
        throwErrnoException(env, "fstat", 2 /* ENOENT */);
        return nullptr;
    }
    return orig_fstat(env, obj, javaFd);
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
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "stat", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_stat(env, obj, redirect);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jobject, fstatat, JNIEnv *env, jobject obj, jobject fd, jstring path, jint flags) {
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "fstatat", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_fstatat(env, obj, fd, redirect, flags);
    releaseRedirectRef(env, path, redirect);
    return res;
}

HOOK_JNI(jobject, openat, JNIEnv *env, jobject obj, jobject fd, jstring path, jint flags, jint mode) {
    if (path && isDevPtsPath(env, path)) {
        throwErrnoException(env, "openat", 2 /* ENOENT */);
        return nullptr;
    }
    jstring redirect = path ? IO::redirectPath(env, path) : nullptr;
    jobject res = orig_openat(env, obj, fd, redirect, flags, mode);
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
    const char *classes[] = {"libcore/io/Linux", "libcore/io/Posix"};
    for (const char *className : classes) {
        jclass clazz = env->FindClass(className);
        if (!clazz) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            continue;
        }
        env->DeleteLocalRef(clazz);

        void *dummy = nullptr;
        JniHook::HookJniFun(env, className, "access", "(Ljava/lang/String;I)Z",(void *) new_access, orig_access ? &dummy : (void **) (&orig_access), false);
        JniHook::HookJniFun(env, className, "chmod", "(Ljava/lang/String;I)V",(void *) new_chmod, orig_chmod ? &dummy : (void **) (&orig_chmod), false);
        JniHook::HookJniFun(env, className, "chown", "(Ljava/lang/String;II)V",(void *) new_chown, orig_chown ? &dummy : (void **) (&orig_chown), false);
        JniHook::HookJniFun(env, className, "execv", "(Ljava/lang/String;[Ljava/lang/String;)V",(void *) new_execv, orig_execv ? &dummy : (void **) (&orig_execv), false);
        JniHook::HookJniFun(env, className, "execve", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",(void *) new_execve, orig_execve ? &dummy : (void **) (&orig_execve), false);
        JniHook::HookJniFun(env, className, "getxattr", "(Ljava/lang/String;Ljava/lang/String;)[B",(void *) new_getxattr, orig_getxattr ? &dummy : (void **) (&orig_getxattr), false);
        JniHook::HookJniFun(env, className, "lchown", "(Ljava/lang/String;II)V",(void *) new_lchown, orig_lchown ? &dummy : (void **) (&orig_lchown), false);
        JniHook::HookJniFun(env, className, "link", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_link, orig_link ? &dummy : (void **) (&orig_link), false);
        JniHook::HookJniFun(env, className, "listxattr", "(Ljava/lang/String;)[Ljava/lang/String;",(void *) new_listxattr, orig_listxattr ? &dummy : (void **) (&orig_listxattr), false);
        JniHook::HookJniFun(env, className, "lstat", "(Ljava/lang/String;)Landroid/system/StructStat;",(void *) new_lstat, orig_lstat ? &dummy : (void **) (&orig_lstat), false);
        JniHook::HookJniFun(env, className, "mkdir", "(Ljava/lang/String;I)V",(void *) new_mkdir, orig_mkdir ? &dummy : (void **) (&orig_mkdir), false);
        JniHook::HookJniFun(env, className, "mkfifo", "(Ljava/lang/String;I)V",(void *) new_mkfifo, orig_mkfifo ? &dummy : (void **) (&orig_mkfifo), false);
        JniHook::HookJniFun(env, className, "open", "(Ljava/lang/String;II)Ljava/io/FileDescriptor;",(void *) new_open, orig_open ? &dummy : (void **) (&orig_open), false);
        JniHook::HookJniFun(env, className, "readlink", "(Ljava/lang/String;)Ljava/lang/String;",(void *) new_readlink, orig_readlink ? &dummy : (void **) (&orig_readlink), false);
        JniHook::HookJniFun(env, className, "realpath", "(Ljava/lang/String;)Ljava/lang/String;",(void *) new_realpath, orig_realpath ? &dummy : (void **) (&orig_realpath), false);
        JniHook::HookJniFun(env, className, "remove", "(Ljava/lang/String;)V",(void *) new_remove, orig_remove ? &dummy : (void **) (&orig_remove), false);
        JniHook::HookJniFun(env, className, "removexattr", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_removexattr, orig_removexattr ? &dummy : (void **) (&orig_removexattr), false);
        JniHook::HookJniFun(env, className, "rename", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_rename, orig_rename ? &dummy : (void **) (&orig_rename), false);
        JniHook::HookJniFun(env, className, "stat", "(Ljava/lang/String;)Landroid/system/StructStat;",(void *) new_stat, orig_stat ? &dummy : (void **) (&orig_stat), false);
        JniHook::HookJniFun(env, className, "fstat", "(Ljava/io/FileDescriptor;)Landroid/system/StructStat;",(void *) new_fstat, orig_fstat ? &dummy : (void **) (&orig_fstat), false);
        JniHook::HookJniFun(env, className, "fstatat", "(Ljava/io/FileDescriptor;Ljava/lang/String;I)Landroid/system/StructStat;",(void *) new_fstatat, orig_fstatat ? &dummy : (void **) (&orig_fstatat), false);
        JniHook::HookJniFun(env, className, "openat", "(Ljava/io/FileDescriptor;Ljava/lang/String;II)Ljava/io/FileDescriptor;",(void *) new_openat, orig_openat ? &dummy : (void **) (&orig_openat), false);
        JniHook::HookJniFun(env, className, "statvfs", "(Ljava/lang/String;)Landroid/system/StructStatVfs;",(void *) new_statvfs, orig_statvfs ? &dummy : (void **) (&orig_statvfs), false);
        JniHook::HookJniFun(env, className, "symlink", "(Ljava/lang/String;Ljava/lang/String;)V",(void *) new_symlink, orig_symlink ? &dummy : (void **) (&orig_symlink), false);
        JniHook::HookJniFun(env, className, "unlink", "(Ljava/lang/String;)V",(void *) new_unlink, orig_unlink ? &dummy : (void **) (&orig_unlink), false);
    }
}