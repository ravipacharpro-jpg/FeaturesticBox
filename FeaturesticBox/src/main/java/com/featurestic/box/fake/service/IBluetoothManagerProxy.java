package com.featurestic.box.fake.service;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.bluetooth.BRIBluetoothManagerStub;
import black.android.content.BRAttributionSource;
import black.android.os.BRServiceManager;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.MethodParameterUtils;
import com.featurestic.box.utils.compat.ContextCompat;

public final class IBluetoothManagerProxy extends BinderInvocationStub {

    private static final String SERVICE_NAME = "bluetooth_manager";
    private Object mBase;

    public IBluetoothManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        if (mBase != null) {
            return mBase;
        }
        Object service = BRServiceManager.get().getService(SERVICE_NAME);
        if (service == null) {
            return null;
        }
        IBinder binder = (IBinder) service;
        mBase = BRIBluetoothManagerStub.get().asInterface(binder);
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object base = (mBase != null) ? mBase : getWho();
        if (base == null) {
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return false;
            if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return 0;
            return null;
        }
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(base, args);
        }
        MethodParameterUtils.replaceAllAppPkg(args);
        if (args != null && BRAttributionSource.getRealClass() != null) {
            String attrClassName = BRAttributionSource.getRealClass().getName();
            for (Object arg : args) {
                if (arg != null && attrClassName.equals(arg.getClass().getName())) {
                    ContextCompat.fixAttributionSourceState(arg, FeaturesticBoxCore.getHostUid());
                }
            }
        }
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException ignored) {
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return false;
            if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return 0;
            return null;
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SecurityException) {
                if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return false;
                if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return 0;
                return null;
            }
            throw e.getTargetException() != null ? e.getTargetException() : e;
        }
    }

    @ProxyMethod("getName")
    public static final class GetName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }
}
