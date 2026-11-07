package com.featurestic.box.compat.auth;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;

/**
 * Compatibility wrapper for Google Play Billing (com.android.vending.billing.IInAppBillingService).
 *
 * When a virtual game is also installed on the host device, Play Store accepts
 * its package name directly. When the virtual game is only installed inside
 * FeaturesticBox (not on the host), Play Store UID validation may reject the
 * virtual package name with BILLING_RESPONSE_RESULT_DEVELOPER_ERROR (5); this
 * wrapper normalizes the caller package to the host package as a fallback.
 */
@Obfuscate
public final class PlayBillingBinderCompat {
    private static final String BILLING_DESCRIPTOR =
            "com.android.vending.billing.IInAppBillingService";
    private static final Map<IBinder, IBinder> BINDER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PlayBillingBinderCompat() {
    }

    public static IBinder wrap(ComponentName name, IBinder base) {
        if (base == null || !isBillingService(name, base)) {
            return base;
        }
        IBinder cached = BINDER_CACHE.get(base);
        if (cached != null) {
            return cached;
        }
        try {
            IBinder wrapper = (IBinder) Proxy.newProxyInstance(
                    PlayBillingBinderCompat.class.getClassLoader(),
                    new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> {
                        if ("queryLocalInterface".equals(method.getName())
                                && args != null && args.length == 1
                                && BILLING_DESCRIPTOR.equals(args[0])) {
                            Object origIface = invokeBase(base, method, args);
                            if (origIface instanceof IInterface) {
                                return wrapBillingInterface((IInterface) origIface, (IBinder) proxy);
                            }
                        }
                        return invokeBase(base, method, args);
                    });
            BINDER_CACHE.put(base, wrapper);
            return wrapper;
        } catch (Throwable ignored) {
            return base;
        }
    }

    private static Object wrapBillingInterface(IInterface baseIface, IBinder wrapperBinder) {
        try {
            Class<?>[] ifaces = baseIface.getClass().getInterfaces();
            return Proxy.newProxyInstance(
                    baseIface.getClass().getClassLoader(),
                    ifaces,
                    (proxy, method, args) -> {
                        if ("asBinder".equals(method.getName())) {
                            return wrapperBinder;
                        }
                        if (args != null && !isVirtualPackageInstalledOnHost()) {
                            String vPkg = BActivityThread.getAppPackageName();
                            String hPkg = FeaturesticBoxCore.getHostPkg();
                            if (vPkg != null && hPkg != null) {
                                for (int i = 0; i < args.length; i++) {
                                    if (vPkg.equals(args[i])) {
                                        args[i] = hPkg;
                                    }
                                }
                            }
                        }
                        return invokeBase(baseIface, method, args);
                    });
        } catch (Throwable ignored) {
            return baseIface;
        }
    }

    private static boolean isVirtualPackageInstalledOnHost() {
        try {
            String vPkg = BActivityThread.getAppPackageName();
            if (vPkg == null || FeaturesticBoxCore.getContext() == null) return false;
            PackageManager pm = FeaturesticBoxCore.getContext().getPackageManager();
            return pm.getPackageInfo(vPkg, 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBillingService(ComponentName name, IBinder binder) {
        try {
            if (name != null && "com.android.vending".equals(name.getPackageName())) {
                return true;
            }
            return BILLING_DESCRIPTOR.equals(binder.getInterfaceDescriptor());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invokeBase(Object base, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(base, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getTargetException();
            throw cause != null ? cause : failure;
        }
    }
}
