package net_62v.external;

import android.content.Intent;
import androidx.annotation.Keep;
import com.featurestic.box.FeaturesticBoxCore;

/**
 * Drop-in compatibility bridge for Loaders built on net_62v.external.MetaActivityManager.
 */
@Keep
public class MetaActivityManager {

    public static boolean launchApp(String packageName) {
        return FeaturesticBoxCore.get().launchApk(packageName, 0);
    }

    public static boolean launchApp(String packageName, int userId) {
        return FeaturesticBoxCore.get().launchApk(packageName, userId);
    }

    public static boolean launchApp(int userId, String packageName) {
        return FeaturesticBoxCore.get().launchApk(packageName, userId);
    }

    public static void startActivity(Intent intent) {
        FeaturesticBoxCore.get().startActivity(intent, 0);
    }

    public static void startActivity(Intent intent, int userId) {
        FeaturesticBoxCore.get().startActivity(intent, userId);
    }

    public static void killApp(String packageName) {
        FeaturesticBoxCore.get().stopPackage(packageName, 0);
    }

    public static void killApp(String packageName, int userId) {
        FeaturesticBoxCore.get().stopPackage(packageName, userId);
    }

    public static void killAllApps() {
        try {
            for (android.content.pm.ApplicationInfo info : FeaturesticBoxCore.get().getInstalledApplications(0, 0)) {
                if (info != null && info.packageName != null) {
                    FeaturesticBoxCore.get().stopPackage(info.packageName, 0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isAppRunning(String packageName) {
        return FeaturesticBoxCore.get().isAppRunning(packageName, 0);
    }

    public static boolean isAppRunning(String packageName, int userId) {
        return FeaturesticBoxCore.get().isAppRunning(packageName, userId);
    }
}
