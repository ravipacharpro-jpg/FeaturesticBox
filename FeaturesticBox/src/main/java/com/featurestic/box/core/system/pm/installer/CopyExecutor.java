package com.featurestic.box.core.system.pm.installer;

import java.io.File;
import java.io.IOException;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.NativeUtils;
import com.featurestic.box.utils.compat.BuildCompat;

public class CopyExecutor implements Executor {

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        try {
            File baseApk = new File(ps.pkg.baseCodePath);
            File appLibDir = BEnvironment.getAppLibDir(ps.pkg.packageName);
            NativeUtils.copyNativeLib(baseApk, appLibDir);
            File parentDir = baseApk.getParentFile();
            if (parentDir != null && parentDir.isDirectory() && !parentDir.equals(BEnvironment.getAppDir(ps.pkg.packageName))) {
                File[] splitApks = parentDir.listFiles((dir, name) ->
                        name != null && name.endsWith(".apk") && !name.equals(baseApk.getName()));
                if (splitApks != null) {
                    for (File splitApk : splitApks) {
                        try {
                            NativeUtils.copyNativeLib(splitApk, appLibDir);
                            if (option.isFlag(InstallOption.FLAG_STORAGE) && !option.isFlag(InstallOption.FLAG_URI_FILE)) {
                                File dstSplit = new File(BEnvironment.getAppDir(ps.pkg.packageName), splitApk.getName());
                                if (!dstSplit.exists() || dstSplit.length() != splitApk.length()) {
                                    FileUtils.copyFile(splitApk, dstSplit);
                                    if (BuildCompat.isUpsideDownCake()) {
                                        try {
                                            dstSplit.setReadOnly();
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (!option.isFlag(InstallOption.FLAG_SYSTEM)) {
                return -1;
            }
        }
        if (option.isFlag(InstallOption.FLAG_STORAGE)) {
            File origFile = new File(ps.pkg.baseCodePath);
            File newFile = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            try {
                boolean alreadySynced = !option.isFlag(InstallOption.FLAG_URI_FILE)
                        && newFile.isFile()
                        && origFile.isFile()
                        && newFile.length() > 0
                        && newFile.length() == origFile.length()
                        && Math.abs(newFile.lastModified() - origFile.lastModified()) <= 2000L;
                if ((!origFile.getAbsolutePath().equals(newFile.getAbsolutePath()) || !newFile.isFile()) && !alreadySynced) {
                    if (newFile.exists()) {
                        try {
                            newFile.setWritable(true, true);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                        boolean b = FileUtils.renameTo(origFile, newFile);
                        if (!b) {
                            FileUtils.copyFile(origFile, newFile);
                        }
                    } else {
                        FileUtils.copyFile(origFile, newFile);
                        try {
                            newFile.setLastModified(origFile.lastModified());
                        } catch (Throwable ignored) {
                        }
                    }
                    if (BuildCompat.isUpsideDownCake()) {
                        try {
                            newFile.setReadOnly();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                ps.pkg.baseCodePath = newFile.getAbsolutePath();
            } catch (IOException e) {
                e.printStackTrace();
                return -1;
            }
        }
        return 0;
    }
}
