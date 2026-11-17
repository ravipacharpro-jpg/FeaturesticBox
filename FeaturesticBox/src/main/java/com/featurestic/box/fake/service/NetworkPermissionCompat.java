package com.featurestic.box.fake.service;

import android.os.Build;
import java.lang.reflect.Method;
import com.featurestic.box.fake.hook.IInjectHook;
import com.featurestic.box.utils.Slog;

/**
 * Disables the Android framework permission cache (introduced in Android 12+)
 * so that permission checks inside virtual processes are always routed to
 * hooked Binder IPC (IActivityManager / IPackageManager) rather than hitting
 * stale or host-denied cached results.
 */
public class NetworkPermissionCompat implements IInjectHook {
    private static final String TAG = "NetworkPermissionCompat";
    private static volatile boolean sInstalled;

    @Override
    public void injectHook() {
        install();
    }

    @Override
    public boolean isBadEnv() {
        return !sInstalled;
    }

    public static void install() {
        if (sInstalled) {
            return;
        }
        synchronized (NetworkPermissionCompat.class) {
            if (sInstalled) {
                return;
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Class<?> permissionManager = Class.forName("android.permission.PermissionManager");
                    Method disablePermissionCache = permissionManager.getDeclaredMethod("disablePermissionCache");
                    disablePermissionCache.setAccessible(true);
                    disablePermissionCache.invoke(null);
                    Slog.d(TAG, "Disabled framework permission cache for Android 12+");
                }
                sInstalled = true;
            } catch (Throwable e) {
                Slog.w(TAG, "Failed to disable framework permission cache: " + e.getMessage());
                // Still mark installed so we don't spam reflection attempts
                sInstalled = true;
            }
        }
    }
}
