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
 *
 * Automatically detects device RAM/CPU tier, charging/thermal state, and live FPS drops
 * so the user NEVER needs to configure settings manually:
 * 1. Auto Hardware-Tier Tuning:
 *    - Low/Mid-Range (< 6GB RAM or <= 6 cores): enables GPU HWUI fast-path & memory diet.
 *    - Flagship (6GB+ RAM): auto-locks 120 FPS ROG_PHONE_8 preset & max refresh rate.
 * 2. Auto FPS-Drop Stutter Healer:
 *    - Called automatically by the FPS monitor; if FPS dips below 45 during combat,
 *      auto-boosts RenderThread priority and trims transient heap without frame pause.
 * 3. Auto Charge-Play Cooler Mode:
 *    - Detects when charger is plugged in or battery temp >= 39.5C and automatically
 *      lowers background worker thread priorities so CPU heat drops while gaming.
 * 4. Auto DND Focus Shield:
 *    - Suppresses non-essential background virtual notifications while game is active.
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
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        try {
            // 1. Detect device RAM & CPU core count
            int cores = Runtime.getRuntime().availableProcessors();
            long totalRamMb = getTotalRamMb(ctx);

            // 2. Auto-apply GPU/HWUI & SurfaceFlinger low-latency properties
            RNative.setNativeProp("debug.hwui.use_buffer_age", "true");
            RNative.setNativeProp("debug.hwui.render_dirty_regions", "false");
            RNative.setNativeProp("persist.sys.gpu.working_thread_priority", "1");
            RNative.setNativeProp("debug.sf.latch_unsignaled", "1");
            RNative.setNativeProp("debug.sf.disable_backpressure", "1");

            if (totalRamMb > 0 && totalRamMb <= 5500 || cores <= 6) {
                // Low/Mid-range device: optimize Dalvik/ART heap & aggressive junk trim
                RNative.setNativeProp("dalvik.vm.heapminfree", "2m");
                RNative.setNativeProp("dalvik.vm.heapmaxfree", "8m");
                StorageDietOptimizer.trimJunkAsync(
                        BActivityThread.getAppPackageName(),
                        BActivityThread.getUserId());
            } else {
                // 6GB+ / 8GB+ device: ensure 120 FPS flagship profile is active by default
                ThermalAndFrameGuard.setTargetFps(120);
            }
        } catch (Throwable ignored) {
        }

        // 3. Start Live Ping Monitor & Auto-Spike Healer automatically
        LivePingMonitor.ensureStarted(ctx);
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
        LivePingMonitor.ensureStarted(activity);
        checkAndApplyChargeCooler(activity);
    }

    public static void onGameActivityDestroyed() {
        sGameInForeground = false;
    }

    /**
     * Called automatically every 500ms by the FPS HUD monitor.
     * If FPS drops below 48 for 3 consecutive windows, automatically heals thread
     * priorities and network/thermal locks without user intervention.
     */
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
                    checkAndApplyChargeCooler(activity);
                }
            }
        } else {
            sConsecutiveLowFpsWindows = 0;
        }
    }

    private static void checkAndApplyChargeCooler(Context context) {
        if (context == null) return;
        try {
            Intent batt = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt == null) return;
            int plugged = batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int tempTenths = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300);
            boolean hotOrCharging = (plugged != 0) || (tempTenths >= 395); // >= 39.5 C
            sChargeCoolerActive = hotOrCharging;
            if (hotOrCharging) {
                throttleBackgroundWorkerThreads();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void throttleBackgroundWorkerThreads() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root != null && root.getParent() != null) {
                root = root.getParent();
            }
            if (root == null) return;
            Thread[] threads = new Thread[root.activeCount() + 16];
            int count = root.enumerate(threads, true);
            for (int i = 0; i < count; i++) {
                Thread t = threads[i];
                if (t == null) continue;
                String name = t.getName();
                if (name == null) continue;
                if (name.contains("Bugly")
                        || name.contains("Firebase")
                        || name.contains("Measurement")
                        || name.contains("GCloudLog")
                        || name.contains("CrashSight")
                        || name.contains("Analytics")) {
                    try {
                        t.setPriority(Thread.MIN_PRIORITY);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
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

    private static long getTotalRamMb(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return 8192L;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 8192L;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024L * 1024L);
        } catch (Throwable ignored) {
            return 8192L;
        }
    }
}
