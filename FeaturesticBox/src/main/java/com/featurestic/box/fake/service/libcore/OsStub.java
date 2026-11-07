package com.featurestic.box.fake.service.libcore;

import android.os.Process;

import java.lang.reflect.Method;

import black.libcore.io.BRLibcore;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.RCore;
import com.featurestic.box.fake.hook.ClassInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.Reflector;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;

/**
 * Created by @FeaturesticBox on 4/9/21.
 */
public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = RCore.get().redirectPath(orig);
                }
            }
        }
        String mName = method != null ? method.getName() : "";
        if ("connect".equals(mName) && args != null && args.length > 0
                && args[0] instanceof java.io.FileDescriptor) {
            NetworkLowLatencyOptimizer.optimizeSocketOnConnect((java.io.FileDescriptor) args[0]);
        }
        Object result = super.invoke(proxy, method, args);
        if ("socket".equals(mName) && result instanceof java.io.FileDescriptor) {
            NetworkLowLatencyOptimizer.optimizeSocketOnCreation((java.io.FileDescriptor) result, args);
        }
        return result;
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("geteuid")
    public static class geteuid extends getuid {
    }

    @ProxyMethod("stat")
    public static class stat extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object invoke;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
            if (invoke != null) {
                try {
                    int currentStUid = (int) Reflector.with(invoke).field("st_uid").get();
                    Reflector.with(invoke).field("st_uid").set(getFakeUid(currentStUid));
                } catch (Throwable ignored) {
                }
            }
            return invoke;
        }
    }

    @ProxyMethod("lstat")
    public static class lstat extends stat {
    }

    private static int getFakeUid(int callUid) {
        if (callUid > 0 && callUid <= Process.FIRST_APPLICATION_UID) {
            return callUid;
        }
        return FeaturesticBoxCore.getHostUid();
    }
}
