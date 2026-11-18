package com.featurestic.box.fake.delegate;

import android.app.Application;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import black.android.app.BRLoadedApkReceiverDispatcher;
import black.android.app.BRLoadedApkReceiverDispatcherInnerReceiver;
import black.android.content.BRIIntentReceiver;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.proxy.record.ProxyBroadcastRecord;

public class InnerReceiverDelegate extends IIntentReceiver.Stub {
    public static final String TAG = "InnerReceiverDelegate";

    private static final Map<IBinder, InnerReceiverDelegate> sInnerReceiverDelegate = new ConcurrentHashMap<>();
    private final IIntentReceiver mIntentReceiver;
    private final WeakReference<?> mDispatcherRef;

    private InnerReceiverDelegate(IIntentReceiver iIntentReceiver, WeakReference<?> dispatcherRef) {
        this.mIntentReceiver = iIntentReceiver;
        this.mDispatcherRef = dispatcherRef;
    }

    public static InnerReceiverDelegate getDelegate(IBinder iBinder) {
        return iBinder != null ? sInnerReceiverDelegate.get(iBinder) : null;
    }

    public static IIntentReceiver createProxy(IIntentReceiver base) {
        if (base == null || base instanceof InnerReceiverDelegate) {
            return base;
        }
        final IBinder iBinder = base.asBinder();
        InnerReceiverDelegate delegate = sInnerReceiverDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sInnerReceiverDelegate.remove(iBinder);
                        try {
                            iBinder.unlinkToDeath(this, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }, 0);
            } catch (RemoteException ignored) {
            }
            WeakReference<?> weakDispatcher = null;
            try {
                weakDispatcher = BRLoadedApkReceiverDispatcherInnerReceiver.get(base).mDispatcher();
            } catch (Throwable ignored) {
            }
            delegate = new InnerReceiverDelegate(base, weakDispatcher);
            sInnerReceiverDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    @Override
    public void performReceive(Intent intent, int resultCode, String data, Bundle extras, boolean ordered, boolean sticky, int sendingUser) throws RemoteException {
        if (intent == null) {
            return;
        }
        Application app = BActivityThread.getApplication();
        ClassLoader loader = app != null ? app.getClassLoader() : InnerReceiverDelegate.class.getClassLoader();
        intent.setExtrasClassLoader(loader);
        ProxyBroadcastRecord proxyBroadcastRecord = ProxyBroadcastRecord.create(intent);
        Intent perIntent;
        if (proxyBroadcastRecord.mIntent != null) {
            proxyBroadcastRecord.mIntent.setExtrasClassLoader(loader);
            perIntent = proxyBroadcastRecord.mIntent;
        } else {
            perIntent = intent;
        }

        // On Android 14-16 (API 34-36), calling the 7-arg InnerReceiver.performReceive
        // directly triggers Log.wtf("LoadedApk", "performReceive() called targeting raw IIntentReceiver").
        // Dispatch directly to LoadedApk$ReceiverDispatcher.performReceive when available.
        Object dispatcher = mDispatcherRef != null ? mDispatcherRef.get() : null;
        if (dispatcher != null) {
            try {
                Method m7 = dispatcher.getClass().getDeclaredMethod(
                        "performReceive",
                        Intent.class, int.class, String.class, Bundle.class,
                        boolean.class, boolean.class, int.class);
                m7.setAccessible(true);
                m7.invoke(dispatcher, perIntent, resultCode, data, extras, ordered, sticky, sendingUser);
                return;
            } catch (Throwable ignored) {
            }
            try {
                for (Method m : dispatcher.getClass().getDeclaredMethods()) {
                    if ("performReceive".equals(m.getName())) {
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 10) {
                            m.setAccessible(true);
                            m.invoke(dispatcher, perIntent, resultCode, data, extras,
                                    ordered, sticky, 0, sendingUser, -1, null);
                            return;
                        } else if (pTypes.length == 9) {
                            m.setAccessible(true);
                            m.invoke(dispatcher, perIntent, resultCode, data, extras,
                                    ordered, sticky, sendingUser, -1, null);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (mIntentReceiver != null) {
            BRIIntentReceiver.get(mIntentReceiver).performReceive(perIntent, resultCode, data, extras, ordered, sticky, sendingUser);
        }
    }
}
