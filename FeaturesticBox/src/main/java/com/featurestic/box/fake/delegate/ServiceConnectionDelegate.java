package com.featurestic.box.fake.delegate;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
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

    private static volatile Boolean sHasIBinderSession;

    public static boolean hasAndroid17BinderSession() {
        Boolean cached = sHasIBinderSession;
        if (cached != null) return cached;
        boolean found = false;
        try {
            Class.forName("android.app.IBinderSession");
            found = true;
        } catch (Throwable ignored) {
        }
        sHasIBinderSession = found;
        return found;
    }

    public static Object asBinderSession(IBinder sessionBinder) {
        if (sessionBinder == null) return null;
        try {
            Class<?> stub = Class.forName("android.app.IBinderSession$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, sessionBinder);
        } catch (Throwable ignored) {
            return sessionBinder;
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.FIRST_CALL_TRANSACTION && hasAndroid17BinderSession()) {
            data.enforceInterface("android.app.IServiceConnection");
            ComponentName name = (data.readInt() != 0) ? ComponentName.CREATOR.createFromParcel(data) : null;
            IBinder service = data.readStrongBinder();
            IBinder sessionBinder = data.readStrongBinder();
            boolean dead = (data.readInt() != 0);
            ComponentName target = mComponentName != null ? mComponentName : name;
            dispatchConnected(mConn, target, service, asBinderSession(sessionBinder), dead);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public static void dispatchConnected(IServiceConnection conn, ComponentName target, IBinder service, Object sessionObj, boolean dead) throws RemoteException {
        if (conn == null) return;
        // 1. Try 4-arg connected(ComponentName, IBinder, IBinderSession | IBinder, boolean) (Android 10-17)
        try {
            for (Method m : conn.getClass().getMethods()) {
                if (!"connected".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 4 && pt[0] == ComponentName.class && pt[1] == IBinder.class && pt[3] == boolean.class) {
                    m.setAccessible(true);
                    Object arg2 = null;
                    if (pt[2] == IBinder.class) {
                        arg2 = (sessionObj instanceof IBinder) ? sessionObj : null;
                    } else if (sessionObj != null && pt[2].isInstance(sessionObj)) {
                        arg2 = sessionObj;
                    } else if (sessionObj instanceof IBinder) {
                        arg2 = asBinderSession((IBinder) sessionObj);
                    }
                    m.invoke(conn, target, service, arg2, dead);
                    return;
                }
            }
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
