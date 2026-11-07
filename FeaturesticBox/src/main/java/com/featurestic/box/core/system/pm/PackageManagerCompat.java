package com.featurestic.box.core.system.pm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

import black.android.content.pm.BRApplicationInfoL;
import black.android.content.pm.BRApplicationInfoN;
import black.android.content.pm.BRPackageParserSigningDetails;
import black.android.content.pm.BRSigningInfo;
import black.android.content.res.BRAssetManager;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.GmsCore;
import com.featurestic.box.core.env.AppSystemEnv;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.ArrayUtils;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/15/21.
 */
@SuppressLint({"SdCardPath", "NewApi"})
public class PackageManagerCompat {

    public static PackageInfo generatePackageInfo(BPackageSettings ps, int flags, BPackageUserState state, int userId) {
        if (ps == null) {
            return null;
        }
        BPackage p = ps.pkg;
        if (p != null) {
            PackageInfo packageInfo = null;
            try {
                packageInfo = generatePackageInfo(p, flags, 0, 0, state, userId);
            } catch (Throwable ignored) {
            }
            return packageInfo;
        }
        return null;
    }

    public static PackageInfo generatePackageInfo(BPackage p, int flags, long firstInstallTime, long lastUpdateTime, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }

        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);

        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (!p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }

        if ((flags & FileUtils.FileMode.MODE_IRUSR) != 0) {
            pi.gids = new int[0];
        }

        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            pi.activities = null;
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.activities.get(i);
                    ActivityInfo activityInfo = generateActivityInfo(a, flags, state, userId);
                    if (activityInfo != null) {
                        res[num++] = activityInfo;
                    }
                }
                pi.activities = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            pi.receivers = null;
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.receivers.get(i);
                    ActivityInfo receiverInfo = generateActivityInfo(a, flags, state, userId);
                    if (receiverInfo != null) {
                        res[num++] = receiverInfo;
                    }
                }
                pi.receivers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            pi.services = null;
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Service s = p.services.get(i);
                    ServiceInfo serviceInfo = generateServiceInfo(s, flags, state, userId);
                    if (serviceInfo != null) {
                        res[num++] = serviceInfo;
                    }
                }
                pi.services = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            pi.providers = null;
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Provider pr = p.providers.get(i);
                    ProviderInfo providerInfo = generateProviderInfo(pr, flags, state, userId);
                    if (providerInfo != null) {
                        res[num++] = providerInfo;
                    }
                }
                pi.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            pi.instrumentation = null;
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            pi.permissions = null;
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            pi.requestedPermissions = null;
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i = 0; i < N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                }
            }
        }
        PackageInfo base = null;
        try {
            base = FeaturesticBoxCore.getContext().getPackageManager().getPackageInfo(p.packageName, flags);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (base == null
                && p.baseCodePath != null
                && ((flags & PackageManager.GET_SIGNATURES) != 0
                || (BuildCompat.isPie() && (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0))) {
            try {
                base = FeaturesticBoxCore.getContext().getPackageManager().getPackageArchiveInfo(p.baseCodePath, flags);
            } catch (Throwable ignored) {
            }
        }
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (base != null && base.signatures != null && base.signatures.length > 0) {
                pi.signatures = base.signatures;
            } else if (p.mSignatures != null && p.mSignatures.length > 0) {
                pi.signatures = p.mSignatures;
            } else if (p.mSigningDetails != null && p.mSigningDetails.signatures != null) {
                pi.signatures = p.mSigningDetails.signatures;
            }
        }
        if (BuildCompat.isPie()) {
            if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
                try {
                    if (base != null && base.signingInfo != null) {
                        pi.signingInfo = base.signingInfo;
                    } else {
                        android.content.pm.Signature[] sigs = (p.mSigningDetails != null && p.mSigningDetails.signatures != null)
                                ? p.mSigningDetails.signatures : p.mSignatures;
                        if (sigs != null && sigs.length > 0) {
                            PackageParser.SigningDetails signingDetails = PackageParser.SigningDetails.UNKNOWN;
                            BRPackageParserSigningDetails.get(signingDetails)._set_signatures(sigs);
                            pi.signingInfo = BRSigningInfo.get()._new(signingDetails);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return pi;
    }

    public static ActivityInfo generateActivityInfo(BPackage.Activity a, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, a.info.applicationInfo)) {
            return null;
        }
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData;
        ai.processName = BPackageManagerService.fixProcessName(ai.packageName, ai.processName);
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        return GmsCore.applyGeneratedVirtualActivityGmsSafety(ai);
    }

    public static ServiceInfo generateServiceInfo(BPackage.Service s, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, s.info.applicationInfo)) {
            return null;
        }
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData;
        si.processName = BPackageManagerService.fixProcessName(si.packageName, si.processName);
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        return GmsCore.applyGeneratedVirtualServiceGmsSafety(si);
    }

    public static ProviderInfo generateProviderInfo(BPackage.Provider p, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.info.applicationInfo)) {
            return null;
        }
        ProviderInfo pi = new ProviderInfo(p.info);
        if (pi.authority == null) return null;
        pi.metaData = p.metaData;
        pi.processName = BPackageManagerService.fixProcessName(pi.packageName, pi.processName);
        if ((flags & FileUtils.FileMode.MODE_ISUID) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        return GmsCore.applyGeneratedVirtualProviderGmsSafety(pi);
    }

    public static PermissionInfo generatePermissionInfo(BPackage.Permission p, int flags) {
        if (p == null) return null;
        if ((flags & FileUtils.FileMode.MODE_IWUSR) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(BPackage.Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static ApplicationInfo generateApplicationInfo(BPackage p, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }
        ApplicationInfo baseApplication;
        try {
            baseApplication = FeaturesticBoxCore.getPackageManager().getApplicationInfo(FeaturesticBoxCore.getHostPkg(), flags);
        } catch (Exception e) {
            return null;
        }
        String sourceDir = p.baseCodePath;
        if (p.applicationInfo == null) {
            PackageInfo archivePi = FeaturesticBoxCore.getPackageManager().getPackageArchiveInfo(sourceDir, 0);
            if (archivePi != null && archivePi.applicationInfo != null) {
                p.applicationInfo = archivePi.applicationInfo;
            } else {
                p.applicationInfo = new ApplicationInfo();
                p.applicationInfo.packageName = p.packageName;
            }
        }
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }

        GmsCore.applyGeneratedVirtualAppGmsSafety(ai);

        ai.dataDir = BEnvironment.getDataDir(ai.packageName, userId).getAbsolutePath();
        java.io.File appLibDir = BEnvironment.getAppLibDir(ai.packageName);
        java.io.File[] extractedLibs = appLibDir.listFiles();
        if (!p.installOption.isFlag(InstallOption.FLAG_SYSTEM) || (extractedLibs != null && extractedLibs.length > 0)) {
            ai.nativeLibraryDir = appLibDir.getAbsolutePath();
        }
        ai.processName = BPackageManagerService.fixProcessName(p.packageName, ai.packageName);
        ai.publicSourceDir = sourceDir;
        ai.sourceDir = sourceDir;
        ai.uid = p.mExtras.appId;

        if (BuildCompat.isL()) {
            String resolvedPrimaryAbi;
            if (FeaturesticBoxCore.is64Bit()) {
                resolvedPrimaryAbi = (Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0)
                        ? Build.SUPPORTED_64_BIT_ABIS[0] : "arm64-v8a";
            } else {
                resolvedPrimaryAbi = (Build.SUPPORTED_32_BIT_ABIS != null && Build.SUPPORTED_32_BIT_ABIS.length > 0)
                        ? Build.SUPPORTED_32_BIT_ABIS[0] : "armeabi-v7a";
            }
            BRApplicationInfoL.get(ai)._set_primaryCpuAbi(resolvedPrimaryAbi);
            String gameCodeDir = sourceDir != null ? new java.io.File(sourceDir).getParent() : null;
            if (gameCodeDir != null) {
                BRApplicationInfoL.get(ai)._set_scanPublicSourceDir(gameCodeDir);
                BRApplicationInfoL.get(ai)._set_scanSourceDir(gameCodeDir);
            }
            try {
                ApplicationInfo hostInstalled = FeaturesticBoxCore.getPackageManager().getApplicationInfo(p.packageName, 0);
                if (hostInstalled != null && hostInstalled.splitSourceDirs != null && hostInstalled.splitSourceDirs.length > 0) {
                    ai.splitSourceDirs = hostInstalled.splitSourceDirs;
                    ai.splitPublicSourceDirs = hostInstalled.splitPublicSourceDirs != null
                            ? hostInstalled.splitPublicSourceDirs : hostInstalled.splitSourceDirs;
                }
            } catch (Throwable ignored) {
            }
            if (ai.splitSourceDirs == null || ai.splitSourceDirs.length == 0) {
                try {
                    java.io.File vAppDir = BEnvironment.getAppDir(p.packageName);
                    java.io.File[] localSplits = vAppDir.listFiles((dir, name) ->
                            name != null && name.endsWith(".apk") && !"base.apk".equals(name));
                    if (localSplits != null && localSplits.length > 0) {
                        String[] splitPaths = new String[localSplits.length];
                        for (int idx = 0; idx < localSplits.length; idx++) {
                            splitPaths[idx] = localSplits[idx].getAbsolutePath();
                        }
                        ai.splitSourceDirs = splitPaths;
                        ai.splitPublicSourceDirs = splitPaths;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (BuildCompat.isN()) {
            ai.deviceProtectedDataDir = BEnvironment.getDeDataDir(p.packageName, userId).getAbsolutePath();

            if (BRApplicationInfoN.get(ai)._check_deviceEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceEncryptedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialEncryptedDataDir(ai.dataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_deviceProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceProtectedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialProtectedDataDir(ai.dataDir);
            }
        }
        fixJar(ai);
        return ai;
    }

    private static boolean checkUseInstalledOrHidden(int flags, BPackageUserState state,
                                                     ApplicationInfo appInfo) {
        if (appInfo == null || AppSystemEnv.isFeaturesticPackage(appInfo.packageName))
            return false;
        if (!state.installed || state.hidden) {
            return false;
        }
        return true;
    }

    private static void fixJar(ApplicationInfo info) {
        String APACHE_LEGACY_JAR = "/system/framework/org.apache.http.legacy.boot.jar";
        String APACHE_LEGACY_JAR_Q = "/system/framework/org.apache.http.legacy.jar";
        Set<String> sharedLibraryFileList = new HashSet<>();
        if (BuildCompat.isQ()) {
            if (!FileUtils.isExist(APACHE_LEGACY_JAR_Q)) {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR);
            } else {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR_Q);
            }
        } else {
            sharedLibraryFileList.add(APACHE_LEGACY_JAR);
        }
        info.sharedLibraryFiles = sharedLibraryFileList.toArray(new String[]{});
    }

    public static Resources getResources(Context context, ApplicationInfo appInfo) {
        BPackageSettings ps = BPackageManagerService.get().getBPackageSetting(appInfo.packageName);
        if (ps != null) {
            AssetManager assets = BRAssetManager.get()._new();
            BRAssetManager.get(assets).addAssetPath(ps.pkg.baseCodePath);
            if (appInfo.splitSourceDirs != null) {
                for (String splitPath : appInfo.splitSourceDirs) {
                    if (splitPath != null) {
                        BRAssetManager.get(assets).addAssetPath(splitPath);
                    }
                }
            }
            Resources hostRes = context.getResources();
            return new Resources(assets, hostRes.getDisplayMetrics(), hostRes.getConfiguration());
        }
        return null;
    }
}
