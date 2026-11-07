package com.featurestic.box.utils.compat;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.core.env.BEnvironment;

/**
 * Unreal Engine / Unity Junk Log & Crash-Dump Auto-Trimmer.
 *
 * Automatically cleans bloated non-essential log/crash/telemetry directories
 * (Saved/Logs, Saved/Crashes, Pandora/cache, tlog, anr, tombstones) that can
 * grow to 500MB-1.5GB after several BGMI/PUBG matches, without ever touching
 * game maps, OBBs, Paks, or login sessions.
 */
@Obfuscate
public final class StorageDietOptimizer {
    private static final ExecutorService DIET_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Featurestic-StorageDiet");
        t.setDaemon(true);
        return t;
    });

    private static final String[] JUNK_DIR_NAMES = new String[]{
            "Logs",
            "Crashes",
            "tlog",
            "TLog",
            "anr",
            "tombstones",
            "bugly",
            "CrashSight",
            "GCloudLog"
    };

    private StorageDietOptimizer() {
    }

    public static void trimJunkAsync(final String packageName, final int userId) {
        if (packageName == null || packageName.isEmpty()) return;
        DIET_POOL.execute(() -> trimJunkNow(packageName, userId));
    }

    public static long trimJunkNow(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return 0L;
        long freedBytes = 0L;
        try {
            File internalData = BEnvironment.getDataDir(packageName, userId);
            freedBytes += trimDirectoryTree(internalData, 0);
        } catch (Throwable ignored) {
        }
        try {
            File extData = BEnvironment.getExternalUserAppDataDir(userId, packageName);
            freedBytes += trimDirectoryTree(extData, 0);
        } catch (Throwable ignored) {
        }
        return freedBytes;
    }

    private static long trimDirectoryTree(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 7) {
            return 0L;
        }
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        long freed = 0L;
        for (File child : children) {
            if (child == null) continue;
            String name = child.getName();
            // Never touch game Paks, OBBs, or account login directories
            if ("Paks".equalsIgnoreCase(name)
                    || "shared_prefs".equalsIgnoreCase(name)
                    || "databases".equalsIgnoreCase(name)
                    || "app_webview".equalsIgnoreCase(name)
                    || "ano_tmp".equalsIgnoreCase(name)
                    || "tss_tmp".equalsIgnoreCase(name)) {
                continue;
            }
            if (child.isDirectory()) {
                if (isJunkDirectoryName(name)) {
                    freed += deleteFilesInside(child);
                } else {
                    freed += trimDirectoryTree(child, depth + 1);
                }
            } else {
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".dmp")
                        || lower.endsWith(".tlog")) {
                    long ageMs = System.currentTimeMillis() - child.lastModified();
                    if (ageMs >= 300_000L) {
                        long len = child.length();
                        if (child.delete()) {
                            freed += len;
                        }
                    }
                }
            }
        }
        return freed;
    }

    private static boolean isJunkDirectoryName(String name) {
        if (name == null) return false;
        for (String j : JUNK_DIR_NAMES) {
            if (j.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static long deleteFilesInside(File dir) {
        if (dir == null || !dir.isDirectory()) return 0L;
        long bytes = 0L;
        File[] files = dir.listFiles();
        if (files == null) return 0L;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                bytes += deleteFilesInside(f);
                f.delete();
            } else {
                long ageMs = System.currentTimeMillis() - f.lastModified();
                if (ageMs >= 300_000L) {
                    long len = f.length();
                    if (f.delete()) {
                        bytes += len;
                    }
                }
            }
        }
        return bytes;
    }
}
