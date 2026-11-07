package com.featurestic.box.core.system;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.compat.BuildCompat;

public class DaemonService extends Service {
    public static final String TAG = "DaemonService";

    private static int getNotifyId() {
        try {
            String pkg = FeaturesticBoxCore.getHostPkg();
            int h = pkg != null ? pkg.hashCode() : 0x52424F58;
            return (h == 0) ? 0x52424F58 : Math.abs(h);
        } catch (Throwable ignored) {
            return 0x52424F58;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        showNotificationSafely();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotificationSafely();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void showNotificationSafely() {
        try {
            String channelId = getPackageName() + ".featuresticbox_core";
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), channelId)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle("FeaturesticBox Runtime")
                    .setContentText("Virtual container active")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setSilent(true);
            int notifyId = getNotifyId();
            if (BuildCompat.isUpsideDownCake() && Build.VERSION.SDK_INT >= 34) {
                startForeground(notifyId, builder.build(), 1073741824);
            } else {
                startForeground(notifyId, builder.build());
            }
        } catch (Throwable ignored) {
            try {
                stopSelf();
            } catch (Throwable ignoredStop) {
            }
        }
    }

    public static class DaemonInnerService extends Service {
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(getNotifyId());
                }
            } catch (Throwable ignored) {
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }
    }
}
