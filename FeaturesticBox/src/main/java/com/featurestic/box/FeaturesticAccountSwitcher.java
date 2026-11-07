package com.featurestic.box;

import android.os.SystemClock;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.user.BUserInfo;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Md5Utils;

/**
 * 1-Click Multi-Account Profile Switcher & Session Snapshot Manager.
 */
@Keep
public final class FeaturesticAccountSwitcher {

    private FeaturesticAccountSwitcher() {
    }

    private static File getSlotsConfigFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_account_slots.properties");
    }

    private static Properties loadProps() {
        Properties p = new Properties();
        File f = getSlotsConfigFile();
        if (f.isFile() && f.length() > 0) {
            try (FileInputStream fis = new FileInputStream(f)) {
                p.load(fis);
            } catch (Throwable ignored) {
            }
        }
        return p;
    }

    private static void saveProps(Properties p) {
        try {
            File f = getSlotsConfigFile();
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f)) {
                p.store(fos, "FeaturesticBox Multi-Account Slots");
            }
        } catch (Throwable ignored) {
        }
    }

    public static int getActiveAccountSlot(String packageName) {
        Properties p = loadProps();
        String key = "active_slot." + (packageName != null ? packageName : "default");
        try {
            return Integer.parseInt(p.getProperty(key, "0"));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setAccountSlotLabel(int userId, String label) {
        Properties p = loadProps();
        p.setProperty("slot_label." + Math.max(0, userId), label != null ? label.trim() : ("Slot #" + userId));
        saveProps(p);
    }

    public static String getAccountSlotLabel(int userId) {
        Properties p = loadProps();
        return p.getProperty("slot_label." + Math.max(0, userId), userId == 0 ? "Main Account (Slot 0)" : ("Account Slot #" + userId));
    }

    public static List<BUserInfo> getAvailableAccountSlots() {
        try {
            List<BUserInfo> users = FeaturesticBoxCore.get().getUsers();
            return users != null ? users : new ArrayList<>();
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static boolean switchAccountSlot(String packageName, int targetUserId, boolean autoLaunch) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        int targetSlot = Math.max(0, targetUserId);
        int previousSlot = getActiveAccountSlot(packageName);
        try {
            FeaturesticBoxCore core = FeaturesticBoxCore.get();
            if (previousSlot != targetSlot) {
                try {
                    core.stopPackage(packageName, previousSlot);
                } catch (Throwable ignored) {
                }
            }
            core.createUser(targetSlot);
            if (!core.isInstalled(packageName, targetSlot)) {
                core.installPackageAsUser(packageName, targetSlot);
            }
            ensureSharedObbForSlot(packageName, previousSlot, targetSlot);

            Properties p = loadProps();
            p.setProperty("active_slot." + packageName, String.valueOf(targetSlot));
            saveProps(p);

            if (autoLaunch) {
                SystemClock.sleep(120L);
                return core.launchApk(packageName, targetSlot);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Ensures the target user slot has immediate access to the game's OBB files via symlink
     * (or relies on RCore Zero-Copy IO Redirection when FUSE rejects symlinks).
     */
    public static void ensureSharedObbForSlot(String packageName, int sourceUserId, int targetUserId) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            File targetObbDir = BEnvironment.getExternalUserAppObbDir(targetUserId, packageName);
            FileUtils.mkdirs(targetObbDir);
            File[] candidates = new File[]{
                    BEnvironment.getExternalUserAppObbDir(Math.max(0, sourceUserId), packageName),
                    BEnvironment.getExternalUserAppObbDir(0, packageName),
                    new File("/sdcard/Android/obb/" + packageName)
            };
            for (File srcDir : candidates) {
                if (srcDir == null || !srcDir.isDirectory() || srcDir.equals(targetObbDir)) {
                    continue;
                }
                File[] obbs = srcDir.listFiles();
                if (obbs == null) continue;
                for (File obb : obbs) {
                    if (obb != null && obb.isFile() && obb.getName().endsWith(".obb") && obb.length() > 0) {
                        File dst = new File(targetObbDir, obb.getName());
                        if (!dst.exists()) {
                            try {
                                android.system.Os.symlink(obb.getAbsolutePath(), dst.getAbsolutePath());
                            } catch (Throwable ignored) {
                                // Never block the UI thread copying a 2GB OBB when FUSE rejects symlinks;
                                // RCore redirects /Android/obb/<pkg> directly to hostObbDir!
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean saveSessionSnapshot(String packageName, int userId, String snapshotTag) {
        if (packageName == null || snapshotTag == null || snapshotTag.trim().isEmpty()) {
            return false;
        }
        try {
            String safeTag = snapshotTag.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
            File snapshotRoot = new File(BEnvironment.getSystemDir(),
                    "account_snapshots/" + packageName + "_u" + Math.max(0, userId) + "/" + safeTag);
            FileUtils.deleteDir(snapshotRoot);
            FileUtils.mkdirs(snapshotRoot);

            File appDataDir = BEnvironment.getDataDir(packageName, userId);
            File prefsDir = new File(appDataDir, "shared_prefs");
            if (prefsDir.isDirectory()) {
                FileUtils.copyFile(prefsDir, new File(snapshotRoot, "shared_prefs"));
            }
            File filesDir = new File(appDataDir, "files");
            if (filesDir.isDirectory()) {
                File loginXml = new File(filesDir, "itop_login.txt");
                if (loginXml.isFile()) {
                    File dstFiles = new File(snapshotRoot, "files");
                    FileUtils.mkdirs(dstFiles);
                    FileUtils.copyFile(loginXml, new File(dstFiles, "itop_login.txt"));
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean restoreSessionSnapshot(String packageName, int userId, String snapshotTag, boolean autoLaunch) {
        if (packageName == null || snapshotTag == null || snapshotTag.trim().isEmpty()) {
            return false;
        }
        try {
            String safeTag = snapshotTag.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
            File snapshotRoot = new File(BEnvironment.getSystemDir(),
                    "account_snapshots/" + packageName + "_u" + Math.max(0, userId) + "/" + safeTag);
            if (!snapshotRoot.isDirectory()) {
                return false;
            }
            FeaturesticBoxCore.get().stopPackage(packageName, userId);

            File appDataDir = BEnvironment.getDataDir(packageName, userId);
            File snapPrefs = new File(snapshotRoot, "shared_prefs");
            if (snapPrefs.isDirectory()) {
                File targetPrefs = new File(appDataDir, "shared_prefs");
                FileUtils.deleteDir(targetPrefs);
                FileUtils.mkdirs(targetPrefs);
                FileUtils.copyFile(snapPrefs, targetPrefs);
            }
            File snapLogin = new File(snapshotRoot, "files/itop_login.txt");
            if (snapLogin.isFile()) {
                File targetFiles = new File(appDataDir, "files");
                FileUtils.mkdirs(targetFiles);
                FileUtils.copyFile(snapLogin, new File(targetFiles, "itop_login.txt"));
            }
            if (autoLaunch) {
                SystemClock.sleep(100L);
                return FeaturesticBoxCore.get().launchApk(packageName, userId);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String rotateDeviceIdentityForSlot(int userId) {
        return Md5Utils.resetVirtualDeviceIdentity(Math.max(0, userId));
    }
}
