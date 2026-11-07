package net_62v.external;

import java.io.File;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class MetaStorageManager {

    private static final String[] HOST_STORAGE_ROOTS = new String[]{
            "/storage/emulated/0",
            "/sdcard",
            "/storage/self/primary",
            "/mnt/sdcard",
            "/mnt/user/0/emulated/0",
            "/mnt/pass_through/0/emulated/0",
            "/mnt/installer/0/emulated/0",
            "/data/media/0"
    };

    private static final String[] KNOWN_GAME_DATA_SUBDIRS = new String[]{
            "files",
            "files/UE4Game",
            "files/ProgramBinaryCache",
            "files/il2cpp",
            "files/UnityCache"
    };

    /**
     * Returns the virtual container external storage root (e.g. /storage/emulated/0/SdCard).
     */
    public static File obtainAppExternalStorageDir() {
        File containerPath = BEnvironment.getExternalStorageDirectory();
        FileUtils.mkdirs(containerPath);
        return containerPath;
    }

    /**
     * Returns the virtual container OBB directory for a package (/SdCard/Android/obb/<packageName>).
     */
    public static File getObbContainerPath(String packageName) {
        try {
            File dir = BEnvironment.getExternalObbDir(packageName);
            FileUtils.mkdirs(dir);
            return dir;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the virtual container Data directory for a package (/SdCard/Android/data/<packageName>).
     */
    public static File getDataContainerPath(String packageName) {
        try {
            File dir = BEnvironment.getExternalDataDir(packageName);
            FileUtils.mkdirs(dir);
            FileUtils.mkdirs(new File(dir, "files"));
            FileUtils.mkdirs(new File(dir, "cache"));
            return dir;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static File getHostObbDir(String packageName) {
        return BEnvironment.getHostExternalObbDir(packageName);
    }

    public static File getHostDataDir(String packageName) {
        return BEnvironment.getHostExternalDataDir(packageName);
    }

    public static File getVirtualObbContainerPath(String packageName) {
        return getObbContainerPath(packageName);
    }

    public static File getVirtualDataContainerPath(String packageName) {
        return getDataContainerPath(packageName);
    }

    public static boolean syncObbToVirtualIfNeeded(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            File virtualObbDir = getVirtualObbContainerPath(packageName);
            boolean linkedAny = false;
            for (String root : HOST_STORAGE_ROOTS) {
                File hostObbDir = new File(root + "/Android/obb", packageName);
                if (!hostObbDir.exists()) continue;
                try {
                    if (hostObbDir.getCanonicalPath().equals(virtualObbDir.getCanonicalPath())) {
                        continue;
                    }
                } catch (Throwable ignored) {
                }
                File[] obbFiles = hostObbDir.listFiles();
                if (obbFiles == null || obbFiles.length == 0) continue;
                for (File srcFile : obbFiles) {
                    if (srcFile == null || !srcFile.isFile()) continue;
                    File dstFile = new File(virtualObbDir, srcFile.getName());
                    if (!dstFile.exists()) {
                        try {
                            android.system.Os.symlink(srcFile.getAbsolutePath(), dstFile.getAbsolutePath());
                            linkedAny = true;
                        } catch (Throwable ignored) {
                            // On FUSE where symlink is not permitted, RCore Zero-Copy IO Redirection
                            // automatically maps /Android/obb/<pkg> directly to hostObbDir in 0ms!
                            linkedAny = true;
                        }
                    }
                }
                if (linkedAny) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean syncDataToVirtualIfNeeded(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            File virtualDataDir = getVirtualDataContainerPath(packageName);
            FileUtils.mkdirs(new File(virtualDataDir, "files"));
            FileUtils.mkdirs(new File(virtualDataDir, "cache"));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean syncAllToVirtual(String packageName) {
        boolean obb = syncObbToVirtualIfNeeded(packageName);
        boolean data = syncDataToVirtualIfNeeded(packageName);
        return obb || data;
    }

    public static boolean cleanVirtualAppCache(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            File internalData = BEnvironment.getDataDir(packageName, 0);
            FileUtils.deleteDir(new File(internalData, "cache"));
            FileUtils.deleteDir(new File(internalData, "code_cache"));
            File externalCache = BEnvironment.getExternalDataCacheDir(packageName);
            FileUtils.deleteDir(externalCache);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
