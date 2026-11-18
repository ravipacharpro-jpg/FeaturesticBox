package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import java.util.concurrent.atomic.AtomicBoolean;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.RNative;

/**
 * Zero-Touch Autopilot Gaming Engine.
 */
@Obfuscate
public final class FeaturesticAutopilotEngine {
    private static volatile boolean sAutopilotEnabled = true;
    private static volatile boolean sGameInForeground = false;
    private static volatile boolean sChargeCoolerActive = false;
    private static final AtomicBoolean sProcessTuned = new AtomicBoolean(false);
    private static volatile long sLastStutterHealMs = 0L;
    private static volatile int sConsecutiveLowFpsWindows = 0;

    private FeaturesticAutopilotEngine() {
    }

    public static void setAutopilotEnabled(boolean enabled) {
        sAutopilotEnabled = enabled;
    }

    public static boolean isAutopilotEnabled() {
        return sAutopilotEnabled;
    }

    public static boolean isGameInForeground() {
        return sGameInForeground;
    }

    public static boolean isChargeCoolerActive() {
        return sChargeCoolerActive;
    }

    /**
     * Automatically runs on virtual process startup (BActivityThread.handleBindApplication).
     */
    public static void autoTuneForProcess(Context context) {
        if (!sAutopilotEnabled || !sProcessTuned.compareAndSet(false, true)) {
            return;
        }
        try {
            // Heal any stale plain-text EnjoyCJZC.ini created by older builds
            FeaturesticGraphicsOptimizer.cleanPlainTextEnjoyIniIfPresent(
                    BActivityThread.getAppPackageName(),
                    BActivityThread.getUserId());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Automatically runs whenever a virtual game Activity resumes.
     */
    public static void onGameActivityResumed(Activity activity) {
        if (!sAutopilotEnabled || activity == null || activity.isFinishing()) {
            return;
        }
        sGameInForeground = true;
        ClawTouchGuard.applyAutoEdgeProtection(activity);
    }

    public static void onGameActivityDestroyed() {
        sGameInForeground = false;
    }

    public static void onLiveFpsSample(Activity activity, int currentFps) {
        if (!sAutopilotEnabled) return;
        int target = ThermalAndFrameGuard.getTargetFps();
        if (currentFps > 0 && currentFps < Math.min(48, target - 15)) {
            sConsecutiveLowFpsWindows++;
            if (sConsecutiveLowFpsWindows >= 3) {
                sConsecutiveLowFpsWindows = 0;
                long now = SystemClock.elapsedRealtime();
                if (now - sLastStutterHealMs > 12_000L) {
                    sLastStutterHealMs = now;
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
                    } catch (Throwable ignored) {
                    }
                    ThermalAndFrameGuard.boostRenderAndGameThreads();
                }
            }
        } else {
            sConsecutiveLowFpsWindows = 0;
        }
    }

    public static void applyResolutionScale(Activity activity, float scaleFactor) {
        if (activity == null || scaleFactor <= 0.5f || scaleFactor > 1.25f) return;
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            DisplayMetrics sysDm = android.content.res.Resources.getSystem().getDisplayMetrics();
            if (dm != null && sysDm != null) {
                dm.density = sysDm.density * scaleFactor;
                dm.scaledDensity = sysDm.scaledDensity * scaleFactor;
                dm.densityDpi = (int) (sysDm.densityDpi * scaleFactor);
            }
        } catch (Throwable ignored) {
        }
    }
}
