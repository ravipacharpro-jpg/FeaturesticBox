#include "BinderHook.h"
#include <BoxCore.h>
#include "JniHook/JniHook.h"
#include <unistd.h>

// android.os.Binder.getCallingUid() is annotated @CriticalNative in Android 8.0-16 (API 26-36).
// AOT-compiled framework callers invoke it with 0 arguments (no JNIEnv*, no jclass) and without
// pushing a JNI HandleScope or leaving kRunnable state. Calling JNI/Java methods from inside a
// @CriticalNative hook corrupts ART thread state. Therefore we call the original 0-arg native
// function directly and resolve the virtual calling UID in 100% pure C++ via atomic state.
typedef jint (*orig_getCallingUid_critical_t)(void);
static orig_getCallingUid_critical_t orig_getCallingUid_fn = nullptr;

static jint new_getCallingUid_safe(void) {
    if (!orig_getCallingUid_fn) {
        return getuid();
    }
    jint orig = orig_getCallingUid_fn();
    return BoxCore::getCallingUidFast(orig);
}

void BinderHook::init(JNIEnv *env) {
    const char *clazz = "android/os/Binder";
    JniHook::HookJniFun(env, clazz, "getCallingUid", "()I",
                        (void *) new_getCallingUid_safe,
                        (void **) (&orig_getCallingUid_fn), true);
}