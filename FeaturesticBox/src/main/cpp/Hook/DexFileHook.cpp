#include "DexFileHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"
#include <sys/stat.h>
#include <cstring>

HOOK_JNI(jobject, openDexFileNative, JNIEnv *env, jobject obj, jstring sourceName, jstring outputName, jint flags, jobject loader, jobject elements) {
    if (env && sourceName) {
        const char *sourceNameC = env->GetStringUTFChars(sourceName, JNI_FALSE);
        if (sourceNameC) {
            ALOGD("openDexFileNative: %s", sourceNameC);
            if (strstr(sourceNameC, "/FEATURESTIC_CORE/") != nullptr
                    || strstr(sourceNameC, "/FeaturesticBox/") != nullptr
                    || strstr(sourceNameC, "/FEATURESTICBOX_CORE/") != nullptr
                    || strstr(sourceNameC, "/files/data/") != nullptr
                    || strstr(sourceNameC, "/files/cache/") != nullptr
                    || strstr(sourceNameC, "/no_backup/") != nullptr) {
                DexFileHook::setFileReadonly(sourceNameC);
            }
            env->ReleaseStringUTFChars(sourceName, sourceNameC);
        }
    }
    return orig_openDexFileNative(env, obj, sourceName, outputName, flags, loader, elements);
}

void DexFileHook::init(JNIEnv *env) {
    if (BoxCore::getApiLevel() >= __ANDROID_API_U__) {
        const char *clazz = "dalvik/system/DexFile";
        JniHook::HookJniFun(env, clazz, "openDexFileNative", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;", (void *) new_openDexFileNative, (void **) (&orig_openDexFileNative), true);
    }
}

void DexFileHook::setFileReadonly(const char* filePath) {
    struct stat fileStat;
    if (stat(filePath, &fileStat) != 0) {
        return;
    }
    chmod(filePath, S_IRUSR | S_IRGRP | S_IROTH);
}
