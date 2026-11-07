package com.featurestic.box.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.content.BRAttributionSource;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.fake.hook.ProxyMethods;
import com.featurestic.box.utils.MethodParameterUtils;
import com.featurestic.box.utils.compat.ContextCompat;

public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) FeaturesticBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    private static void normalizeArgs(Object[] args) {
        if (args == null) return;
        MethodParameterUtils.replaceAllAppPkg(args);
        MethodParameterUtils.replaceFirstUid(args);
        MethodParameterUtils.replaceLastUid(args);
        if (BRAttributionSource.getRealClass() != null) {
            String attrClassName = BRAttributionSource.getRealClass().getName();
            for (Object arg : args) {
                if (arg != null && attrClassName.equals(arg.getClass().getName())) {
                    ContextCompat.fixAttributionSourceState(arg, FeaturesticBoxCore.getHostUid());
                }
            }
        }
    }

    static Object allowedAppOpResult(Method method, Object[] args) {
        if (method == null) return AppOpsManager.MODE_ALLOWED;
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return AppOpsManager.MODE_ALLOWED;
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return true;
        }
        if ("android.app.SyncNotedAppOp".equals(returnType.getName())) {
            int opCode = 27; // OP_RECORD_AUDIO fallback
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof Integer) {
                        int val = (Integer) arg;
                        if (val >= 0 && val < 200) {
                            opCode = val;
                            break;
                        }
                    }
                }
            }
            try {
                for (Constructor<?> c : returnType.getDeclaredConstructors()) {
                    c.setAccessible(true);
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 4 && p[0] == int.class && p[1] == int.class && p[2] == String.class && p[3] == String.class) {
                        return c.newInstance(AppOpsManager.MODE_ALLOWED, opCode, null, FeaturesticBoxCore.getHostPkg());
                    }
                    if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == String.class) {
                        return c.newInstance(AppOpsManager.MODE_ALLOWED, opCode, null);
                    }
                    if (p.length == 2 && p[0] == int.class && p[1] == String.class) {
                        return c.newInstance( opCode, null);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        normalizeArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException | IllegalArgumentException ignored) {
            return allowedAppOpResult(method, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) {
                return allowedAppOpResult(method, args);
            }
            throw cause != null ? cause : e;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowedAppOpResult(method, args);
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @ProxyMethods({"checkOperation", "checkOperationRaw", "checkOperationForDevice", "checkOperationRawForDevice", "checkAudioOperation", "noteOperation", "noteOp", "noteOpNoThrow", "startOperation", "startOp", "startOpNoThrow"})
    public static class SafeOpCheck extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            normalizeArgs(args);
            try {
                Object res = method.invoke(who, args);
                if (res instanceof Integer && ((Integer) res) != AppOpsManager.MODE_ALLOWED) {
                    return AppOpsManager.MODE_ALLOWED;
                }
                return res;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException();
                if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) {
                    return allowedAppOpResult(method, args);
                }
                throw cause != null ? cause : e;
            } catch (SecurityException | IllegalArgumentException ignored) {
                return allowedAppOpResult(method, args);
            }
        }
    }
}
