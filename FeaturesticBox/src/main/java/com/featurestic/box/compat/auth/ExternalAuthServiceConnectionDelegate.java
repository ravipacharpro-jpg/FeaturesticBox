package com.featurestic.box.compat.auth;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.lsposed.lsparanoid.Obfuscate;

import black.android.app.BRIServiceConnectionO;
import com.featurestic.box.utils.compat.BuildCompat;

/**
 * Keeps external authentication service callbacks inside the virtual process
 * while allowing the actual provider service on the phone to remain authoritative.
 */
@Obfuscate
public final class ExternalAuthServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ExternalAuthServiceConnectionDelegate> CACHE =
            new ConcurrentHashMap<>();

    private final IServiceConnection base;

    private ExternalAuthServiceConnectionDelegate(IServiceConnection base) {
        this.base = base;
    }

    public static IServiceConnection createProxy(IServiceConnection base) {
        if (base == null) {
            return null;
        }
        final IBinder binder = base.asBinder();
        ExternalAuthServiceConnectionDelegate cached = CACHE.get(binder);
        if (cached != null) {
            return cached;
        }

        ExternalAuthServiceConnectionDelegate created =
                new ExternalAuthServiceConnectionDelegate(base);
        ExternalAuthServiceConnectionDelegate previous = CACHE.putIfAbsent(binder, created);
        ExternalAuthServiceConnectionDelegate result = previous != null ? previous : created;

        if (previous == null) {
            try {
                binder.linkToDeath(() -> CACHE.remove(binder), 0);
            } catch (Throwable ignored) {
                // The original IServiceConnection may already be local/dead. The
                // cache is only an optimization; callback delivery still works.
            }
        }
        return result;
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead)
            throws RemoteException {
        connected(name, service, null, dead);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == IBinder.FIRST_CALL_TRANSACTION
                && com.featurestic.box.fake.delegate.ServiceConnectionDelegate.hasAndroid17BinderSession()) {
            data.enforceInterface("android.app.IServiceConnection");
            ComponentName name = (data.readInt() != 0) ? ComponentName.CREATOR.createFromParcel(data) : null;
            IBinder service = data.readStrongBinder();
            IBinder sessionBinder = data.readStrongBinder();
            boolean dead = (data.readInt() != 0);
            IBinder delivered = PlayBillingBinderCompat.wrap(name, GmsBrokerCompat.wrap(service));
            Object sessionObj = com.featurestic.box.fake.delegate.ServiceConnectionDelegate.asBinderSession(sessionBinder);
            com.featurestic.box.fake.delegate.ServiceConnectionDelegate.dispatchConnected(
                    base, name, delivered, sessionObj, dead);
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public void connected(ComponentName name, IBinder service, IBinder session, boolean dead)
            throws RemoteException {
        IBinder delivered = PlayBillingBinderCompat.wrap(name, GmsBrokerCompat.wrap(service));
        com.featurestic.box.fake.delegate.ServiceConnectionDelegate.dispatchConnected(base, name, delivered, session, dead);
    }
}
