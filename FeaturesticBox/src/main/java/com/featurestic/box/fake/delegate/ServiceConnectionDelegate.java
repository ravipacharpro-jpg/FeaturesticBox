package com.featurestic.box.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import black.android.app.BRIServiceConnectionO;
import com.featurestic.box.utils.compat.BuildCompat;

public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new ConcurrentHashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return iBinder != null ? sServiceConnectDelegate.get(iBinder) : null;
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        if (base == null || base instanceof ServiceConnectionDelegate) {
            return base;
        }
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        try {
                            iBinder.unlinkToDeath(this, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }, 0);
            } catch (RemoteException ignored) {
            }
            ComponentName target = intent != null ? intent.getComponent() : null;
            delegate = new ServiceConnectionDelegate(base, target);
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, null, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        connected(name, service, null, dead);
    }

    // Android 10 - 16 (API 29 - 36) IServiceConnection.aidl signature:
    // void connected(in ComponentName name, IBinder service, IBinder session, boolean dead);
    public void connected(ComponentName name, IBinder service, IBinder session, boolean dead) throws RemoteException {
        ComponentName target = mComponentName != null ? mComponentName : name;
        dispatchConnected(mConn, target, service, session, dead);
    }

    public static void dispatchConnected(IServiceConnection conn, ComponentName target, IBinder service, IBinder session, boolean dead) throws RemoteException {
        if (conn == null) return;
        // 1. Try 4-arg connected(ComponentName, IBinder, IBinder, boolean) (Android 10-16)
        try {
            Method m4 = conn.getClass().getMethod("connected", ComponentName.class, IBinder.class, IBinder.class, boolean.class);
            m4.setAccessible(true);
            m4.invoke(conn, target, service, session, dead);
            return;
        } catch (Throwable ignored) {
        }
        // 2. Try 3-arg connected(ComponentName, IBinder, boolean) (Android 8-9)
        try {
            Method m3 = conn.getClass().getMethod("connected", ComponentName.class, IBinder.class, boolean.class);
            m3.setAccessible(true);
            m3.invoke(conn, target, service, dead);
            return;
        } catch (Throwable ignored) {
        }
        if (BuildCompat.isOreo()) {
            try {
                if (BRIServiceConnectionO.get(conn)._check_connected(target, service, dead) != null) {
                    BRIServiceConnectionO.get(conn).connected(target, service, dead);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 3. Fallback to 2-arg connected(ComponentName, IBinder)
        conn.connected(target, service);
    }
}
