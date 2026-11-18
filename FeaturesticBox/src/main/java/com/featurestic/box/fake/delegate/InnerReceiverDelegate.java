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

    private static boolean tryInvokeExtendedPerformReceive(
            Object target,
            Intent perIntent,
            int resultCode,
            String data,
            Bundle extras,
            boolean ordered,
            boolean sticky,
            int sendingUser) {
        if (target == null) return false;
        try {
            for (Method m : target.getClass().getDeclaredMethods()) {
                if (!"performReceive".equals(m.getName())) continue;
                Class<?>[] pTypes = m.getParameterTypes();
                // On Android 14-16 (API 34-36), LoadedApk$ReceiverDispatcher.performReceive
                // and InnerReceiver.performReceive have an 8, 9, or 10-arg overload
                // (e.g. Intent, int, String, Bundle, boolean, boolean, boolean assumeDelivered, int sendingUser, int sendingUid, String sendingPackage)
                // that avoids the 7-arg raw IIntentReceiver Log.wtf check.
                if (pTypes.length >= 8
                        && pTypes[0] == Intent.class
                        && pTypes[1] == int.class
                        && pTypes[2] == String.class
                        && pTypes[3] == Bundle.class
                        && pTypes[4] == boolean.class
                        && pTypes[5] == boolean.class) {
                    Object[] invokeArgs = new Object[pTypes.length];
                    invokeArgs[0] = perIntent;
                    invokeArgs[1] = resultCode;
                    invokeArgs[2] = data;
                    invokeArgs[3] = extras;
                    invokeArgs[4] = ordered;
                    invokeArgs[5] = sticky;
                    int intSlot = 0;
                    for (int i = 6; i < pTypes.length; i++) {
                        if (pTypes[i] == boolean.class) {
                            invokeArgs[i] = ordered; // assumeDelivered
                        } else if (pTypes[i] == int.class) {
                            if (intSlot == 0) {
                                invokeArgs[i] = sendingUser;
                            } else {
                                invokeArgs[i] = -1; // sendingUid / flags
                            }
                            intSlot++;
                        } else if (pTypes[i] == String.class) {
                            invokeArgs[i] = null; // sendingPackage
                        } else {
                            invokeArgs[i] = null;
                        }
                    }
                    m.setAccessible(true);
                    m.invoke(target, invokeArgs);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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

        Object dispatcher = mDispatcherRef != null ? mDispatcherRef.get() : null;
        if (tryInvokeExtendedPerformReceive(dispatcher, perIntent, resultCode, data, extras, ordered, sticky, sendingUser)) {
            return;
        }
        if (tryInvokeExtendedPerformReceive(mIntentReceiver, perIntent, resultCode, data, extras, ordered, sticky, sendingUser)) {
            return;
        }
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
        }
        if (mIntentReceiver != null) {
            BRIIntentReceiver.get(mIntentReceiver).performReceive(perIntent, resultCode, data, extras, ordered, sticky, sendingUser);
        }
    }
}
