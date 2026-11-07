package com.featurestic.box.utils;

import android.os.Build;

import java.io.File;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.featurestic.box.FeaturesticBoxCore;

public class AbiUtils {
    private final Set<String> mLibs = new HashSet<>();
    private final long mLastModified;
    private static final Map<String, AbiUtils> sAbiUtilsMap = new ConcurrentHashMap<>();

    public static boolean isSupport(File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            return false;
        }
        String key = apkFile.getAbsolutePath();
        long lastMod = apkFile.lastModified();
        AbiUtils abiUtils = sAbiUtilsMap.get(key);
        if (abiUtils == null || abiUtils.mLastModified != lastMod) {
            abiUtils = new AbiUtils(apkFile);
            sAbiUtilsMap.put(key, abiUtils);
        }
        if (abiUtils.isEmptyAib()) {
            return true;
        }
        if (Build.SUPPORTED_ABIS != null) {
            for (String supportedAbi : Build.SUPPORTED_ABIS) {
                if (abiUtils.mLibs.contains(supportedAbi)) {
                    return true;
                }
            }
        }
        if (FeaturesticBoxCore.is64Bit()) {
            return abiUtils.is64Bit() || abiUtils.is32Bit();
        } else {
            return abiUtils.is32Bit() || abiUtils.is64Bit();
        }
    }

    public AbiUtils(File apkFile) {
        this.mLastModified = apkFile != null ? apkFile.lastModified() : 0L;
        scanSingleApk(apkFile);
        if (apkFile != null) {
            File parent = apkFile.getParentFile();
            if (parent != null && parent.isDirectory()) {
                File[] splits = parent.listFiles((dir, name) ->
                        name != null && name.endsWith(".apk") && !name.equals(apkFile.getName()));
                if (splits != null) {
                    for (File split : splits) {
                        scanSingleApk(split);
                    }
                }
            }
        }
    }

    private void scanSingleApk(File apk) {
        if (apk == null || !apk.isFile()) return;
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apk);
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String name = zipEntry.getName();
                if (name.startsWith("lib/arm64-v8a/")) {
                    mLibs.add("arm64-v8a");
                } else if (name.startsWith("lib/armeabi-v7a/")) {
                    mLibs.add("armeabi-v7a");
                } else if (name.startsWith("lib/armeabi/")) {
                    mLibs.add("armeabi");
                } else if (name.startsWith("lib/x86_64/")) {
                    mLibs.add("x86_64");
                } else if (name.startsWith("lib/x86/")) {
                    mLibs.add("x86");
                }
            }
        } catch (Throwable ignored) {
        } finally {
            CloseUtils.close(zipFile);
        }
    }

    public boolean is64Bit() {
        return mLibs.contains("arm64-v8a") || mLibs.contains("x86_64");
    }

    public boolean is32Bit() {
        return mLibs.contains("armeabi") || mLibs.contains("armeabi-v7a") || mLibs.contains("x86");
    }

    public boolean isEmptyAib() {
        return mLibs.isEmpty();
    }

    public Set<String> getDetectedAbis() {
        return Collections.unmodifiableSet(mLibs);
    }
}
