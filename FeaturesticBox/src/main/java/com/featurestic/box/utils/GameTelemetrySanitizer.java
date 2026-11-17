package com.featurestic.box.utils;

import android.app.Application;
import java.io.File;

/**
 * Sanitizes and suppresses game crash/telemetry dump directories (Bugly, CrashSight, ano_tmp, xlog)
 * to prevent games from dumping violation stack traces or reporting false-positive crash flags.
 */
public class GameTelemetrySanitizer {
    private static final String TAG = "GameTelemetrySanitizer";

    public static void sanitizeGameTelemetry(Application app) {
        if (app == null) return;
        try {
            String pkg = app.getPackageName();
            if (pkg == null) return;

            // Target known game packages and general telemetry directories
            if (pkg.contains("pubg") || pkg.contains("imobile") || pkg.contains("tencent") || pkg.contains("krafton") || pkg.contains("vng")) {
                File filesDir = app.getFilesDir();
                if (filesDir != null && filesDir.exists()) {
                    cleanDir(new File(filesDir, "ano_tmp"));
                    cleanDir(new File(filesDir, "xlog"));
                }

                if (app.getApplicationInfo() != null && app.getApplicationInfo().dataDir != null) {
                    File dataDir = new File(app.getApplicationInfo().dataDir);
                    if (dataDir.exists()) {
                        cleanDir(new File(dataDir, "app_bugly"));
                        cleanDir(new File(dataDir, "app_crashrecord"));
                        cleanDir(new File(dataDir, "app_crashSight"));
                    }
                }
                Slog.d(TAG, "Sanitized game crash dump & telemetry directories for " + pkg);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Failed to sanitize game telemetry: " + e.getMessage());
        }
    }

    private static void cleanDir(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isDirectory()) {
                            cleanDir(f);
                        }
                        f.delete();
                    }
                }
            } else {
                dir.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
