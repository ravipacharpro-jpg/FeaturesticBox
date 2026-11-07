package com.featurestic.box.core.system.am;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.IEmpty;
import com.featurestic.box.core.system.BProcessManagerService;
import com.featurestic.box.core.system.ProcessRecord;
import com.featurestic.box.core.system.pm.BPackageManagerService;
import com.featurestic.box.entity.UnbindRecord;
import com.featurestic.box.entity.am.RunningServiceInfo;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.proxy.record.ProxyServiceRecord;
import com.featurestic.box.utils.provider.ProviderCall;

@SuppressLint("NewApi")
public class ActiveServices {
    public static final String TAG = "ActiveServices";

    private final Map<Intent.FilterComparison, RunningServiceRecord> mRunningServiceRecords = new HashMap<>();
    private final Map<IBinder, RunningServiceRecord> mRunningTokens = new HashMap<>();
    private final Map<IBinder, ConnectedServiceRecord> mConnectedServices = new HashMap<>();

    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        if (intent == null) return null;
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        }
        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        final ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(
                serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
        if (processRecord == null) {
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }
        RunningServiceRecord runningServiceRecord;
        synchronized (mRunningServiceRecords) {
            runningServiceRecord = getOrCreateRunningServiceRecord(intent);
            runningServiceRecord.mServiceInfo = serviceInfo;
            runningServiceRecord.getAndIncrementStartId();
        }
        final Intent stubServiceIntent = createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ComponentName started = FeaturesticBoxCore.getContext().startService(stubServiceIntent);
                    if (started != null) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
                // Fallback for Android 12-16 background service start restrictions:
                // dispatch directly to the target virtual process via its ProxyContentProvider
                try {
                    Bundle extras = new Bundle();
                    extras.putParcelable("_Black_|_service_intent_", stubServiceIntent);
                    ProviderCall.callSafely(processRecord.getProviderAuthority(), "_Black_|_start_service_", null, extras);
                } catch (Throwable t) {
                    Log.w(TAG, "Direct provider startService fallback failed", t);
                }
            }
        }, "fbox-start-service").start();
        return new ComponentName(serviceInfo.packageName, serviceInfo.name);
    }

    public int stopService(Intent intent, String resolvedType, int userId) {
        if (intent == null) return 0;
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
            if (runningServiceRecord == null) {
                return 0;
            }
            if (runningServiceRecord.mBindCount.get() > 0) {
                Log.d(TAG, "There are also connections");
                return 0;
            }
            runningServiceRecord.mStartId.set(0);
            ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
            if (resolveInfo == null || resolveInfo.serviceInfo == null)
                return 0;
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(
                    serviceInfo.packageName, serviceInfo.processName, userId);
            if (processRecord == null || processRecord.bActivityThread == null) {
                mRunningServiceRecords.remove(new Intent.FilterComparison(intent));
                mRunningTokens.remove(runningServiceRecord);
                return 0;
            }
            try {
                processRecord.bActivityThread.stopService(intent);
            } catch (Exception ignored) {
            }
        }
        return 1;
    }

    public Intent bindService(Intent intent, final IBinder binder, String resolvedType, int userId) {
        if (intent == null) return null;
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.serviceInfo == null)
            return intent;
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(
                serviceInfo.packageName,
                serviceInfo.processName,
                userId,
                -1, Binder.getCallingPid());

        if (processRecord == null) {
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }

        RunningServiceRecord runningServiceRecord;
        synchronized (mRunningServiceRecords) {
            if (intent.getComponent() == null) {
                intent.setComponent(new ComponentName(serviceInfo.packageName, serviceInfo.name));
            }
            runningServiceRecord = getOrCreateRunningServiceRecord(intent);
            runningServiceRecord.mServiceInfo = serviceInfo;

            if (binder != null) {
                ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
                boolean isBound = false;
                if (connectedService != null) {
                    isBound = true;
                } else {
                    connectedService = new ConnectedServiceRecord();
                    try {
                        binder.linkToDeath(new IBinder.DeathRecipient() {
                            @Override
                            public void binderDied() {
                                try {
                                    binder.unlinkToDeath(this, 0);
                                } catch (Throwable ignored) {
                                }
                                mConnectedServices.remove(binder);
                            }
                        }, 0);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    connectedService.mIBinder = binder;
                    connectedService.mIntent = intent;
                    mConnectedServices.put(binder, connectedService);
                }

                if (!isBound) {
                    runningServiceRecord.incrementBindCountAndGet();
                }
                runningServiceRecord.mConnectedServiceRecord = connectedService;
            }
        }
        return createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
    }

    public void unbindService(IBinder binder, int userId) {
        if (binder == null) return;
        ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
        if (connectedService == null || connectedService.mIntent == null) {
            return;
        }
        RunningServiceRecord runningServiceRecord = getOrCreateRunningServiceRecord(connectedService.mIntent);
        runningServiceRecord.mConnectedServiceRecord = null;
        if (runningServiceRecord.mBindCount.get() > 0) {
            runningServiceRecord.mBindCount.decrementAndGet();
        }
        mConnectedServices.remove(binder);
    }

    public void stopServiceToken(ComponentName className, IBinder token, int userId) {
        RunningServiceRecord runningServiceByToken = findRunningServiceByToken(token);
        if (runningServiceByToken != null && runningServiceByToken.mIntent != null) {
            stopService(runningServiceByToken.mIntent, null, userId);
        }
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
        if (proxyIntent == null)
            return;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        if (proxyServiceRecord.mServiceIntent != null) {
            proxyIntent = proxyServiceRecord.mServiceIntent;
        }
        RunningServiceRecord remove = mRunningServiceRecords.remove(new Intent.FilterComparison(proxyIntent));
        if (remove != null) {
            mRunningTokens.remove(remove);
        }
    }

    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) {
        if (proxyIntent == null)
            return null;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        if (proxyServiceRecord.mServiceIntent == null)
            return null;
        ComponentName component = proxyServiceRecord.mServiceIntent.getComponent();

        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(proxyServiceRecord.mServiceIntent);
        if (runningServiceRecord == null)
            return null;
        UnbindRecord record = new UnbindRecord();
        record.setComponentName(component);
        record.setBindCount(runningServiceRecord.mBindCount.get());
        record.setStartId(runningServiceRecord.mStartId.get());
        return record;
    }

    private Intent createStubServiceIntent(Intent targetIntent, ServiceInfo serviceInfo, ProcessRecord processRecord, RunningServiceRecord runningServiceRecord) {
        Intent stub = new Intent();
        ComponentName stubComp = new ComponentName(FeaturesticBoxCore.getHostPkg(), ProxyManifest.getProxyService(processRecord.bpid));
        stub.setComponent(stubComp);
        stub.setAction(UUID.randomUUID().toString());
        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord, processRecord.userId, runningServiceRecord.mStartId.get());
        return stub;
    }

    private RunningServiceRecord getOrCreateRunningServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
        if (runningServiceRecord == null) {
            runningServiceRecord = new RunningServiceRecord();
            runningServiceRecord.mIntent = intent;
            mRunningServiceRecords.put(new Intent.FilterComparison(intent), runningServiceRecord);
            mRunningTokens.put(runningServiceRecord, runningServiceRecord);
        }
        return runningServiceRecord;
    }

    private RunningServiceRecord findRunningServiceRecord(Intent intent) {
        if (intent == null) return null;
        return mRunningServiceRecords.get(new Intent.FilterComparison(intent));
    }

    private RunningServiceRecord findRunningServiceByToken(IBinder token) {
        if (token == null) return null;
        return mRunningTokens.get(token);
    }

    public RunningServiceInfo getRunningServiceInfo(String callerPackage, int userId) {
        RunningServiceInfo info = new RunningServiceInfo();
        ActivityManager manager = (ActivityManager)
                FeaturesticBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return info;
        List<ActivityManager.RunningServiceInfo> runningServices = null;
        try {
            runningServices = manager.getRunningServices(Integer.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        Map<Integer, ActivityManager.RunningServiceInfo> serviceInfoMap = new HashMap<>();
        if (runningServices != null) {
            for (ActivityManager.RunningServiceInfo runningService : runningServices) {
                if (runningService != null) {
                    serviceInfoMap.put(runningService.pid, runningService);
                }
            }
        }

        for (RunningServiceRecord value : mRunningServiceRecords.values()) {
            if (value == null || value.mServiceInfo == null) continue;
            ServiceInfo serviceInfo = value.mServiceInfo;
            if (BActivityManagerService.isProtectedGamePackage(callerPackage)
                    && !callerPackage.equals(serviceInfo.packageName)) {
                continue;
            }
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(
                    serviceInfo.packageName, serviceInfo.processName, userId);
            if (processRecord == null)
                continue;
            ActivityManager.RunningServiceInfo runningServiceInfo = serviceInfoMap.get(processRecord.pid);
            if (runningServiceInfo == null) {
                runningServiceInfo = new ActivityManager.RunningServiceInfo();
                runningServiceInfo.pid = processRecord.pid;
                runningServiceInfo.uid = processRecord.uid;
            }
            runningServiceInfo.process = processRecord.processName;
            runningServiceInfo.service = new ComponentName(serviceInfo.packageName, serviceInfo.name);
            info.mRunningServiceInfoList.add(runningServiceInfo);
        }
        return info;
    }

    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.serviceInfo == null)
            return null;
        ProcessRecord processRecord =
                BProcessManagerService.get().findProcessRecord(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.processName, userId);
        if (processRecord == null || processRecord.bActivityThread == null)
            return null;
        try {
            return processRecord.bActivityThread.peekService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private ResolveInfo resolveService(Intent intent, String resolvedType, int userId) {
        if (intent == null) return null;
        return BPackageManagerService.get().resolveService(intent, 0, resolvedType, userId);
    }

    public static class RunningServiceRecord extends IEmpty.Stub {
        private final AtomicInteger mStartId = new AtomicInteger(1);
        private final AtomicInteger mBindCount = new AtomicInteger(0);
        private ConnectedServiceRecord mConnectedServiceRecord;

        private ServiceInfo mServiceInfo;
        private Intent mIntent;

        public int getAndIncrementStartId() {
            return mStartId.getAndIncrement();
        }

        public int decrementBindCountAndGet() {
            return mBindCount.decrementAndGet();
        }

        public int incrementBindCountAndGet() {
            return mBindCount.incrementAndGet();
        }
    }

    public static class ConnectedServiceRecord {
        private IBinder mIBinder;
        private Intent mIntent;
    }
}
