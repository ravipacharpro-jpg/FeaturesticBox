package com.featurestic.box.core.env;

import android.app.Application;
import android.content.Context;
import android.os.Environment;

import com.featurestic.box.utils.compat.BuildCompat;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Locale;

import black.android.app.BRActivityThread;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.app.BActivityThread;

public class BEnvironment {

    private static volatile File sVBoxRoot;
    private static volatile File sExternalVBoxRoot;

    private static File resolveVBoxRoot() {
        if (sVBoxRoot != null) {
            return sVBoxRoot;
        }
        synchronized (BEnvironment.class) {
            if (sVBoxRoot != null) {
                return sVBoxRoot;
            }
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx == null) {
                try {
                    Object mainThread = FeaturesticBoxCore.mainThread();
                    if (mainThread != null) {
                        Application app = BRActivityThread.get(mainThread).mInitialApplication();
                        if (app != null) {
                            ctx = app;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (ctx != null && ctx.getFilesDir() != null) {
                sVBoxRoot = ctx.getFilesDir();
                return sVBoxRoot;
            }
            return new File("/data/local/tmp/featurestic_vbox");
        }
    }

    private static File resolveExternalVBoxRoot() {
        if (sExternalVBoxRoot != null) {
            return sExternalVBoxRoot;
        }
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                sExternalVBoxRoot = ext;
                return ext;
            }
        } catch (Throwable ignored) {
        }
        return new File("/storage/emulated/0");
    }

    public static void load() {
        FileUtils.mkdirs(getVBoxRoot());
        try {
            FileUtils.mkdirs(resolveExternalVBoxRoot());
        } catch (Throwable ignored) {
        }
        FileUtils.mkdirs(getSystemDir());
        FileUtils.mkdirs(getCacheDir());
        FileUtils.mkdirs(getProcDir());
    }

    public static File getVBoxRoot() {
        return resolveVBoxRoot();
    }

    public static ArrayList<String> getAllDex(String str) {
        File appDir = getAppDir(str);
        ArrayList<String> arrayList = new ArrayList<>();
        File[] listFiles = appDir.listFiles((FilenameFilter) new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && name.endsWith(".apk");
            }
        });
        if (listFiles != null) {
            for (File file : listFiles) {
                if (BuildCompat.isUpsideDownCake()) {
                    try {
                        file.setReadOnly();
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                }
                arrayList.add(file.getAbsolutePath());
            }
        }
        return arrayList;
    }

    public static File getSystemDir() {
        return new File(getVBoxRoot(), "system");
    }

    public static File getProcDir() {
        return new File(getVBoxRoot(), "proc");
    }

    public static File getCacheDir() {
        return new File(getVBoxRoot(), "cache");
    }

    public static File getUserDir(int userId) {
        return new File(getVBoxRoot(), String.format(Locale.CHINA, "data/user/%d", userId));
    }

    public static File getDeDataDir(String packageName, int userId) {
        return new File(getVBoxRoot(), String.format(Locale.CHINA, "data/user_de/%d/%s", userId, packageName));
    }

    public static File getDataDir(String packageName, int userId) {
        return new File(getVBoxRoot(), String.format(Locale.CHINA, "data/user/%d/%s", userId, packageName));
    }

    public static File getAppDir(String packageName) {
        return new File(getVBoxRoot(), "data/app/" + packageName);
    }

    public static File getBaseApkDir(String packageName) {
        return new File(getVBoxRoot(), "data/app/" + packageName + "/base.apk");
    }

    public static File getUserInfoConf() {
        return new File(getSystemDir(), "user.conf");
    }

    public static File getAccountsConf() {
        return new File(getSystemDir(), "accounts.conf");
    }

    public static File getUidConf() {
        return new File(getSystemDir(), "uid.conf");
    }

    public static File getSharedUserConf() {
        return new File(getSystemDir(), "shared-user.conf");
    }

    public static File getXPModuleConf() {
        return new File(getSystemDir(), "xposed-module.conf");
    }

    public static File getFakeLocationConf() {
        return new File(getSystemDir(), "fake-location.conf");
    }

    public static File getFakeDeviceConf() {
        return new File(getSystemDir(), "fake-device.conf");
    }

    public static File getPackageConf(String packageName) {
        return new File(getAppDir(packageName), "package.conf");
    }

    public static File getExternalStorageDirectory() {
        File legacySdCard = new File(resolveExternalVBoxRoot(), "SdCard");
        try {
            if (legacySdCard.isDirectory() && legacySdCard.canWrite()) {
                return legacySdCard;
            }
            if (legacySdCard.mkdirs() && legacySdCard.canWrite()) {
                return legacySdCard;
            }
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                File appExt = ctx.getExternalFilesDir("SdCard");
                if (appExt != null) {
                    FileUtils.mkdirs(appExt);
                    return appExt;
                }
            }
        } catch (Throwable ignored) {
        }
        return legacySdCard;
    }

    public static File getHostExternalDataDir(String packageName) {
        return new File(resolveExternalVBoxRoot(), String.format(Locale.CHINA, "Android/data/%s", packageName));
    }

    public static File getHostExternalObbDir(String packageName) {
        return new File(resolveExternalVBoxRoot(), String.format(Locale.CHINA, "Android/obb/%s", packageName));
    }

    public static File getExternalDataDir(String packageName) {
        return new File(getExternalStorageDirectory(), String.format(Locale.CHINA, "Android/data/%s", packageName));
    }

    public static File getExternalObbDir(String packageName) {
        return new File(getExternalStorageDirectory(), String.format(Locale.CHINA, "Android/obb/%s/", packageName));
    }

    public static File getExternalUserAppDataDir(int userId, String packageName) {
        if (userId <= 0) {
            return getExternalDataDir(packageName);
        }
        return new File(getExternalStorageDirectory(),
                String.format(Locale.CHINA, "users/%d/Android/data/%s", userId, packageName));
    }

    public static File getExternalUserAppObbDir(int userId, String packageName) {
        if (userId <= 0) {
            return getExternalObbDir(packageName);
        }
        return new File(getExternalStorageDirectory(),
                String.format(Locale.CHINA, "users/%d/Android/obb/%s", userId, packageName));
    }

    public static File getProcDir(int pid) {
        File file = new File(getProcDir(), String.format(Locale.CHINA, "%d", pid));
        FileUtils.mkdirs(file);
        return file;
    }

    public static File getExternalDataFilesDir(String packageName) {
        return new File(getExternalDataDir(packageName), "files");
    }

    public static File getDataFilesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "files");
    }

    public static File getExternalDataCacheDir(String packageName) {
        return new File(getExternalDataDir(packageName), "cache");
    }

    public static File getDataCacheDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "cache");
    }

    public static File getDataLibDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "lib");
    }

    public static File getDataDatabasesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "databases");
    }

    public static File getAppRootDir() {
        return getAppDir("");
    }

    public static File getAppLibDir(String packageName) {
        return new File(getAppDir(packageName), "lib");
    }

    public static File getXSharedPreferences(String packageName, String prefFileName) {
        return new File(BEnvironment.getDataDir(packageName, BActivityThread.getUserId()), "shared_prefs/" + prefFileName + ".xml");
    }
}
