package com.featurestic.box.fake.service;

import android.os.Build;
import android.os.IInterface;
import android.util.SparseArray;
import android.view.Display;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.hardware.display.BRDisplayManagerGlobal;
import com.featurestic.box.fake.hook.ClassInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.MethodParameterUtils;

public class IDisplayManagerProxy extends ClassInvocationStub {

    public IDisplayManagerProxy() {
    }

    @Override
    protected Object getWho() {
        return BRDisplayManagerGlobal.get(BRDisplayManagerGlobal.get().getInstance()).mDm();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        Object dmg = BRDisplayManagerGlobal.get().getInstance();
        BRDisplayManagerGlobal.get(dmg)._set_mDm(getProxyInvocation());
        flushDisplayInfoCache();
    }

    @Override
    public boolean isBadEnv() {
        Object dmg = BRDisplayManagerGlobal.get().getInstance();
        IInterface mDm = BRDisplayManagerGlobal.get(dmg).mDm();
        return mDm != getProxyInvocation();
    }

    /**
     * Clears DisplayManagerGlobal's cached DisplayInfo so Display.getRefreshRate() and
     * Display.getMode() immediately fetch the 120Hz DisplayInfo from IDisplayManagerProxy.
     */
    public static void flushDisplayInfoCache() {
        try {
            Object dmg = BRDisplayManagerGlobal.get().getInstance();
            if (dmg == null) return;
            Field cacheField = findFieldRecursive(dmg.getClass(), "mDisplayInfoCache");
            if (cacheField != null) {
                cacheField.setAccessible(true);
                Object cache = cacheField.get(dmg);
                if (cache instanceof SparseArray) {
                    ((SparseArray<?>) cache).clear();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceFirstAppPkg(args);
        Object result = super.invoke(proxy, method, args);
        if (result != null && "getDisplayInfo".equals(method.getName())) {
            try {
                int targetFps = com.featurestic.box.utils.compat.ThermalAndFrameGuard.getTargetFps();
                if (targetFps >= 90) {
                    patchDisplayInfoForTargetHz(result, (float) targetFps);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static void patchDisplayInfoForTargetHz(Object displayInfo, float requestedFps) {
        if (displayInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Class<?> cls = displayInfo.getClass();
        float targetFps = requestedFps;

        // 1. Inspect supportedModes & ensure a 120Hz (or higher hardware max) Display.Mode exists
        try {
            Field modesField = findFieldRecursive(cls, "supportedModes");
            if (modesField != null) {
                modesField.setAccessible(true);
                Display.Mode[] modes = (Display.Mode[]) modesField.get(displayInfo);
                int logicalW = getIntField(displayInfo, cls, "logicalWidth", 1080);
                int logicalH = getIntField(displayInfo, cls, "logicalHeight", 2400);

                Display.Mode bestMode = null;
                float maxRate = 0f;
                if (modes != null && modes.length > 0) {
                    for (Display.Mode m : modes) {
                        if (m != null && m.getRefreshRate() > maxRate) {
                            maxRate = m.getRefreshRate();
                            bestMode = m;
                        }
                    }
                }

                if (maxRate > 120.0f) {
                    targetFps = maxRate;
                }

                // If hardware modes only go up to 60Hz/90Hz, synthesize a 120Hz Display.Mode
                // so Unreal Engine / Unity sees a valid 120Hz mode in Display.getSupportedModes() & Display.getMode()!
                if (bestMode == null || bestMode.getRefreshRate() < 118.0f) {
                    int w = (bestMode != null) ? bestMode.getPhysicalWidth() : logicalW;
                    int h = (bestMode != null) ? bestMode.getPhysicalHeight() : logicalH;
                    Display.Mode synth120 = createDisplayMode(999, w, h, 120.0f);
                    if (synth120 != null) {
                        bestMode = synth120;
                        if (modes != null) {
                            Display.Mode[] expanded = new Display.Mode[modes.length + 1];
                            System.arraycopy(modes, 0, expanded, 0, modes.length);
                            expanded[modes.length] = synth120;
                            modesField.set(displayInfo, expanded);
                        } else {
                            modesField.set(displayInfo, new Display.Mode[]{synth120});
                        }
                    }
                }

                if (bestMode != null) {
                    setIntField(displayInfo, cls, "modeId", bestMode.getModeId());
                    setIntField(displayInfo, cls, "defaultModeId", bestMode.getModeId());

                    Field appsModesField = findFieldRecursive(cls, "appsSupportedModes");
                    if (appsModesField != null) {
                        appsModesField.setAccessible(true);
                        appsModesField.set(displayInfo, modesField.get(displayInfo));
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Patch Android 11-16 refreshRateOverride & renderFrameRate (used by Display.getRefreshRate() & FramePacer!)
        setFloatField(displayInfo, cls, "refreshRateOverride", targetFps);
        setFloatField(displayInfo, cls, "renderFrameRate", targetFps);

        // 3. Patch 120Hz VSYNC presentation deadline (8.33ms = 8,333,333 ns)
        setLongField(displayInfo, cls, "presentationDeadlineNanos", 8333333L);
        setLongField(displayInfo, cls, "appVsyncOffsetNanos", 1000000L);
    }

    private static Display.Mode createDisplayMode(int modeId, int width, int height, float refreshRate) {
        try {
            for (Constructor<?> ctor : Display.Mode.class.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 4
                        && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class && p[3] == float.class) {
                    return (Display.Mode) ctor.newInstance(modeId, width, height, refreshRate);
                }
                if (p.length == 6
                        && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class && p[3] == float.class
                        && p[4] == float[].class && p[5] == int[].class) {
                    return (Display.Mode) ctor.newInstance(
                            modeId, width, height, refreshRate, new float[0], new int[0]);
                }
                if (p.length == 7
                        && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class && p[3] == float.class
                        && p[4] == float.class
                        && p[5] == float[].class && p[6] == int[].class) {
                    return (Display.Mode) ctor.newInstance(
                            modeId, width, height, refreshRate, refreshRate, new float[0], new int[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Field findFieldRecursive(Class<?> cls, String name) {
        Class<?> curr = cls;
        while (curr != null && curr != Object.class) {
            try {
                return curr.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    private static int getIntField(Object obj, Class<?> cls, String name, int defVal) {
        try {
            Field f = findFieldRecursive(cls, name);
            if (f != null) {
                f.setAccessible(true);
                return f.getInt(obj);
            }
        } catch (Throwable ignored) {
        }
        return defVal;
    }

    private static void setIntField(Object obj, Class<?> cls, String name, int val) {
        try {
            Field f = findFieldRecursive(cls, name);
            if (f != null) {
                f.setAccessible(true);
                f.setInt(obj, val);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setFloatField(Object obj, Class<?> cls, String name, float val) {
        try {
            Field f = findFieldRecursive(cls, name);
            if (f != null) {
                f.setAccessible(true);
                f.setFloat(obj, val);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setLongField(Object obj, Class<?> cls, String name, long val) {
        try {
            Field f = findFieldRecursive(cls, name);
            if (f != null) {
                f.setAccessible(true);
                f.setLong(obj, val);
            }
        } catch (Throwable ignored) {
        }
    }

    @ProxyMethod("createVirtualDisplay")
    public static class CreateVirtualDisplay extends MethodHook {
        @Override
        protected String getMethodName() {
            return "createVirtualDisplay";
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
}
