package com.featurestic.box.fake.hook;

import android.text.TextUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.featurestic.box.utils.MethodParameterUtils;

public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {
    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    @Override
    public void injectHook() {
        mBase = getWho();
        if (mBase == null) {
            return;
        }
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(), MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) {
            inject(mBase, mProxyInvocation);
        }
        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
            }
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            String[] value = proxyMethods.value();
            for (String name : value) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    public static Throwable sanitizeStackTrace(Throwable t) {
        if (t == null) return null;
        try {
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth < 8) {
                StackTraceElement[] orig = cur.getStackTrace();
                if (orig != null && orig.length > 0) {
                    List<StackTraceElement> cleaned = new ArrayList<>(orig.length);
                    boolean modified = false;
                    for (StackTraceElement el : orig) {
                        if (el == null) continue;
                        String cls = el.getClassName();
                        if (cls != null && (cls.startsWith("com.featurestic.")
                                || cls.startsWith("top.niunaijun.")
                                || cls.startsWith("com.vcore.")
                                || cls.startsWith("net_62v.")
                                || cls.startsWith("black.")
                                || cls.startsWith("$Proxy")
                                || cls.startsWith("java.lang.reflect.Proxy"))) {
                            modified = true;
                            continue;
                        }
                        cleaned.add(el);
                    }
                    if (modified) {
                        cur.setStackTrace(cleaned.toArray(new StackTraceElement[0]));
                    }
                }
                cur = cur.getCause();
                depth++;
            }
        } catch (Throwable ignored) {
        }
        return t;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return method.invoke(mBase, args);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
                // Re-throw ErrnoException and GaiException so Os callers can handle missing
                // files and DNS resolution properly (e.g. android_getaddrinfo in iTOP SDK).
                if (cause instanceof android.system.ErrnoException
                        || cause.getClass().getName().contains("GaiException")) {
                    throw cause;
                }
                // Swallow all other exceptions so they never become pending
                // JNI exceptions that crash cloned games with SIGABRT.
                // Return a type-appropriate default so callers expecting a
                // primitive (e.g. boolean from File.exists()) don't get NPE.
                sanitizeStackTrace(cause).printStackTrace();
                return defaultValueForReturnType(method != null ? method.getReturnType() : null);
            } catch (Throwable e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof android.system.ErrnoException
                        || cause.getClass().getName().contains("GaiException")) {
                    throw cause;
                }
                sanitizeStackTrace(cause).printStackTrace();
                return defaultValueForReturnType(method != null ? method.getReturnType() : null);
            }
        }

        try {
            Object result = methodHook.beforeHook(mBase, method, args);
            if (result != null) {
                return result;
            }
            result = methodHook.hook(mBase, method, args);
            result = methodHook.afterHook(result);
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            if (cause instanceof android.system.ErrnoException
                    || cause.getClass().getName().contains("GaiException")) {
                throw cause;
            }
            // Swallow exceptions from hook methods so they never become pending
            // JNI exceptions that crash cloned games with SIGABRT. Hooks are an
            // interception layer; a failing hook must not abort the target app.
            // Return a type-appropriate default so callers expecting a primitive
            // don't get NPE when unboxing.
            sanitizeStackTrace(cause).printStackTrace();
            return defaultValueForReturnType(method != null ? method.getReturnType() : null);
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof android.system.ErrnoException) {
                throw cause;
            }
            sanitizeStackTrace(cause).printStackTrace();
            return defaultValueForReturnType(method != null ? method.getReturnType() : null);
        }
    }

    /**
     * Returns a type-appropriate default value for the given return type so that
     * callers expecting a primitive (e.g. boolean from {@code File.exists()})
     * never receive {@code null} and trigger a NullPointerException during
     * unboxing. Hooks are an interception layer; a failing hook must degrade
     * gracefully instead of aborting the target app.
     */
    private static Object defaultValueForReturnType(Class<?> returnType) {
        if (returnType == null || returnType == Void.TYPE) {
            return null;
        }
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Byte.TYPE) {
            return (byte) 0;
        }
        if (returnType == Character.TYPE) {
            return (char) 0;
        }
        if (returnType == Short.TYPE) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0f;
        }
        if (returnType == Double.TYPE) {
            return 0.0;
        }
        return null;
    }
}
