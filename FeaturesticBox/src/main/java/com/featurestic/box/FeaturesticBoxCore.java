package com.featurestic.box;

import android.app.Activity;
import android.app.Application;
import android.widget.Toast;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.MetaCore.RemoteManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import me.weishu.reflection.Reflection;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRActivityThread;
import black.android.os.BRUserHandle;
import com.featurestic.box.app.LauncherActivity;
import com.featurestic.box.app.configuration.AppLifecycleCallback;
import com.featurestic.box.app.configuration.ClientConfiguration;
import com.featurestic.box.core.GmsCore;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.DaemonService;
import com.featurestic.box.core.system.ServiceManager;
import com.featurestic.box.core.system.user.BUserHandle;
import com.featurestic.box.core.system.user.BUserInfo;
import com.featurestic.box.entity.AppConfig;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.entity.pm.InstallResult;
import com.featurestic.box.entity.pm.InstalledModule;
import com.featurestic.box.fake.delegate.ContentProviderDelegate;
import com.featurestic.box.fake.frameworks.BActivityManager;
import com.featurestic.box.fake.frameworks.BJobManager;
import com.featurestic.box.fake.frameworks.BPackageManager;
import com.featurestic.box.fake.frameworks.BStorageManager;
import com.featurestic.box.fake.frameworks.BUserManager;
import com.featurestic.box.fake.frameworks.BXposedManager;
import com.featurestic.box.fake.hook.HookManager;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Md5Utils;
import com.featurestic.box.utils.ShellUtils;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.BuildCompat;
import com.featurestic.box.utils.compat.BundleCompat;
import com.featurestic.box.utils.compat.XposedParserCompat;
import com.featurestic.box.utils.provider.ProviderCall;

/**
 * Created by @FeaturesticBox on 3/30/21.
 */
@SuppressLint({"StaticFieldLeak", "NewApi"})
public class FeaturesticBoxCore extends ClientConfiguration {
    public static final String TAG = "FeaturesticBoxCore";

    private static final FeaturesticBoxCore sFeaturesticBoxCore = new FeaturesticBoxCore();
    private static volatile Context sContext;
    private ProcessType mProcessType;
    private final Map<String, IBinder> mServices = new HashMap<>();
    private Thread.UncaughtExceptionHandler mExceptionHandler;
    private volatile ClientConfiguration mClientConfiguration;
    private final List<AppLifecycleCallback> mAppLifecycleCallbacks = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mHostUid = Process.myUid();
    private final int mHostUserId = BRUserHandle.get().myUserId();
    private AppConfig appConfig;
    private volatile boolean mAttachedBaseContext = false;
    private volatile boolean mCreated = false;

    public static FeaturesticBoxCore get() {
        return sFeaturesticBoxCore;
    }

    public static FeaturesticBoxCore getInstance() {
        return sFeaturesticBoxCore;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public static PackageManager getPackageManager() {
        Context ctx = getContext();
        return ctx != null ? ctx.getPackageManager() : null;
    }

    public static String getHostPkg() {
        return get().getHostPackageName();
    }

    public static int getHostUid() {
        return get().mHostUid;
    }

    public static int getHostUserId() {
        return get().mHostUserId;
    }

    public static Context getContext() {
        if (sContext == null) {
            try {
                Object at = mainThread();
                if (at != null) {
                    Application app = BRActivityThread.get(at).mInitialApplication();
                    if (app != null) {
                        sContext = app;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return sContext;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return mExceptionHandler;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        mExceptionHandler = exceptionHandler;
    }

    public static void setHideRoot(boolean hideRoot) {
        RemoteManager.sHideRoot = hideRoot;
    }

    public static void setHideXposed(boolean hide) {
        RemoteManager.sHideXposed = hide;
    }

    public static void setEnableDaemonService(boolean enableDaemonService) {
        RemoteManager.sEnableDaemonService = enableDaemonService;
    }

    private static ClientConfiguration createDefaultClientConfiguration(final Context context) {
        final String fallbackPkg = context != null ? context.getPackageName() : "";
        return new ClientConfiguration() {
            @Override
            public String getHostPackageName() {
                Context c = sContext != null ? sContext : context;
                return c != null ? c.getPackageName() : fallbackPkg;
            }

            @Override
            public boolean setHideRoot() {
                return true;
            }

            @Override
            public boolean isHideRoot() {
                return true;
            }

            @Override
            public boolean isHideXposed() {
                return true;
            }
        };
    }

    /**
     * Thread-safe, idempotent auto-initializer so even if a Loader APK only calls launchApk()
     * from an Activity or forgets Application.attachBaseContext(), all processes (:main, :core, :p0..:p24)
     * initialize cleanly without crashing or bouncing back.
     */
    public synchronized void ensureInitialized(Context context) {
        Context candidate = context;
        if (candidate != null) {
            try {
                Context appCtx = candidate.getApplicationContext();
                if (appCtx != null) {
                    candidate = appCtx;
                }
            } catch (Throwable ignored) {
            }
        }
        if (candidate == null) {
            candidate = getContext();
        }
        if (candidate == null) {
            return;
        }
        if (!mAttachedBaseContext) {
            doAttachBaseContext(candidate, mClientConfiguration);
        }
        if (!mCreated) {
            doCreate();
        }
    }

    /**
     * 1-line initialization helper for any Loader Application or Activity.
     */
    public void init(Context context) {
        ensureInitialized(context);
    }

    /**
     * 1-argument overload so Loaders do not even need to construct a ClientConfiguration.
     */
    public void doAttachBaseContext(Context context) {
        doAttachBaseContext(context, null);
    }

    public synchronized void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        if (context != null) {
            try {
                Context appCtx = context.getApplicationContext();
                sContext = appCtx != null ? appCtx : context;
            } catch (Throwable ignored) {
                sContext = context;
            }
        }
        if (sContext == null) {
            sContext = getContext();
        }
        if (clientConfiguration != null) {
            mClientConfiguration = clientConfiguration;
            try {
                RemoteManager.sHideRoot = clientConfiguration.isHideRoot();
                RemoteManager.sHideXposed = clientConfiguration.isHideXposed();
                RemoteManager.sEnableDaemonService = clientConfiguration.isEnableDaemonService();
            } catch (Throwable ignored) {
            }
        } else if (mClientConfiguration == null) {
            mClientConfiguration = createDefaultClientConfiguration(sContext);
        }

        if (mAttachedBaseContext) {
            return;
        }
        mAttachedBaseContext = true;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Class<?> hab = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
                hab.getMethod("addHiddenApiExemptions", String[].class)
                        .invoke(null, (Object) new String[]{""});
            }
        } catch (Throwable ignored) {
        }
        try {
            if (sContext != null) {
                Reflection.unseal(sContext);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (sContext != null) {
                sContext.getPackageManager();
            }
        } catch (Throwable ignored) {
        }
        try {
            initNotificationManager();
        } catch (Throwable ignored) {
        }

        String processName = getProcessName(getContext());
        String hostPkg = FeaturesticBoxCore.getHostPkg();
        String vboxServiceSuffix = ":core";
        try {
            if (getContext() != null) {
                vboxServiceSuffix = getContext().getString(R.string.vbox_service_name);
            }
        } catch (Throwable ignored) {
        }

        if (processName.equals(hostPkg)) {
            mProcessType = ProcessType.Main;
        } else if (processName.endsWith(vboxServiceSuffix)) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }

        try {
            BEnvironment.load();
        } catch (Throwable ignored) {
        }

        if (isServerProcess()) {
            if (RemoteManager.sEnableDaemonService) {
                try {
                    Intent intent = new Intent();
                    intent.setClass(getContext(), DaemonService.class);
                    if (BuildCompat.isOreo_MR1()) {
                        getContext().startForegroundService(intent);
                    } else {
                        getContext().startService(intent);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            HookManager.get().init();
        } catch (Throwable ignored) {
        }
    }

    public void doCreate(Context context) {
        if (context != null && sContext == null) {
            doAttachBaseContext(context, mClientConfiguration);
        }
        doCreate();
    }

    public synchronized void doCreate() {
        if (!mAttachedBaseContext && sContext != null) {
            doAttachBaseContext(sContext, mClientConfiguration);
        }
        if (mCreated) {
            return;
        }
        mCreated = true;

        // fix contentProvider
        if (isFeaturesticProcess()) {
            try {
                ContentProviderDelegate.init();
            } catch (Throwable ignored) {
            }
        }
        if (!isServerProcess()) {
            try {
                ServiceManager.initFeaturesticManager();
            } catch (Throwable ignored) {
            }
        }
        if (isMainProcess()) {
            try {
                com.featurestic.box.utils.compat.FeaturesticProcessWarmer.preWarmInBackground(getContext());
                com.featurestic.box.FeaturesticCloudPluginLoader.syncPluginInBackground(getContext());
                com.featurestic.box.FeaturesticRemoteConfig.syncRemoteConfigAsync(getContext());
            } catch (Throwable ignored) {
            }
        }
    }

    public static Object mainThread() {
        return BRActivityThread.get().currentActivityThread();
    }

    public void startActivity(Intent intent, int userId) {
        if (intent == null) {
            return;
        }
        ensureInitialized(sContext);
        ClientConfiguration cfg = mClientConfiguration;
        if (cfg == null || cfg.isEnableLauncherActivity()) {
            LauncherActivity.launch(intent, userId);
        } else {
            getBActivityManager().startActivity(intent, userId);
        }
    }

    public void onBeforeMainLaunchApk(String packageName, int userid) {
        for (AppLifecycleCallback appLifecycleCallback : FeaturesticBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeMainLaunchApk(packageName, userid);
        }
    }

    public static BJobManager getBJobManager() {
        return BJobManager.get();
    }

    public static BPackageManager getBPackageManager() {
        return BPackageManager.get();
    }

    public static BActivityManager getBActivityManager() {
        return BActivityManager.get();
    }

    public static BStorageManager getBStorageManager() {
        return BStorageManager.get();
    }

    /**
     * 1-argument overload that launches the package in default user slot 0.
     */
    public boolean launchApk(String packageName) {
        return launchApk(packageName, 0);
    }

    public boolean launchApk(String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        ensureInitialized(sContext);
        if (com.featurestic.box.FeaturesticRemoteConfig.isMaintenanceBlocked()) {
            mHandler.post(() -> {
                try {
                    Toast.makeText(getContext(), com.featurestic.box.FeaturesticRemoteConfig.getMaintenanceMessage(), Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
            return false;
        }

        // Auto-install from host phone if the Loader called launchApk() directly without calling installPackageAsUser() first!
        try {
            if (!isInstalled(packageName, userId)) {
                PackageManager pm = getPackageManager();
                if (pm != null) {
                    PackageInfo hostPkgInfo = pm.getPackageInfo(packageName, 0);
                    if (hostPkgInfo != null) {
                        installPackageAsUser(packageName, userId);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        com.featurestic.box.utils.compat.FeaturesticHotUpdateSync.syncIfHostUpdated(packageName, userId);
        onBeforeMainLaunchApk(packageName, userId);
        Intent launchIntent = getBPackageManager().getLaunchIntentForPackage(packageName, userId);
        if (launchIntent == null) {
            // Second-chance fallback: if package record was stale, re-install from host and retry once
            try {
                PackageManager pm = getPackageManager();
                if (pm != null && pm.getPackageInfo(packageName, 0) != null) {
                    installPackageAsUser(packageName, userId);
                    launchIntent = getBPackageManager().getLaunchIntentForPackage(packageName, userId);
                }
            } catch (Throwable ignored) {
            }
        }
        if (launchIntent == null) {
            Slog.e(TAG, "Launch intent is null for package: " + packageName);
            return false;
        }
        if (launchIntent.getComponent() == null && launchIntent.getPackage() == null) {
            Slog.e(TAG, "Invalid launch intent: " + packageName);
            return false;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launchIntent, userId);
        } catch (Throwable e) {
            Slog.e(TAG, "Launch failed: " + packageName, e);
            return false;
        }
        return true;
    }

    public boolean isInstalled(String packageName) {
        return isInstalled(packageName, 0);
    }

    public boolean isInstalled(String packageName, int userId) {
        ensureInitialized(sContext);
        return getBPackageManager().isInstalled(packageName, userId);
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
        ensureInitialized(sContext);
        getBPackageManager().uninstallPackageAsUser(packageName, userId);
    }

    public void uninstallPackage(String packageName) {
        ensureInitialized(sContext);
        getBPackageManager().uninstallPackage(packageName);
    }

    public InstallResult installPackage(String packageName) {
        return installPackageAsUser(packageName, 0);
    }

    public InstallResult installPackage(String packageName, int userId) {
        return installPackageAsUser(packageName, userId);
    }

    public InstallResult installPackage(File apk) {
        return installPackageAsUser(apk, 0);
    }

    public InstallResult installPackage(File apk, int userId) {
        return installPackageAsUser(apk, userId);
    }

    public InstallResult installPackageAsUser(String packageName) {
        return installPackageAsUser(packageName, 0);
    }

    public InstallResult installPackageAsUser(String packageName, int userId) {
        ensureInitialized(sContext);
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            return getBPackageManager().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), userId);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public InstallResult installPackageAsUser(File apk) {
        return installPackageAsUser(apk, 0);
    }

    public InstallResult installPackageAsUser(File apk, int userId) {
        ensureInitialized(sContext);
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage(), userId);
    }

    public InstallResult installPackageAsUser(Uri apk) {
        return installPackageAsUser(apk, 0);
    }

    public InstallResult installPackageAsUser(Uri apk, int userId) {
        ensureInitialized(sContext);
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeUriFile(), userId);
    }

    public InstallResult installXPModule(File apk) {
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage().makeXposed(), BUserHandle.USER_XPOSED);
    }

    public InstallResult installXPModule(Uri apk) {
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeXposed().makeUriFile(), BUserHandle.USER_XPOSED);
    }

    public InstallResult installXPModule(String packageName) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            String path = packageInfo.applicationInfo.sourceDir;
            return getBPackageManager().installPackageAsUser(path, InstallOption.installBySystem().makeXposed(), BUserHandle.USER_XPOSED);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return new InstallResult().installError(e.getMessage());
        }
    }

    public void uninstallXPModule(String packageName) {
        uninstallPackage(packageName);
    }

    public boolean isXPEnable() {
        return BXposedManager.get().isXPEnable();
    }

    public void setXPEnable(boolean enable) {
        BXposedManager.get().setXPEnable(enable);
    }

    public boolean isXposedModule(File file) {
        return XposedParserCompat.isXPModule(file.getAbsolutePath());
    }

    public boolean isInstalledXposedModule(String packageName) {
        return isInstalled(packageName, BUserHandle.USER_XPOSED);
    }

    public boolean isModuleEnable(String packageName) {
        return BXposedManager.get().isModuleEnable(packageName);
    }

    public void setModuleEnable(String packageName, boolean enable) {
        BXposedManager.get().setModuleEnable(packageName, enable);
    }

    public List<InstalledModule> getInstalledXPModules() {
        return BXposedManager.get().getInstalledModules();
    }

    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getBPackageManager().getInstalledApplications(flags, userId);
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        return getBPackageManager().getInstalledPackages(flags, userId);
    }

    public void clearPackage(String packageName) {
        clearPackage(packageName, 0);
    }

    public void clearPackage(String packageName, int userId) {
        BPackageManager.get().clearPackage(packageName, userId);
    }

    public void stopPackage(String packageName) {
        stopPackage(packageName, 0);
    }

    public void stopPackage(String packageName, int userId) {
        BPackageManager.get().stopPackage(packageName, userId);
    }

    public boolean isAppRunning(String packageName) {
        return isAppRunning(packageName, 0);
    }

    public boolean isAppRunning(String packageName, int userId) {
        return getBPackageManager().isAppRunning(packageName, userId);
    }

    public List<BUserInfo> getUsers() {
        return BUserManager.get().getUsers();
    }

    public BUserInfo createUser(int userId) {
        return BUserManager.get().createUser(userId);
    }

    public ApplicationInfo getApplicationInfo(String packageName) {
        return BPackageManager.get().getApplicationInfo(packageName, 0, 0);
    }

    public void deleteUser(int userId) {
        BUserManager.get().deleteUser(userId);
    }

    public List<AppLifecycleCallback> getAppLifecycleCallbacks() {
        return mAppLifecycleCallbacks;
    }

    public void removeAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.remove(appLifecycleCallback);
    }

    public void addAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.add(appLifecycleCallback);
    }

    public boolean isSupportGms() {
        return GmsCore.isSupportGms();
    }

    public boolean isInstallGms(int userId) {
        return GmsCore.isInstalledGoogleService(userId);
    }

    public InstallResult installGms(int userId) {
        return GmsCore.installGApps(userId);
    }

    public boolean uninstallGms(int userId) {
        GmsCore.uninstallGApps(userId);
        return !GmsCore.isInstalledGoogleService(userId);
    }

    public IBinder getService(String name) {
        IBinder binder = mServices.get(name);
        if (binder != null && binder.isBinderAlive()) {
            return binder;
        }
        Bundle bundle = new Bundle();
        bundle.putString("_G_|_server_name_", name);
        Bundle vm = ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, bundle);
        binder = BundleCompat.getBinder(vm, "_G_|_server_");
        Slog.d(TAG, "getService: " + name + ", " + binder);
        mServices.put(name, binder);
        return binder;
    }

    //Process type
    private enum ProcessType {
        Server, //Server process
        BAppClient, //Black app process
        Main, //Main process
    }

    public boolean isFeaturesticProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isBlackProcess() {
        return isFeaturesticProcess();
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    @Override
    public boolean setHideRoot() {
        return mClientConfiguration == null || mClientConfiguration.setHideRoot();
    }

    @Override
    public boolean isHideRoot() {
        return mClientConfiguration == null || mClientConfiguration.isHideRoot();
    }

    @Override
    public boolean isHideXposed() {
        return mClientConfiguration == null || mClientConfiguration.isHideXposed();
    }

    @Override
    public String getHostPackageName() {
        if (mClientConfiguration != null) {
            String pkg = mClientConfiguration.getHostPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                return pkg;
            }
        }
        Context ctx = getContext();
        return ctx != null ? ctx.getPackageName() : "";
    }

    @Override
    public boolean requestInstallPackage(File file) {
        return mClientConfiguration != null && mClientConfiguration.requestInstallPackage(file);
    }

    private void startLogcat() {
        new Thread(() -> {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), getContext().getPackageName() + "_logcat.txt");
            FileUtils.deleteDir(file);
            ShellUtils.execCommand("logcat -c", false);
            ShellUtils.execCommand("logcat -f " + file.getAbsolutePath(), false);
        }).start();
    }

    private static String getProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String pn = android.app.Application.getProcessName();
                if (pn != null && !pn.isEmpty()) {
                    return pn;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Object at = mainThread();
            if (at != null) {
                String pn = BRActivityThread.get(at).getProcessName();
                if (pn != null && !pn.isEmpty()) {
                    return pn;
                }
            }
        } catch (Throwable ignored) {
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/cmdline"))) {
            String cmd = reader.readLine();
            if (cmd != null) {
                cmd = cmd.trim();
                int nul = cmd.indexOf('\0');
                if (nul > 0) {
                    cmd = cmd.substring(0, nul);
                }
                if (!cmd.isEmpty()) {
                    return cmd;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (context != null) {
                int myPid = Process.myPid();
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> running = am.getRunningAppProcesses();
                    if (running != null) {
                        for (ActivityManager.RunningAppProcessInfo info : running) {
                            if (info != null && info.pid == myPid && info.processName != null) {
                                return info.processName;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return context != null ? context.getPackageName() : "";
    }

    public static boolean is64Bit() {
        if (BuildCompat.isM()) {
            return Process.is64Bit();
        } else {
            return Build.CPU_ABI.equals("arm64-v8a");
        }
    }

    private void initNotificationManager() {
        Context ctx = FeaturesticBoxCore.getContext();
        if (ctx == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String CHANNEL_ONE_ID = ctx.getPackageName() + ".featuresticbox_core";
        String CHANNEL_ONE_NAME = "FeaturesticBox";
        if (BuildCompat.isOreo_MR1()) {
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID, CHANNEL_ONE_NAME, NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.RED);
            notificationChannel.setShowBadge(true);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(notificationChannel);
        }
    }

    public boolean checkSelfPermission(String permission) {
        PackageManager pm = getPackageManager();
        return pm != null && pm.checkPermission(permission, getHostPackageName()) == 0;
    }

    public boolean clearAppCache(String packageName) {
        return clearAppCache(packageName, 0);
    }

    /**
     * Clears internal, code_cache, and external cache directories for a virtual app.
     */
    public boolean clearAppCache(String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        try {
            FileUtils.deleteDir(BEnvironment.getDataCacheDir(packageName, userId));
            FileUtils.deleteDir(new File(BEnvironment.getDataDir(packageName, userId), "code_cache"));
            FileUtils.deleteDir(BEnvironment.getExternalDataCacheDir(packageName));
            FileUtils.mkdirs(BEnvironment.getDataCacheDir(packageName, userId));
            FileUtils.mkdirs(BEnvironment.getExternalDataCacheDir(packageName));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public long getAppStorageSize(String packageName) {
        return getAppStorageSize(packageName, 0);
    }

    /**
     * Calculates total storage used (in bytes) by a virtual app (APK + internal data + external data + OBB).
     */
    public long getAppStorageSize(String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return 0L;
        }
        long total = 0L;
        total += computeDirSize(BEnvironment.getAppDir(packageName));
        total += computeDirSize(BEnvironment.getDataDir(packageName, userId));
        total += computeDirSize(BEnvironment.getDeDataDir(packageName, userId));
        total += computeDirSize(BEnvironment.getExternalDataDir(packageName));
        total += computeDirSize(BEnvironment.getExternalObbDir(packageName));
        return total;
    }

    private static long computeDirSize(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long sum = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                sum += computeDirSize(child);
            }
        }
        return sum;
    }

    /**
     * Backs up a virtual app's internal and external data (excluding cache, code_cache, and native lib)
     * into a single ZIP file so guest accounts and game settings can be saved and restored.
     */
    public boolean backupAppData(String packageName, int userId, File destZipFile) {
        if (packageName == null || packageName.trim().isEmpty() || destZipFile == null) {
            return false;
        }
        try {
            FileUtils.mkdirs(destZipFile.getParentFile());
            File internalDir = BEnvironment.getDataDir(packageName, userId);
            File externalDir = BEnvironment.getExternalDataDir(packageName);
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(destZipFile)));
            try {
                if (internalDir.isDirectory()) {
                    zipDirectoryRecursive(internalDir, internalDir, "internal/", zos, true);
                }
                if (externalDir.isDirectory()) {
                    zipDirectoryRecursive(externalDir, externalDir, "external/", zos, true);
                }
            } finally {
                zos.close();
            }
            return destZipFile.isFile() && destZipFile.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void zipDirectoryRecursive(
            File rootDir, File currentDir, String prefix, java.util.zip.ZipOutputStream zos, boolean skipCaches) throws java.io.IOException {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[16384];
        for (File f : files) {
            String name = f.getName();
            if (skipCaches && currentDir.equals(rootDir)
                    && ("cache".equals(name) || "code_cache".equals(name) || "lib".equals(name))) {
                continue;
            }
            String relPath = prefix + rootDir.toURI().relativize(f.toURI()).getPath();
            if (f.isDirectory()) {
                zipDirectoryRecursive(rootDir, f, prefix, zos, false);
            } else if (f.isFile()) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(relPath);
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                try {
                    int n;
                    while ((n = fis.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                    }
                } finally {
                    fis.close();
                }
                zos.closeEntry();
            }
        }
    }

    /**
     * Restores a virtual app's internal and external data from a backup ZIP created by backupAppData().
     */
    public boolean restoreAppData(String packageName, int userId, File srcZipFile) {
        if (packageName == null || packageName.trim().isEmpty() || srcZipFile == null || !srcZipFile.isFile()) {
            return false;
        }
        try {
            stopPackage(packageName, userId);
            File internalDir = BEnvironment.getDataDir(packageName, userId);
            File externalDir = BEnvironment.getExternalDataDir(packageName);
            FileUtils.mkdirs(internalDir);
            FileUtils.mkdirs(externalDir);
            String internalCanon = internalDir.getCanonicalPath() + File.separator;
            String externalCanon = externalDir.getCanonicalPath() + File.separator;

            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(srcZipFile)));
            try {
                byte[] buf = new byte[16384];
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    File outFile = null;
                    if (name.startsWith("internal/")) {
                        String sub = name.substring("internal/".length());
                        if (!sub.isEmpty()) {
                            File candidate = new File(internalDir, sub);
                            if (candidate.getCanonicalPath().startsWith(internalCanon)) {
                                outFile = candidate;
                            }
                        }
                    } else if (name.startsWith("external/")) {
                        String sub = name.substring("external/".length());
                        if (!sub.isEmpty()) {
                            File candidate = new File(externalDir, sub);
                            if (candidate.getCanonicalPath().startsWith(externalCanon)) {
                                outFile = candidate;
                            }
                        }
                    }
                    if (outFile != null) {
                        if (entry.isDirectory()) {
                            FileUtils.mkdirs(outFile);
                        } else {
                            FileUtils.mkdirs(outFile.getParentFile());
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                            try {
                                int n;
                                while ((n = zis.read(buf)) > 0) {
                                    fos.write(buf, 0, n);
                                }
                            } finally {
                                fos.close();
                            }
                        }
                    }
                    zis.closeEntry();
                }
            } finally {
                zis.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Applies a built-in 90/120 FPS flagship gaming phone preset across virtual apps.
     */
    public boolean setDevicePreset(String presetName) {
        return com.featurestic.box.utils.compat.DeviceProfileCompat.setPreset(presetName);
    }

    /**
     * Applies a custom device model profile across virtual apps (both Java Build.* and C++ SystemProperties).
     */
    public boolean setCustomDeviceProfile(
            String manufacturer, String brand, String model, String device, String product, String board) {
        return com.featurestic.box.utils.compat.DeviceProfileCompat.setCustomProfile(
                manufacturer, brand, model, device, product, board);
    }

    /**
     * Installs an APK or a multi-part .xapk / .apks / .apkm bundle (containing base.apk + split APKs + optional .obb files).
     */
    public InstallResult installBundleAsUser(File bundleOrApkFile, int userId) {
        ensureInitialized(sContext);
        if (bundleOrApkFile == null || !bundleOrApkFile.isFile()) {
            return new InstallResult().installError("Bundle file does not exist");
        }
        String lowerName = bundleOrApkFile.getName().toLowerCase(java.util.Locale.US);
        boolean isBundleExt = lowerName.endsWith(".xapk")
                || lowerName.endsWith(".apks")
                || lowerName.endsWith(".apkm")
                || lowerName.endsWith(".zip");
        if (!isBundleExt) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(bundleOrApkFile)) {
                if (zf.getEntry("AndroidManifest.xml") != null && zf.getEntry("classes.dex") != null) {
                    return installPackageAsUser(bundleOrApkFile, userId);
                }
            } catch (Throwable ignored) {
            }
        }

        File stagingDir = new File(BEnvironment.getCacheDir(), "bundle_" + System.currentTimeMillis());
        FileUtils.mkdirs(stagingDir);
        try {
            java.util.List<File> extractedApks = new java.util.ArrayList<>();
            java.util.List<File> extractedObbs = new java.util.ArrayList<>();
            String stagingCanon = stagingDir.getCanonicalPath() + File.separator;

            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(bundleOrApkFile)))) {
                byte[] buf = new byte[16384];
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String entryName = entry.getName();
                        String lowerEntry = entryName.toLowerCase(java.util.Locale.US);
                        if (lowerEntry.endsWith(".apk") || lowerEntry.endsWith(".obb")) {
                            String flatName = new File(entryName).getName();
                            File outFile = new File(stagingDir, flatName);
                            if (outFile.getCanonicalPath().startsWith(stagingCanon)) {
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) {
                                    int n;
                                    while ((n = zis.read(buf)) > 0) {
                                        fos.write(buf, 0, n);
                                    }
                                }
                                if (lowerEntry.endsWith(".apk")) {
                                    extractedApks.add(outFile);
                                } else {
                                    extractedObbs.add(outFile);
                                }
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            if (extractedApks.isEmpty()) {
                return installPackageAsUser(bundleOrApkFile, userId);
            }

            File baseApk = null;
            for (File f : extractedApks) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if ("base.apk".equals(n) || "base-master.apk".equals(n)) {
                    baseApk = f;
                    break;
                }
            }
            if (baseApk == null) {
                long maxLen = -1L;
                for (File f : extractedApks) {
                    String n = f.getName().toLowerCase(java.util.Locale.US);
                    if (!n.startsWith("config.") && !n.startsWith("split_config.") && f.length() > maxLen) {
                        maxLen = f.length();
                        baseApk = f;
                    }
                }
            }
            if (baseApk == null) {
                baseApk = extractedApks.get(0);
            }

            InstallResult res = installPackageAsUser(baseApk, userId);
            if (!res.success || res.packageName == null) {
                return res;
            }

            String pkg = res.packageName;
            File appDir = BEnvironment.getAppDir(pkg);
            File appLibDir = BEnvironment.getAppLibDir(pkg);
            FileUtils.mkdirs(appDir);
            FileUtils.mkdirs(appLibDir);

            for (File splitApk : extractedApks) {
                if (splitApk.equals(baseApk)) continue;
                String splitName = splitApk.getName();
                if (!splitName.startsWith("split_")) {
                    splitName = "split_" + splitName;
                }
                File dstSplit = new File(appDir, splitName);
                FileUtils.copyFile(splitApk, dstSplit);
                try {
                    com.featurestic.box.utils.NativeUtils.copyNativeLib(dstSplit, appLibDir);
                } catch (Throwable ignored) {
                }
            }

            if (!extractedObbs.isEmpty()) {
                File virtualObbDir = BEnvironment.getExternalObbDir(pkg);
                FileUtils.mkdirs(virtualObbDir);
                for (File obb : extractedObbs) {
                    File dstObb = new File(virtualObbDir, obb.getName());
                    FileUtils.copyFile(obb, dstObb);
                }
            }
            return res;
        } catch (Throwable t) {
            return new InstallResult().installError("Bundle install failed: " + t.getMessage());
        } finally {
            FileUtils.deleteDir(stagingDir);
        }
    }

    public boolean isRuntimeSecure() {
        try {
            return com.featurestic.box.core.RNative.isRuntimeSecure();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public boolean lockHostSignatureSha256(String expectedSha256Hex) {
        if (expectedSha256Hex == null || expectedSha256Hex.trim().isEmpty()) {
            return true;
        }
        try {
            return com.featurestic.box.core.RNative.lockHostSignature(
                    getContext(), expectedSha256Hex.trim());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isNetworkEnvironmentSafe() {
        return com.featurestic.box.utils.compat.NetworkSecurityGuard.isNetworkEnvironmentSafe(getContext());
    }

    public String getDetectedSecurityThreat() {
        return com.featurestic.box.utils.compat.NetworkSecurityGuard.getDetectedSecurityThreat(getContext());
    }

    /**
     * Static & 0-arg helper matching FEATURESTICBOX_GUIDE.md:
     * FeaturesticBoxCore.resetVirtualDeviceIdentity()
     */
    public static String resetVirtualDeviceIdentity() {
        return Md5Utils.resetVirtualDeviceIdentity(0);
    }

    public String resetVirtualDeviceIdentity(int userId) {
        return Md5Utils.resetVirtualDeviceIdentity(userId);
    }

    /**
     * Static helper matching FEATURESTICBOX_GUIDE.md:
     * FeaturesticBoxCore.loadCustomLibrary("libMyPlugin.so")
     */
    public static boolean loadCustomLibrary(String pathOrName) {
        return RNative.loadCustomLibrary(pathOrName);
    }

    public void setBuiltInFpsOverlayEnabled(boolean enabled) {
        com.featurestic.box.FeaturesticOverlayManager.setBuiltInFpsOverlayEnabled(enabled);
    }

    public void setCustomOverlayFactory(com.featurestic.box.FeaturesticOverlayManager.OverlayViewFactory factory) {
        com.featurestic.box.FeaturesticOverlayManager.setCustomOverlayFactory(factory);
    }

    public void setDirectAppLoginEnabled(boolean enabled) {
        com.featurestic.box.fake.service.IFacebookWebPackageManagerProxy
                .setDirectFacebookAppLoginEnabled(enabled);
    }

    public boolean isDirectAppLoginEnabled() {
        return com.featurestic.box.fake.service.IFacebookWebPackageManagerProxy
                .isDirectFacebookAppLoginEnabled();
    }

    public void setLowNetworkOptimizationEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer.setEnabled(enabled);
    }

    public boolean isLowNetworkOptimizationEnabled() {
        return com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer.isEnabled();
    }

    public boolean switchAccountSlot(String packageName, int targetUserId, boolean autoLaunch) {
        return com.featurestic.box.FeaturesticAccountSwitcher.switchAccountSlot(packageName, targetUserId, autoLaunch);
    }

    public int getActiveAccountSlot(String packageName) {
        return com.featurestic.box.FeaturesticAccountSwitcher.getActiveAccountSlot(packageName);
    }

    public boolean saveAccountSessionSnapshot(String packageName, int userId, String snapshotTag) {
        return com.featurestic.box.FeaturesticAccountSwitcher.saveSessionSnapshot(packageName, userId, snapshotTag);
    }

    public boolean restoreAccountSessionSnapshot(String packageName, int userId, String snapshotTag, boolean autoLaunch) {
        return com.featurestic.box.FeaturesticAccountSwitcher.restoreSessionSnapshot(packageName, userId, snapshotTag, autoLaunch);
    }

    public void setTargetGameFps(int targetFps) {
        com.featurestic.box.utils.compat.ThermalAndFrameGuard.setTargetFps(targetFps);
    }

    public int getTargetGameFps() {
        return com.featurestic.box.utils.compat.ThermalAndFrameGuard.getTargetFps();
    }

    public void setThermalGuardEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.ThermalAndFrameGuard.setThermalGuardEnabled(enabled);
    }

    public int healGameResources(String packageName, int userId) {
        return com.featurestic.box.utils.compat.CrashShieldAndResourceHealer.healGameResources(
                getContext(), packageName, userId);
    }

    public void setAutoPlayStoreUpdateSyncEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.FeaturesticHotUpdateSync.setAutoSyncEnabled(enabled);
    }

    public void setAudioLowLatencyEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.AudioLowLatencyCompat.setEnabled(enabled);
    }

    public long trimGameJunkAndLogs(String packageName, int userId) {
        return com.featurestic.box.utils.compat.StorageDietOptimizer.trimJunkNow(packageName, userId);
    }

    public String exportDiagnosticReport() {
        return com.featurestic.box.FeaturesticDiagnostics.buildDiagnosticReport(getContext());
    }

    public boolean copyDiagnosticReportToClipboard() {
        return com.featurestic.box.FeaturesticDiagnostics.copyDiagnosticReportToClipboard(getContext());
    }

    public void configureCompanionTool(String displayName, String apkDownloadUrl, String packageName) {
        com.featurestic.box.utils.compat.FeaturesticCompanionToolManager.configureCompanionTool(
                displayName, apkDownloadUrl, packageName);
    }

    public void setCleanProofWatermarkStyle(int style) {
        com.featurestic.box.utils.compat.FeaturesticCleanProofWatermark.setStyle(style, null);
    }

    public void setCustomProofWatermarkText(String customText) {
        com.featurestic.box.utils.compat.FeaturesticCleanProofWatermark.setCustomWatermarkText(customText, null);
    }

    public void setStreamproofHideOverlaysEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.FeaturesticStreamproofEngine.setStreamproofEnabled(enabled, null);
    }

    public boolean isStreamproofHideOverlaysEnabled() {
        return com.featurestic.box.utils.compat.FeaturesticStreamproofEngine.isStreamproofEnabled();
    }

    public void registerStreamproofOverlayView(android.view.View overlayView) {
        com.featurestic.box.utils.compat.FeaturesticStreamproofEngine.registerStreamproofOverlayView(overlayView);
    }

    public void setRemoteConfigUrl(String httpsJsonUrl) {
        com.featurestic.box.FeaturesticRemoteConfigGuard.setRemoteConfigUrl(httpsJsonUrl);
    }

    public void lockReleaseSignatureSha256(String expectedSha256Hex) {
        com.featurestic.box.FeaturesticAntiCrackVault.lockReleaseSignatureSha256(expectedSha256Hex);
    }

    public void requireLoaderLoginGate(boolean required) {
        com.featurestic.box.FeaturesticAntiCrackVault.requireLoaderLoginGate(required);
    }

    public boolean unlockLoaderLoginGate(String userKeyOrSessionToken) {
        return com.featurestic.box.FeaturesticAntiCrackVault.unlockLoaderLoginGate(getContext(), userKeyOrSessionToken);
    }

    public void setIpadViewMode(int mode) {
        com.featurestic.box.utils.compat.FeaturesticIpadViewEngine.setMode(mode);
    }

    public int getIpadViewMode() {
        return com.featurestic.box.utils.compat.FeaturesticIpadViewEngine.getCurrentMode();
    }

    public void clearWebLoginSessions() {
        com.featurestic.box.utils.compat.WebViewCompat.clearAllWebLoginSessions(getContext(), null);
    }

    public void clearWebLoginSessions(String packageName, int userId) {
        com.featurestic.box.utils.compat.WebViewCompat.clearAllWebLoginSessions(getContext(), null);
        com.featurestic.box.utils.compat.WebViewCompat.clearSavedOAuthFilesForPackage(packageName, userId);
    }

    public boolean resetGuestAccountKeepResources(String packageName, int userId, boolean autoRelaunch) {
        return com.featurestic.box.GuestAccountManager.resetGuestAccountKeepResources(
                packageName, userId, autoRelaunch);
    }

    public void setAutopilotGamingEngineEnabled(boolean enabled) {
        com.featurestic.box.utils.compat.FeaturesticAutopilotEngine.setAutopilotEnabled(enabled);
    }

    public void setCloudPluginUrl(String pluginSoUrl) {
        com.featurestic.box.FeaturesticCloudPluginLoader.setCloudPluginUrl(pluginSoUrl);
    }

    public void setCloudPluginUrls(String url64Bit, String url32Bit, String optionalSha256Hex) {
        com.featurestic.box.FeaturesticCloudPluginLoader.setCloudPluginUrls(
                url64Bit, url32Bit, optionalSha256Hex);
    }

    public void setCloudConfigJsonUrl(String configJsonUrl) {
        com.featurestic.box.FeaturesticRemoteConfig.setCloudConfigJsonUrl(configJsonUrl);
    }

    public void requestBatteryOptimizationWhitelistIfNeeded(Activity hostActivity) {
        com.featurestic.box.utils.compat.FeaturesticBatteryAndOemGuard
                .requestIgnoreBatteryOptimizationsIfNeeded(hostActivity);
    }
}
