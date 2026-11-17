package com.featurestic.box.core.system.permission;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.BuildCompat;

public class XiaomiPermissionManager {
    private static final String TAG = "XiaomiPermissionManager";
    private static final XiaomiPermissionManager sInstance = new XiaomiPermissionManager();

    private static final String XIAOMI_AUTOSTART_PERMISSION = "miui.permission.USE_INTERNAL_GENERAL_API";
    private static final String XIAOMI_BATTERY_OPTIMIZATION = "miui.permission.OPTIMIZE_POWER";
    private static final String XIAOMI_BACKGROUND_RUNNING = "miui.permission.RUN_IN_BACKGROUND";
    private static final String XIAOMI_NOTIFICATION_PERMISSION = "miui.permission.POST_NOTIFICATIONS";
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";

    public static XiaomiPermissionManager get() {
        return sInstance;
    }

    private XiaomiPermissionManager() {
    }

    public boolean isXiaomiDevice() {
        return BuildCompat.isMIUI()
                || Build.MANUFACTURER.toLowerCase().contains("xiaomi")
                || Build.BRAND.toLowerCase().contains("xiaomi")
                || Build.DISPLAY.toLowerCase().contains("hyperos");
    }

    public boolean isNotificationPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        Context context = FeaturesticBoxCore.getContext();
        if (context == null) return true;
        return ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isXiaomiBackgroundPermissionGranted() {
        if (!isXiaomiDevice()) {
            return true;
        }
        Context context = FeaturesticBoxCore.getContext();
        if (context == null) return true;

        try {
            if (ContextCompat.checkSelfPermission(context, XIAOMI_AUTOSTART_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (ContextCompat.checkSelfPermission(context, XIAOMI_BATTERY_OPTIMIZATION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (ContextCompat.checkSelfPermission(context, XIAOMI_BACKGROUND_RUNNING) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (ContextCompat.checkSelfPermission(context, XIAOMI_NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public void grantAllRequiredPermissions() {
        if (!isXiaomiDevice()) return;
        try {
            grantPermissionThroughReflection(XIAOMI_AUTOSTART_PERMISSION);
            grantPermissionThroughReflection(XIAOMI_BATTERY_OPTIMIZATION);
            grantPermissionThroughReflection(XIAOMI_BACKGROUND_RUNNING);
            grantPermissionThroughReflection(XIAOMI_NOTIFICATION_PERMISSION);
        } catch (Throwable e) {
            Slog.w(TAG, "grantAllRequiredPermissions error: " + e.getMessage());
        }
    }

    private void grantPermissionThroughReflection(String permission) {
        try {
            Context context = FeaturesticBoxCore.getContext();
            if (context == null) return;
            PackageManager pm = context.getPackageManager();
            if (pm != null) {
                java.lang.reflect.Method grantMethod = pm.getClass().getDeclaredMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class);
                grantMethod.setAccessible(true);
                android.os.UserHandle user = android.os.Process.myUserHandle();
                grantMethod.invoke(pm, context.getPackageName(), permission, user);
            }
        } catch (Throwable ignored) {
        }
    }
}
