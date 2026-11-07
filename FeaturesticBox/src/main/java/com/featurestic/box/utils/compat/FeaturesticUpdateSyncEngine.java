package com.featurestic.box.utils.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.entity.pm.InstallResult;
import com.featurestic.box.utils.FileUtils;

/**
 * Auto Host-Game Update Sync Engine (#5 — Zero Data Loss on Game Update):
 * Automatically detects when a game installed on the host phone is updated on the Play Store
 * (via versionCode + lastUpdateTime stamp) and seamlessly syncs the virtual container's
 * base.apk, split APKs, and OBB/Pak files without wiping the user's Guest ID, controls,
 * sensitivity settings, or login sessions.
 */
public final class FeaturesticUpdateSyncEngine {

    private FeaturesticUpdateSyncEngine() {
    }

    private static File getSyncStampFile(String pkg, int userId) {
        return new File(BEnvironment.getSystemDir(), "update_sync_" + pkg + "_u" + userId + ".stamp");
    }

    /**
     * Checks if the host-installed package has been updated since the last launch in this slot,
     * and if so, refreshes the virtual package metadata & OBB/Pak symlinks while preserving user data.
     */
    public static boolean autoSyncHostGameUpdateIfNeeded(Context context, String pkg, int userId, boolean forceSync) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
            if (ctx == null) return false;
            PackageManager hostPm = ctx.getPackageManager();
            PackageInfo hostPi;
            try {
                hostPi = hostPm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException notInstalledOnHost) {
                // Standalone APK installed from storage — still ensure OBB/Paks are linked
                FeaturesticZeroCopyLinker.linkGameObbAndMapPaks(pkg, userId);
                return false;
            }
            if (hostPi == null || hostPi.applicationInfo == null) return false;

            long vCode = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? hostPi.getLongVersionCode() : hostPi.versionCode;
            long updateTime = hostPi.lastUpdateTime;
            String sourceDir = hostPi.applicationInfo.sourceDir != null ? hostPi.applicationInfo.sourceDir : "";
            String currentStamp = vCode + "|" + updateTime + "|" + sourceDir;

            File stampFile = getSyncStampFile(pkg, userId);
            String savedStamp = readStamp(stampFile);

            if (!forceSync && currentStamp.equals(savedStamp)) {
                return false;
            }

            // Host game was updated (or forceSync requested): re-install/update by package name
            // in the virtual PackageManager (which updates baseCodePath/splitCodePaths in-place
            // without deleting /data/user/<userId>/<pkg>!) and re-link OBB + Map Paks.
            InstallResult res = FeaturesticBoxCore.get().installPackageAsUser(pkg, userId);
            FeaturesticZeroCopyLinker.linkGameObbAndMapPaks(pkg, userId);
            writeStamp(stampFile, currentStamp);
            return res != null && res.success;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readStamp(File f) {
        try {
            if (f != null && f.isFile() && f.length() > 0 && f.length() < 512) {
                byte[] buf = new byte[(int) f.length()];
                try (FileInputStream fis = new FileInputStream(f)) {
                    int n = fis.read(buf);
                    if (n > 0) {
                        return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static void writeStamp(File f, String stamp) {
        try {
            if (f == null) return;
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(stamp.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
