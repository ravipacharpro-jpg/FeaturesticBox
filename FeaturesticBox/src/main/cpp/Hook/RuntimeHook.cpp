#include "RuntimeHook.h"
#import "JniHook/JniHook.h"
#include "BoxCore.h"
#include <sys/stat.h>
#include <dlfcn.h>
#include <cstring>
#include <unistd.h>
#include <cstdio>

typedef void (*FeaturesticBoxLibLoadedCallback_t)(const char *libPath);

static inline void releaseRedirectJString(JNIEnv *env, jstring orig, jstring redirected) {
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

static void ensureReadOnlySoIfNeeded(JNIEnv *env, jstring name) {
    if (!env || !name) return;
    const char *nameC = env->GetStringUTFChars(name, nullptr);
    if (!nameC) return;
    // Never chmod real host /data/app/ libraries (read-only system/installd owned);
    // only adjust virtual container extracted .so files if writable.
    if (nameC[0] == '/' && strstr(nameC, "/data/app/") == nullptr && strstr(nameC, "/data/") != nullptr) {
        struct stat st;
        if (stat(nameC, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == getuid()) {
            chmod(nameC, S_IRUSR | S_IXUSR | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
        }
    }
    env->ReleaseStringUTFChars(name, nameC);
}

static void notifyLibraryLoadedIfNeeded(JNIEnv *env, jstring targetName) {
    if (!env || !targetName) return;
    const char *nameC = env->GetStringUTFChars(targetName, nullptr);
    if (!nameC) return;
    // IMPORTANT: Never call mprotect() to strip Execute-Only Memory (--xp -> r-xp) on
    // libanogs.so, libUE4.so, libgcloud.so, or libTDataMaster.so!
    // BGMI v4.7.0 (libanogs.so) verifies its --xp segments in /proc/self/maps and performs
    // an XOM self-read check ~7-8s after noticesList: []. Stripping --xp triggers ACE's
    // delayed background thread crash.
    BoxCore::onNativeLibraryLoaded(nameC);
    void *sym = dlsym(RTLD_DEFAULT, "FeaturesticBox_OnLibraryLoaded");
    if (sym) {
        FeaturesticBoxLibLoadedCallback_t cb = reinterpret_cast<FeaturesticBoxLibLoadedCallback_t>(sym);
        cb(nameC);
    }
    env->ReleaseStringUTFChars(targetName, nameC);
    jclass rnativeCls = env->FindClass(VMCORE_CLASS);
    if (rnativeCls) {
        jmethodID mid = env->GetStaticMethodID(rnativeCls, "onGameLibraryLoaded", "(Ljava/lang/String;)V");
        if (mid) {
            env->CallStaticVoidMethod(rnativeCls, mid, targetName);
        }
        env->DeleteLocalRef(rnativeCls);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

HOOK_JNI(jstring, nativeLoad, JNIEnv *env, jobject obj, jstring name, jobject class_loader) {
    if (!env || !name) {
        return orig_nativeLoad(env, obj, name, class_loader);
    }
    jstring redirected = BoxCore::redirectPathString(env, name);
    jstring targetName = redirected ? redirected : name;
    ensureReadOnlySoIfNeeded(env, targetName);
    jstring errorMsg = orig_nativeLoad(env, obj, targetName, class_loader);
    if (errorMsg == nullptr) {
        notifyLibraryLoadedIfNeeded(env, targetName);
    }
    releaseRedirectJString(env, name, redirected);
    return errorMsg;
}

HOOK_JNI(jstring, nativeLoad2, JNIEnv *env, jobject obj, jstring name, jobject class_loader, jobject caller) {
    if (!env || !name) {
        return orig_nativeLoad2(env, obj, name, class_loader, caller);
    }
    jstring redirected = BoxCore::redirectPathString(env, name);
    jstring targetName = redirected ? redirected : name;
    ensureReadOnlySoIfNeeded(env, targetName);
    jstring errorMsg = orig_nativeLoad2(env, obj, targetName, class_loader, caller);
    if (errorMsg == nullptr) {
        notifyLibraryLoadedIfNeeded(env, targetName);
    }
    releaseRedirectJString(env, name, redirected);
    return errorMsg;
}

void RuntimeHook::init(JNIEnv *env) {
    const char *className = "java/lang/Runtime";
    // On Android 11-16 (API 30-36), Runtime.nativeLoad(String, ClassLoader) is the actual
    // native method while the 3-arg overload is a regular Java method.
    // On Android 10 (API 29), the 3-arg overload was native. Try 2-arg first, then fallback to 3-arg.
    bool hooked = JniHook::HookJniFun(
        env,
        className,
        "nativeLoad",
        "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
        (void *) new_nativeLoad,
        (void **) (&orig_nativeLoad),
        true);
    if (!hooked) {
        JniHook::HookJniFun(
            env,
            className,
            "nativeLoad",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
            (void *) new_nativeLoad2,
            (void **) (&orig_nativeLoad2),
            true);
    }
}
