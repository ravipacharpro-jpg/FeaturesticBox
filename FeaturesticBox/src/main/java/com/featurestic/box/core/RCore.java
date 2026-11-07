package com.featurestic.box.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.TextUtils;
import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.TrieTree;

@SuppressLint("SdCardPath")
public class RCore {
    public static final String TAG = "RCore";

    private static final RCore sRCore = new RCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    public static RCore get() {
        return sRCore;
    }

    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        RNative.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.startsWith("/system/fonts/")
                || path.startsWith("/system/framework/")
                || path.startsWith("/apex/")
                || path.startsWith("/vendor/firmware/")
                || path.contains("/SdCard/")
                || path.contains("/files/data/")
                || path.contains("/files/user_de/")) {
            return path;
        }
        if (path.startsWith("/proc/") && path.endsWith("/cmdline") && !path.startsWith("/proc/self/")) {
            int secondSlash = path.indexOf('/', 6);
            if (secondSlash > 6) {
                String pidSegment = path.substring(6, secondSlash);
                File fakeCmd = new File(BEnvironment.getProcDir(), pidSegment + "/cmdline");
                if (fakeCmd.isFile()) {
                    return fakeCmd.getAbsolutePath();
                }
            }
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return path;

        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key)) {
            String target = mRedirectMap.get(key);
            if (target != null && !path.startsWith(target)) {
                if (path.startsWith(key)) {
                    path = target + path.substring(key.length());
                } else {
                    int idx = path.indexOf(key);
                    if (idx >= 0) {
                        path = path.substring(0, idx) + target + path.substring(idx + key.length());
                    }
                }
            }
        }
        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        String redirected = redirectPath(pathStr);
        if (redirected == null || redirected.equals(pathStr)) {
            return path;
        }
        return new File(redirected);
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = FeaturesticBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BActivityThread.getUserId());
            int systemUserId = FeaturesticBoxCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/0/%s", packageName), packageInfo.dataDir);
            String deDataDir = BEnvironment.getDeDataDir(packageName, BActivityThread.getUserId()).getAbsolutePath();
            rule.put(String.format("/data/user_de/%d/%s", systemUserId, packageName), deDataDir);
            rule.put(String.format("/data/user_de/0/%s", packageName), deDataDir);
            for (String subDir : new String[]{"files", "cache", "code_cache", "databases", "shared_prefs", "app_webview", "files/ano_tmp"}) {
                FileUtils.mkdirs(new File(packageInfo.dataDir, subDir));
                FileUtils.mkdirs(new File(deDataDir, subDir));
            }
            FileUtils.mkdirs(BEnvironment.getExternalDataFilesDir(packageName));
            FileUtils.mkdirs(BEnvironment.getExternalDataCacheDir(packageName));
            FileUtils.mkdirs(BEnvironment.getExternalObbDir(packageName));

            // Ensure ART profile directories exist and redirect both current and reference profiles
            File profilesRoot = new File(BEnvironment.getVBoxRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", BActivityThread.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", BActivityThread.getUserId(), packageName));
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", BActivityThread.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", BActivityThread.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            File external = BEnvironment.getExternalStorageDirectory();
            FileUtils.mkdirs(external);

            // Instant Zero-Copy OBB Redirection (0ms, no multi-GB main-thread file copy!)
            File virtualObbDir = BEnvironment.getExternalObbDir(packageName);
            File hostObbDir = BEnvironment.getHostExternalObbDir(packageName);
            File[] hObbs = hostObbDir.isDirectory() ? hostObbDir.listFiles((dir, name) -> name != null && name.endsWith(".obb")) : null;
            if (hObbs != null && hObbs.length > 0) {
                // Remove any partial/corrupted OBB copy in virtualObbDir whose size differs from hostObbDir
                File[] vObbsCheck = virtualObbDir.listFiles((dir, name) -> name != null && name.endsWith(".obb"));
                if (vObbsCheck != null) {
                    for (File vObb : vObbsCheck) {
                        File matchingHost = new File(hostObbDir, vObb.getName());
                        if (matchingHost.isFile() && vObb.length() != matchingHost.length()) {
                            vObb.delete();
                        }
                    }
                }
            }
            File[] vObbs = virtualObbDir.listFiles((dir, name) -> name != null && name.endsWith(".obb") && new File(dir, name).length() > 0);
            if ((vObbs == null || vObbs.length == 0) && hObbs != null && hObbs.length > 0) {
                String hostObbPath = hostObbDir.getAbsolutePath();
                rule.put("/sdcard/Android/obb/" + packageName, hostObbPath);
                rule.put("/mnt/sdcard/Android/obb/" + packageName, hostObbPath);
                rule.put("/storage/self/primary/Android/obb/" + packageName, hostObbPath);
                rule.put("/storage/emulated/0/Android/obb/" + packageName, hostObbPath);
                rule.put(String.format("/storage/emulated/%d/Android/obb/%s", systemUserId, packageName), hostObbPath);
            }

            rule.put("/sdcard", external.getAbsolutePath());
            rule.put("/mnt/sdcard", external.getAbsolutePath());
            rule.put("/storage/self/primary", external.getAbsolutePath());
            rule.put("/storage/emulated/0", external.getAbsolutePath());
            rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());
            blackRule.add("/sdcard/Pictures");
            blackRule.add("/storage/emulated/0/Pictures");
            blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            if (FeaturesticBoxCore.get().setHideRoot() && com.featurestic.box.core.system.am.BActivityManagerService.isProtectedGamePackage(packageName)) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        RNative.enableIO();
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
        rule.put("/sbin/magisk", "/sbin/magisk-fake");
        rule.put("/system/bin/magisk", "/system/bin/magisk-fake");
        rule.put("/data/adb/magisk", "/data/adb/magisk-fake");
        rule.put("/data/adb/ksud", "/data/adb/ksud-fake");
        rule.put("/data/adb/ap", "/data/adb/ap-fake");
        rule.put("/data/adb/modules", "/data/adb/modules-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = BActivityThread.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);
        File procRoot = BEnvironment.getProcDir();
        File[] procDirs = procRoot != null ? procRoot.listFiles() : null;
        if (procDirs != null) {
            for (File dir : procDirs) {
                if (dir != null && dir.isDirectory()) {
                    File subCmd = new File(dir, "cmdline");
                    if (subCmd.isFile()) {
                        rule.put("/proc/" + dir.getName() + "/cmdline", subCmd.getAbsolutePath());
                    }
                }
            }
        }
    }
}
