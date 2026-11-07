package com.featurestic.box.fake.service;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import black.android.app.BRLoadedApkServiceDispatcher;
import black.android.app.BRLoadedApkServiceDispatcherInnerConnection;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.compat.auth.ExternalAuthRouter;
import com.featurestic.box.compat.auth.ExternalAuthServiceConnectionDelegate;
import com.featurestic.box.compat.oauth.VirtualOAuthRouter;
import com.featurestic.box.core.env.AppSystemEnv;
import com.featurestic.box.fake.delegate.ServiceConnectionDelegate;
import com.featurestic.box.fake.frameworks.BActivityManager;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.fake.hook.ProxyMethods;
import com.featurestic.box.fake.provider.FileProviderHandler;
import com.featurestic.box.utils.ComponentUtils;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.MethodParameterUtils;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.BuildCompat;
import com.featurestic.box.utils.compat.ContextCompat;
import com.featurestic.box.utils.compat.ShareIntentCompat;
import com.featurestic.box.utils.compat.StartActivityCompat;

/**
 * Created by @FeaturesticBox on 3/30/21.
 */
public class ActivityManagerCommonProxy {

    public static final String TAG = "ActivityManagerCommonProxy";

    @ProxyMethods({
            "startActivity",
            "startActivityWithFeature",
            "startActivityAsUser",
            "startActivityAsCaller",
            "startActivityWithConfig"
    })
    public static class StartActivity extends MethodHook {
        @Override
        public Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            Intent intent = getIntent(args);

            if (intent == null) {
                Slog.e(TAG, "Intent is null, calling original method");
                return method.invoke(who, args);
            }
            try {
                if (BActivityThread.getApplication() != null
                        && BActivityThread.getApplication().getClassLoader() != null) {
                    intent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
                }
            } catch (Throwable ignored) {
            }

            if (ExternalAuthRouter.isDirectProviderDispatch(intent)) {
                // The marker lives in an Intent extra and can therefore be set by
                // virtual applications too. Never treat it as authorization on
                // its own: only the provider intent created by our bridge may
                // leave the virtual namespace directly.
                ExternalAuthRouter.clearDirectProviderDispatch(intent);
                if (ExternalAuthRouter.isTrustedProviderIntent(intent)) {
                    return method.invoke(who, args);
                }
            }

            if (intent.getParcelableExtra("_G_|_target_") != null) {
                return method.invoke(who, args);
            }
            if (ComponentUtils.isRequestInstall(intent)) {
                File file = FileProviderHandler.convertFile(BActivityThread.getApplication(), intent.getData());
                if (FeaturesticBoxCore.get().requestInstallPackage(file)) {
                    intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                    return method.invoke(who, args);
                }
                intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                return method.invoke(who, args);
            }
            String dataString = intent.getDataString();
            if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {
                intent.setData(Uri.parse("package:" + FeaturesticBoxCore.getHostPkg()));
            }
            ShareIntentCompat.prepareExternalShareIntent(intent);

            if (ExternalAuthRouter.isGoogleInteractiveSignInIntent(intent)
                    && StartActivityCompat.getRequestCode(args) == 40962) {
                ExternalAuthRouter.normalizeGoogleAuthIntentInGuest(intent);
                return 0;
            }

            Intent externalAuthBridge = ExternalAuthRouter.createResultBridgeIntent(
                    intent,
                    StartActivityCompat.getResultTo(args),
                    StartActivityCompat.getResultWho(args),
                    StartActivityCompat.getRequestCode(args),
                    BActivityThread.getAppPackageName());
            if (externalAuthBridge != null) {
                replaceIntent(args, externalAuthBridge);
                MethodParameterUtils.replaceLastUserId(args);
                return method.invoke(who, args);
            }

            Intent oauthBridge = VirtualOAuthRouter.createBridgeIntent(
                    intent,
                    BActivityThread.getUserId(),
                    BActivityThread.getAppPackageName());
            if (oauthBridge != null) {
                replaceIntent(args, oauthBridge);
                MethodParameterUtils.replaceLastUserId(args);
                return method.invoke(who, args);
            }

            String directTrustedPkg = ExternalAuthRouter.getTrustedProviderPackage(intent);
            if (directTrustedPkg != null) {
                if (intent.getComponent() == null && intent.getPackage() == null) {
                    intent.setPackage(directTrustedPkg);
                }
                if (ExternalAuthRouter.isGoogleDirectAuthPackage(directTrustedPkg)) {
                    ExternalAuthRouter.normalizeGoogleAuthIntentInGuest(intent);
                }
                if (StartActivityCompat.getResultTo(args) == null || StartActivityCompat.getRequestCode(args) < 0) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                ContextCompat.fix(BActivityThread.getApplication());
                MethodParameterUtils.replaceFirstAppPkg(args);
                MethodParameterUtils.replaceLastUserId(args);
                return method.invoke(who, args);
            }

            Slog.d(TAG, "Hook in : " + intent);

            ResolveInfo resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveActivity(
                    intent,
                    FileUtils.FileMode.MODE_IWUSR,
                    StartActivityCompat.getResolvedType(args),
                    BActivityThread.getUserId());
            if (resolveInfo == null) {
                String origPackage = intent.getPackage();
                if (intent.getPackage() == null && intent.getComponent() == null) {
                    intent.setPackage(BActivityThread.getAppPackageName());
                } else {
                    origPackage = intent.getPackage();
                }
                resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveActivity(
                        intent,
                        FileUtils.FileMode.MODE_IWUSR,
                        StartActivityCompat.getResolvedType(args),
                        BActivityThread.getUserId());
                if (resolveInfo == null) {
                    intent.setPackage(origPackage);
                    return method.invoke(who, args);
                }
            }

            intent.setExtrasClassLoader(who.getClass().getClassLoader());
            intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            FeaturesticBoxCore.getBActivityManager().startActivityAms(
                    BActivityThread.getUserId(),
                    StartActivityCompat.getIntent(args),
                    StartActivityCompat.getResolvedType(args),
                    StartActivityCompat.getResultTo(args),
                    StartActivityCompat.getResultWho(args),
                    StartActivityCompat.getRequestCode(args),
                    StartActivityCompat.getFlags(args),
                    StartActivityCompat.getOptions(args));
            return 0;
        }

        private Intent getIntent(Object[] args) {
            if (args == null) return null;
            int index = BuildCompat.isR() ? 3 : 2;
            if (index < args.length && args[index] instanceof Intent) {
                return (Intent) args[index];
            }
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
            return null;
        }

        private void replaceIntent(Object[] args, Intent replacement) {
            if (args == null || replacement == null) return;
            int index = BuildCompat.isR() ? 3 : 2;
            if (index < args.length && args[index] instanceof Intent) {
                args[index] = replacement;
                return;
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent) {
                    args[i] = replacement;
                    return;
                }
            }
        }
    }

    @ProxyMethod("startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int index = getIntents();
            Intent[] intents = (Intent[]) args[index++];
            String[] resolvedTypes = (String[]) args[index++];
            IBinder resultTo = (IBinder) args[index++];
            Bundle options = (Bundle) args[index];
            if (!ComponentUtils.isSelf(intents)) {
                return method.invoke(who, args);
            }

            for (Intent intent : intents) {
                intent.setExtrasClassLoader(who.getClass().getClassLoader());
            }
            return FeaturesticBoxCore.getBActivityManager().startActivities(
                    BActivityThread.getUserId(), intents, resolvedTypes, resultTo, options);
        }

        public int getIntents() {
            return BuildCompat.isR() ? 3 : 2;
        }
    }

    /**
     * Registered after IActivityManagerProxy's own bind hooks via @ScanClass, so
     * this compatibility hook becomes authoritative for all bindService variants.
     * Android has changed these signatures repeatedly, so arguments are located
     * by type instead of assuming fixed slots. Real auth providers are handed to
     * Android with the real caller package/user and their IServiceConnection is
     * wrapped to normalize real GMS broker requests.
     */
    @ProxyMethods({"bindService", "bindServiceInstance", "bindIsolatedService"})
    public static class BindServiceCompat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null) {
                return method.invoke(who, args);
            }

            int intentIndex = MethodParameterUtils.getIndex(args, Intent.class);
            if (intentIndex < 0) {
                return method.invoke(who, args);
            }
            Intent intent = (Intent) args[intentIndex];

            int connectionIndex = MethodParameterUtils.getIndex(args, IServiceConnection.class);
            IServiceConnection connection = connectionIndex < 0
                    ? null : (IServiceConnection) args[connectionIndex];

            if (AppSystemEnv.isOpenPackage(intent)) {
                // Refresh the outbound Context right at the provider boundary.
                // This is best-effort and keeps Android 16 package/UID attribution
                // paired even when a provider client cached Context state earlier.
                ContextCompat.fix(BActivityThread.getApplication());

                if (connection != null) {
                    IServiceConnection proxy =
                            ExternalAuthServiceConnectionDelegate.createProxy(connection);
                    args[connectionIndex] = proxy;
                    replaceLoadedApkConnection(connection, proxy);
                }

                intent.removeExtra("_G_|_UserId");
                MethodParameterUtils.replaceAllAppPkg(args);
                MethodParameterUtils.replaceLastUserId(args);
                return method.invoke(who, args);
            }

            if (connectionIndex < 0) {
                return method.invoke(who, args);
            }

            String resolvedType = null;
            int resolvedTypeIndex = intentIndex + 1;
            if (resolvedTypeIndex < args.length && args[resolvedTypeIndex] instanceof String) {
                resolvedType = (String) args[resolvedTypeIndex];
            }

            int userId = intent.getIntExtra("_G_|_UserId", -1);
            userId = userId == -1 ? BActivityThread.getUserId() : userId;
            ResolveInfo resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveService(
                    intent, 0, resolvedType, userId);
            if (resolveInfo == null && intent.getComponent() == null && intent.getPackage() == null) {
                String currentPkg = BActivityThread.getAppPackageName();
                if (currentPkg != null && !currentPkg.isEmpty()) {
                    intent.setPackage(currentPkg);
                    resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveService(
                            intent, 0, resolvedType, userId);
                }
            }
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                MethodParameterUtils.replaceAllAppPkg(args);
                MethodParameterUtils.replaceLastUserId(args);
                try {
                    return method.invoke(who, args);
                } catch (Throwable ignored) {
                    return 0;
                }
            }

            Intent bindService = FeaturesticBoxCore.getBActivityManager().bindService(
                    intent,
                    connection == null ? null : connection.asBinder(),
                    resolvedType,
                    userId);

            IServiceConnection proxyConn = connection;
            if (connection != null) {
                if (intent.getComponent() == null) {
                    intent.setComponent(new ComponentName(
                            resolveInfo.serviceInfo.packageName,
                            resolveInfo.serviceInfo.name));
                }
                proxyConn = ServiceConnectionDelegate.createProxy(connection, intent);
                args[connectionIndex] = proxyConn;
                replaceLoadedApkConnection(connection, proxyConn);
            }

            if (bindService != null) {
                args[intentIndex] = bindService;
                String mName = method != null ? method.getName() : "";
                if ("bindIsolatedService".equals(mName) || "bindServiceInstance".equals(mName)) {
                    // Clear instanceName (the String argument immediately after flags)
                    int instanceIdx = connectionIndex + 2;
                    if (instanceIdx < args.length && args[instanceIdx] instanceof String
                            && instanceIdx + 1 < args.length) {
                        args[instanceIdx] = null;
                    }
                }
                MethodParameterUtils.replaceAllAppPkg(args);
                MethodParameterUtils.replaceLastUserId(args);
                try {
                    Object res = method.invoke(who, args);
                    if (res instanceof Integer && ((Integer) res) > 0) {
                        return res;
                    }
                } catch (Throwable ignored) {
                }
                // Direct Provider bindService fallback for Android 12-16 background restrictions:
                try {
                    ComponentName stubComp = bindService.getComponent();
                    String stubClass = stubComp != null ? stubComp.getClassName() : "";
                    int bpid = BActivityThread.getAppPid();
                    int pIdx = stubClass.lastIndexOf("$P");
                    if (pIdx > 0) {
                        try {
                            bpid = Integer.parseInt(stubClass.substring(pIdx + 2));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (bpid >= 0) {
                        Bundle extras = new Bundle();
                        extras.putParcelable("_Black_|_service_intent_", bindService);
                        Bundle bundleRes = com.featurestic.box.utils.provider.ProviderCall.callSafely(
                                com.featurestic.box.proxy.ProxyManifest.getProxyAuthorities(bpid),
                                "_Black_|_bind_service_",
                                null,
                                extras);
                        IBinder serviceBinder = bundleRes != null
                                ? com.featurestic.box.utils.compat.BundleCompat.getBinder(bundleRes, "_Black_|_service_binder_")
                                : null;
                        if (proxyConn != null) {
                            ComponentName targetComp = intent.getComponent() != null
                                    ? intent.getComponent()
                                    : new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
                            ServiceConnectionDelegate.dispatchConnected(proxyConn, targetComp, serviceBinder, null, false);
                        }
                        return 1;
                    }
                } catch (Throwable ignored) {
                }
                return 1;
            }
            return 0;
        }

        private static void replaceLoadedApkConnection(
                IServiceConnection original, IServiceConnection proxy) {
            if (original == null || proxy == null) {
                return;
            }
            try {
                WeakReference<?> weakReference =
                        BRLoadedApkServiceDispatcherInnerConnection.get(original).mDispatcher();
                if (weakReference != null && weakReference.get() != null) {
                    BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(proxy);
                }
            } catch (Throwable ignored) {
                // OEM framework internals can vary; the actual system bind still
                // receives the proxy even when this local dispatcher field differs.
            }
        }
    }

    /**
     * Android's binder method is startActivityIntentSender. Older compatibility
     * layers exposed the public API name startIntentSenderForResult, so register
     * both. Only provider-owned senders with an allow-listed Android creator are
     * launched outside the virtual namespace.
     */
    @ProxyMethods({"startActivityIntentSender", "startIntentSenderForResult"})
    public static class StartIntentSenderForResult extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || method == null) {
                return method == null ? 0 : method.invoke(who, args);
            }

            int targetIndex = findIntentSenderTargetIndex(method, args);
            int fillInIndex = findIntentParameterIndex(method, targetIndex);
            if (targetIndex < 0 || fillInIndex < 0) {
                return method.invoke(who, args);
            }

            IntentSender sender = ExternalAuthRouter.wrapIntentSender(args[targetIndex]);
            if (!ExternalAuthRouter.isTrustedProviderIntentSender(sender)) {
                return method.invoke(who, args);
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            int resultToIndex = findParameterIndexAfter(
                    parameterTypes, fillInIndex, IBinder.class);
            int resultWhoIndex = findParameterIndexAfter(
                    parameterTypes, resultToIndex, String.class);
            int requestCodeIndex = findIntParameterIndexAfter(
                    parameterTypes, resultWhoIndex);
            int flagsMaskIndex = findIntParameterIndexAfter(
                    parameterTypes, requestCodeIndex);
            int flagsValuesIndex = findIntParameterIndexAfter(
                    parameterTypes, flagsMaskIndex);
            int optionsIndex = findLastParameterIndexAfter(
                    parameterTypes, fillInIndex, Bundle.class);
            if (!validIndex(resultToIndex, args)
                    || !validIndex(resultWhoIndex, args)
                    || !validIndex(requestCodeIndex, args)
                    || !validIndex(flagsMaskIndex, args)
                    || !validIndex(flagsValuesIndex, args)
                    || !(args[resultToIndex] instanceof IBinder)
                    || !(args[requestCodeIndex] instanceof Integer)
                    || !(args[flagsMaskIndex] instanceof Integer)
                    || !(args[flagsValuesIndex] instanceof Integer)) {
                return method.invoke(who, args);
            }

            IBinder resultTo = (IBinder) args[resultToIndex];
            String resultWho = args[resultWhoIndex] instanceof String
                    ? (String) args[resultWhoIndex] : null;
            int requestCode = (Integer) args[requestCodeIndex];
            int flagsMask = (Integer) args[flagsMaskIndex];
            int flagsValues = (Integer) args[flagsValuesIndex];
            Intent fillInIntent = args[fillInIndex] instanceof Intent
                    ? (Intent) args[fillInIndex] : null;
            Bundle options = validIndex(optionsIndex, args)
                    && args[optionsIndex] instanceof Bundle
                    ? (Bundle) args[optionsIndex] : null;

            Intent bridge = ExternalAuthRouter.createIntentSenderBridgeIntent(
                    sender,
                    fillInIntent,
                    flagsMask,
                    flagsValues,
                    options,
                    resultTo,
                    resultWho,
                    requestCode,
                    BActivityThread.getAppPackageName());
            if (bridge == null) {
                return method.invoke(who, args);
            }

            try {
                ContextCompat.fix(BActivityThread.getApplication());
                if (fillInIntent != null) {
                    ExternalAuthRouter.normalizeGoogleAuthIntentInGuest(fillInIntent);
                }
                MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            } catch (Throwable directFailure) {
                try {
                    FeaturesticBoxCore.getContext().startActivity(bridge);
                    return 0;
                } catch (Throwable ignored) {
                    throw directFailure;
                }
            }
        }

        private static int findIntentSenderTargetIndex(Method method, Object[] args) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            int count = Math.min(parameterTypes.length, args.length);
            for (int i = 0; i < count; i++) {
                Class<?> type = parameterTypes[i];
                String typeName = type == null ? "" : type.getName();
                if (IntentSender.class.equals(type)
                        || "android.content.IIntentSender".equals(typeName)) {
                    return i;
                }
            }
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof IntentSender || implementsIntentSenderInterface(arg)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findIntentParameterIndex(Method method, int targetIndex) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = Math.max(0, targetIndex + 1); i < parameterTypes.length; i++) {
                if (Intent.class.equals(parameterTypes[i])) {
                    return i;
                }
            }
            return -1;
        }

        private static int findParameterIndexAfter(
                Class<?>[] parameterTypes, int afterIndex, Class<?> expectedType) {
            if (parameterTypes == null || expectedType == null) {
                return -1;
            }
            for (int i = Math.max(0, afterIndex + 1); i < parameterTypes.length; i++) {
                Class<?> actual = parameterTypes[i];
                if (actual != null && expectedType.isAssignableFrom(actual)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findLastParameterIndexAfter(
                Class<?>[] parameterTypes, int afterIndex, Class<?> expectedType) {
            if (parameterTypes == null || expectedType == null) {
                return -1;
            }
            for (int i = parameterTypes.length - 1; i > afterIndex; i--) {
                Class<?> actual = parameterTypes[i];
                if (actual != null && expectedType.isAssignableFrom(actual)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findIntParameterIndexAfter(
                Class<?>[] parameterTypes, int afterIndex) {
            if (parameterTypes == null) {
                return -1;
            }
            for (int i = Math.max(0, afterIndex + 1); i < parameterTypes.length; i++) {
                if (parameterTypes[i] == int.class || parameterTypes[i] == Integer.class) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean validIndex(int index, Object[] args) {
            return args != null && index >= 0 && index < args.length;
        }

        private static boolean implementsIntentSenderInterface(Object value) {
            if (value == null) {
                return false;
            }
            try {
                Class<?> type = value.getClass();
                while (type != null && type != Object.class) {
                    for (Class<?> iface : type.getInterfaces()) {
                        if ("android.content.IIntentSender".equals(iface.getName())) {
                            return true;
                        }
                    }
                    type = type.getSuperclass();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /**
     * Preserve the real creator metadata for external provider PendingIntents.
     * Virtual metadata is returned only when FeaturesticBox actually owns the sender.
     */
    @ProxyMethod("getPackageForIntentSender")
    public static class GetPackageForIntentSenderCompat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof IInterface) {
                try {
                    String virtualPackage = BActivityManager.get().getPackageForIntentSender(
                            ((IInterface) args[0]).asBinder());
                    if (virtualPackage != null && !virtualPackage.trim().isEmpty()) {
                        return virtualPackage;
                    }
                } catch (Throwable ignored) {
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class GetUidForIntentSenderCompat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof IInterface) {
                try {
                    int virtualUid = BActivityManager.get().getUidForIntentSender(
                            ((IInterface) args[0]).asBinder());
                    if (virtualUid >= 0) {
                        return virtualUid;
                    }
                } catch (Throwable ignored) {
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FeaturesticBoxCore.getBActivityManager().onActivityResumed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FeaturesticBoxCore.getBActivityManager().onActivityDestroyed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            FeaturesticBoxCore.getBActivityManager().onFinishActivity((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAppTasks")
    public static class GetAppTasks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getCallingPackage")
    public static class getCallingPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return FeaturesticBoxCore.getBActivityManager().getCallingPackage(
                    (IBinder) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getCallingActivity")
    public static class getCallingActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return FeaturesticBoxCore.getBActivityManager().getCallingActivity(
                    (IBinder) args[0], BActivityThread.getUserId());
        }
    }
}
