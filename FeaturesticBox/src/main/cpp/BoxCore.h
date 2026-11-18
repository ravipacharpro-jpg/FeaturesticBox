#ifndef VIRTUALM_VMCORE_H
#define VIRTUALM_VMCORE_H

#include <jni.h>

#define VMCORE_CLASS "com/featurestic/box/core/RNative"

class BoxCore {
public:
    static JavaVM *getJavaVM();
    static int getApiLevel();
    static int getCallingUid(JNIEnv *env, int orig);
    static int getCallingUidFast(int orig);
    static void updateUidConfig(int hostUid, int callingBUid, int appMode);
    static void onNativeLibraryLoaded(const char *libPath);
    static jstring redirectPathString(JNIEnv *env, jstring path);
    static jobject redirectPathFile(JNIEnv *env, jobject path);
    static jlongArray loadEmptyDex(JNIEnv *env);
};


#endif //VIRTUALM_VMCORE_H