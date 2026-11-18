package com.featurestic.box.fake.service;

import android.os.Build;
import android.os.IInterface;
import android.view.WindowManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.MethodParameterUtils;

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
        if (lp == null) return;
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

    private static void normalizeAllWindowSessionArgs(Object[] args) {
        if (args == null) return;
        MethodParameterUtils.replaceAllAppPkg(args);
        for (Object arg : args) {
            if (arg instanceof WindowManager.LayoutParams) {
                normalizeLayoutParams((WindowManager.LayoutParams) arg);
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        normalizeAllWindowSessionArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException ignored) {
            return getSafeDefaultReturn(method);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof SecurityException) {
                return getSafeDefaultReturn(method);
            }
            throw target != null ? target : e;
        }
    }

    private static Object getSafeDefaultReturn(Method method) {
        if (method == null) return null;
        Class<?> rt = method.getReturnType();
        if (rt == int.class) return 0;
        if (rt == boolean.class) return false;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }

    @ProxyMethod("addToDisplay")
    public static class AddToDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            normalizeAllWindowSessionArgs(args);
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

    @ProxyMethod("relayoutAsync")
    public static class RelayoutAsync extends AddToDisplay {
    }
}
