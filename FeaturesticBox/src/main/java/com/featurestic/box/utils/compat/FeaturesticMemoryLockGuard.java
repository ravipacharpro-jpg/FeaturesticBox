package com.featurestic.box.utils.compat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * GG & Companion Process Anti-Kill Memory Lock (Low-RAM Phone Shield):
 * Prevents Android's Low Memory Killer (LMK) and Phantom Process Killer on 4GB/6GB/8GB RAM
 * phones from killing GameGuardian or virtual companion processes in the background while
 * heavy 3D games are running.
 */
public final class FeaturesticMemoryLockGuard {
    private static volatile Boolean sMemoryLockEnabled;
    private static final AtomicBoolean sHeartbeatRunning = new AtomicBoolean(false);
    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private FeaturesticMemoryLockGuard() {
    }

    private static File getDisabledFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_gg_memlock_disabled.flag");
    }

    public static boolean isMemoryLockEnabled() {
        if (sMemoryLockEnabled != null) return sMemoryLockEnabled;
        try {
            File f = getDisabledFlagFile();
            sMemoryLockEnabled = !(f != null && f.exists());
            return sMemoryLockEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setMemoryLockEnabled(boolean enabled) {
        sMemoryLockEnabled = enabled;
        try {
            File f = getDisabledFlagFile();
            FileUtils.mkdirs(f.getParentFile());
            if (enabled) {
                if (f.exists()) f.delete();
                engageMemoryLockForProcess(null);
            } else {
                f.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void engageMemoryLockForProcess(Context context) {
        if (!isMemoryLockEnabled()) return;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vmRuntimeClass.getMethod("getRuntime").invoke(null);
            vmRuntimeClass.getMethod("setTargetHeapUtilization", float.class).invoke(runtime, 0.75f);
        } catch (Throwable ignored) {
        }
        if (sHeartbeatRunning.compareAndSet(false, true)) {
            scheduleKeepAliveHeartbeat();
        }
    }

    private static void scheduleKeepAliveHeartbeat() {
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isMemoryLockEnabled()) {
                    sHeartbeatRunning.set(false);
                    return;
                }
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
                } catch (Throwable ignored) {
                }
                scheduleKeepAliveHeartbeat();
            }
        }, 12000L);
    }
}
