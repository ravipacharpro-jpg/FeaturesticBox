package com.featurestic.box.utils.compat;

import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Auto Crash Shield & Corrupted Shader/OBB Resource Healer.
 *
 * 1. Absorbs non-fatal background thread exceptions (e.g. DeadSystemException,
 *    RemoteException, GMS/analytics/telemetry worker crashes) so a background
 *    service hiccup never kills an active game match.
 * 2. Detects startup/shader crashes and automatically purges corrupted Unreal/Unity
 *    shader caches (*.ushaderprecache, PipelineCache, GPUCache) and verifies OBB
 *    integrity on launch.
 */
@Obfuscate
public final class CrashShieldAndResourceHealer {
    private static final String TAG = "FeaturesticCrashShield";
    private static volatile boolean sInstalled = false;

    private CrashShieldAndResourceHealer() {
    }

    private static File getCrashMarkerFile(String pkg, int userId) {
        String safePkg = (pkg != null && !pkg.isEmpty()) ? pkg.replace('.', '_') : "app";
        return new File(BEnvironment.getSystemDir(), "crash_heal_" + safePkg + "_u" + Math.max(0, userId) + ".flag");
    }

    public static void installForVirtualProcess(final Context appContext) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        final String pkg = BActivityThread.getAppPackageName();
        final int userId = BActivityThread.getUserId();

        // Check if previous session crashed and auto-heal corrupted shader cache & OBB links
        try {
            File marker = getCrashMarkerFile(pkg, userId);
            if (marker.exists()) {
                marker.delete();
                healGameResources(appContext, pkg, userId);
            }
        } catch (Throwable ignored) {
        }

        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (shouldAbsorbNonFatalException(thread, throwable)) {
                Log.w(TAG, "Absorbed non-fatal background exception on thread="
                        + (thread != null ? thread.getName() : "null") + ": " + throwable);
                return;
            }
            try {
                File marker = getCrashMarkerFile(pkg, userId);
                FileUtils.mkdirs(marker.getParentFile());
                marker.createNewFile();
            } catch (Throwable ignored) {
            }
            try {
                com.featurestic.box.core.CrashHandler.reportCrashToTelemetry(throwable);
            } catch (Throwable ignored) {
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean shouldAbsorbNonFatalException(Thread thread, Throwable t) {
        if (t == null) {
            return true;
        }
        boolean isMainThread = false;
        try {
            Looper mainLooper = Looper.getMainLooper();
            isMainThread = mainLooper != null && mainLooper.getThread() == thread;
        } catch (Throwable ignored) {
        }
        if (isMainThread) {
            return false;
        }
        String msg = t.getMessage() != null ? t.getMessage() : "";
        String cls = t.getClass().getName();
        String tName = thread != null && thread.getName() != null ? thread.getName() : "";

        // Never kill the game when a background Binder, GMS, WebView, or telemetry worker throws
        if (t instanceof RemoteException
                || cls.contains("DeadSystemException")
                || cls.contains("DeadObjectException")
                || (t instanceof SecurityException && !isMainThread)
                || (t instanceof IllegalStateException && (msg.contains("Service") || msg.contains("Broadcast") || msg.contains("SharedPreferences")))
                || tName.contains("Firebase")
                || tName.contains("Measurement")
                || tName.contains("Gms")
                || tName.contains("OkHttp")
                || tName.contains("Crashlytics")
                || tName.contains("Sentry")
                || tName.contains("Bugly")) {
            return true;
        }
        return false;
    }

    /**
     * Purges corrupted shader caches (.ushaderprecache, GPUCache, PipelineCache, temp dex)
     * and ensures OBB directories are intact without touching user login sessions or cookies.
     */
    public static int healGameResources(Context context, String packageName, int userId) {
        int cleanedCount = 0;
        String pkg = (packageName != null && !packageName.isEmpty())
                ? packageName
                : BActivityThread.getAppPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return 0;
        }
        try {
            Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
            if (ctx != null && ctx.getCacheDir() != null) {
                cleanedCount += cleanCorruptCacheTree(ctx.getCacheDir());
            }
        } catch (Throwable ignored) {
        }
        try {
            File extData = BEnvironment.getExternalUserAppDataDir(userId, pkg);
            if (extData != null && extData.isDirectory()) {
                cleanedCount += cleanCorruptCacheTree(extData);
            }
        } catch (Throwable ignored) {
        }
        try {
            // Ensure OBB folder exists and link any missing .obb from host /sdcard/Android/obb/<pkg>
            File virtualObbDir = BEnvironment.getExternalUserAppObbDir(userId, pkg);
            File hostObbDir = new File("/sdcard/Android/obb/" + pkg);
            if (hostObbDir.isDirectory()) {
                FileUtils.mkdirs(virtualObbDir);
                File[] hostObbs = hostObbDir.listFiles();
                if (hostObbs != null) {
                    for (File obb : hostObbs) {
                        if (obb != null && obb.isFile() && obb.getName().endsWith(".obb") && obb.length() > 0) {
                            File target = new File(virtualObbDir, obb.getName());
                            if (!target.exists() || target.length() == 0) {
                                try {
                                    android.system.Os.symlink(obb.getAbsolutePath(), target.getAbsolutePath());
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return cleanedCount;
    }

    private static int cleanCorruptCacheTree(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        int removed = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            if (child == null) continue;
            String name = child.getName();
            if (child.isDirectory()) {
                if ("GPUCache".equalsIgnoreCase(name)
                        || "ShaderCache".equalsIgnoreCase(name)
                        || "GrShaderCache".equalsIgnoreCase(name)
                        || "Code Cache".equalsIgnoreCase(name)) {
                    FileUtils.deleteDir(child);
                    removed++;
                } else {
                    removed += cleanCorruptCacheTree(child);
                }
            } else {
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".ushaderprecache")
                        || lower.endsWith(".upipelinecache")
                        || lower.startsWith("pipelinecache")
                        || lower.endsWith(".tmp")
                        || (lower.endsWith(".shader") && child.length() == 0)) {
                    if (child.delete()) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }
}
