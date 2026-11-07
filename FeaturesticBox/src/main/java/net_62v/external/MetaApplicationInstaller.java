package net_62v.external;

import androidx.annotation.Keep;
import java.io.File;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.entity.pm.InstallResult;

/**
 * Drop-in compatibility bridge for Loaders built on net_62v.external.MetaApplicationInstaller.
 */
@Keep
public class MetaApplicationInstaller {

    public static int cloneApp(String packageName) {
        return cloneApp(packageName, 0);
    }

    public static int cloneApp(String packageName, int userId) {
        InstallResult res = FeaturesticBoxCore.get().installPackageAsUser(packageName, userId);
        return (res != null && res.success) ? 0 : -1;
    }

    public static int installPackage(String packageName) {
        return cloneApp(packageName, 0);
    }

    public static int installPackage(String packageName, int userId) {
        return cloneApp(packageName, userId);
    }

    public static int installPackageFromApk(String apkFilePath) {
        return installPackageFromApk(apkFilePath, 0);
    }

    public static int installPackageFromApk(String apkFilePath, int userId) {
        if (apkFilePath == null || apkFilePath.isEmpty()) return -1;
        InstallResult res = FeaturesticBoxCore.get().installBundleAsUser(new File(apkFilePath), userId);
        return (res != null && res.success) ? 0 : -1;
    }

    public static int installPackageFromApk(File apkFile) {
        return installPackageFromApk(apkFile, 0);
    }

    public static int installPackageFromApk(File apkFile, int userId) {
        if (apkFile == null) return -1;
        InstallResult res = FeaturesticBoxCore.get().installBundleAsUser(apkFile, userId);
        return (res != null && res.success) ? 0 : -1;
    }
}
