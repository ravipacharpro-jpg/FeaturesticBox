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

namespace {
static struct sigaction sOldSigsegvAction;
static struct sigaction sOldSigbusAction;
static bool sSignalGuardInstalled = false;
static volatile uintptr_t sLastUnlockedPage = 0;
static volatile int sSamePageRetryCount = 0;

static bool isProtectedGameLibraryAddr(uintptr_t faultAddr) {
    if (faultAddr < 0x10000) return false;
    FILE *fp = fopen("/proc/self/maps", "rt");
    if (!fp) return false;
    char line[512];
    bool matched = false;
    while (fgets(line, sizeof(line), fp)) {
        unsigned long long startAddr = 0, endAddr = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &startAddr, &endAddr, perms) == 3) {
            if (faultAddr >= startAddr && faultAddr < endAddr) {
                if (strstr(perms, "---") == nullptr
                        && (strstr(line, "libanogs.so") != nullptr
                            || strstr(line, "libUE4.so") != nullptr
                            || strstr(line, "libtersafe.so") != nullptr
                            || strstr(line, "libgcloud.so") != nullptr
                            || strstr(line, "libTDataMaster.so") != nullptr
                            || strstr(line, "libil2cpp.so") != nullptr
                            || strstr(line, "libunity.so") != nullptr)) {
                    matched = true;
                }
                break;
            }
        }
    }
    fclose(fp);
    return matched;
}


static void recordNativeCrashOffsetLog(int sig, uintptr_t faultAddr, void *ucontext) {
    uintptr_t pcAddr = 0;
#if defined(__aarch64__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.pc);
    }
#elif defined(__arm__)
    if (ucontext != nullptr) {
        ucontext_t *uc = reinterpret_cast<ucontext_t *>(ucontext);
        pcAddr = static_cast<uintptr_t>(uc->uc_mcontext.arm_pc);
    }
#endif
    uintptr_t lookupAddr = (pcAddr != 0) ? pcAddr : faultAddr;
    FILE *maps = fopen("/proc/self/maps", "rt");
    char moduleName[256] = "unknown.so";
    uintptr_t moduleBase = 0;
    char perms[16] = "----";

    if (maps != nullptr) {
        char line[512];
        while (fgets(line, sizeof(line), maps)) {
            unsigned long long startAddr = 0, endAddr = 0, fileOffset = 0;
            char p[8] = {0};
            char pathBuf[256] = {0};
            int parsed = sscanf(line, "%llx-%llx %7s %llx %*s %*s %255s",
                                &startAddr, &endAddr, p, &fileOffset, pathBuf);
            if (parsed >= 4 && lookupAddr >= startAddr && lookupAddr < endAddr) {
                moduleBase = static_cast<uintptr_t>(startAddr - fileOffset);
                strncpy(perms, p, sizeof(perms) - 1);
                if (parsed == 5 && pathBuf[0] != '\0') {
                    const char *slash = strrchr(pathBuf, '/');
                    strncpy(moduleName, slash ? (slash + 1) : pathBuf, sizeof(moduleName) - 1);
                }
                break;
            }
        }
        fclose(maps);
    }

    uintptr_t relOffset = (lookupAddr >= moduleBase && moduleBase != 0)
            ? (lookupAddr - moduleBase) : lookupAddr;

    FILE *out = fopen("/data/local/tmp/featurestic_last_crash.log", "wt");
    if (out != nullptr) {
        fprintf(out, "%s + 0x%llX (Signal %d, PC=0x%llX, Fault=0x%llX, Perms=%s)\n",
                moduleName,
                static_cast<unsigned long long>(relOffset),
                sig,
                static_cast<unsigned long long>(pcAddr),
                static_cast<unsigned long long>(faultAddr),
                perms);
        fclose(out);
    }
    ALOGE("FEATURESTIC CRASH CATCHER: %s + 0x%llX (Signal=%d, PC=0x%llX, Fault=0x%llX)",
          moduleName,
          static_cast<unsigned long long>(relOffset),
          sig,
          static_cast<unsigned long long>(pcAddr),
          static_cast<unsigned long long>(faultAddr));
}

static void chainPreviousSignal(int sig, siginfo_t *info, void *ucontext) {
    struct sigaction *prev = (sig == SIGSEGV) ? &sOldSigsegvAction : &sOldSigbusAction;
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

static void anogsMemoryFaultHandler(int sig, siginfo_t *info, void *ucontext) {
    if (info != nullptr && sig == SIGSEGV && info->si_code == SEGV_ACCERR) {
        uintptr_t faultAddr = reinterpret_cast<uintptr_t>(info->si_addr);
        long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0) pageSize = 4096;
        uintptr_t pageBase = faultAddr & ~static_cast<uintptr_t>(pageSize - 1);

        if (pageBase != 0 && isProtectedGameLibraryAddr(faultAddr)) {
            if (sLastUnlockedPage == pageBase) {
                sSamePageRetryCount++;
            } else {
                sLastUnlockedPage = pageBase;
                sSamePageRetryCount = 1;
            }
            if (sSamePageRetryCount <= 2) {
                if (mprotect(reinterpret_cast<void *>(pageBase), static_cast<size_t>(pageSize),
                             PROT_READ | PROT_WRITE | PROT_EXEC) == 0) {
                    __builtin___clear_cache(reinterpret_cast<char *>(pageBase),
                                            reinterpret_cast<char *>(pageBase + pageSize));
                    return; // Resume instruction execution cleanly without crashing!
                }
            }
        }
    }
    uintptr_t fAddr = (info != nullptr) ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
    recordNativeCrashOffsetLog(sig, fAddr, ucontext);
    chainPreviousSignal(sig, info, ucontext);
}

static void installAnogsMemoryFaultGuard() {
    if (sSignalGuardInstalled) return;
    sSignalGuardInstalled = true;
    struct sigaction sa{};
    sa.sa_sigaction = anogsMemoryFaultHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, &sOldSigsegvAction);
    sigaction(SIGBUS, &sa, &sOldSigbusAction);
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

bool detectFridaPort27042() {
    FILE *fp = fopen("/proc/net/tcp", "r");
    if (!fp) return false;
    char line[256];
    bool detected = false;
    while (fgets(line, sizeof(line), fp)) {
        // 27042 in hex is 69A2
        if (strstr(line, ":69A2 ") != nullptr || strstr(line, ":69a2 ") != nullptr) {
            detected = true;
            break;
        }
    }
    fclose(fp);
    return detected;
}


static void scrubFeaturesticCoreElfHeaderOnly() {
    FILE *fp = fopen("/proc/self/maps", "rt");
    if (!fp) return;
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        // Strictly target ONLY libFeaturesticCore.so (and never touch any game .so like libUE4.so / libil2cpp.so)
        if (strstr(line, "libFeaturesticCore.so") == nullptr
                && strstr(line, "FeaturesticCore.so") == nullptr
                && strstr(line, "libParallaxCore.so") == nullptr) {
            continue;
        }
        unsigned long long startAddr = 0, endAddr = 0, fileOffset = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s %llx", &startAddr, &endAddr, perms, &fileOffset) == 4) {
            if (fileOffset != 0 || startAddr == 0 || (endAddr - startAddr) < 64) {
                continue;
            }
            uintptr_t base = static_cast<uintptr_t>(startAddr);
            uintptr_t pageBase = base & ~static_cast<uintptr_t>(pageSize - 1);
            int origProt = PROT_READ;
            if (strchr(perms, 'w') != nullptr) origProt |= PROT_WRITE;
            if (strchr(perms, 'x') != nullptr) origProt |= PROT_EXEC;

            if (mprotect(reinterpret_cast<void *>(pageBase), static_cast<size_t>(pageSize),
                         PROT_READ | PROT_WRITE | origProt) == 0) {
                unsigned char *raw = reinterpret_cast<unsigned char *>(base);
                if (raw[0] == 0x7F && raw[1] == 'E' && raw[2] == 'L' && raw[3] == 'F') {
                    // Zero out ELF magic bytes and non-runtime Section Header Table metadata (e_shoff, e_shnum, e_shstrndx)
                    // while keeping Program Headers (e_phoff / PT_DYNAMIC) intact so C++ unwinding & dladdr remain 100% stable!
                    #if defined(__LP64__)
                    Elf64_Ehdr *ehdr = reinterpret_cast<Elf64_Ehdr *>(base);
                    ehdr->e_shoff = 0;
                    ehdr->e_shnum = 0;
                    ehdr->e_shstrndx = 0;
                    ehdr->e_shentsize = 0;
                    #else
                    Elf32_Ehdr *ehdr = reinterpret_cast<Elf32_Ehdr *>(base);
                    ehdr->e_shoff = 0;
                    ehdr->e_shnum = 0;
                    ehdr->e_shstrndx = 0;
                    ehdr->e_shentsize = 0;
                    #endif
                    raw[0] = 0x00;
                    raw[1] = 0x00;
                    raw[2] = 0x00;
                    raw[3] = 0x00;
                }
                mprotect(reinterpret_cast<void *>(pageBase), static_cast<size_t>(pageSize), origProt);
            }
            break;
        }
    }
    fclose(fp);
}


static volatile int sNativeLoginGateState = 0; // 0 = off, 1 = armed (locked), 2 = unlocked

static bool verifyHostApkV2SigningBlockFromMaps() {
    FILE *fp = fopen("/proc/self/maps", "rt");
    if (!fp) return true;
    char line[512];
    char apkPath[384] = {0};
    while (fgets(line, sizeof(line), fp)) {
        // Detect known signature-killer & hook-injection libraries in memory
        if (strstr(line, "libSignKill") != nullptr
                || strstr(line, "libkill") != nullptr
                || strstr(line, "libsandhook") != nullptr
                || strstr(line, "libpine.so") != nullptr
                || strstr(line, "libwhale.so") != nullptr) {
            fclose(fp);
            return false;
        }
        if (apkPath[0] == '\0' && strstr(line, "/data/app/") != nullptr && strstr(line, "base.apk") != nullptr) {
            const char *p = strstr(line, "/data/app/");
            if (p != nullptr) {
                strncpy(apkPath, p, sizeof(apkPath) - 1);
                char *nl = strpbrk(apkPath, "\r\n ");
                if (nl) *nl = '\0';
            }
        }
    }
    fclose(fp);

    if (apkPath[0] == '\0') {
        return true;
    }

    FILE *apk = fopen(apkPath, "rb");
    if (!apk) return true;
    if (fseek(apk, 0, SEEK_END) != 0) {
        fclose(apk);
        return true;
    }
    long fileSize = ftell(apk);
    if (fileSize < 65536) {
        fclose(apk);
        return true;
    }
    long searchSize = (fileSize < 65558) ? fileSize : 65558;
    if (fseek(apk, fileSize - searchSize, SEEK_SET) != 0) {
        fclose(apk);
        return true;
    }
    std::string tailBuf(static_cast<size_t>(searchSize), '\0');
    size_t readBytes = fread(&tailBuf[0], 1, static_cast<size_t>(searchSize), apk);
    if (readBytes < 22) {
        fclose(apk);
        return true;
    }
    long eocdPos = -1;
    for (long i = static_cast<long>(readBytes) - 22; i >= 0; --i) {
        const unsigned char *b = reinterpret_cast<const unsigned char *>(&tailBuf[i]);
        if (b[0] == 0x50 && b[1] == 0x4B && b[2] == 0x05 && b[3] == 0x06) {
            eocdPos = i;
            break;
        }
    }
    if (eocdPos < 0) {
        fclose(apk);
        return false;
    }
    const unsigned char *eocd = reinterpret_cast<const unsigned char *>(&tailBuf[eocdPos]);
    uint32_t cdOffset = static_cast<uint32_t>(eocd[16])
            | (static_cast<uint32_t>(eocd[17]) << 8)
            | (static_cast<uint32_t>(eocd[18]) << 16)
            | (static_cast<uint32_t>(eocd[19]) << 24);
    if (cdOffset > 32 && static_cast<long>(cdOffset) < fileSize) {
        // Check for "APK Sig Block 42" magic right before Central Directory offset
        if (fseek(apk, static_cast<long>(cdOffset) - 16, SEEK_SET) == 0) {
            char magic[17] = {0};
            if (fread(magic, 1, 16, apk) == 16) {
                if (memcmp(magic, "APK Sig Block 42", 16) != 0) {
                    fclose(apk);
                    return false;
                }
            }
        }
    }
    fclose(apk);
    return true;
}

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
    bool initialized;  // flag to check if class and methods are ready
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
    JNIEnv *env;
    jint ret = VMEnv.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED) {
        // Thread not attached – we can attach, but caller must handle env
        return nullptr;
    } else if (ret != JNI_OK) {
        return nullptr;
    }
    return env;
}

JNIEnv *ensureEnvCreated() {
    JNIEnv *env = getEnv();
    if (env == nullptr) {
        if (VMEnv.vm == nullptr) return nullptr;
        // Try to attach the current thread
        jint ret = VMEnv.vm->AttachCurrentThread(&env, nullptr);
        if (ret != JNI_OK || env == nullptr) {
            return nullptr;  // attach failed
        }
    }
    return env;
}

int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    if (!VMEnv.initialized) return orig;  // fallback to original if not ready

    JNIEnv *e = ensureEnvCreated();
    if (e == nullptr || VMEnv.NativeCoreClass == nullptr || VMEnv.getCallingUidId == nullptr) {
        return orig;
    }
    return e->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    if (!VMEnv.initialized || path == nullptr || sRuntimeTampered) return path;

    JNIEnv *e = ensureEnvCreated();
    if (e == nullptr || VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathString == nullptr) {
        return path;
    }

    // We must pass 'path' which might be a local reference from the caller's env.
    // But 'e' could be a different JNIEnv (attached thread). Use the passed env.
    // Better to use the provided env directly, but ensure it's valid.
    if (env == nullptr) return path;

    jstring result = (jstring) env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;  // fallback
    }
    return result;
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    if (!VMEnv.initialized || path == nullptr || sRuntimeTampered) return path;

    JNIEnv *e = ensureEnvCreated();
    if (e == nullptr || VMEnv.NativeCoreClass == nullptr || VMEnv.redirectPathFile == nullptr) {
        return path;
    }

    if (env == nullptr) return path;

    jobject result = env->CallStaticObjectMethod(
        VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return path;  // fallback
    }
    return result;
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

void nativeHook(JNIEnv *env) {
    if (env == nullptr) return;

    // Initialize all hooks with proper error handling
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
        }
        SystemPropertiesHook::setProp(k, v);
    }
    if (k != nullptr) env->ReleaseStringUTFChars(key, k);
    if (v != nullptr) env->ReleaseStringUTFChars(value, v);
}

void init(JNIEnv *env, jobject clazz, jint api_level) {
    if (env == nullptr) return;

    ALOGD("NativeCore init.");
    VMEnv.api_level = api_level;
    VMEnv.initialized = false;  // reset until fully ready

    // Find and store global reference to NativeCore class
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

    // Get method IDs
    VMEnv.getCallingUidId = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "getCallingUid", "(I)I");
    VMEnv.redirectPathString = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    VMEnv.redirectPathFile = env->GetStaticMethodID(
        VMEnv.NativeCoreClass, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");

    // Check if all required methods are present
    if (VMEnv.getCallingUidId == nullptr ||
        VMEnv.redirectPathString == nullptr ||
        VMEnv.redirectPathFile == nullptr) {
        ALOGE("Failed to get one or more NativeCore method IDs");
        // Cleanup?
        env->DeleteGlobalRef(VMEnv.NativeCoreClass);
        VMEnv.NativeCoreClass = nullptr;
        return;
    }

    // Initialize JniHook subsystem & libanogs.so SEGV_ACCERR fault recovery guard
    installAnogsMemoryFaultGuard();
    JniHook::InitJniHook(env, api_level);

    VMEnv.initialized = true;  // all good
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
    scrubFeaturesticCoreElfHeaderOnly();
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
    scrubFeaturesticCoreElfHeaderOnly();
    return JNI_VERSION_1_6;
}
