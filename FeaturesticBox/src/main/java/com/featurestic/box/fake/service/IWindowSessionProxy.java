package com.featurestic.box.fake.service;

import android.os.Build;
import android.os.IInterface;
import android.view.WindowManager;

import java.lang.reflect.Method;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;

/**
 * Created by @FeaturesticBox on 4/6/21.
 */
public class IWindowSessionProxy extends BinderInvocationStub {
    public static final String TAG = "WindowSessionStub";

    private IInterface mSession;

    public IWindowSessionProxy(IInterface session) {
        super(session.asBinder());
        mSession = session;
    }

    @Override
    protected Object getWho() {
        return mSession;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object getProxyInvocation() {
        return super.getProxyInvocation();
    }

    @SuppressWarnings("deprecation")
    private static void normalizeLayoutParams(WindowManager.LayoutParams lp) {
        lp.packageName = FeaturesticBoxCore.getHostPkg();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (lp.type == WindowManager.LayoutParams.TYPE_PHONE
                    || lp.type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    || lp.type == WindowManager.LayoutParams.TYPE_TOAST
                    || lp.type == WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                    || lp.type == WindowManager.LayoutParams.TYPE_PRIORITY_PHONE
                    || lp.type == WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                    || (lp.type >= 2002 && lp.type <= 2010)) {
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            }
        }
    }

    @ProxyMethod("addToDisplay")
    public static class AddToDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof WindowManager.LayoutParams) {
                        normalizeLayoutParams((WindowManager.LayoutParams) arg);
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("addToDisplayAsUser")
    public static class AddToDisplayAsUser extends AddToDisplay {
    }

    @ProxyMethod("addToDisplayWithoutInputChannel")
    public static class AddToDisplayWithoutInputChannel extends AddToDisplay {
    }

    @ProxyMethod("relayout")
    public static class Relayout extends AddToDisplay {
    }
}
