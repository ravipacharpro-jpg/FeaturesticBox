package com.featurestic.box.fake.service;

import android.os.IBinder;
import java.lang.reflect.Method;
import black.android.os.BRServiceManager;
import black.android.os.System.BRISystemUpdateStub;
import com.featurestic.box.fake.hook.BinderInvocationStub;

public class ISystemUpdateProxy extends BinderInvocationStub {
    public ISystemUpdateProxy() {
        super(BRServiceManager.get().getService("system_update"));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService("system_update");
        if (binder == null) return null;
        return BRISystemUpdateStub.get().asInterface(binder);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (getWho() != null) {
            replaceSystemService("system_update");
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
