package com.featurestic.box.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import android.MetaCore.RemoteManager;
import android.webkit.WebView;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.configuration.AppLifecycleCallback;
import com.featurestic.box.app.dispatcher.AppServiceDispatcher;
import com.featurestic.box.core.CrashHandler;
import com.featurestic.box.core.IBActivityThread;
import com.featurestic.box.core.RCore;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.env.VirtualRuntime;
import com.featurestic.box.utils.compat.DexFileCompat;
import com.featurestic.box.core.system.user.BUserHandle;
import com.featurestic.box.entity.AppConfig;
import com.featurestic.box.entity.am.ReceiverData;
import com.featurestic.box.entity.pm.InstalledModule;
import com.featurestic.box.fake.delegate.AppInstrumentation;
import com.featurestic.box.fake.delegate.ContentProviderDelegate;
import com.featurestic.box.fake.frameworks.BXposedManager;
import com.featurestic.box.fake.hook.HookManager;
import com.featurestic.box.fake.service.HCallbackStub;
import com.featurestic.box.utils.Reflector;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.ActivityManagerCompat;
import com.featurestic.box.utils.compat.BuildCompat;
import com.featurestic.box.utils.compat.ContextCompat;
import com.featurestic.box.utils.compat.StrictModeCompat;
import com.featurestic.box.utils.compat.DeviceProfileCompat;
import com.featurestic.box.utils.compat.AudioLowLatencyCompat;
import com.featurestic.box.utils.compat.FeaturesticAutopilotEngine;
import com.featurestic.box.utils.compat.CrashShieldAndResourceHealer;
import com.featurestic.box.utils.compat.StorageDietOptimizer;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;
import com.featurestic.box.utils.compat.ThermalAndFrameGuard;
import com.featurestic.box.utils.compat.WebViewCompat;

public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";
    private static final Object mConfigLock = new Object();
    private static volatile BActivityThread sBActivityThread;
    private AppConfig mAppConfig;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = FeaturesticBoxCore.get().getHandler();

    public static class AppBindData {
        ApplicationInfo appInfo;
        Object info;
        String processName;
        List<ProviderInfo> providers;
    }

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) return getAppConfig().processName;
        if (currentActivityThread().mBoundApplication != null) return currentActivityThread().mBoundApplication.processName;
        return null;
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) return getAppConfig().packageName;
        if (currentActivityThread().mInitialApplication != null) return currentActivityThread().mInitialApplication.getPackageName();
        return null;
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(FeaturesticBoxCore.getHostUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? FeaturesticBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            final IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            synchronized (BActivityThread.mConfigLock) {
                                try {
                                    iBinder.linkToDeath(this, 0);
                                } catch (RemoteException e) {
                                    // ignore
                                }
                                BActivityThread.this.mAppConfig = null;
                            }
                        }
                    }, 0);

            } catch (RemoteException e) {
                Log.e(TAG, "error", e);
            }
        }
    }

    /**
     * True only after the guest Application is fully created and published back
     * to the real framework ActivityThread.
     *
     * Android 16 calls ConfigurationController from handleLaunchActivity before
     * Activity.onCreate(). ClientTransactionListenerController expects
     * ActivityThread.currentApplication() to be non-null there, so treating a
     * partially-populated AppBindData as "initialized" can crash the process.
     */
    public boolean isInit() {
        return this.mBoundApplication != null
                && this.mInitialApplication != null
                && ensureFrameworkInitialApplication();
    }

    /**
     * Android 16 ConfigurationController does not stop at
     * ActivityThread.currentApplication(). During launch it also calls
     * app.getApplicationContext().getResources(). ContextImpl#getApplicationContext()
     * resolves through LoadedApk.mApplication, so all three references must agree:
     *
     *   ActivityThread.mInitialApplication
     *   LoadedApk.mApplication
     *   Application base ContextImpl.mPackageInfo
     *
     * Old virtualization code can leave the second reference null even though the
     * Application object itself is already alive. That produces the framework NPE
     * in updateLocaleListFromAppContext().
     */
    public boolean ensureFrameworkInitialApplication() {
        try {
            Object activityThread = FeaturesticBoxCore.mainThread();
            if (activityThread == null) {
                return false;
            }

            Application frameworkApplication =
                    BRActivityThread.get(activityThread).mInitialApplication();
            Application desiredApplication = this.mInitialApplication;

            if (desiredApplication == null) {
                desiredApplication = frameworkApplication;
            }
            if (desiredApplication == null) {
                Context hostContext = FeaturesticBoxCore.getContext();
                Context hostApplicationContext =
                        hostContext == null ? null : hostContext.getApplicationContext();
                if (hostApplicationContext instanceof Application) {
                    desiredApplication = (Application) hostApplicationContext;
                }
            }
            if (desiredApplication == null) {
                return false;
            }

            // Repair the LoadedApk used by the virtual bind data.
            if (mBoundApplication != null) {
                publishApplicationToLoadedApk(mBoundApplication.info, desiredApplication);
            }

            // Repair the LoadedApk actually backing Application.getBaseContext().
            // ContextImpl#getApplicationContext() returns that LoadedApk's
            // mApplication, and Android 16 dereferences it during launch config.
            Context baseContext = desiredApplication.getBaseContext();
            if (baseContext != null) {
                try {
                    Object baseLoadedApk = BRContextImpl.get(baseContext).mPackageInfo();
                    publishApplicationToLoadedApk(baseLoadedApk, desiredApplication);
                } catch (Throwable error) {
                    Log.w(TAG, "Unable to repair Application base LoadedApk", error);
                }
            }

            if (frameworkApplication != desiredApplication) {
                BRActivityThread.get(activityThread)
                        ._set_mInitialApplication(desiredApplication);
            }

            return isApplicationContextReady(desiredApplication);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to ensure framework Application context", error);
            return false;
        }
    }

    private void publishApplicationToLoadedApk(Object loadedApk, Application application) {
        if (loadedApk == null || application == null) {
            return;
        }
        try {
            Application current = BRLoadedApk.get(loadedApk).mApplication();
            if (current != application) {
                // Use the existing hidden-API reflector instead of depending on
                // a generated setter name. Reflection is already unsealed by
                // FeaturesticBoxCore during attachBaseContext().
                Reflector.with(loadedApk).field("mApplication").set(application);
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to publish Application into LoadedApk", error);
        }
    }

    private boolean isApplicationContextReady(Application application) {
        if (application == null) {
            return false;
        }
        try {
            if (application.getResources() == null) {
                return false;
            }
            Context appContext = application.getApplicationContext();
            return appContext != null && appContext.getResources() != null;
        } catch (Throwable error) {
            Log.w(TAG, "Application context/resources are not launch-ready", error);
            return false;
        }
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            Service service = (Service) BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name).newInstance();
            Context context = resolveComponentContext(service, serviceInfo.packageName);
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, FeaturesticBoxCore.mainThread(), serviceInfo.name,token, this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            throw new RuntimeException("Unable to create service " + serviceInfo.name, e);
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!isInit()) bindApplication(serviceInfo.packageName, serviceInfo.processName);
        try {
            JobService service = (JobService) BRLoadedApk.get(this.mBoundApplication.info).getClassLoader().loadClass(serviceInfo.name).newInstance();
            Context context = resolveComponentContext(service, serviceInfo.packageName);
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(context, FeaturesticBoxCore.mainThread(), serviceInfo.name,getActivityThread(), this.mInitialApplication, BRActivityManagerNative.get().getDefault());
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            Log.e(TAG, "error", e);
            throw new RuntimeException("Unable to create JobService " + serviceInfo.name, e);
        }
    }

    /**
     * Build a fresh component ContextImpl from the already-bound guest LoadedApk.
     *
     * <p>Do not call the host PackageManager for the current virtual package:
     * package visibility on Android 11+ (and OEM Android 16 builds in particular)
     * may hide it from the Loader even though FeaturesticBox has already loaded it.
     * Also do not reuse Application.getBaseContext(), because setOuterContext()
     * is component-specific and mutating the Application's shared ContextImpl
     * would corrupt its outer-context owner.</p>
     *
     * <p>Android 12+ lets Service create its own base context; this mirrors
     * ActivityThread and preserves specialized Service overrides. Android 11 and
     * older use ContextImpl.createAppContext(ActivityThread, LoadedApk), matching
     * the platform implementation for those releases.</p>
     */
    private Context resolveComponentContext(Service service, String componentPackage)
            throws PackageManager.NameNotFoundException {
        String boundPackage = mBoundApplication != null
                && mBoundApplication.appInfo != null
                ? mBoundApplication.appInfo.packageName : null;

        if (isBoundComponentPackage(componentPackage, boundPackage)) {
            Object loadedApk = mBoundApplication == null ? null : mBoundApplication.info;
            if (loadedApk == null) {
                throw new IllegalStateException(
                        "Bound LoadedApk unavailable for " + componentPackage);
            }

            Context context = null;

            // Service.createServiceBaseContext() was added in Android 12 and is
            // what modern ActivityThread uses before attaching the Service.
            if (BuildCompat.isS()) {
                try {
                    context = BRService.get(service).createServiceBaseContext(
                            FeaturesticBoxCore.mainThread(), loadedApk);
                } catch (Throwable error) {
                    Log.w(TAG, "createServiceBaseContext failed for "
                            + componentPackage + "; falling back to createAppContext", error);
                }
            }

            if (context == null) {
                Object appContext = BRContextImpl.get().createAppContext(
                        FeaturesticBoxCore.mainThread(), loadedApk);
                if (appContext instanceof Context) {
                    context = (Context) appContext;
                }
            }

            if (context == null) {
                throw new IllegalStateException(
                        "Unable to create guest component context for " + componentPackage);
            }
            return context;
        }

        // Cross-package components should normally have been routed to the real
        // provider before reaching the virtual dispatcher. Keep the historical
        // system-package fallback for that exceptional case only.
        return FeaturesticBoxCore.getContext().createPackageContext(
                componentPackage,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
    }

    static boolean isBoundComponentPackage(String componentPackage, String boundPackage) {
        return componentPackage != null && componentPackage.equals(boundPackage);
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            FeaturesticBoxCore.get().getHandler().post(() -> {
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;

        // Android 16 configuration dispatch assumes the process has a live
        // Application even while a virtual guest is being rebound. Preserve
        // the host Application until the guest Application is ready.
        if (Build.VERSION.SDK_INT >= 36 && !ensureFrameworkInitialApplication()) {
            Log.w(TAG, "Framework initial Application is unavailable before guest bind");
        }

        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }
        Binder.clearCallingIdentity();
        PackageInfo packageInfo = FeaturesticBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));
        Object boundApplication = BRActivityThread.get(FeaturesticBoxCore.mainThread()).mBoundApplication();
        Context packageContext = createPackageContext(applicationInfo);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        // fix applicationInfo & nativeLibraryDir
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);
        if (applicationInfo.nativeLibraryDir != null) {
            try {
                BRLoadedApk.get(loadedApk)._set_mLibDir(applicationInfo.nativeLibraryDir);
            } catch (Throwable ignored) {
            }
        }
        try {
            ClassLoader guestClassLoader = BRLoadedApk.get(loadedApk).getClassLoader();
            File appLibDir = BEnvironment.getAppLibDir(packageName);
            File pluginDir = new File(FeaturesticBoxCore.getContext().getNoBackupFilesDir(), "native");
            DexFileCompat.addNativeLibraryDirectories(guestClassLoader, appLibDir, pluginDir);
        } catch (Throwable ignored) {
        }
        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        
        WebViewCompat.prepareBeforeApplicationCreate(packageContext, getUserId(), packageName, processName);
        
        VirtualRuntime.setupRuntime(processName, applicationInfo);
        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }
        RNative.init(Build.VERSION.SDK_INT);
        assert packageContext != null;
        RCore.get().enableRedirect(packageContext);
        DeviceProfileCompat.applyActiveProfile();
        NetworkLowLatencyOptimizer.initForProcess(packageContext);
        ThermalAndFrameGuard.initForProcess();
        CrashShieldAndResourceHealer.installForVirtualProcess(packageContext);
        AudioLowLatencyCompat.initForGameProcess(packageContext);
        FeaturesticAutopilotEngine.autoTuneForProcess(packageContext);
        StorageDietOptimizer.trimJunkAsync(packageName, getUserId());
        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;
        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);
        mBoundApplication = bindData;
        //ssl适配
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);
            application = BRLoadedApk.get(loadedApk).makeApplication(false, null);
            if (application == null) {
                throw new IllegalStateException("LoadedApk.makeApplication returned null for " + packageName);
            }

            // Publish the guest Application immediately and repair LoadedApk's
            // back-reference before any context/configuration work. On Android 16
            // Application.getApplicationContext() is resolved through
            // LoadedApk.mApplication during launch-time locale handling.
            mInitialApplication = application;
            publishApplicationToLoadedApk(loadedApk, application);
            BRActivityThread.get(FeaturesticBoxCore.mainThread())
                    ._set_mInitialApplication(mInitialApplication);
            if (!ensureFrameworkInitialApplication()) {
                throw new IllegalStateException(
                        "Unable to publish launch-ready guest Application context for " + packageName);
            }

            ContextCompat.fix(application);
            ContextCompat.fix((Context) BRActivityThread.get(FeaturesticBoxCore.mainThread()).getSystemContext());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "com.tencent.mm:recovery".equals(processName)) {
                fixWeChatRecovery(application);
            }
            List<ProviderInfo> providers;
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            // Defer WebView suffix finalization until after Application.onCreate() so
            // guest SDKs can configure WebView.setDataDirectorySuffix() without IllegalStateException
            try {
				fixAiLiaoPhoto(mInitialApplication);
			} catch (Throwable e) {
				e.printStackTrace();
			}
            RNative.loadExternalPlugin();
            onBeforeApplicationOnCreate(packageName, processName, application);
            AppInstrumentation.get().callApplicationOnCreate(application);
            WebViewCompat.ensureAfterApplicationCreate(application, getUserId(), packageName, processName);
            onAfterApplicationOnCreate(packageName, processName, application);
            HookManager.get().checkEnv(HCallbackStub.class);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }
    
    private void fixAiLiaoPhoto(Application application) throws Throwable {
		if (application.getPackageName().equals("com.mosheng")) {
			ClassLoader loader = AppInstrumentation.get().getDelegateAppClassLoader();
			Class fileProviderClass = loader.loadClass("androidx.core.content.FileProvider");
			Method parsePathStrategyMethod = fileProviderClass.getDeclaredMethod("getPathStrategy", Context.class, String.class);
			parsePathStrategyMethod.setAccessible(true);
			Object pathStrategy = parsePathStrategyMethod.invoke(null, application, "com.mosheng.provider");
			Field fieldAuthority = pathStrategy.getClass().getDeclaredField("mAuthority");
			fieldAuthority.setAccessible(true);
            String newAuthority = "files." + FeaturesticBoxCore.getHostPkg();
			fieldAuthority.set(pathStrategy, newAuthority);
		}
	}
    
    private void fixWeChatRecovery(Application app) {
        try {
            Field field = app.getClassLoader().loadClass("com.tencent.recovery.Recovery").getField("context");
            field.setAccessible(true);
            if (field.get(null) != null) {
                return;
            }
            field.set(null, app.getBaseContext());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            Context ctx = FeaturesticBoxCore.getContext().createPackageContext(
                    info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            if (ctx != null) {
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object mainThread = FeaturesticBoxCore.mainThread();
            Object compatInfo = black.android.content.res.BRCompatibilityInfo.get().DEFAULT_COMPATIBILITY_INFO();
            Object loadedApk = BRActivityThread.get(mainThread).getPackageInfo(
                    info,
                    compatInfo,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            if (loadedApk != null) {
                Object appCtx = BRContextImpl.get().createAppContext(mainThread, loadedApk);
                if (appCtx instanceof Context) {
                    return (Context) appCtx;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "createPackageContext fallback error", e);
        }
        return FeaturesticBoxCore.getContext();
    }
    
    public Object getPackageInfo() {
        return this.mBoundApplication.info;
    }
    
    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(FeaturesticBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable ignored) { }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
        }
    }
    
    public void loadXposed(Context context) {
        String vPackageName = getAppPackageName();
        String vProcessName = getAppProcessName();
        if (!TextUtils.isEmpty(vPackageName) && !TextUtils.isEmpty(vProcessName) && BXposedManager.get().isXPEnable()) {
            assert vPackageName != null;
            assert vProcessName != null;
            boolean isFirstApplication = vPackageName.equals(vProcessName);
            List<InstalledModule> installedModules = BXposedManager.get().getInstalledModules();
            for (InstalledModule installedModule : installedModules) {
                if (!installedModule.enable) {
                    continue;
                }
                try {
                  //  PineXposed.loadModule(new File(installedModule.getApplication().sourceDir));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            try {
              //  PineXposed.onPackageLoad(vPackageName, vProcessName, context.getApplicationInfo(), isFirstApplication, context.getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        if (RemoteManager.sHideXposed) {
            RNative.hideXposed();
        }
    }

    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(FeaturesticBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) bindApplication(getAppPackageName(), getAppProcessName());
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) {}

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) {
        if (!isInit()) bindApplication(getAppConfig().packageName, getAppConfig().processName);
        for (String auth : providerInfo.authority.split(";")) {
            ContentProviderClient client = FeaturesticBoxCore.getContext().getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(client).mContentProvider();
            if (iInterface != null) return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(FeaturesticBoxCore.mainThread()).mActivities();
            Object clientRecord = activities.get(token);
            if (clientRecord == null) return;
            Activity activity = getActivityByToken(token);
            while (activity.getParent() != null) activity = activity.getParent();
            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            if (intent == null) return;
            try {
                if (mInitialApplication != null && mInitialApplication.getClassLoader() != null) {
                    intent.setExtrasClassLoader(mInitialApplication.getClassLoader());
                }
            } catch (Throwable ignored) {
            }
            Intent newIntent = BuildCompat.isLollipop_MR1()
                    ? BRReferrerIntent.get()._new(intent, FeaturesticBoxCore.getHostPkg())
                    : intent;
            Object mainThread = FeaturesticBoxCore.mainThread();
            boolean delivered = false;
            try {
                if (BRActivityThread.get(mainThread)._check_performNewIntents(null, null) != null) {
                    BRActivityThread.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent));
                    delivered = true;
                } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                    BRActivityThreadNMR1.get(mainThread).performNewIntents(token, Collections.singletonList(newIntent), true);
                    delivered = true;
                } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                    BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
                    delivered = true;
                } else {
                    Map<IBinder, Object> activities = BRActivityThread.get(mainThread).mActivities();
                    Object clientRecord = activities != null ? activities.get(token) : null;
                    if (clientRecord != null) {
                        for (Method m : mainThread.getClass().getDeclaredMethods()) {
                            if ("handleNewIntent".equals(m.getName()) && m.getParameterTypes().length == 2) {
                                m.setAccessible(true);
                                m.invoke(mainThread, clientRecord, Collections.singletonList(newIntent));
                                delivered = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!delivered) {
                try {
                    Activity activity = getActivityByToken(token);
                    if (activity != null) {
                        activity.setIntent(intent);
                        AppInstrumentation.get().callActivityOnNewIntent(activity, intent);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @Override
    public void scheduleReceiver(final ReceiverData data) {
        if (!isInit()) bindApplication();
        mH.post(() -> {
            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader cl = baseContext.getClassLoader();
                data.intent.setExtrasClassLoader(cl);
                BroadcastReceiver receiver = (BroadcastReceiver) cl.loadClass(data.activityInfo.name).newInstance();
                BRBroadcastReceiver.get(receiver).setPendingResult(data.data.build());
                receiver.onReceive(baseContext, data.intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(receiver).getPendingResult();
                if (finish != null) finish.finish();
                FeaturesticBoxCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable e) {
                Log.e(TAG, "error", e);
                Slog.e(TAG, "Error receiving broadcast " + data.intent);
            }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> map = BRActivityThread.get(FeaturesticBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(map.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback cb : FeaturesticBoxCore.get().getAppLifecycleCallbacks()) {
            cb.beforeCreateApplication(packageName, processName, context, getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : FeaturesticBoxCore.get().getAppLifecycleCallbacks()) {
            cb.beforeApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application app) {
        for (AppLifecycleCallback cb : FeaturesticBoxCore.get().getAppLifecycleCallbacks()) {
            cb.afterApplicationOnCreate(packageName, processName, app, getUserId());
        }
    }
}
