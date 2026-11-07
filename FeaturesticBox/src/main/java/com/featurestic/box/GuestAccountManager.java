package com.featurestic.box;

import androidx.annotation.Keep;

import java.io.File;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Md5Utils;

/**
 * 1-Click Guest Account Reset & Backup Manager.
 *
 * Resets ONLY the guest authentication tokens, WebView cookies, and virtual device
 * identity salt for the specified game and user slot while keeping 100% of OBB files,
 * UE4/Unity Paks, downloaded maps, and HD resource packs untouched!
 */
@Keep
public final class GuestAccountManager {

    private GuestAccountManager() {
    }

    /**
     * Resets the guest account and rotates the virtual device identity for the slot
     * WITHOUT deleting any OBBs, Paks, or downloaded in-game maps.
     */
    public static boolean resetGuestAccountKeepResources(String packageName, int userId, boolean autoRelaunch) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        int slot = Math.max(0, userId);
        try {
            // 1. Backup current guest session automatically into "last_guest_auto_backup" before resetting
            FeaturesticAccountSwitcher.saveSessionSnapshot(packageName, slot, "last_guest_auto_backup");

            // 2. Stop running game process in that slot
            FeaturesticBoxCore.get().stopPackage(packageName, slot);

            // 3. Remove only auth/guest session files in internal dataDir (keep databases/Paks/OBBs intact)
            File internalData = BEnvironment.getDataDir(packageName, slot);
            if (internalData != null && internalData.isDirectory()) {
                FileUtils.deleteDir(new File(internalData, "shared_prefs"));
                FileUtils.deleteDir(new File(internalData, "app_webview"));
                FileUtils.deleteDir(new File(internalData, "app_textures"));
                File filesDir = new File(internalData, "files");
                if (filesDir.isDirectory()) {
                    String[] guestAuthFiles = new String[]{
                            "itop_login.txt",
                            "login-identifier.txt",
                            "vmpcloudconfig.json",
                            "gcloud_config.json",
                            "JWT_TOKEN"
                    };
                    for (String fName : guestAuthFiles) {
                        File f = new File(filesDir, fName);
                        if (f.isFile()) f.delete();
                    }
                }
            }

            // 4. Clean external UE4/Unity guest login token files without touching Paks or OBB
            File extData = BEnvironment.getExternalUserAppDataDir(slot, packageName);
            if (extData != null && extData.isDirectory()) {
                deleteGuestTokenFilesOnly(extData, 0);
            }

            // 5. Rotate virtual device identity (ANDROID_ID, GSF_ID, AdvertisingId, MediaDrmId, IMEI, Serial, MAC)
            Md5Utils.resetVirtualDeviceIdentity(slot);

            // 6. Ensure shared OBB is linked and relaunch if requested
            FeaturesticAccountSwitcher.ensureSharedObbForSlot(packageName, 0, slot);
            if (autoRelaunch) {
                return FeaturesticBoxCore.get().launchApk(packageName, slot);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void deleteGuestTokenFilesOnly(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c == null) continue;
            String name = c.getName();
            if ("Paks".equalsIgnoreCase(name) || "Movies".equalsIgnoreCase(name)) {
                continue; // Never touch game resource packs!
            }
            if (c.isDirectory()) {
                deleteGuestTokenFilesOnly(c, depth + 1);
            } else {
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.equals("itop_login.txt")
                        || lower.equals("device_id.xml")
                        || lower.equals("guest_info.json")) {
                    c.delete();
                }
            }
        }
    }
}
