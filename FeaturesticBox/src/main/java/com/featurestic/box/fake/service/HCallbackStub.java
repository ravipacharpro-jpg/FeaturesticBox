package com.featurestic.box.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.compat.auth.GoogleSignInTokenHelper;
import com.featurestic.box.fake.hook.IInjectHook;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.proxy.record.ProxyActivityRecord;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.BuildCompat;

/**
 * ActivityThread callback used to replace host stub activities with their
 * virtual targets before Android executes the launch transaction.
 */
public class HCallbackStub implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";

    private Handler.Callback mOtherCallback;
    private final AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = FeaturesticBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null
                && (mOtherCallback == this
                || mOtherCallback.getClass().getName().equals(getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (mBeing.getAndSet(true)) {
            return false;
        }

        try {
            if (BuildCompat.isPie()) {
                if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                    GoogleSignInTokenHelper.inspectClientTransaction(msg.obj);
                }
                if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()
                        && handleLaunchActivity(msg.obj)) {
                    getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                    return true;
                }
            } else if (msg.what == BRActivityThreadH.get().LAUNCH_ACTIVITY()
                    && handleLaunchActivity(msg.obj)) {
                getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                return true;
            }

            if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                return handleCreateService(msg.obj);
            }
            if (mOtherCallback != null) {
                return mOtherCallback.handleMessage(msg);
            }
            return false;
        } finally {
            mBeing.set(false);
        }
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        if (clientTransaction == null) {
            return null;
        }

        try {
            List<Object> callbacks = GoogleSignInTokenHelper.extractClientTransactionItems(clientTransaction);
            if (callbacks == null) {
                return null;
            }

            Class<?> launchClass = BRLaunchActivityItem.getRealClass();
            for (Object item : callbacks) {
                if (item == null) {
                    continue;
                }
                if (launchClass.isInstance(item)
                        || launchClass.getName().equals(item.getClass().getName())) {
                    return item;
                }
            }
        } catch (Throwable error) {
            Slog.e(TAG, "Unable to inspect ClientTransaction launch item: " + error);
        }
        return null;
    }

    private Boolean handleLaunchActivity(Object client) {
        if (client == null) {
            return false;
        }

        Object launchItem;
        if (BuildCompat.isPie()) {
            launchItem = getLaunchActivityItem(client);
            if (launchItem == null) {
                return false;
            }
        } else {
            launchItem = client;
        }

        Intent stubIntent;
        IBinder token;
        if (BuildCompat.isPie()) {
            stubIntent = BRLaunchActivityItem.get(launchItem).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext recordContext =
                    BRActivityThreadActivityClientRecord.get(launchItem);
            stubIntent = recordContext.intent();
            token = recordContext.token();
        }

        if (stubIntent == null) {
            Slog.e(TAG, "Ignoring launch transaction with an already-null Intent");
            return false;
        }

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(stubIntent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo == null) {
            return false;
        }

        Intent targetIntent = stubRecord.mTarget;
        BActivityThread activityThread = BActivityThread.currentActivityThread();

        if (BuildCompat.isS() && !activityThread.ensureFrameworkInitialApplication()) {
            Slog.e(TAG, "Framework Application is not ready for virtual launch");
        }

        if (BActivityThread.getAppConfig() == null) {
            FeaturesticBoxCore.getBActivityManager().restartProcess(
                    activityInfo.packageName,
                    activityInfo.processName,
                    stubRecord.mUserId);

            Intent packageLaunchIntent = FeaturesticBoxCore.getBPackageManager()
                    .getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);

            Intent restartTarget = packageLaunchIntent != null
                    ? packageLaunchIntent : targetIntent;
            if (restartTarget == null) {
                Slog.e(TAG, "Process restart has no safe virtual target; keeping host stub intact");
                return false;
            }

            stubIntent.setExtrasClassLoader(getClass().getClassLoader());
            ProxyActivityRecord.saveStub(
                    stubIntent,
                    restartTarget,
                    stubRecord.mActivityInfo,
                    stubRecord.mActivityRecord,
                    stubRecord.mUserId);
            writeLaunchRecord(launchItem, stubIntent, activityInfo, null);
            return true;
        }

        if (!activityThread.isInit()) {
            boolean wasOnMainLooper = (Looper.myLooper() == Looper.getMainLooper());
            activityThread.bindApplication(
                    activityInfo.packageName, activityInfo.processName);

            if (!activityThread.isInit()) {
                Slog.e(TAG, "Guest Application bind did not reach a launch-ready state");
                return false;
            }
            if (!wasOnMainLooper) {
                return true;
            }
        }

        if (!activityThread.ensureFrameworkInitialApplication()) {
            Slog.e(TAG, "Refusing virtual launch without a framework Application");
            return false;
        }

        if (targetIntent == null) {
            Slog.e(TAG, "Virtual stub is missing target Intent; refusing null launch rewrite");
            return false;
        }

        int taskId = resolveTaskIdSafely(token);
        FeaturesticBoxCore.getBActivityManager().onActivityCreated(
                taskId, token, stubRecord.mActivityRecord);

        if (BuildCompat.isS()) {
            Object launchingRecord = BRActivityThread.get(FeaturesticBoxCore.mainThread())
                    .getLaunchingActivity(token);

            writeLaunchRecord(launchItem, targetIntent, activityInfo, launchingRecord);
            checkActivityClient();
        } else if (BuildCompat.isPie()) {
            writeLaunchRecord(launchItem, targetIntent, activityInfo, null);
        } else {
            ActivityThreadActivityClientRecordContext recordContext =
                    BRActivityThreadActivityClientRecord.get(launchItem);
            recordContext._set_intent(targetIntent);
            recordContext._set_activityInfo(activityInfo);
        }
        return false;
    }

    private void writeLaunchRecord(
            Object launchItem,
            Intent targetIntent,
            ActivityInfo activityInfo,
            Object launchingRecord) {
        if (launchItem == null || targetIntent == null || activityInfo == null) {
            return;
        }

        if (BuildCompat.isPie()) {
            LaunchActivityItemContext launchContext = BRLaunchActivityItem.get(launchItem);
            launchContext._set_mIntent(targetIntent);
            launchContext._set_mInfo(activityInfo);
        } else {
            ActivityThreadActivityClientRecordContext recordContext =
                    BRActivityThreadActivityClientRecord.get(launchItem);
            recordContext._set_intent(targetIntent);
            recordContext._set_activityInfo(activityInfo);
        }

        if (launchingRecord != null) {
            ActivityThreadActivityClientRecordContext recordContext =
                    BRActivityThreadActivityClientRecord.get(launchingRecord);
            recordContext._set_intent(targetIntent);
            recordContext._set_activityInfo(activityInfo);
            recordContext._set_packageInfo(
                    BActivityThread.currentActivityThread().getPackageInfo());
        }
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            if (appPackageName == null || data == null) {
                return false;
            }

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (serviceInfo == null || serviceInfo.name == null) {
                return false;
            }

            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(
                    ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                FeaturesticBoxCore.getBActivityManager().startService(
                        intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private int resolveTaskIdSafely(IBinder token) {
        if (token == null) return 0;
        try {
            Integer id = BRIActivityManager.get(BRActivityManagerNative.get().getDefault())
                    .getTaskForActivity(token, false);
            if (id != null && id != -1) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object client = BRActivityClient.get().getActivityClientController();
            if (client != null) {
                java.lang.reflect.Method m = client.getClass().getMethod("getTaskForActivity", IBinder.class, boolean.class);
                Object res = m.invoke(client, token, false);
                if (res instanceof Integer) {
                    return (Integer) res;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController =
                    BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy proxy = new IActivityClientProxy(activityClientController);
                proxy.onlyProxy(true);
                proxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object singleton = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(singleton)
                        ._set_mKnownInstance(proxy.getProxyInvocation());
            }
        } catch (Throwable error) {
            Slog.e(TAG, "Unable to refresh ActivityClient hook: " + error);
        }
    }
}
