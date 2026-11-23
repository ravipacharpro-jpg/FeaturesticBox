package com.featurestic.box.proxy;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.dispatcher.AppServiceDispatcher;
import com.featurestic.box.utils.compat.BuildCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyService extends Service {
    public static final String TAG = "StubService";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return AppServiceDispatcher.get().onBind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        showNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification();
        AppServiceDispatcher.get().onStartCommand(intent);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppServiceDispatcher.get().onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppServiceDispatcher.get().onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AppServiceDispatcher.get().onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppServiceDispatcher.get().onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AppServiceDispatcher.get().onUnbind(intent);
        return false;
    }

    private void showNotification() {
        try {
            String channelId = getPackageName() + ".featuresticbox_core";
            NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), channelId)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle("Featurestic Shield")
                    .setContentText("Game process protection active")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setSilent(true);
            int notifyId = Math.abs(FeaturesticBoxCore.getHostPkg().hashCode()) + 100;
            if (BuildCompat.isUpsideDownCake()) {
                startForeground(notifyId, builder.build(), 1073741824);
            } else if (BuildCompat.isOreo()) {
                startForeground(notifyId, builder.build());
            }
        } catch (Throwable ignored) {
        }
    }

    public static class P0 extends ProxyService { }

    public static class P1 extends ProxyService { }

    public static class P2 extends ProxyService { }

    public static class P3 extends ProxyService { }

    public static class P4 extends ProxyService { }

    public static class P5 extends ProxyService { }

    public static class P6 extends ProxyService { }

    public static class P7 extends ProxyService { }

    public static class P8 extends ProxyService { }

    public static class P9 extends ProxyService { }

    public static class P10 extends ProxyService { }

    public static class P11 extends ProxyService { }

    public static class P12 extends ProxyService { }

    public static class P13 extends ProxyService { }

    public static class P14 extends ProxyService { }

    public static class P15 extends ProxyService { }

    public static class P16 extends ProxyService { }

    public static class P17 extends ProxyService { }

    public static class P18 extends ProxyService { }

    public static class P19 extends ProxyService { }

    public static class P20 extends ProxyService { }

    public static class P21 extends ProxyService { }

    public static class P22 extends ProxyService { }

    public static class P23 extends ProxyService { }

    public static class P24 extends ProxyService { }

}
