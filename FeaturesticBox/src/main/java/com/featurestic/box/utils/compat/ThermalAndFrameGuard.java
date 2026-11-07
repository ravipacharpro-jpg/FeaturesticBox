package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.os.Build;
import android.os.Process;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Hardware 90/120Hz Display Unlocker, Surface.setFrameRate Enforcer & Thermal Guard.
 *
 * Ensures devices that unlock 120 FPS in-game also switch their physical display
 * panel to 90Hz/120Hz on Xiaomi/MIUI/HyperOS, Realme/ColorOS, OnePlus, Vivo, and Samsung:
 * 1. Selects the highest refresh-rate Display.Mode matching the active physical resolution.
 * 2. Sets preferredRefreshRate, preferredMinDisplayRefreshRate, and preferredMaxDisplayRefreshRate.
 * 3. Calls Surface.setFrameRate(targetFps, FIXED_SOURCE, ALWAYS) and SurfaceControl.Transaction
 *    directly on the game's rendering SurfaceView!
 * 4. Syncs UE4 EnjoyCJZC.ini / UserCustom.ini 120 FPS CVars.
 */
@Obfuscate
public final class ThermalAndFrameGuard {
    private static volatile boolean sThermalGuardEnabled = true;
    private static volatile int sTargetFps = 120;
    private static volatile boolean sTargetFpsLoaded = false;

    private ThermalAndFrameGuard() {
    }

    private static File getConfigFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_fps_target.conf");
    }

    public static void setThermalGuardEnabled(boolean enabled) {
        sThermalGuardEnabled = enabled;
    }

    public static boolean isThermalGuardEnabled() {
        return sThermalGuardEnabled;
    }

    public static void setTargetFps(int fps) {
        int clamped = (fps >= 110) ? 120 : (fps >= 80 ? 90 : 60);
        sTargetFps = clamped;
        sTargetFpsLoaded = true;
        try {
            File f = getConfigFile();
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(String.valueOf(clamped).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        try {
            if (clamped >= 120) {
                DeviceProfileCompat.setPreset(DeviceProfileCompat.PRESET_ROG_PHONE_8);
            } else if (clamped >= 90) {
                DeviceProfileCompat.setPreset(DeviceProfileCompat.PRESET_ONEPLUS_12);
            }
        } catch (Throwable ignored) {
        }
        try {
            String pkg = BActivityThread.isThreadInit() ? BActivityThread.getAppPackageName() : null;
            int uid = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
            if (pkg != null) {
                FeaturesticGraphicsOptimizer.applyGraphicsPresetAsync(
                        pkg, uid, FeaturesticGraphicsOptimizer.getCurrentPreset());
            }
        } catch (Throwable ignored) {
        }
    }

    public static int getTargetFps() {
        if (sTargetFpsLoaded) {
            return sTargetFps;
        }
        sTargetFpsLoaded = true;
        try {
            File f = getConfigFile();
            if (f.isFile() && f.length() > 0 && f.length() <= 16) {
                byte[] buf = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int n = fis.read(buf);
                    if (n > 0) {
                        int parsed = Integer.parseInt(new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                        if (parsed == 60 || parsed == 90 || parsed == 120) {
                            sTargetFps = parsed;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sTargetFps;
    }

    public static void initForProcess() {
        if (!sThermalGuardEnabled) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        try {
            int fps = getTargetFps();
            RNative.setNativeProp("debug.sf.frame_rate_multiple_threshold", String.valueOf(fps));
            RNative.setNativeProp("ro.surface_flinger.use_content_detection_for_refresh_rate", "false");
            RNative.setNativeProp("sys.thermal.is_throttling", "0");
            RNative.setNativeProp("vendor.thermal.mode", "game_perf");
            RNative.setNativeProp("debug.graphics.game_default_frame_rate.disabled", "true");
        } catch (Throwable ignored) {
        }
        boostRenderAndGameThreads();
    }

    private static final java.util.Set<SurfaceView> sHookedSurfaces =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    public static void applyToActivityWindow(final Activity activity) {
        try {
            com.featurestic.box.fake.service.IDisplayManagerProxy.flushDisplayInfoCache();
        } catch (Throwable ignored) {
        }
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
        }
        try {
            final Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final int targetFps = getTargetFps();
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null) {
                lp.preferredRefreshRate = (float) targetFps;
                setMinMaxRefreshRateFields(lp, (float) targetFps);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Display display = activity.getWindowManager().getDefaultDisplay();
                    if (display != null) {
                        Display.Mode currentMode = display.getMode();
                        Display.Mode[] modes = display.getSupportedModes();
                        Display.Mode bestSameRes = null;
                        Display.Mode bestAnyRes = null;
                        if (modes != null) {
                            for (Display.Mode m : modes) {
                                float rate = m.getRefreshRate();
                                if (rate > targetFps + 6f) continue;
                                boolean sameRes = currentMode != null
                                        && m.getPhysicalWidth() == currentMode.getPhysicalWidth()
                                        && m.getPhysicalHeight() == currentMode.getPhysicalHeight();
                                if (sameRes) {
                                    if (bestSameRes == null || Math.abs(rate - targetFps) < Math.abs(bestSameRes.getRefreshRate() - targetFps)) {
                                        bestSameRes = m;
                                    }
                                }
                                if (bestAnyRes == null || Math.abs(rate - targetFps) < Math.abs(bestAnyRes.getRefreshRate() - targetFps)) {
                                    bestAnyRes = m;
                                }
                            }
                        }
                        Display.Mode chosen = bestSameRes != null ? bestSameRes : bestAnyRes;
                        if (chosen != null) {
                            lp.preferredDisplayModeId = chosen.getModeId();
                        }
                    }
                }
                window.setAttributes(lp);
            }

            // Apply Surface.setFrameRate & SurfaceControl.Transaction to all game SurfaceViews
            View decor = window.getDecorView();
            if (decor != null) {
                decor.post(() -> applySurfaceFrameRateRecursive(decor, (float) targetFps));
                decor.postDelayed(() -> applySurfaceFrameRateRecursive(decor, (float) targetFps), 1200L);
                decor.postDelayed(() -> applySurfaceFrameRateRecursive(decor, (float) targetFps), 3200L);
                decor.postDelayed(() -> applySurfaceFrameRateRecursive(decor, (float) targetFps), 6500L);
            }
        } catch (Throwable ignored) {
        }
        boostRenderAndGameThreads();
    }

    private static void setMinMaxRefreshRateFields(WindowManager.LayoutParams lp, float targetHz) {
        String[] fieldNames = new String[]{
                "preferredMinDisplayRefreshRate",
                "preferredMaxDisplayRefreshRate",
                "minRefreshRate",
                "maxRefreshRate"
        };
        for (String name : fieldNames) {
            try {
                Field f = WindowManager.LayoutParams.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setFloat(lp, targetHz);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applySurfaceFrameRateRecursive(View root, float targetFps) {
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            List<SurfaceView> surfaces = new ArrayList<>();
            if (root instanceof ViewGroup) {
                collectSurfaceViews((ViewGroup) root, surfaces);
            }
            for (final SurfaceView sv : surfaces) {
                if (sv == null || sv.getHolder() == null) continue;
                if (sHookedSurfaces.add(sv)) {
                    final float finalFps = targetFps;
                    sv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(android.view.SurfaceHolder holder) {
                            applySurfaceFrameRateRecursive(sv, (float) getTargetFps());
                        }
                        @Override
                        public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) {
                            applySurfaceFrameRateRecursive(sv, (float) getTargetFps());
                        }
                        @Override
                        public void surfaceDestroyed(android.view.SurfaceHolder holder) {
                        }
                    });
                }
                Surface surface = sv.getHolder().getSurface();
                if (surface != null && surface.isValid()) {
                    try {
                        // setFrameRate(frameRate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE=1, CHANGE_FRAME_RATE_ALWAYS=1)
                        Method m3 = Surface.class.getMethod("setFrameRate", float.class, int.class, int.class);
                        m3.invoke(surface, targetFps, 1, 1);
                    } catch (Throwable fallback) {
                        try {
                            Method m2 = Surface.class.getMethod("setFrameRate", float.class, int.class);
                            m2.invoke(surface, targetFps, 1);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        SurfaceControl sc = sv.getSurfaceControl();
                        if (sc != null && sc.isValid()) {
                            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                            try {
                                Method txSet = SurfaceControl.Transaction.class.getMethod(
                                        "setFrameRate", SurfaceControl.class, float.class, int.class, int.class);
                                txSet.invoke(tx, sc, targetFps, 1, 1);
                                tx.apply();
                            } finally {
                                try {
                                    tx.close();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectSurfaceViews(ViewGroup group, List<SurfaceView> out) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView) {
                out.add((SurfaceView) child);
            } else if (child instanceof ViewGroup) {
                collectSurfaceViews((ViewGroup) child, out);
            }
        }
    }

    private static volatile long sLastThermalStabilizeMs = 0L;

    /**
     * Smart Overheat Frame Stabilizer (#6 — 43°C+ Anti-Lag Guard):
     * When battery temperature rises to 43°C or higher during intense matches, Android's
     * thermal daemon tries to throttle CPU cores and drop SurfaceFlinger back to 60Hz.
     * This stabilizer throttles non-essential background worker threads (logging, analytics,
     * crash reporters) down to Thread.MIN_PRIORITY while locking RenderThread/GameThread/RHIThread
     * at MAX_PRIORITY and re-asserting the 120Hz Surface frame rate.
     */
    public static void onThermalTelemetryTick(Activity activity, float batteryTempCelsius, int currentFps) {
        if (!sThermalGuardEnabled || activity == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (batteryTempCelsius >= 43.0f || (getTargetFps() >= 90 && currentFps > 0 && currentFps < 68)) {
            if (now - sLastThermalStabilizeMs < 10000L) {
                return;
            }
            sLastThermalStabilizeMs = now;
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            } catch (Throwable ignored) {
            }
            stabilizeThreadsUnderThermalLoad();
            try {
                Window window = activity.getWindow();
                if (window != null && window.getDecorView() != null) {
                    applySurfaceFrameRateRecursive(window.getDecorView(), (float) getTargetFps());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void stabilizeThreadsUnderThermalLoad() {
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
                if (name.contains("RenderThread")
                        || name.contains("GameThread")
                        || name.contains("RHIThread")
                        || name.contains("UnityMain")
                        || name.contains("UnityGfx")) {
                    try {
                        t.setPriority(Thread.MAX_PRIORITY);
                    } catch (Throwable ignored) {
                    }
                } else {
                    String lower = name.toLowerCase();
                    if (lower.contains("bugly")
                            || lower.contains("analytics")
                            || lower.contains("report")
                            || lower.contains("telemetry")
                            || lower.contains("log")
                            || lower.contains("workmanager")
                            || lower.contains("firebase")) {
                        try {
                            t.setPriority(Thread.MIN_PRIORITY);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void boostRenderAndGameThreads() {
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
                if (name.contains("RenderThread")
                        || name.contains("GameThread")
                        || name.contains("RHIThread")
                        || name.contains("UnityMain")
                        || name.contains("UnityGfx")) {
                    try {
                        t.setPriority(Thread.MAX_PRIORITY);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
