package com.featurestic.box.app.dispatcher;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.entity.ServiceRecord;
import com.featurestic.box.entity.UnbindRecord;
import com.featurestic.box.proxy.record.ProxyServiceRecord;

public class AppServiceDispatcher {
    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();
    private final Map<Intent.FilterComparison, ServiceRecord> mService = new ConcurrentHashMap<>();
    private final Handler mHandler = FeaturesticBoxCore.get().getHandler();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    public IBinder onBind(Intent proxyIntent) {
        if (proxyIntent == null) {
            return null;
        }
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null) {
            return null;
        }

        Service service = getOrCreateService(serviceRecord);
        if (service == null) {
            return null;
        }
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        record.incrementAndGetBindCount(intent);

        if (record.hasBinder(intent)) {
            if (record.isRebind()) {
                try {
                    service.onRebind(intent);
                } catch (Throwable ignored) {
                }
                record.setRebind(false);
            }
            return record.getBinder(intent);
        }

        try {
            IBinder iBinder = service.onBind(intent);
            record.addBinder(intent, iBinder);
            return iBinder;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    public int onStartCommand(Intent proxyIntent) {
        if (proxyIntent == null) {
            return Service.START_NOT_STICKY;
        }
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return Service.START_NOT_STICKY;
        }

        Service service = getOrCreateService(stubRecord);
        if (service == null) {
            return Service.START_NOT_STICKY;
        }
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        if (record != null) {
            record.setStartId(stubRecord.mStartId);
        }
        try {
            int ret = service.onStartCommand(stubRecord.mServiceIntent, 0, stubRecord.mStartId);
            FeaturesticBoxCore.getBActivityManager().onStartCommand(proxyIntent, stubRecord.mUserId);
            return ret;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return Service.START_NOT_STICKY;
    }

    public void onDestroy() {
        if (!mService.isEmpty()) {
            for (ServiceRecord record : mService.values()) {
                try {
                    if (record != null && record.getService() != null) {
                        record.getService().onDestroy();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        mService.clear();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (!mService.isEmpty()) {
            for (ServiceRecord record : mService.values()) {
                try {
                    if (record != null && record.getService() != null) {
                        record.getService().onConfigurationChanged(newConfig);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void onLowMemory() {
        if (!mService.isEmpty()) {
            for (ServiceRecord record : mService.values()) {
                try {
                    if (record != null && record.getService() != null) {
                        record.getService().onLowMemory();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void onTrimMemory(int level) {
        if (!mService.isEmpty()) {
            for (ServiceRecord record : mService.values()) {
                try {
                    if (record != null && record.getService() != null) {
                        record.getService().onTrimMemory(level);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public void onUnbind(Intent proxyIntent) {
        if (proxyIntent == null) {
            return;
        }
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return;
        }

        Intent intent = stubRecord.mServiceIntent;
        try {
            UnbindRecord unbindRecord = FeaturesticBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, BActivityThread.getUserId());
            if (unbindRecord == null) {
                return;
            }

            Service service = getOrCreateService(stubRecord);
            if (service == null) {
                return;
            }
            stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

            ServiceRecord record = findRecord(intent);
            if (record == null) {
                return;
            }
            boolean destroy = unbindRecord.getStartId() == 0;

            if (destroy || record.decreaseConnectionCount(intent)) {
                try {
                    service.onUnbind(intent);
                } catch (Throwable ignored) {
                }
                if (destroy) {
                    try {
                        service.onDestroy();
                    } catch (Throwable ignored) {
                    }
                    FeaturesticBoxCore.getBActivityManager().onServiceDestroy(proxyIntent, BActivityThread.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
                record.setRebind(true);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent intent) {
        if (intent == null) {
            return;
        }

        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return;
        }

        final Service service = record.getService();
        if (service != null) {
            boolean destroy = record.getStartId() > 0;
            try {
                if (destroy) {
                    mHandler.post(() -> {
                        try {
                            service.onDestroy();
                        } catch (Throwable ignored) {
                        }
                    });
                    FeaturesticBoxCore.getBActivityManager().onServiceDestroy(intent, BActivityThread.getUserId());
                    mService.remove(new Intent.FilterComparison(intent));
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private ServiceRecord findRecord(Intent intent) {
        if (intent == null) {
            return null;
        }
        return mService.get(new Intent.FilterComparison(intent));
    }

    private synchronized Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            return record.getService();
        }

        Service service = BActivityThread.currentActivityThread().createService(serviceInfo, token);
        if (service == null) {
            return null;
        }

        record = new ServiceRecord();
        record.setService(service);
        mService.put(new Intent.FilterComparison(intent), record);
        return service;
    }
}
