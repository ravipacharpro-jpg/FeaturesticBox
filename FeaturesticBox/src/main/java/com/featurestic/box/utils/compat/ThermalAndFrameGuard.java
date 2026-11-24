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

import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Hardware 90/120Hz Display Unlocker, Surface.setFrameRate Enforcer & Thermal Guard.
 *
 * Uses official Android Window/Surface refresh-rate APIs without mutating Build.MODEL
 * or writing unencrypted EnjoyCJZC.ini files into BGMI's Saved/Config/Android folder.
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
                // Safe Window attributes only: NEVER touch lp.preferredDisplayModeId or SurfaceControl
                // to prevent Android 16 display mode recreation and SkiaOpenGLPipeline surface detachment (surface=NULL).
                window.setAttributes(lp);
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

    private static volatile long sLastThermalStabilizeMs = 0L;

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
