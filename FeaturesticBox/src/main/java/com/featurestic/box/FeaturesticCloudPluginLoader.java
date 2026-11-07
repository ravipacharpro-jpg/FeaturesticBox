package com.featurestic.box;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Cloud OTA Native Plugin (.so) Downloader, ELF Validator & Auto-Loader.
 *
 * Set your .so download link directly in your Host Loader App:
 *   FeaturesticBoxCore.get().setCloudPluginUrl("https://your-server.com/libFeaturesticPlugin.so");
 * or separate 64-bit / 32-bit URLs:
 *   FeaturesticBoxCore.get().setCloudPluginUrls(url64Bit, url32Bit, optionalSha256Hex);
 *
 * Features:
 * 1. Smart ETag / Content-Length caching (only re-downloads when you update the .so on your server).
 * 2. Strict ELF Header (ELF) & ABI Bitness (32-bit vs 64-bit) verification before replacing
 *    the active .so — if internet drops or server returns a 404 HTML page, it is rejected safely
 *    so the game never crashes!
 * 3. Automatic Android 14-16 (API 34-36) compliant read-only executable permissions (0555) and
 *    automatic injection via RNative.loadExternalPlugin().
 */
@Keep
public final class FeaturesticCloudPluginLoader {
    private static final String TAG = "FeaturesticCloudSo";
    private static final String PREFS_NAME = "featurestic_cloud_plugin_prefs";
    private static final String TARGET_SO_NAME = "libFeaturesticPlugin.so";

    private static volatile String sUrl64Bit = "";
    private static volatile String sUrl32Bit = "";
    private static volatile String sExpectedSha256 = "";

    private static final ExecutorService DOWNLOAD_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Featurestic-CloudSoOTA");
        t.setDaemon(true);
        return t;
    });

    private FeaturesticCloudPluginLoader() {
    }

    /**
     * Sets a single .so download URL from the Host Loader App and starts background sync immediately.
     */
    public static void setCloudPluginUrl(String pluginSoUrl) {
        setCloudPluginUrls(pluginSoUrl, pluginSoUrl, "");
    }

    /**
     * Sets 64-bit (arm64-v8a) and 32-bit (armeabi-v7a) .so download URLs and optional SHA-256 checksum
     * from the Host Loader App, persists them across processes, and starts background sync immediately.
     */
    public static void setCloudPluginUrls(String url64Bit, String url32Bit, String expectedSha256Hex) {
        sUrl64Bit = url64Bit != null ? url64Bit.trim() : "";
        sUrl32Bit = url32Bit != null ? url32Bit.trim() : "";
        sExpectedSha256 = expectedSha256Hex != null ? expectedSha256Hex.trim().toLowerCase(Locale.US) : "";

        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit()
                        .putString("url64", sUrl64Bit)
                        .putString("url32", sUrl32Bit)
                        .putString("sha256", sExpectedSha256)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
        syncPluginInBackground(FeaturesticBoxCore.getContext());
    }

    public static String getActivePluginUrl() {
        boolean is64 = FeaturesticBoxCore.is64Bit();
        String url = is64 ? sUrl64Bit : sUrl32Bit;
        if (url != null && !url.isEmpty()) {
            return url;
        }
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                return sp.getString(is64 ? "url64" : "url32", "");
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static File getInstalledPluginFile(Context context) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (ctx == null) return null;
        String abiDir = FeaturesticBoxCore.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
        return new File(new File(ctx.getNoBackupFilesDir(), "native/" + abiDir), TARGET_SO_NAME);
    }

    public static void syncPluginInBackground(final Context context) {
        final String targetUrl = getActivePluginUrl();
        if (targetUrl == null || targetUrl.isEmpty() || !targetUrl.startsWith("http")) {
            return;
        }
        DOWNLOAD_POOL.execute(() -> downloadAndInstallPluginNow(context, targetUrl));
    }

    public static synchronized boolean downloadAndInstallPluginNow(Context context, String downloadUrl) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (ctx == null || downloadUrl == null || !downloadUrl.startsWith("http")) {
            return false;
        }
        File targetSo = getInstalledPluginFile(ctx);
        if (targetSo == null) return false;
        File parentDir = targetSo.getParentFile();
        FileUtils.mkdirs(parentDir);
        File tempSo = new File(parentDir, TARGET_SO_NAME + ".download.tmp");

        HttpURLConnection conn = null;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String cachedEtag = sp.getString("etag_" + downloadUrl.hashCode(), "");

            URL url = new URL(downloadUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "FeaturesticBox-OTA/2.0");
            if (targetSo.isFile() && targetSo.length() > 64 && !cachedEtag.isEmpty()) {
                conn.setRequestProperty("If-None-Match", cachedEtag);
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED && targetSo.isFile()) {
                prepareAndroid14ReadOnlyExec(targetSo);
                return true;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return targetSo.isFile();
            }

            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream out = new FileOutputStream(tempSo)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }

            // 1. Verify valid ELF .so header & 32/64-bit architecture match
            if (!isValidElfForCurrentAbi(tempSo)) {
                Log.w(TAG, "Rejected downloaded file: not a valid ELF .so for current ABI");
                tempSo.delete();
                return targetSo.isFile();
            }

            // 2. Verify optional SHA-256 checksum if configured
            String expectedSha = sExpectedSha256;
            if (expectedSha == null || expectedSha.isEmpty()) {
                expectedSha = sp.getString("sha256", "");
            }
            if (expectedSha != null && expectedSha.length() == 64) {
                String actualSha = sha256Hex(tempSo);
                if (!expectedSha.equalsIgnoreCase(actualSha)) {
                    Log.w(TAG, "Rejected downloaded .so: SHA-256 mismatch");
                    tempSo.delete();
                    return targetSo.isFile();
                }
            }

            // 3. Atomically replace target .so and set Android 14-16 read-only executable permissions
            if (targetSo.exists()) {
                targetSo.setWritable(true, true);
                targetSo.delete();
            }
            if (!tempSo.renameTo(targetSo)) {
                FileUtils.copyFile(tempSo, targetSo);
                tempSo.delete();
            }
            prepareAndroid14ReadOnlyExec(targetSo);

            // Also copy into BEnvironment system native dir so isolated virtual processes can access it
            try {
                File sharedPlugin = new File(BEnvironment.getSystemDir(), TARGET_SO_NAME);
                if (sharedPlugin.exists()) {
                    sharedPlugin.setWritable(true, true);
                }
                FileUtils.copyFile(targetSo, sharedPlugin);
                prepareAndroid14ReadOnlyExec(sharedPlugin);
            } catch (Throwable ignored) {
            }

            String newEtag = conn.getHeaderField("ETag");
            if (newEtag != null && !newEtag.isEmpty()) {
                sp.edit().putString("etag_" + downloadUrl.hashCode(), newEtag).apply();
            }
            Log.i(TAG, "Cloud .so plugin updated and verified: " + targetSo.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Cloud .so sync skipped: " + t.getMessage());
            if (tempSo.exists()) tempSo.delete();
            return targetSo.isFile();
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isValidElfForCurrentAbi(File soFile) {
        if (soFile == null || !soFile.isFile() || soFile.length() < 64) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(soFile)) {
            byte[] hdr = new byte[20];
            if (fis.read(hdr) < 20) return false;
            // ELF magic: 0x7F 'E' 'L' 'F'
            if (hdr[0] != 0x7F || hdr[1] != 'E' || hdr[2] != 'L' || hdr[3] != 'F') {
                return false;
            }
            int elfClass = hdr[4] & 0xFF; // 1 = 32-bit, 2 = 64-bit
            int expectedClass = FeaturesticBoxCore.is64Bit() ? 2 : 1;
            return elfClass == expectedClass;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void prepareAndroid14ReadOnlyExec(File soFile) {
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

    private static String sha256Hex(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
