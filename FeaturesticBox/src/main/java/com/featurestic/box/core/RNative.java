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
            "libbgmi.so",
            "libDARK.so",
            "DARK.so",
            "FeaturesticPlugin.so",
            "libFeaturesticPlugin.so",
            "FeaturesticCore.so",
            "libFeaturesticCore.so"
    };
    private static volatile boolean isInjected = false;
    private static volatile boolean sGameActivityResumed = false;
    private static volatile boolean sGameLibLoaded = false;
    private static volatile boolean sScheduledLoad = false;
    private static final Set<String> sLoadedLibraries = new HashSet<>();

    static {
        System.loadLibrary("FeaturesticCore");
    }

    public static void onGameActivityResumed(android.app.Activity activity) {
        if (activity == null || isInjected) return;
        String cls = activity.getClass().getName();
        String lower = cls.toLowerCase(java.util.Locale.US);
        if (lower.contains("splash") || lower.contains("proxy") || lower.contains("launcher")
                || lower.contains("signin") || lower.contains("oauth")) {
            return;
        }
        if (lower.contains("gameactivity") || lower.contains("ue4") || lower.contains("unityplayer")) {
            sGameActivityResumed = true;
            scheduleExternalPluginLoad(2500L);
        }
    }

    private static boolean isLibraryMappedInProcMaps(String... substrings) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            boolean[] found = new boolean[substrings.length];
            int remaining = substrings.length;
            String line;
            while ((line = br.readLine()) != null) {
                for (int i = 0; i < substrings.length; i++) {
                    if (!found[i] && line.contains(substrings[i])) {
                        found[i] = true;
                        remaining--;
                        if (remaining == 0) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static synchronized void scheduleExternalPluginLoad(final long delayMs) {
        if (isInjected || sScheduledLoad) return;
        sScheduledLoad = true;
        new Thread(() -> {
            try {
                // Wait up to 30s until both libUE4.so and libanogs.so (or Unity libs) are actually mapped
                boolean ready = false;
                for (int i = 0; i < 100; i++) {
                    if (isLibraryMappedInProcMaps("libUE4.so", "libanogs.so")
                            || isLibraryMappedInProcMaps("libil2cpp.so")
                            || isLibraryMappedInProcMaps("libunity.so")) {
                        ready = true;
                        sGameLibLoaded = true;
                        try {
                            setNativeProp("debug.featurestic.gamelibs_ready", "1");
                        } catch (Throwable ignored) {
                        }
                        break;
                    }
                    Thread.sleep(300L);
                }
                Thread.sleep(Math.max(1000L, delayMs));
                try {
                    setNativeProp("debug.featurestic.gamelibs_ready", "1");
                } catch (Throwable ignored) {
                }
                if (!isInjected) {
                    android.util.Log.i(TAG, "Loading external plugin .so after GameActivity + maps ready (ready=" + ready + ", libLoaded=" + sGameLibLoaded + ")");
                    loadExternalPlugin();
                }
            } catch (Throwable ignored) {
            }
        }, "Featurestic-PluginInit").start();
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

    public static native boolean loadSoFromBytes(byte[] soBytes);

    private static boolean loadSingleMemfdSo(String urlStr, String expectedSha) {
        byte[] soBytes = null;
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "FeaturesticBox/1.0");
            if (conn.getResponseCode() != 200) {
                android.util.Log.e(TAG, "Multi-SO HTTP " + conn.getResponseCode() + " for: " + urlStr);
                return false;
            }

            try (java.io.InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int read;
                while ((read = is.read(chunk)) != -1) {
                    baos.write(chunk, 0, read);
                }
                soBytes = baos.toByteArray();
            }
            if (soBytes == null || soBytes.length < 64) return false;

            if (!expectedSha.isEmpty()) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(soBytes);
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) {
                    String h = Integer.toHexString(0xff & b);
                    if (h.length() == 1) hex.append('0');
                    hex.append(h);
                }
                if (!hex.toString().equalsIgnoreCase(expectedSha)) {
                    android.util.Log.e(TAG, "RAM-only .so SHA-256 mismatch for: " + urlStr);
                    return false;
                }
            }
            boolean ok = loadSoFromBytes(soBytes);
            android.util.Log.i(TAG, "RAM-only memfd loaded (" + soBytes.length + " bytes): " + ok + " from " + urlStr);
            return ok;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "loadSingleMemfdSo error: " + t.getMessage());
            return false;
        } finally {
            if (soBytes != null) java.util.Arrays.fill(soBytes, (byte) 0);
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean loadMemfdPluginFromConfig(Context ctx) {
        if (ctx == null) return false;
        java.io.File cfg = new java.io.File(ctx.getFilesDir(), "featurestic_memfd_plugin.txt");
        if (!cfg.isFile() || cfg.length() == 0) return false;
        try {
            byte[] cfgBuf = new byte[(int) Math.min(cfg.length(), 16384)];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(cfg)) {
                n = fis.read(cfgBuf);
            }
            if (n <= 0) return false;
            String raw = new String(cfgBuf, 0, n, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) return false;

            // Support multiple .so entries in sequential order (delimited by comma, semicolon, or newline)
            String[] entries = raw.split("[,;\\n]+");
            boolean anyLoaded = false;
            for (String entry : entries) {
                String cleanEntry = entry.trim();
                if (cleanEntry.isEmpty()) continue;
                String urlStr = cleanEntry;
                String expectedSha = "";
                int bar = cleanEntry.indexOf('|');
                if (bar >= 0) {
                    urlStr = cleanEntry.substring(0, bar).trim();
                    expectedSha = cleanEntry.substring(bar + 1).trim();
                }
                if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) continue;

                if (loadSingleMemfdSo(urlStr, expectedSha)) {
                    anyLoaded = true;
                    android.util.Log.i(TAG, "Multi-SO sequential RAM load success: " + urlStr);
                } else {
                    android.util.Log.w(TAG, "Multi-SO RAM load failed for: " + urlStr);
                }
            }
            return anyLoaded;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "loadMemfdPluginFromConfig error: " + t.getMessage());
            return false;
        }
    }

    public static synchronized boolean loadExternalPlugin() {
        if (isInjected) return true;
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx == null) return false;
            if (loadMemfdPluginFromConfig(ctx)) {
                isInjected = true;
                return true;
            }
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
            if (libPath.contains("libUE4.so") || libPath.contains("libil2cpp.so") || libPath.contains("libunity.so")) {
                sGameLibLoaded = true;
                if (sGameActivityResumed) {
                    scheduleExternalPluginLoad(1500L);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
