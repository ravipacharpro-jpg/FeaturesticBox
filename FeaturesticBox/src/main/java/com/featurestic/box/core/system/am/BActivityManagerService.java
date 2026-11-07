package com.featurestic.box.core.system.am;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.BProcessManagerService;
import com.featurestic.box.core.system.ISystemService;
import com.featurestic.box.core.system.ProcessRecord;
import com.featurestic.box.core.system.pm.BPackageManagerService;
import com.featurestic.box.entity.AppConfig;
import com.featurestic.box.entity.UnbindRecord;
import com.featurestic.box.entity.am.PendingResultData;
import com.featurestic.box.entity.am.ReceiverData;
import com.featurestic.box.entity.am.RunningAppProcessInfo;
import com.featurestic.box.entity.am.RunningServiceInfo;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Slog;

import static android.content.pm.PackageManager.GET_META_DATA;

/**
 * Created by @FeaturesticBox on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityManagerService extends IBActivityManagerService.Stub implements ISystemService {
    public static final String TAG = "BActivityManagerService";
    private static final BActivityManagerService sService = new BActivityManagerService();
    private final Map<Integer, UserSpace> mUserSpace = new HashMap<>();
    private final BPackageManagerService mPms = BPackageManagerService.get();
    private final BroadcastManager mBroadcastManager;

    public static BActivityManagerService get() {
        return sService;
    }

    public BActivityManagerService() {
        mBroadcastManager = BroadcastManager.startSystem(this, mPms);
    }

    @Override
    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.startService(intent, resolvedType, requireForeground, userId);
        }
    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(providerInfo.packageName,providerInfo.processName,BProcessManagerService.get().getUserIdByCallingPid(callingPid),-1,Binder.getCallingPid());
        if (processRecord == null) {
            throw new RuntimeException("Unable to create process " + providerInfo.name);
        }
        try {
            return processRecord.bActivityThread.acquireContentProviderClient(providerInfo);
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    @Override
    public Intent sendBroadcast(Intent intent, String resolvedType, int userId) {
        List<ResolveInfo> resolves;
        try {
            resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, resolvedType, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            resolves = new java.util.ArrayList<>();
        }

        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            if (processRecord == null) {
                continue;
            }
            try {
                processRecord.bActivityThread.bindApplication();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Intent shadow = new Intent();
        shadow.setPackage(FeaturesticBoxCore.getHostPkg());
        shadow.setComponent(null);
        shadow.setAction(intent.getAction());
        return shadow;
    }
    
    @Override
    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.peekService(intent, resolvedType, userId);
        }
    }

    @Override
    public void onActivityCreated(int taskId, IBinder token, IBinder activityRecord) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        ActivityRecord record = (ActivityRecord) activityRecord;
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityCreated(process, taskId, token, record);
        }
    }

    @Override
    public void onActivityResumed(IBinder token) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityResumed(process.userId, token);
        }
    }

    @Override
    public void onActivityDestroyed(IBinder token) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityDestroyed(process.userId, token);
        }
    }

    @Override
    public void onFinishActivity(IBinder token) {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onFinishActivity(process.userId, token);
        }
    }

    public static boolean isProtectedGamePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String lower = pkg.toLowerCase();
        if (lower.startsWith("com.pubg.")
                || lower.startsWith("com.tencent.ig")
                || lower.startsWith("com.tencent.tmgp.")
                || lower.startsWith("com.dts.freefire")
                || lower.startsWith("com.garena.")
                || lower.startsWith("com.activision.")
                || lower.startsWith("com.krafton.")
                || lower.startsWith("com.proximabeta.")
                || lower.startsWith("com.mobile.legends")
                || lower.startsWith("com.mihoyo.")) {
            return true;
        }
        try {
            java.io.File libDir = BEnvironment.getAppLibDir(pkg);
            if (libDir != null && libDir.isDirectory()) {
                if (new java.io.File(libDir, "libanogs.so").exists()
                        || new java.io.File(libDir, "libUE4.so").exists()
                        || new java.io.File(libDir, "libtersafe.so").exists()
                        || new java.io.File(libDir, "libil2cpp.so").exists()
                        || new java.io.File(libDir, "libunity.so").exists()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId) {
        ActivityManager manager = (ActivityManager) FeaturesticBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Map<Integer, ActivityManager.RunningAppProcessInfo> runningProcessMap = new HashMap<>();
        if (runningAppProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo runningProcess : runningAppProcesses) {
                runningProcessMap.put(runningProcess.pid, runningProcess);
            }
        }
        List<ProcessRecord> targetProcesses = isProtectedGamePackage(callerPackage)
                ? BProcessManagerService.get().getPackageProcessAsUser(callerPackage, userId)
                : BProcessManagerService.get().getAllProcessAsUser(userId);

        RunningAppProcessInfo appProcessInfo = new RunningAppProcessInfo();
        for (ProcessRecord processRecord : targetProcesses) {
            ActivityManager.RunningAppProcessInfo runningAppProcessInfo = runningProcessMap.get(processRecord.pid);
            if (runningAppProcessInfo != null) {
                runningAppProcessInfo.processName = processRecord.processName;
                if (processRecord.getPackageName() != null) {
                    runningAppProcessInfo.pkgList = new String[]{processRecord.getPackageName()};
                }
                appProcessInfo.mAppProcessInfoList.add(runningAppProcessInfo);
            }
        }
        return appProcessInfo;
    }

    @Override
    public RunningServiceInfo getRunningServices(String callerPackage, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        return userSpace.mActiveServices.getRunningServiceInfo(callerPackage, userId);
    }

    @Override
    public void scheduleBroadcastReceiver(Intent intent, PendingResultData pendingResultData, int userId) {
        List<ResolveInfo> resolves;
        try {
            resolves = BPackageManagerService.get().queryBroadcastReceivers(intent, GET_META_DATA, null, userId);
        } catch (RemoteException e) {
            e.printStackTrace();
            pendingResultData.build().finish();
            return;
        }

        if (resolves.isEmpty()) {
            pendingResultData.build().finish();
            Slog.d(TAG, "scheduleBroadcastReceiver empty");
            return;
        }
        mBroadcastManager.sendBroadcast(pendingResultData);
        for (ResolveInfo resolve : resolves) {
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(resolve.activityInfo.packageName, resolve.activityInfo.processName, userId);
            if (processRecord != null) {
                ReceiverData data = new ReceiverData();
                data.intent = intent;
                data.activityInfo = resolve.activityInfo;
                data.data = pendingResultData;
                try {
                    processRecord.bActivityThread.scheduleReceiver(data);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void finishBroadcast(PendingResultData data) {
        mBroadcastManager.finishBroadcast(data);
    }

    @Override
    public String getCallingPackage(IBinder token, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingPackage(token, userId);
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingActivity(token, userId);
        }
    }

    @Override
    public void getIntentSender(IBinder target, String packageName, int uid, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = new PendingIntentRecord();
            record.uid = uid;
            record.packageName = packageName;
            userSpace.mIntentSenderRecords.put(target, record);
        }
    }

    @Override
    public String getPackageForIntentSender(IBinder target, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.packageName;
            }
        }
        return null;
    }

    @Override
    public int getUidForIntentSender(IBinder target, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.uid;
            }
        }
        return -1;
    }
    
    @Override
	public int checkPermission(String permission, int pid, int uid, String packageName) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (Manifest.permission.ACCOUNT_MANAGER.equals(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (Manifest.permission.RECEIVE_BOOT_COMPLETED.equals(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if ("android.permission.BACKUP".equals(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if ("android.permission.INTERACT_ACROSS_USERS".equals(permission) || "android.permission.INTERACT_ACROSS_USERS_FULL".equals(permission)) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (uid == 0) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return BPackageManagerService.get().checkUidPermission(permission, uid, packageName);
        } catch (RemoteException e) {
            e.printStackTrace();
            return PackageManager.PERMISSION_DENIED;
        }
    }

    @Override
    public void onStartCommand(Intent intent, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }

    @Override
    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }

    @Override
    public void onServiceDestroy(Intent proxyIntent, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }

    @Override
    public int stopService(Intent intent, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.stopService(intent, resolvedType, userId);
        }
    }

    @Override
    public Intent bindService(Intent service, IBinder binder, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.bindService(service, binder, resolvedType, userId);
        }
    }

    @Override
    public void unbindService(IBinder binder, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public void stopServiceToken(ComponentName className, IBinder token, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.stopServiceToken(className, token, userId);
        }
    }

    @Override
    public AppConfig initProcess(String packageName, String processName, int userId) {
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(packageName, processName, userId, -1, Binder.getCallingPid());
        if (processRecord == null)
            return null;
        return processRecord.getClientConfig();
    }

    @Override
    public void restartProcess(String packageName, String processName, int userId) {
        BProcessManagerService.get().restartAppProcess(packageName, processName, userId);
    }

    @Override
    public void startActivity(Intent intent, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.startActivityLocked(userId, intent, null, null, null, -1, -1, null);
        }
    }

    @Override
    public int startActivityAms(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivityLocked(userId, intent, resolvedType, resultTo, resultWho, requestCode, flags, options);
        }
    }

    @Override
    public int startActivities(int userId, Intent[] intent, String[] resolvedType, IBinder resultTo, Bundle options) {
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) {
            return space.mStack.startActivitiesLocked(userId, intent, resolvedType, resultTo, options);
        }
    }

    private UserSpace getOrCreateSpaceLocked(int userId) {
        synchronized (mUserSpace) {
            UserSpace userSpace = mUserSpace.get(userId);
            if (userSpace != null) return userSpace;
            userSpace = new UserSpace();
            mUserSpace.put(userId, userSpace);
            return userSpace;
        }
    }

    @Override
    public void systemReady() {
        mBroadcastManager.startup();
    }
}
