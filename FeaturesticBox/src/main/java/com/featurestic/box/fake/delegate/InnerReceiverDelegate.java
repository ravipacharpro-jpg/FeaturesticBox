package com.featurestic.box.fake.delegate;

import android.app.Application;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import black.android.content.BRIIntentReceiver;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.proxy.record.ProxyBroadcastRecord;

public class InnerReceiverDelegate extends IIntentReceiver.Stub {
    public static final String TAG = "InnerReceiverDelegate";

    private static final Map<IBinder, InnerReceiverDelegate> sInnerReceiverDelegate = new ConcurrentHashMap<>();
    private final IIntentReceiver mIntentReceiver;

    private InnerReceiverDelegate(IIntentReceiver iIntentReceiver) {
        this.mIntentReceiver = iIntentReceiver;
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
            delegate = new InnerReceiverDelegate(base);
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
        if (mIntentReceiver != null) {
            BRIIntentReceiver.get(mIntentReceiver).performReceive(perIntent, resultCode, data, extras, ordered, sticky, sendingUser);
        }
    }
}
