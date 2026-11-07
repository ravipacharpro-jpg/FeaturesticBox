package com.featurestic.box.core;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Base64;
import androidx.annotation.Keep;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashSet;
import java.util.Set;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.BuildConfig;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;

public class RNative {

    public static final String TAG = "RNative";
    private static final String NATIVE_ARTIFACT_DIRECTORY = "native";
    private static final String NATIVE_ARTIFACT_NAME = "FeaturesticCore.so";
    private static final String[] PLUGIN_CANDIDATE_NAMES = new String[]{
            "FeaturesticCore.so",
            "libFeaturesticCore.so",
            "FeaturesticPlugin.so",
            "libFeaturesticPlugin.so"
    };
    private static volatile boolean isInjected = false;
    private static final Set<String> sLoadedLibraries = new HashSet<>();

    static {
        System.loadLibrary("FeaturesticCore");
        try {
            if (FeaturesticBoxCore.get() != null && FeaturesticBoxCore.get().isFeaturesticProcess()) {
                loadExternalPlugin();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isCompatibleElf(File soFile) {
        if (soFile == null || !soFile.isFile() || soFile.length() < 16) return false;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(soFile)) {
            byte[] header = new byte[5];
            if (fis.read(header) < 5) return false;
            if (header[0] != 0x7F || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return false;
            }
            int elfClass = header[4] & 0xFF;
            int expectedClass = FeaturesticBoxCore.is64Bit() ? 2 : 1;
            return elfClass == expectedClass;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void prepareSoPermissions(File soFile) {
        if (soFile == null || !soFile.isFile()) return;
        try {
            soFile.setReadable(true, false);
            soFile.setExecutable(true, false);
            if (Build.VERSION.SDK_INT >= 34) {
                soFile.setWritable(false, false);
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized boolean loadExternalPlugin() {
        if (isInjected) return true;
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx == null) return false;
            String abiSubDir = FeaturesticBoxCore.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
            File[] searchDirs = new File[]{
                    new File(new File(ctx.getNoBackupFilesDir(), NATIVE_ARTIFACT_DIRECTORY), abiSubDir),
                    new File(new File(ctx.getFilesDir(), NATIVE_ARTIFACT_DIRECTORY), abiSubDir),
                    new File(ctx.getNoBackupFilesDir(), NATIVE_ARTIFACT_DIRECTORY),
                    new File(ctx.getFilesDir(), NATIVE_ARTIFACT_DIRECTORY),
                    ctx.getFilesDir(),
                    BEnvironment.getSystemDir(),
                    BActivityThread.getAppPackageName() != null
                            ? BEnvironment.getAppLibDir(BActivityThread.getAppPackageName())
                            : null
            };
            for (File dir : searchDirs) {
                if (dir == null || !dir.isDirectory()) continue;
                for (String candidate : PLUGIN_CANDIDATE_NAMES) {
                    File soFile = new File(dir, candidate);
                    if (soFile.isFile() && soFile.length() > 0) {
                        if (loadCustomLibrary(soFile.getAbsolutePath())) {
                            isInjected = true;
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static synchronized boolean loadCustomLibrary(String pathOrName) {
        if (pathOrName == null || pathOrName.trim().isEmpty()) return false;
        String trimmed = pathOrName.trim();
        if (sLoadedLibraries.contains(trimmed)) return true;
        try {
            File directFile = new File(trimmed);
            if (directFile.isAbsolute() || trimmed.contains("/") || trimmed.endsWith(".so")) {
                if (!directFile.isFile()) {
                    Context ctx = FeaturesticBoxCore.getContext();
                    if (ctx != null) {
                        File inNoBackup = new File(new File(ctx.getNoBackupFilesDir(), NATIVE_ARTIFACT_DIRECTORY), trimmed);
                        File inFiles = new File(ctx.getFilesDir(), trimmed);
                        if (inNoBackup.isFile()) directFile = inNoBackup;
                        else if (inFiles.isFile()) directFile = inFiles;
                    }
                }
                if (directFile.isFile() && directFile.length() > 0) {
                    String abs = directFile.getAbsolutePath();
                    if (sLoadedLibraries.contains(abs)) return true;
                    prepareSoPermissions(directFile);
                    System.load(abs);
                    sLoadedLibraries.add(abs);
                    sLoadedLibraries.add(trimmed);
                    return true;
                }
            }
            String libShortName = trimmed;
            if (libShortName.startsWith("lib")) {
                libShortName = libShortName.substring(3);
            }
            if (libShortName.endsWith(".so")) {
                libShortName = libShortName.substring(0, libShortName.length() - 3);
            }
            System.loadLibrary(libShortName);
            sLoadedLibraries.add(trimmed);
            sLoadedLibraries.add(libShortName);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static native void init(int apiLevel);
    public static native void enableIO();
    public static native void addIORule(String targetPath, String relocatePath);
    public static native void hideXposed();
    public static native void setNativeProp(String key, String value);
    public static native boolean isRuntimeSecure();
    public static native boolean lockHostSignature(Context context, String expectedSha256);
    public static native boolean authorizeSdkSession(
            Context context,
            String currentPackage,
            String currentSigningSha256,
            String authorizedPackage,
            String authorizedSigningSha256,
            String responseCanonical,
            String responseSignature,
            String identityCanonical,
            String identitySignature,
            long leaseExpiresAt,
            long serverTime);
    public static native boolean verifyInstalledIdentity(
            Context context,
            String expectedPackage,
            String expectedSigningSha256);
    public static native boolean isSdkSessionValid(long currentTime);
    public static native void clearSdkSession();
    public static native String getSdkPanelEndpoint();

    @Keep
    private static boolean verifyServerSignature(String canonical, String signatureBase64) {
        try {
            byte[] publicDer = Base64.decode(BuildConfig.SDK_PANEL_SIGNING_PUBLIC_KEY, Base64.DEFAULT);
            byte[] signatureBytes = Base64.decode(signatureBase64, Base64.DEFAULT);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicDer)));
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid > Process.LAST_APPLICATION_UID) return origCallingUid;
        if (origCallingUid == FeaturesticBoxCore.getHostUid()) {
            if ("com.google.android.gms".equals(BActivityThread.getAppPackageName())) {
                return Process.ROOT_UID;
            }
            if ("com.google.android.webview".equals(BActivityThread.getAppPackageName())) {
                return Process.myUid();
            }
            return BActivityThread.getCallingBUid();
        }
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return RCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return RCore.get().redirectPath(path);
    }

    @Keep
    public static void onGameLibraryLoaded(String libPath) {
        if (libPath == null || libPath.isEmpty()) return;
        sLoadedLibraries.add(libPath);
        try {
            if (libPath.contains("libUE4.so") || libPath.contains("libanogs.so")
                    || libPath.contains("libil2cpp.so") || libPath.contains("libunity.so")
                    || libPath.contains("libtersafe.so") || libPath.contains("libgcloud.so")) {
                loadExternalPlugin();
            }
        } catch (Throwable ignored) {
        }
    }
}
