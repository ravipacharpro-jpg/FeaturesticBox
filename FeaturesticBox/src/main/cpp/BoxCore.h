//
// Created by Milk on 4/9/21.
//

#ifndef VIRTUAL_APP_BOXCORE
#define VIRTUAL_APP_BOXCORE

#include <jni.h>
#include <unistd.h>

#define VMCORE_CLASS "com/featurestic/box/core/RNative"

class BoxCore {
public:
    static JavaVM *getJavaVM();
    static int getApiLevel();
    static int getCallingUid(JNIEnv *env, int orig);
    static int getCallingUidFast(int orig);
    static void updateUidConfig(int hostUid, int callingBUid, int appMode);
    static jstring redirectPathString(JNIEnv *env, jstring path);
    static jobject redirectPathFile(JNIEnv *env, jobject path);
    static jlongArray loadEmptyDex(JNIEnv *env);
};

#endif //VIRTUAL_APP_BOXCORE