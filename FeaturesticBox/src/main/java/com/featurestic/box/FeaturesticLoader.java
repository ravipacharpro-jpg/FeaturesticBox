package com.featurestic.box;

import android.content.Context;
import androidx.annotation.Keep;
import java.io.File;
import com.featurestic.box.entity.pm.InstallResult;
import net_62v.external.MetaActivationManager;

/**
 * 1-Line Universal Loader Helper for any Android Loader APK.
 *
 * Usage in ANY Activity or Application:
 *   // 1. Optional init (also happens automatically on app start!):
 *   FeaturesticLoader.init(this);
 *
 *   // 2. One-click install + OBB auto-link + launch:
 *   FeaturesticLoader.launchGame(this, "com.pubg.imobile");
 */
@Keep
public final class FeaturesticLoader {

    private FeaturesticLoader() {
    }

    public static void init(Context context) {
        FeaturesticBoxCore.get().ensureInitialized(context);
    }

    public static boolean launchGame(Context context, String packageName) {
        return launchGame(context, packageName, 0);
    }

    public static boolean launchGame(Context context, String packageName, int userId) {
        if (context != null) {
            FeaturesticBoxCore.get().ensureInitialized(context);
        }
        return FeaturesticBoxCore.get().launchApk(packageName, userId);
    }

    public static boolean installAndLaunch(Context context, String packageName) {
        return launchGame(context, packageName, 0);
    }

    public static boolean installAndLaunch(Context context, File apkOrBundleFile, int userId) {
        if (context != null) {
            FeaturesticBoxCore.get().ensureInitialized(context);
        }
        InstallResult res = FeaturesticBoxCore.get().installBundleAsUser(apkOrBundleFile, userId);
        if (res != null && res.success && res.packageName != null) {
            return FeaturesticBoxCore.get().launchApk(res.packageName, userId);
        }
        return false;
    }

    public static boolean stopGame(String packageName) {
        return stopGame(packageName, 0);
    }

    public static boolean stopGame(String packageName, int userId) {
        try {
            FeaturesticBoxCore.get().stopPackage(packageName, userId);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean loadPluginSo(String pathOrLibName) {
        return FeaturesticBoxCore.loadCustomLibrary(pathOrLibName);
    }

    public static boolean resetGuestAccount(String packageName) {
        return FeaturesticBoxCore.get().resetGuestAccountKeepResources(packageName, 0, false);
    }

    public static String resetDeviceId() {
        return MetaActivationManager.resetVirtualDeviceIdentity();
    }
}
