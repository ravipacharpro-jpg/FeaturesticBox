#include "RuntimeHook.h"
#import "JniHook/JniHook.h"
#include "BoxCore.h"
#include <sys/stat.h>
#include <dlfcn.h>
#include <cstring>

typedef void (*FeaturesticBoxLibLoadedCallback_t)(const char *libPath);
#include <sys/mman.h>
#include <unistd.h>
#include <cstdio>

static void unlockLibraryXomSegments(const char *libPathOrName) {
    if (!libPathOrName || libPathOrName[0] == '\0') return;
    const char *baseName = strrchr(libPathOrName, '/');
    baseName = baseName ? (baseName + 1) : libPathOrName;
    if (strstr(baseName, "libanogs.so") == nullptr
            && strstr(baseName, "libUE4.so") == nullptr
            && strstr(baseName, "libtersafe.so") == nullptr
            && strstr(baseName, "libgcloud.so") == nullptr
            && strstr(baseName, "libil2cpp.so") == nullptr
            && strstr(baseName, "libunity.so") == nullptr
            && strstr(baseName, "libTDataMaster.so") == nullptr) {
        return;
    }
    FILE *fp = fopen("/proc/self/maps", "rt");
    if (!fp) return;
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, baseName) == nullptr) continue;
        unsigned long long startAddr = 0, endAddr = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &startAddr, &endAddr, perms) == 3) {
            if (strstr(perms, "---") != nullptr) continue;
            // Unlock Execute-Only Memory (--xp) to r-xp so .so offset reads/patches never trigger SEGV_ACCERR
            if (strchr(perms, 'x') != nullptr && strchr(perms, 'r') == nullptr) {
                uintptr_t alignedStart = static_cast<uintptr_t>(startAddr) & ~static_cast<uintptr_t>(pageSize - 1);
                uintptr_t alignedEnd = (static_cast<uintptr_t>(endAddr) + pageSize - 1) & ~static_cast<uintptr_t>(pageSize - 1);
                mprotect(reinterpret_cast<void *>(alignedStart), alignedEnd - alignedStart, PROT_READ | PROT_EXEC);
            }
        }
    }
    fclose(fp);
}


static void ensureReadOnlySoIfNeeded(JNIEnv *env, jstring name) {
    if (!env || !name) return;
    const char *nameC = env->GetStringUTFChars(name, nullptr);
    if (!nameC) return;
    if (nameC[0] == '/' && strstr(nameC, "/data/") != nullptr) {
        struct stat st;
        if (stat(nameC, &st) == 0 && S_ISREG(st.st_mode)) {
            chmod(nameC, S_IRUSR | S_IXUSR | S_IRGRP | S_IXGRP | S_IROTH | S_IXOTH);
        }
    }
    env->ReleaseStringUTFChars(name, nameC);
}

static void notifyLibraryLoadedIfNeeded(JNIEnv *env, jstring targetName) {
    if (!env || !targetName) return;
    const char *nameC = env->GetStringUTFChars(targetName, nullptr);
    if (!nameC) return;
    unlockLibraryXomSegments(nameC);
    void *sym = dlsym(RTLD_DEFAULT, "FeaturesticBox_OnLibraryLoaded");
    if (!sym) {
        sym = dlsym(RTLD_DEFAULT, "FeaturesticBox_OnLibraryLoaded");
    }
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
    return errorMsg;
}

void RuntimeHook::init(JNIEnv *env) {
    const char *className = "java/lang/Runtime";
    if (BoxCore::getApiLevel() >= __ANDROID_API_Q__) {
        JniHook::HookJniFun(env, className, "nativeLoad","(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",(void *) new_nativeLoad2,(void **) (&orig_nativeLoad2), true);
    } else {
        JniHook::HookJniFun(env, className, "nativeLoad","(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",(void *) new_nativeLoad,(void **) (&orig_nativeLoad), true);
    }
}
