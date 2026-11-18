package com.featurestic.box.utils;

import android.app.Application;

/**
 * Preserves game anti-cheat runtime directories (ano_tmp, tss_tmp, xlog) intact.
 * Deleting files/ano_tmp during startup corrupts libanogs.so heartbeat state on warm/cold launches.
 */
public class GameTelemetrySanitizer {
    private static final String TAG = "GameTelemetrySanitizer";

    public static void sanitizeGameTelemetry(Application app) {
        // Intentionally no-op for ano_tmp / xlog / app_crashSight:
        // libanogs.so and libanort.so actively verify and mmap files inside files/ano_tmp
        // during initialization and at the ~25-37s lobby heartbeat check.
    }
}
