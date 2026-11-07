package com.featurestic.box.utils.compat;

import android.system.Os;

import androidx.annotation.Keep;

import java.io.File;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * 0.5-Second Zero-Copy OBB & UE4 Map/Resource Pak Instant Symlink Engine.
 */
@Keep
public final class FeaturesticZeroCopyLinker {
    private FeaturesticZeroCopyLinker() {
    }

    public static int linkGameObbAndMapPaks(String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) return 0;
        int linkedCount = 0;
        try {
            File targetObbDir = BEnvironment.getExternalUserAppObbDir(userId, packageName);
            FileUtils.mkdirs(targetObbDir);
            File[] obbSources = new File[]{
                    new File("/sdcard/Android/obb/" + packageName),
                    BEnvironment.getExternalUserAppObbDir(0, packageName)
            };
            for (File srcDir : obbSources) {
                if (srcDir == null || !srcDir.isDirectory() || srcDir.equals(targetObbDir)) continue;
                File[] files = srcDir.listFiles();
                if (files == null) continue;
                for (File src : files) {
                    if (src != null && src.isFile() && src.getName().endsWith(".obb") && src.length() > 0) {
                        File dst = new File(targetObbDir, src.getName());
                        if (!dst.exists() || dst.length() == 0) {
                            if (tryZeroCopyLinkOnly(src, dst)) {
                                linkedCount++;
                            }
                        }
                    }
                }
            }

            String relPaks = "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Paks";
            File targetPaksDir = new File(BEnvironment.getExternalUserAppDataDir(userId, packageName), relPaks);
            File[] pakSources = new File[]{
                    new File("/sdcard/Android/data/" + packageName, relPaks),
                    new File(BEnvironment.getExternalUserAppDataDir(0, packageName), relPaks)
            };
            for (File srcPaks : pakSources) {
                if (srcPaks == null || !srcPaks.isDirectory() || srcPaks.equals(targetPaksDir)) continue;
                FileUtils.mkdirs(targetPaksDir);
                File[] paks = srcPaks.listFiles();
                if (paks == null) continue;
                for (File pak : paks) {
                    if (pak != null && pak.isFile() && pak.getName().endsWith(".pak") && pak.length() > 1024 * 1024) {
                        File dstPak = new File(targetPaksDir, pak.getName());
                        if (!dstPak.exists()) {
                            if (tryZeroCopyLinkOnly(pak, dstPak)) {
                                linkedCount++;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return linkedCount;
    }

    private static boolean tryZeroCopyLinkOnly(File src, File dst) {
        try {
            Os.symlink(src.getAbsolutePath(), dst.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
            // Do not copy multi-GB OBB/PAK files on the main thread when FUSE rejects symlinks;
            // RCore Zero-Copy IO Redirection maps /Android/obb/<pkg> directly in C++.
            return false;
        }
    }
}
