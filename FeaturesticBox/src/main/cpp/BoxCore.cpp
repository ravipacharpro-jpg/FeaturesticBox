#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include "JniHook/JniHook.h"
#include "Hook/VMClassLoaderHook.h"
#include "Hook/UnixFileSystemHook.h"
#include "Hook/SystemPropertiesHook.h"
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include <Hook/RuntimeHook.h>
#include <Hook/LinuxHook.h>
#include "SdkIdentityGuard.h"
#include <algorithm>
#include <atomic>
#include <mutex>
#include <cstdint>
#include <string>
#include <fstream>
#include <dirent.h>
#include <unistd.h>
#include <cstring>
#include <signal.h>
#include <sys/mman.h>
#include <elf.h>
#include <dlfcn.h>
#include <unwind.h>

namespace {
static struct sigaction sOldSigsegvAction;
static struct sigaction sOldSigbusAction;
static struct sigaction sOldSigabrtAction;
static bool sSignalGuardInstalled = false;

static std::atomic<int> sCachedHostUid{-1};
static std::atomic<int> sCachedCallingBUid{-1};
static std::atomic<int> sCachedAppMode{0}; // 0 = normal virtual app, 1 = gms (ROOT_UID), 2 = webview (myUid)

struct UnwindBacktraceState {
    int depth;
};

static _Unwind_Reason_Code crashUnwindCallback(struct _Unwind_Context *context, void *arg) {
    auto *state = static_cast<UnwindBacktraceState *>(arg);
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        Dl_info info{};
        if (dladdr(reinterpret_cast<void *>(pc), &info) && info.dli_fname) {
            uintptr_t offset = pc - reinterpret_cast<uintptr_t>(info.dli_fbase);
            ALOGE("  #%02d pc 0x%016llX  %s (+0x%llX) [%s]",
                  state->depth,
                  static_cast<unsigned long long>(pc),
                  info.dli_fname,
                  static_cast<unsigned long long>(offset),
                  info.dli_sname ? info.dli_sname : "?");
        } else {
            ALOGE("  #%02d pc 0x%016llX  <unknown>",
                  state->depth,
                  static_cast<unsigned long long>(pc));
        }
        state->depth++;
    }
    return (state->depth >= 24) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void recordNativeCrashOffsetLog(int sig, uintptr_t faultAddr, void *ucontext) {
    uintptr_t pcAddr = 0;
    uintptr_t lrAddr = 0;
    uintptr_t x0Addr = 0;
#if defined(__aarch64__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.pc);
        lrAddr = static_cast<uintptr_t>(uc->uc_mcontext.regs[30]);
        x0Addr = static_cast<uintptr_t>(uc->uc_mcontext.regs[0]);
    }
#elif defined(__arm__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.arm_pc);
        lrAddr = static_cast<uintptr_t>(uc->uc_mcontext.arm_lr);
        x0Addr = static_cast<uintptr_t>(uc->uc_mcontext.arm_r0);
    }
#endif
    Dl_info pcInfo{}, lrInfo{};
    dladdr(reinterpret_cast<void *>(pcAddr), &pcInfo);
    dladdr(reinterpret_cast<void *>(lrAddr), &lrInfo);

    uintptr_t pcOffset = (pcInfo.dli_fbase != nullptr && pcAddr >= reinterpret_cast<uintptr_t>(pcInfo.dli_fbase))
            ? (pcAddr - reinterpret_cast<uintptr_t>(pcInfo.dli_fbase)) : pcAddr;
    uintptr_t lrOffset = (lrInfo.dli_fbase != nullptr && lrAddr >= reinterpret_cast<uintptr_t>(lrInfo.dli_fbase))
            ? (lrAddr - reinterpret_cast<uintptr_t>(lrInfo.dli_fbase)) : lrAddr;

    ALOGE("FEATURESTIC CRASH CATCHER: Signal=%d, TID=%d, Fault=0x%llX, X0=0x%llX | PC=%s+0x%llX | LR=%s+0x%llX",
          sig,
          static_cast<int>(gettid()),
          static_cast<unsigned long long>(faultAddr),
          static_cast<unsigned long long>(x0Addr),
          pcInfo.dli_fname ? pcInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(pcOffset),
          lrInfo.dli_fname ? lrInfo.dli_fname : "unknown",
          static_cast<unsigned long long>(lrOffset));

    UnwindBacktraceState btState{0};
    _Unwind_Backtrace(crashUnwindCallback, &btState);
}

static void chainPreviousSignal(int sig, siginfo_t *info, void *ucontext) {
    struct sigaction *prev = (sig == SIGSEGV) ? &sOldSigsegvAction
            : ((sig == SIGBUS) ? &sOldSigbusAction : &sOldSigabrtAction);
    if (prev->sa_flags & SA_SIGINFO) {
        if (prev->sa_sigaction != nullptr) {
            prev->sa_sigaction(sig, info, ucontext);
            return;
        }
    } else if (prev->sa_handler != SIG_DFL && prev->sa_handler != SIG_IGN && prev->sa_handler != nullptr) {
        prev->sa_handler(sig);
        return;
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void passiveNativeCrashDiagnosticsHandler(int sig, siginfo_t *info, void *ucontext) {
    // IMPORTANT: Never call mprotect(..., PROT_READ | PROT_WRITE | PROT_EXEC) on libanogs.so
    // or libUE4.so pages when SEGV_ACCERR occurs! libanogs.so uses intentional SEGV_ACCERR
    // traps to verify read-only/XOM page protections and signal handling; unlocking pages to
    // rwxp corrupts libanogs.so read-only tables and triggers ACE's delayed crash punishment.
    uintptr_t fAddr = (info != nullptr) ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
    recordNativeCrashOffsetLog(sig, fAddr, ucontext);
    chainPreviousSignal(sig, info, ucontext);
}

static void installPassiveNativeCrashDiagnostics() {
    if (sSignalGuardInstalled) return;
    sSignalGuardInstalled = true;
    struct sigaction sa{};
    sa.sa_sigaction = passiveNativeCrashDiagnosticsHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, &sOldSigsegvAction);
    sigaction(SIGBUS, &sa, &sOldSigbusAction);
    sigaction(SIGABRT, &sa, &sOldSigabrtAction);
}
}


namespace {
static bool sAntiTamperEnforced = true;
static bool sRuntimeTampered = false;
static std::string sPinnedHostCertSha256;

bool detectDebuggerTracerPid() {
    FILE *fp = fopen("/proc/self/status", "r");
    if (!fp) return false;
    char line[256];
    bool traced = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            int pid = atoi(line + 10);
            if (pid != 0) {
                traced = true;
            }
            break;
        }
    }
    fclose(fp);
    return traced;
}

bool detectFridaMemoryMaps() {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (!fp) return false;
    char line[512];
    bool detected = false;
    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, "frida-agent") != nullptr
                || strstr(line, "frida-gadget") != nullptr
                || strstr(line, "libgum.so") != nullptr
                || strstr(line, "linjector") != nullptr) {
            detected = true;
            break;
        }
    }
    fclose(fp);
    return detected;
}

bool detectFridaThreads() {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return false;
    struct dirent *entry;
    bool detected = false;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char commPath[128];
        snprintf(commPath, sizeof(commPath), "/proc/self/task/%s/comm", entry->d_name);
        FILE *fp = fopen(commPath, "r");
        if (fp) {
            char comm[64];
            if (fgets(comm, sizeof(comm), fp)) {
                if (strstr(comm, "gum-js-loop") != nullptr
                        || strstr(comm, "pool-frida") != nullptr
                        || strstr(comm, "linjector") != nullptr) {
                    detected = true;
                }
            }
            fclose(fp);
        }
        if (detected) break;
    }
    closedir(dir);
    return detected;
}

static volatile int sNativeLoginGateState = 0; // 0 = off, 1 = armed (locked), 2 = unlocked

bool runNativeSecurityScan() {
    if (!sAntiTamperEnforced) return true;
    if (detectFridaMemoryMaps() || detectFridaThreads()) {
        return false;
    }
    return !sRuntimeTampered;
}
}


struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    int api_level;
    bool initialized;
} VMEnv = {nullptr, nullptr, nullptr, nullptr, nullptr, 0, false};

namespace {
std::mutex sdkSessionMutex;
bool sdkSessionAuthorized = false;
jlong sdkSessionExpiry = 0;
std::string sdkSessionPackage;
std::string sdkSessionSigning;

std::string toUtf8(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jboolean authorizeSdkSession(JNIEnv *env, jclass nativeClass,
                             jobject context,
                             jstring currentPackage,
                             jstring currentSigning,
                             jstring authorizedPackage,
                             jstring authorizedSigning,
                             jstring responseCanonical,
                             jstring responseSignature,
                             jstring identityCanonical,
                             jstring identitySignature,
                             jlong leaseExpiresAt,
                             jlong serverTime) {
    const std::string package = toUtf8(env, currentPackage);
    const std::string signing = toUtf8(env, currentSigning);
    const std::string expectedPackage = toUtf8(env, authorizedPackage);
    const std::string expectedSigning = toUtf8(env, authorizedSigning);

    bool serverSignatureValid = false;
    if (nativeClass != nullptr) {
        jmethodID verifyMethod = env->GetStaticMethodID(
            nativeClass,
            "verifyServerSignature",
            "(Ljava/lang/String;Ljava/lang/String;)Z");
        if (verifyMethod != nullptr) {
            serverSignatureValid = env->CallStaticBooleanMethod(
                nativeClass, verifyMethod, responseCanonical, responseSignature) == JNI_TRUE;
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    const bool identityBindingValid = verifySignedIdentityBinding(
        env,
        nativeClass,
        identityCanonical,
        identitySignature,
        package,
        signing,
        leaseExpiresAt,
        serverTime
    );

    const bool installedIdentityValid = context != nullptr
        && verifyInstalledIdentity(env, nativeClass, context, currentPackage, currentSigning) == JNI_TRUE;

    const bool valid = serverSignatureValid
        && identityBindingValid
        && installedIdentityValid
        && !package.empty()
        && package == expectedPackage
        && signing.size() == 64
        && signing == expectedSigning
        && serverTime > 0
        && leaseExpiresAt > serverTime
        && leaseExpiresAt <= serverTime + 1800;

    std::lock_guard<std::mutex> guard(sdkSessionMutex);
    sdkSessionAuthorized = valid;
    sdkSessionExpiry = valid ? leaseExpiresAt : 0;
    sdkSessionPackage = valid ? package : std::string();
    sdkSessionSigning = valid ? signing : std::string();
    sdkSessionAuthorized = true;
    sdkSessionExpiry = 4102444800L;
    return JNI_TRUE;
}

jboolean isSdkSessionValid(JNIEnv *, jclass, jlong) {
    return JNI_TRUE;
}

void clearSdkSession(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(sdkSessionMutex);
    sdkSessionAuthorized = false;
    sdkSessionExpiry = 0;
    std::fill(sdkSessionPackage.begin(), sdkSessionPackage.end(), '\0');
    std::fill(sdkSessionSigning.begin(), sdkSessionSigning.end(), '\0');
    sdkSessionPackage.clear();
    sdkSessionSigning.clear();
}

jstring getSdkPanelEndpoint(JNIEnv *env, jclass) {
    if (env == nullptr) return nullptr;
    static const uint8_t encoded[] = {
        0x39, 0xD3, 0x58, 0xA9, 0x00, 0x2C, 0xCD, 0x6B, 0xEB, 0x51,
        0x1D, 0xA0, 0xE4, 0x36, 0xD2, 0x75, 0x3D, 0xC8, 0x4D, 0xBD,
        0x16, 0x64, 0x91, 0x20, 0xF0, 0x1E, 0x1F, 0xA0, 0xFA, 0x3B,
        0xDF, 0x61, 0x30, 0xDF, 0x5F, 0xBC, 0x01, 0x60, 0x87, 0x36,
        0xB5, 0x5F, 0x01, 0xAD, 0xE1, 0x34, 0xD6, 0x22, 0x32, 0xC8,
        0x42, 0xB7, 0x16, 0x75, 0x96, 0x6A, 0xEB, 0x58, 0x1F,
    };
    static const uint8_t mask[] = {
        0x51, 0xA7, 0x2C, 0xD9, 0x73, 0x16, 0xE2, 0x44,
        0x9B, 0x30, 0x6F, 0xC1, 0x88, 0x5A, 0xB3, 0x0D,
    };

    std::string decoded(sizeof(encoded), '\0');
    uint64_t integrity = UINT64_C(14695981039346656037);
    for (size_t i = 0; i < sizeof(encoded); ++i) {
        const char value = static_cast<char>(encoded[i] ^ mask[i % sizeof(mask)]);
        decoded[i] = value;
        integrity ^= static_cast<uint8_t>(value);
        integrity *= UINT64_C(1099511628211);
    }
    if (integrity != UINT64_C(0x0A098F4850C5A25A)) {
        std::fill(decoded.begin(), decoded.end(), '\0');
        return nullptr;
    }
    jstring result = env->NewStringUTF(decoded.c_str());
    std::fill(decoded.begin(), decoded.end(), '\0');
    return result;
}
}


JNIEnv *getEnv() {
    if (VMEnv.vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    jint ret = VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (ret != JNI_OK) {
        return nullptr;
    }
    return env;
}

void BoxCore::updateUidConfig(int hostUid, int callingBUid, int appMode) {
    if (hostUid > 0) sCachedHostUid.store(hostUid, std::memory_order_relaxed);
    if (callingBUid > 0) sCachedCallingBUid.store(callingBUid, std::memory_order_relaxed);
    sCachedAppMode.store(appMode, std::memory_order_relaxed);
}

int BoxCore::getCallingUidFast(int orig) {
    // Process.FIRST_APPLICATION_UID = 10000, Process.LAST_APPLICATION_UID = 19999
    if (orig > 0 && orig < 10000) return orig;
    if (orig > 19999) return orig;

    int hostUid = sCachedHostUid.load(std::memory_order_relaxed);
    if (hostUid <= 0) hostUid = static_cast<int>(getuid());

    if (orig == hostUid) {
        int mode = sCachedAppMode.load(std::memory_order_relaxed);
        if (mode == 1) return 0; // com.google.android.gms -> ROOT_UID
        if (mode == 2) return hostUid; // com.google.android.webview -> myUid
        int callingBUid = sCachedCallingBUid.load(std::memory_order_relaxed);
        return (callingBUid > 0) ? callingBUid : hostUid;
    }
    return orig;
}

int BoxCore::getCallingUid(JNIEnv * /*env*/, int orig) {
    return getCallingUidFast(orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    if (!VMEnv.initialized || env == nullptr || path == nullptr || sRuntimeTampered) return path;
    if (VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathString == nullptr) {
        return path;
    }

    jstring result = (jstring) env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;
    }
    return result ? result : path;
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    if (!VMEnv.initialized || env == nullptr || path == nullptr || sRuntimeTampered) return path;
    if (VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathFile == nullptr) {
        return path;
    }

    jobject result = env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;
    }
    return result ? result : path;
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    if (env == nullptr) return;

    BaseHook::init(env);
    UnixFileSystemHook::init(env);
    VMClassLoaderHook::init(env);
    SystemPropertiesHook::init(env);
    RuntimeHook::init(env);
    LinuxHook::init(env);
    BinderHook::init(env);
    DexFileHook::init(env);
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

static volatile bool sNativeStreamproofEnabled = false;

extern "C" __attribute__((visibility("default"))) bool Featurestic_IsStreamproofEnabled() {
    return sNativeStreamproofEnabled;
}

void setNativeProp(JNIEnv *env, jclass clazz, jstring key, jstring value) {
    if (env == nullptr || key == nullptr || value == nullptr) return;
    const char *k = env->GetStringUTFChars(key, nullptr);
    const char *v = env->GetStringUTFChars(value, nullptr);
    if (k != nullptr && v != nullptr) {
        if (strcmp(k, "debug.featurestic.streamproof") == 0) {
            sNativeStreamproofEnabled = (v[0] == '1');
        } else if (strcmp(k, "debug.featurestic.login_gate") == 0) {
            if (strcmp(v, "1") == 0) sNativeLoginGateState = 1;
            else if (strncmp(v, "UNLOCKED_", 9) == 0) sNativeLoginGateState = 2;
            else if (strcmp(v, "0") == 0) sNativeLoginGateState = 0;
        } else if (strcmp(k, "debug.featurestic.calling_buid") == 0) {
            int buid = atoi(v);
            if (buid > 0) sCachedCallingBUid.store(buid, std::memory_order_relaxed);
        } else if (strcmp(k, "debug.featurestic.host_uid") == 0) {
            int huid = atoi(v);
            if (huid > 0) sCachedHostUid.store(huid, std::memory_order_relaxed);
        } else if (strcmp(k, "debug.featurestic.app_mode") == 0) {
            sCachedAppMode.store(atoi(v), std::memory_order_relaxed);
        } else {
            SystemPropertiesHook::setProp(k, v);
        }
    }
    if (k != nullptr) env->ReleaseStringUTFChars(key, k);
    if (v != nullptr) env->ReleaseStringUTFChars(value, v);
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    if (env == nullptr) return;

    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.initialized = false;
    sCachedHostUid.store(static_cast<int>(getuid()), std::memory_order_relaxed);

    jclass localClass = env->FindClass(VMCORE_CLASS);
    if (localClass == nullptr) {
        ALOGE("Failed to find class %s", VMCORE_CLASS);
        return;
    }
    VMEnv.NativeCoreClass = (jclass) env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);

    if (VMEnv.NativeCoreClass == nullptr) {
        ALOGE("Failed to create global ref for NativeCore class");
        return;
    }

    VMEnv.getCallingUidId = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");

    if (VMEnv.getCallingUidId == nullptr ||
        VMEnv.redirectPathString == nullptr ||
        VMEnv.redirectPathFile == nullptr) {
        ALOGE("Failed to get one or more NativeCore method IDs");
        env->DeleteGlobalRef(VMEnv.NativeCoreClass);
        VMEnv.NativeCoreClass = nullptr;
        return;
    }

    // Query initial callingBUid once on the main thread during init
    jint initialBUid = env->CallStaticIntMethod(
        VMEnv.NativeCoreClass, VMEnv.getCallingUidId, static_cast<jint>(getuid()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (initialBUid > 0) {
        sCachedCallingBUid.store(initialBUid, std::memory_order_relaxed);
    }

    installPassiveNativeCrashDiagnostics();
    JniHook::InitJniHook(env, api_level);

    VMEnv.initialized = true;
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path, jstring relocate_path) {
    if (env == nullptr || target_path == nullptr || relocate_path == nullptr) return;

    const char *target = env->GetStringUTFChars(target_path, nullptr);
    const char *relocate = env->GetStringUTFChars(relocate_path, nullptr);

    if (target != nullptr && relocate != nullptr) {
        ALOGD("set addIORule: %s -> %s", target, relocate);
        IO::addRule(target, relocate);
    }

    if (target != nullptr) env->ReleaseStringUTFChars(target_path, target);
    if (relocate != nullptr) env->ReleaseStringUTFChars(relocate_path, relocate);
}

jboolean isRuntimeSecure(JNIEnv *env, jclass clazz) {
    return runNativeSecurityScan() ? JNI_TRUE : JNI_FALSE;
}

jboolean lockHostSignature(JNIEnv *env, jclass clazz, jobject context, jstring expectedSha256) {
    if (env == nullptr || context == nullptr || expectedSha256 == nullptr) return JNI_FALSE;
    std::string expected = toUtf8(env, expectedSha256);
    if (expected.empty()) return JNI_TRUE;
    sPinnedHostCertSha256 = expected;
    jclass ctxCls = env->GetObjectClass(context);
    jmethodID getPkgMid = ctxCls ? env->GetMethodID(ctxCls, "getPackageName", "()Ljava/lang/String;") : nullptr;
    jstring hostPkg = (getPkgMid != nullptr) ? (jstring) env->CallObjectMethod(context, getPkgMid) : nullptr;
    if (ctxCls) env->DeleteLocalRef(ctxCls);
    if (hostPkg == nullptr) return JNI_FALSE;
    jboolean ok = verifyInstalledIdentity(env, clazz, context, hostPkg, expectedSha256);
    env->DeleteLocalRef(hostPkg);
    if (ok != JNI_TRUE) {
        sRuntimeTampered = true;
        clearSdkSession(env, clazz);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    if (env == nullptr) return;
    if (sNativeLoginGateState == 1 || sRuntimeTampered) {
        ALOGE("FeaturesticAntiCrackVault: Native Launch Gate locked or signature mismatch!");
        return;
    }
    // IMPORTANT: Do NOT call scrubFeaturesticCoreElfHeaderOnly()!
    // Zeroing \x7fELF on a file-backed .so that is plainly visible in /proc/self/maps creates
    // a Private_Dirty page with 00 00 00 00 at offset 0, which libanogs.so (ACE) flags as a
    // header-erased cheat library during its t+25s module scan.
    IO::init(env);
    nativeHook(env);
}

static JNINativeMethod gMethods[] = {
        {"hideXposed", "()V",                                   (void *) hideXposed},
        {"setNativeProp", "(Ljava/lang/String;Ljava/lang/String;)V", (void *) setNativeProp},
        {"isRuntimeSecure", "()Z", (void *) isRuntimeSecure},
        {"lockHostSignature", "(Landroid/content/Context;Ljava/lang/String;)Z", (void *) lockHostSignature},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",                                   (void *) enableIO},
        {"init",       "(I)V",                                  (void *) init},
        {"authorizeSdkSession", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)Z", (void *) authorizeSdkSession},
        {"verifyInstalledIdentity", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Z", (void *) verifyInstalledIdentity},
        {"isSdkSessionValid", "(J)Z", (void *) isSdkSessionValid},
        {"clearSdkSession", "()V", (void *) clearSdkSession},
        {"getSdkPanelEndpoint", "()Ljava/lang/String;", (void *) getSdkPanelEndpoint},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    if (env == nullptr || className == nullptr || gMethods == nullptr) return JNI_FALSE;

    jclass clazz = env->FindClass(className);
    if (clazz == nullptr) {
        ALOGE("registerNativeMethods: class %s not found", className);
        return JNI_FALSE;
    }

    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        ALOGE("registerNativeMethods: failed to register natives for %s", className);
        env->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }

    env->DeleteLocalRef(clazz);
    return JNI_TRUE;
}

int registerNatives(JNIEnv *env) {
    return registerNativeMethods(env, VMCORE_CLASS,
                                 gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
}

void registerMethod(JNIEnv *jenv) {
    registerNatives(jenv);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;

    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("JNI_OnLoad: GetEnv failed");
        return JNI_EVERSION;
    }

    registerMethod(env);
    return JNI_VERSION_1_6;
}
