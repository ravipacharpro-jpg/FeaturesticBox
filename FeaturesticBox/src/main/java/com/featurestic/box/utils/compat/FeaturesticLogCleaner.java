package com.featurestic.box.utils.compat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Real-Time Crash-Dump, ANR, Bugly & Telemetry Log Auto-Cleaner:
 * Automatically cleans/truncates crash dumps (.dmp), ANR traces, tombstone logs, Bugly reports,
 * and temporary telemetry report directories inside the virtual game container on startup
 * and every 20 seconds in the background.
 */
public final class FeaturesticLogCleaner {
    private static volatile Boolean sEnabled;
    private static final AtomicBoolean sPeriodicRunning = new AtomicBoolean(false);
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static volatile int sLastCleanedCount = 0;

    private static final String[] LOG_REL_DIRS = new String[]{
            "app_bugly",
            "app_crashrecord",
            "app_crashSight",
            "cache/crash",
            "files/crash",
            "files/Diagnostic",
            "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Crashes"
    };

    private FeaturesticLogCleaner() {
    }

    private static File getDisabledFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_log_cleaner_disabled.flag");
    }

    public static boolean isAutoLogCleanerEnabled() {
        if (sEnabled != null) return sEnabled;
        try {
            File f = getDisabledFlagFile();
            sEnabled = !(f != null && f.exists());
            return sEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setAutoLogCleanerEnabled(boolean enabled) {
        sEnabled = enabled;
        try {
            File f = getDisabledFlagFile();
            FileUtils.mkdirs(f.getParentFile());
            if (enabled) {
                if (f.exists()) f.delete();
            } else {
                f.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    public static int getLastCleanedCount() {
        return sLastCleanedCount;
    }

    public static void startAutoCleanerForGame(final Context context, final String pkg, final int userId) {
        if (!isAutoLogCleanerEnabled()) return;
        cleanContainerLogsAsync(context, pkg, userId);
        if (sPeriodicRunning.compareAndSet(false, true)) {
            scheduleNextSweep(context, pkg, userId);
        }
    }

    private static void scheduleNextSweep(final Context context, final String pkg, final int userId) {
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isAutoLogCleanerEnabled()) {
                    sPeriodicRunning.set(false);
                    return;
                }
                cleanContainerLogsAsync(context, pkg, userId);
                scheduleNextSweep(context, pkg, userId);
            }
        }, 20000L);
    }

    public static void cleanContainerLogsAsync(final Context context, final String pkg, final int userId) {
        new Thread(() -> {
            try {
                int count = cleanContainerLogsNow(context, pkg, userId);
                if (count > 0) {
                    sLastCleanedCount += count;
                }
            } catch (Throwable ignored) {
            }
        }, "FeaturesticLogCleaner").start();
    }

    public static int cleanContainerLogsNow(Context context, String pkg, int userId) {
        int cleaned = 0;
        try {
            if (pkg != null && !pkg.isEmpty()) {
                File dataDir = BEnvironment.getDataDir(pkg, userId);
                if (dataDir != null && dataDir.exists()) {
                    cleaned += sweepAppDirectory(dataDir);
                }
                File extDir = BEnvironment.getExternalDataDir(pkg);
                if (extDir != null && extDir.exists()) {
                    cleaned += sweepAppDirectory(extDir);
                }
            }
            if (context != null) {
                File appData = context.getFilesDir() != null ? context.getFilesDir().getParentFile() : null;
                if (appData != null && appData.exists()) {
                    cleaned += sweepAppDirectory(appData);
                }
            }
        } catch (Throwable ignored) {
        }
        return cleaned;
    }

    private static int sweepAppDirectory(File baseDir) {
        int count = 0;
        for (String rel : LOG_REL_DIRS) {
            File target = new File(baseDir, rel);
            if (target.exists()) {
                count += wipeLogFilesRecursive(target, 0);
            }
        }
        count += wipeCrashExtensionsInDir(new File(baseDir, "files"), 0);
        count += wipeCrashExtensionsInDir(new File(baseDir, "cache"), 0);
        return count;
    }

    private static int wipeLogFilesRecursive(File file, int depth) {
        if (file == null || !file.exists() || depth > 4) return 0;
        int wiped = 0;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) {
                    wiped += wipeLogFilesRecursive(c, depth + 1);
                }
            }
        } else if (file.isFile()) {
            try {
                // Never truncate mmap'd files (causes SIGBUS) and skip recently modified active files (< 5 min old)
                long ageMs = System.currentTimeMillis() - file.lastModified();
                if (ageMs >= 300_000L && file.delete()) {
                    wiped++;
                }
            } catch (Throwable ignored) {
            }
        }
        return wiped;
    }

    private static int wipeCrashExtensionsInDir(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3) return 0;
        int wiped = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File f : children) {
            if (f.isDirectory()) {
                wiped += wipeCrashExtensionsInDir(f, depth + 1);
            } else {
                String name = f.getName().toLowerCase(Locale.US);
                if (name.endsWith(".dmp")
                        || name.endsWith(".crash")
                        || name.startsWith("tombstone")
                        || name.startsWith("anr_")) {
                    try {
                        long ageMs = System.currentTimeMillis() - f.lastModified();
                        if (ageMs >= 300_000L && f.delete()) {
                            wiped++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return wiped;
    }
}
