package com.featurestic.box.utils;

import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.featurestic.box.FeaturesticBoxCore;

public class NativeUtils {
    public static final String TAG = "VirtualM";

    public static List<String> getPreferredAbiOrder() {
        Set<String> ordered = new LinkedHashSet<>();
        if (FeaturesticBoxCore.is64Bit()) {
            if (Build.SUPPORTED_64_BIT_ABIS != null) {
                for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                    if (abi != null && !abi.isEmpty()) ordered.add(abi);
                }
            }
            ordered.add("arm64-v8a");
            ordered.add("x86_64");
            if (Build.SUPPORTED_32_BIT_ABIS != null) {
                for (String abi : Build.SUPPORTED_32_BIT_ABIS) {
                    if (abi != null && !abi.isEmpty()) ordered.add(abi);
                }
            }
            ordered.add("armeabi-v7a");
            ordered.add("armeabi");
            ordered.add("x86");
        } else {
            if (Build.SUPPORTED_32_BIT_ABIS != null) {
                for (String abi : Build.SUPPORTED_32_BIT_ABIS) {
                    if (abi != null && !abi.isEmpty()) ordered.add(abi);
                }
            }
            ordered.add("armeabi-v7a");
            ordered.add("armeabi");
            ordered.add("x86");
            if (Build.SUPPORTED_64_BIT_ABIS != null) {
                for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
                    if (abi != null && !abi.isEmpty()) ordered.add(abi);
                }
            }
            ordered.add("arm64-v8a");
            ordered.add("x86_64");
        }
        return new ArrayList<>(ordered);
    }

    public static void copyNativeLib(File apk, File nativeLibDir) throws Exception {
        long startTime = System.currentTimeMillis();
        if (!nativeLibDir.exists()) {
            nativeLibDir.mkdirs();
        }
        try (ZipFile zipfile = new ZipFile(apk.getAbsolutePath())) {
            for (String abi : getPreferredAbiOrder()) {
                if (findAndCopyNativeLib(zipfile, abi, nativeLibDir)) {
                    return;
                }
            }
        } finally {
            Log.d(TAG, "Done! +" + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    private static void applyLibraryPermissions(File libFile) {
        if (libFile == null || !libFile.exists()) return;
        try {
            libFile.setReadable(true, false);
            libFile.setExecutable(true, false);
            if (Build.VERSION.SDK_INT >= 34) {
                libFile.setWritable(false, false);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean findAndCopyNativeLib(ZipFile zipfile, String cpuArch, File nativeLibDir) throws Exception {
        if (cpuArch == null || cpuArch.isEmpty()) return false;
        boolean findLib = false;
        boolean findSo = false;
        byte[] buffer = null;
        String libPrefix = "lib/" + cpuArch + "/";
        ZipEntry entry;
        Enumeration<? extends ZipEntry> e = zipfile.entries();

        while (e.hasMoreElements()) {
            entry = e.nextElement();
            String entryName = entry.getName();
            if (!findLib && !entryName.startsWith("lib/")) {
                continue;
            }
            findLib = true;
            if (!entryName.endsWith(".so") || !entryName.startsWith(libPrefix)) {
                continue;
            }

            if (buffer == null) {
                findSo = true;
                buffer = new byte[65536];
            }

            String libName = entryName.substring(entryName.lastIndexOf('/') + 1);
            File libFile = new File(nativeLibDir, libName);
            if (libFile.exists() && libFile.length() == entry.getSize()) {
                applyLibraryPermissions(libFile);
                continue;
            }
            if (libFile.exists()) {
                try {
                    libFile.setWritable(true, true);
                } catch (Throwable ignored) {
                }
            }
            FileOutputStream fos = new FileOutputStream(libFile);
            copySo(buffer, zipfile.getInputStream(entry), fos);
            applyLibraryPermissions(libFile);
        }

        if (!findLib) {
            return true;
        }

        return findSo;
    }

    private static void copySo(byte[] buffer, InputStream input, OutputStream output) throws IOException {
        BufferedInputStream bufferedInput = new BufferedInputStream(input);
        BufferedOutputStream bufferedOutput = new BufferedOutputStream(output);
        int count;

        while ((count = bufferedInput.read(buffer)) > 0) {
            bufferedOutput.write(buffer, 0, count);
        }
        bufferedOutput.flush();
        bufferedOutput.close();
        output.close();
        bufferedInput.close();
        input.close();
    }
}
