package com.featurestic.box.fake.service;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.app.IServiceConnection;
import android.app.Notification;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityManagerOreo;
import black.android.app.BRLoadedApkReceiverDispatcher;
import black.android.app.BRLoadedApkReceiverDispatcherInnerReceiver;
import black.android.app.BRLoadedApkServiceDispatcher;
import black.android.app.BRLoadedApkServiceDispatcherInnerConnection;
import black.android.content.BRContentProviderNative;
import black.android.content.pm.BRUserInfo;
import black.android.util.BRSingleton;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.AppSystemEnv;
import com.featurestic.box.core.system.DaemonService;
import com.featurestic.box.core.system.user.BUserHandle;
import com.featurestic.box.entity.AppConfig;
import com.featurestic.box.entity.am.RunningAppProcessInfo;
import com.featurestic.box.entity.am.RunningServiceInfo;
import com.featurestic.box.fake.delegate.ContentProviderDelegate;
import com.featurestic.box.fake.delegate.InnerReceiverDelegate;
import com.featurestic.box.fake.delegate.ServiceConnectionDelegate;
import com.featurestic.box.fake.frameworks.BActivityManager;
import com.featurestic.box.fake.frameworks.BPackageManager;
import com.featurestic.box.fake.hook.ClassInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.fake.hook.ProxyMethods;
import com.featurestic.box.fake.hook.ScanClass;
import com.featurestic.box.fake.service.base.PkgMethodProxy;
import com.featurestic.box.fake.service.context.providers.ContentProviderStub;
import com.featurestic.box.fake.service.context.providers.SystemProviderStub;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.proxy.record.ProxyBroadcastRecord;
import com.featurestic.box.proxy.record.ProxyPendingRecord;
import com.featurestic.box.utils.ArrayUtils;
import com.featurestic.box.utils.ComponentUtils;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.MethodParameterUtils;
import com.featurestic.box.utils.Reflector;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.ActivityManagerCompat;
import com.featurestic.box.utils.compat.BuildCompat;
import com.featurestic.box.utils.compat.ParceledListSliceCompat;
import com.featurestic.box.utils.compat.TaskDescriptionCompat;
import com.featurestic.box.utils.compat.VirtualPermissionCompat;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

/**
 * Created by @FeaturesticBox on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityManagerProxy extends ClassInvocationStub {
    public static final String TAG = "ActivityManagerStub";

    @Override
    protected Object getWho() {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        return BRSingleton.get(iActivityManager).get();
    }

    @Override
    protected void inject(Object base, Object proxy) {
        Object iActivityManager = null;
        if (BuildCompat.isOreo()) {
            iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        } else if (BuildCompat.isL()) {
            iActivityManager = BRActivityManagerNative.get().gDefault();
        }
        BRSingleton.get(iActivityManager)._set_mInstance(proxy);
    }

    @Override
    public boolean isBadEnv() {
        return getProxyInvocation() != getWho();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getAppStartMode"));
        addMethodHook(new PkgMethodProxy("setAppLockedVerifying"));
        addMethodHook(new PkgMethodProxy("reportJunkFromApp"));
    }

    @ProxyMethod("getContentProvider")
	public static class GetContentProvider extends MethodHook {

		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Exception {

			int authIndex = getAuthIndex();
			Object auth = args[authIndex];

			if (!(auth instanceof String)) {
				return method.invoke(who, args);
			}

			String authority = (String) auth;

			if (ProxyManifest.isProxy(authority)) {
				return method.invoke(who, args);
			}

			if (BuildCompat.isQ() || BuildCompat.isR()) {
				if (args.length > 1 && args[1] instanceof String) {
					args[1] = FeaturesticBoxCore.getHostPkg();
				}
			}

			if ("settings".equals(authority) || "media".equals(authority) || "telephony".equals(authority)) {
				Object content = method.invoke(who, args);
				if (content != null) {
					ContentProviderDelegate.update(content, authority);
				}
				return content;
			}

			ProviderInfo providerInfo = FeaturesticBoxCore.getBPackageManager().resolveContentProvider(authority, FileUtils.FileMode.MODE_IWUSR, BActivityThread.getUserId());

			if (providerInfo == null) {
				return method.invoke(who, args);
			}

			IBinder providerBinder = null;

			if (BActivityThread.getAppPid() != -1) {

				AppConfig appConfig = FeaturesticBoxCore.getBActivityManager().initProcess(providerInfo.packageName, providerInfo.processName, BActivityThread.getUserId());

				if (appConfig == null) {
					return method.invoke(who, args);
				}

				if (appConfig.bpid != BActivityThread.getAppPid()) {
					providerBinder = FeaturesticBoxCore.getBActivityManager().acquireContentProviderClient(providerInfo);
				}

				if (providerBinder == null) {
					return method.invoke(who, args);
				}

				args[authIndex] = ProxyManifest.getProxyAuthorities(appConfig.bpid);

				int userIndex = getUserIndex();
				if (args.length > userIndex) {
					args[userIndex] = FeaturesticBoxCore.getHostUserId();
				}
			}

			Object content = method.invoke(who, args);

			if (content == null) {
				return null;
			}

			try {
				Reflector.with(content).field("info").set(providerInfo);
			} catch (Throwable ignored) { }

			try {
				Reflector.with(content).field("provider").set(new ContentProviderStub().wrapper(BRContentProviderNative.get().asInterface(providerBinder),BActivityThread.getAppPackageName()));
			} catch (Throwable ignored) { }
			return content;
		}

		private int getAuthIndex() {
			if (BuildCompat.isQ() || BuildCompat.isR()) {
				return 2;
			}
			return 1;
		}

		private int getUserIndex() {
			return getAuthIndex() + 1;
		}
	}
    
    @ProxyMethod("startService")
    public static class StartService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int intentIdx = MethodParameterUtils.getIndex(args, Intent.class);
            if (intentIdx < 0) intentIdx = 1;
            Intent intent = (Intent) args[intentIdx];
            String resolvedType = (intentIdx + 1 < args.length && args[intentIdx + 1] instanceof String)
                    ? (String) args[intentIdx + 1] : null;
            if (intent == null) {
                return null;
            }
            int userId = BActivityThread.getUserId();
            ResolveInfo resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);
            if (resolveInfo == null && intent.getComponent() == null && intent.getPackage() == null) {
                String currentPkg = BActivityThread.getAppPackageName();
                if (currentPkg != null && !currentPkg.isEmpty()) {
                    intent.setPackage(currentPkg);
                    resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);
                }
            }
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                MethodParameterUtils.replaceAllAppPkg(args);
                MethodParameterUtils.replaceLastUserId(args);
                try {
                    return method.invoke(who, args);
                } catch (Throwable ignored) {
                    return null;
                }
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1 && requireForegroundIndex < args.length && args[requireForegroundIndex] instanceof Boolean) {
                requireForeground = (Boolean) args[requireForegroundIndex];
            }
            ComponentName cn = FeaturesticBoxCore.getBActivityManager().startService(intent, resolvedType, requireForeground, userId);
            if (cn == null) {
                cn = new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
            }
            return cn;
        }

        public int getRequireForeground() {
            if (BuildCompat.isOreo()) {
                return 3;
            }
            return -1;
        }
    }

    @ProxyMethod("stopService")
    public static class StopService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            return FeaturesticBoxCore.getBActivityManager().stopService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    @ProxyMethod("stopServiceToken")
    public static class StopServiceToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            IBinder token = (IBinder) args[1];
            FeaturesticBoxCore.getBActivityManager().stopServiceToken(componentName, token, BActivityThread.getUserId());
            return true;
        }
    }
    
    //TODO 待修复
    @ProxyMethod("bindService")
    public static class BindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[2];
            String resolvedType = (String) args[3];
            IServiceConnection connection = (IServiceConnection) args[4];

            int userId = intent.getIntExtra("_G_|_UserId", -1);
            userId = userId == -1 ? BActivityThread.getUserId() : userId;
            ResolveInfo resolveInfo = FeaturesticBoxCore.getBPackageManager().resolveService(intent, 0, resolvedType, userId);
            if (resolveInfo != null || AppSystemEnv.isOpenPackage(intent.getComponent())) {
                Intent bindService = FeaturesticBoxCore.getBActivityManager().bindService(intent,connection == null ? null : connection.asBinder(),resolvedType,userId);
                if (connection != null) {
                    if (intent.getComponent() == null && resolveInfo != null) {
                        intent.setComponent(new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
                    }
                    IServiceConnection proxy = ServiceConnectionDelegate.createProxy(connection, intent);
                    args[4] = proxy;

                    WeakReference<?> weakReference = BRLoadedApkServiceDispatcherInnerConnection.get(connection).mDispatcher();
                    if (weakReference != null) {
                        BRLoadedApkServiceDispatcher.get(weakReference.get())._set_mConnection(proxy);
                    }
                }
                if (bindService != null) {
                    args[2] = bindService;
                    return method.invoke(who, args);
                }
            }
            return 0;
        }

        @Override
        protected boolean isEnable() {
            return FeaturesticBoxCore.get().isFeaturesticProcess() || FeaturesticBoxCore.get().isServerProcess();
        }
    }

    //android 13.0变更
    @ProxyMethod("bindServiceInstance")
    public static class BindServiceInstance extends BindIsolatedService {

    }
    

    // 10.0
    @ProxyMethod("bindIsolatedService")
    public static class BindIsolatedService extends BindService {
        @Override
        protected Object beforeHook(Object who, Method method, Object[] args) throws Throwable {
            // instanceName
            args[6] = null;
            return super.beforeHook(who, method, args);
        }
    }

    @ProxyMethod("unbindService")
    public static class UnbindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IServiceConnection iServiceConnection = (IServiceConnection) args[0];
            if (iServiceConnection == null) {
                return method.invoke(who, args);
            }
            FeaturesticBoxCore.getBActivityManager().unbindService(iServiceConnection.asBinder(), BActivityThread.getUserId());
            ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getDelegate(iServiceConnection.asBinder());
            if (delegate != null) {
                args[0] = delegate;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getRunningAppProcesses")
    public static class GetRunningAppProcesses extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningAppProcessInfo runningAppProcesses = BActivityManager.get().getRunningAppProcesses(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningAppProcesses == null) {
                return new ArrayList<>();
            }
            return runningAppProcesses.mAppProcessInfoList;
        }
    }

    @ProxyMethod("getServices")
    public static class GetServices extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningServiceInfo runningServices = BActivityManager.get().getRunningServices(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (runningServices == null) {
                return new ArrayList<>();
            }
            return runningServices.mRunningServiceInfoList;
        }
    }

    @ProxyMethod("getIntentSender")
    public static class GetIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int type = (int) args[0];
            Intent[] intents = (Intent[]) args[getIntentsIndex(args)];
            MethodParameterUtils.replaceFirstAppPkg(args);

            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                switch (type) {
                    case ActivityManagerCompat.INTENT_SENDER_ACTIVITY:
                        Intent shadow = new Intent();
                        shadow.setComponent(new ComponentName(FeaturesticBoxCore.getHostPkg(), ProxyManifest.getProxyPendingActivity(BActivityThread.getAppPid())));
                        ProxyPendingRecord.saveStub(shadow, intent, BActivityThread.getUserId());
                        intents[i] = shadow;
                        break;
                }
            }

            // Android 12 (API 31) compat: PendingIntent requires FLAG_IMMUTABLE or FLAG_MUTABLE
            if (BuildCompat.isS()) {
                int flagsIndex = getIntentsIndex(args) + 2;
                if (flagsIndex < args.length && args[flagsIndex] instanceof Integer) {
                    int flags = (int) args[flagsIndex];
                    if ((flags & (0x4000000 | 0x2000000)) == 0) {
                        flags |= 0x4000000; // FLAG_IMMUTABLE
                        args[flagsIndex] = flags;
                    }
                }
            }

            IInterface invoke = (IInterface) method.invoke(who, args);
            if (invoke != null) {
                String[] packagesForUid = BPackageManager.get().getPackagesForUid(BActivityThread.getCallingBUid());
                if (packagesForUid.length < 1) {
                    packagesForUid = new String[]{FeaturesticBoxCore.getHostPkg()};
                }
                FeaturesticBoxCore.getBActivityManager().getIntentSender(invoke.asBinder(), packagesForUid[0], BActivityThread.getCallingBUid());
            }
            return invoke;
        }

        private int getIntentsIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    return i;
                }
            }
            if (BuildCompat.isR()) {
                return 6;
            } else {
                return 5;
            }
        }
    }

    @ProxyMethod("getPackageForIntentSender")
    public static class getPackageForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return FeaturesticBoxCore.getBActivityManager().getPackageForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class getUidForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return FeaturesticBoxCore.getBActivityManager().getUidForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getIntentSenderWithSourceToken")
    public static class GetIntentSenderWithSourceToken extends GetIntentSender {
    }

    @ProxyMethod("getIntentSenderWithFeature")
    public static class GetIntentSenderWithFeature extends GetIntentSender {
    }

    @ProxyMethod("broadcastIntent")
    public static class BroadcastIntent extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int intentIndex = getIntentIndex(args);
            Intent intent = (Intent) args[intentIndex];
            String resolvedType = (String) args[intentIndex + 1];

            Intent proxyIntent = FeaturesticBoxCore.getBActivityManager().sendBroadcast(intent, resolvedType, BActivityThread.getUserId());
            if (proxyIntent != null) {
                proxyIntent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());

                ProxyBroadcastRecord.saveStub(proxyIntent, intent, BActivityThread.getUserId());
                args[intentIndex] = proxyIntent;
            }
            // ignore permission
            for (int i = 0; i < args.length; i++) {
                Object o = args[i];
                if (o instanceof String[]) {
                    args[i] = null;
                }
            }
            MethodParameterUtils.replaceLastUserId(args);
            return method.invoke(who, args);
        }

        int getIntentIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    return i;
                }
            }
            return 1;
        }
    }

    @ProxyMethod("unregisterReceiver")
    public static class unregisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishReceiver")
    public static class finishReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("publishService")
    public static class PublishService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekService")
    public static class PeekService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            return FeaturesticBoxCore.getBActivityManager().peekService(intent, resolvedType, BActivityThread.getUserId());
        }
    }

    // todo
    @ProxyMethod("sendIntentSender")
    public static class SendIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    // android 10
    @ProxyMethod("registerReceiverWithFeature")
    public static class RegisterReceiverWithFeature extends RegisterReceiver {

    }

    @ProxyMethod("registerReceiver")
    public static class RegisterReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            int receiverIndex = getReceiverIndex();
            if (args[receiverIndex] != null) {
                IIntentReceiver intentReceiver = (IIntentReceiver) args[receiverIndex];
                IIntentReceiver proxy = InnerReceiverDelegate.createProxy(intentReceiver);

                WeakReference<?> weakReference = BRLoadedApkReceiverDispatcherInnerReceiver.get(intentReceiver).mDispatcher();
                if (weakReference != null) {
                    BRLoadedApkReceiverDispatcher.get(weakReference.get())._set_mIIntentReceiver(proxy);
                }

                args[receiverIndex] = proxy;
            }
            // ignore permission
            int permIdx = getPermissionIndex();
            if (permIdx < args.length && args[permIdx] != null) {
                args[permIdx] = null;
            }
            int userIdx = permIdx + 1;
            if (userIdx < args.length && args[userIdx] instanceof Integer) {
                args[userIdx] = FeaturesticBoxCore.getHostUserId();
            }
            int flagsIdx = permIdx + 2;
            if (BuildCompat.isT() && flagsIdx < args.length && args[flagsIdx] instanceof Integer) {
                int flags = (Integer) args[flagsIdx];
                if ((flags & (RECEIVER_EXPORTED | RECEIVER_NOT_EXPORTED)) == 0) {
                    args[flagsIdx] = flags | RECEIVER_EXPORTED;
                }
            }
            return method.invoke(who, args);
        }

        public int getReceiverIndex() {
            if (BuildCompat.isS()) {
                return 4;
            } else if (BuildCompat.isR()) {
                return 3;
            }
            return 2;
        }

        public int getPermissionIndex() {
            if (BuildCompat.isS()) {
                return 6;
            } else if (BuildCompat.isR()) {
                return 5;
            }
            return 4;
        }
    }

    //这里需要修复
    @ProxyMethod("grantUriPermission")
    public static class GrantUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setServiceForeground")
    public static class setServiceForeground extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getHistoricalProcessExitReasons")
    public static class getHistoricalProcessExitReasons extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getCurrentUser")
    public static class getCurrentUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BRUserInfo.get()._new(BActivityThread.getUserId(), "Owner", BRUserInfo.get().FLAG_PRIMARY());
        }
    }

    /**
     * Android 16 routes Context permission checks through
     * IActivityManager.checkPermissionForDevice instead of checkPermission.
     * Hook both names so install-time network permissions declared by the guest
     * remain visible to SDKs without granting any dangerous/runtime permission.
     */
    @ProxyMethods({"checkPermission", "checkPermissionForDevice"})
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = args != null && args.length > 0 && args[0] instanceof String
                    ? (String) args[0] : null;
            if (VirtualPermissionCompat.shouldGrantDeclaredNetworkPermission(permission)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            replacePermissionCheckUid(args);
            if (Manifest.permission.ACCOUNT_MANAGER.equals(permission)
                    || Manifest.permission.SEND_SMS.equals(permission)) {
                return PackageManager.PERMISSION_GRANTED;
            }
            return method.invoke(who, args);
        }

        private static void replacePermissionCheckUid(Object[] args) {
            // Both signatures place uid at index 2. On Android 16 the last int is
            // deviceId, so replaceLastUid() would target the wrong argument.
            if (args == null || args.length <= 2 || !(args[2] instanceof Integer)) {
                return;
            }
            int uid = (Integer) args[2];
            if (uid == BActivityThread.getBUid()) {
                args[2] = FeaturesticBoxCore.getHostUid();
            }
        }
    }

    @ProxyMethod("checkUriPermission")
    public static class checkUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    // for < Android 10
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }
    
    @ProxyMethod("setRequestedOrientation")
    public static class setRequestedOrientation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    @ProxyMethod("registerUidObserver")
    public static class registerUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("unregisterUidObserver")
    public static class unregisterUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("updateConfiguration")
    public static class updateConfiguration extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

}
