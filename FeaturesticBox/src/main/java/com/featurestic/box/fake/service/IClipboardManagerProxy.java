package com.featurestic.box.fake.service;

import android.content.ClipData;
import android.content.ClipDescription;
import android.util.Log;

import black.android.content.BRAttributionSource;
import black.android.content.BRIClipboardStub;
import black.android.os.BRServiceManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.MethodParameterUtils;
import com.featurestic.box.utils.compat.ContextCompat;

public class IClipboardManagerProxy extends BinderInvocationStub {
    private static final String TAG = "IClipboardManagerProxy";
    private static volatile ClipData sSharedClipData;

    public IClipboardManagerProxy() {
        super(BRServiceManager.get().getService("clipboard"));
    }

    @Override
    public Object getWho() {
        return BRIClipboardStub.get().asInterface(BRServiceManager.get().getService("clipboard"));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        normalizeClipboardArgs(args);

        if ("setPrimaryClip".equals(methodName) || "setPrimaryClipAsPackage".equals(methodName)) {
            cacheClipData(args);
            try {
                return super.invoke(proxy, method, args);
            } catch (Throwable throwable) {
                Log.w(TAG, "System clipboard write fallback to virtual cache");
                return null;
            }
        }

        if ("clearPrimaryClip".equals(methodName)) {
            sSharedClipData = null;
            clearPersistedClip();
            try {
                return super.invoke(proxy, method, args);
            } catch (Throwable throwable) {
                return null;
            }
        }

        if ("getPrimaryClip".equals(methodName)) {
            try {
                Object result = super.invoke(proxy, method, args);
                if (result instanceof ClipData) {
                    sSharedClipData = (ClipData) result;
                    return result;
                }
            } catch (Throwable ignored) {
            }
            return getFallbackClipData();
        }

        if ("getPrimaryClipDescription".equals(methodName)) {
            try {
                Object result = super.invoke(proxy, method, args);
                if (result instanceof ClipDescription) {
                    return result;
                }
            } catch (Throwable ignored) {
            }
            ClipData fallback = getFallbackClipData();
            return fallback == null ? null : fallback.getDescription();
        }

        if ("hasPrimaryClip".equals(methodName)) {
            try {
                Object result = super.invoke(proxy, method, args);
                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return getFallbackClipData() != null;
        }

        if ("hasClipboardText".equals(methodName)) {
            try {
                Object result = super.invoke(proxy, method, args);
                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            ClipData fallback = getFallbackClipData();
            return fallback != null && fallback.getItemCount() > 0 && fallback.getItemAt(0).getText() != null;
        }

        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable ignored) {
            if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) return false;
            if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return 0;
            return null;
        }
    }

    private static void normalizeClipboardArgs(Object[] args) {
        if (args == null) return;
        String hostPkg = FeaturesticBoxCore.getHostPkg();
        String virtualPkg = BActivityThread.getAppPackageName();
        boolean sawPkg = false;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof String) {
                String str = (String) arg;
                if (!sawPkg && (str.equals(virtualPkg) || FeaturesticBoxCore.get().isInstalled(str, BActivityThread.getUserId()))) {
                    args[i] = hostPkg;
                    sawPkg = true;
                } else if (sawPkg && !str.equals(hostPkg)) {
                    // Clear guest attributionTag so AppOps does not reject an undeclared tag on hostPkg
                    args[i] = null;
                }
            } else if (arg != null && BRAttributionSource.getRealClass() != null
                    && BRAttributionSource.getRealClass().getName().equals(arg.getClass().getName())) {
                ContextCompat.fixAttributionSourceState(arg, FeaturesticBoxCore.getHostUid());
            }
        }
        // Replace userId slot (first Integer after String arguments) with hostUserId, leaving trailing deviceId (0) intact
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                args[i] = FeaturesticBoxCore.getHostUserId();
                break;
            }
        }
    }

    private void cacheClipData(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof ClipData) {
                ClipData clip = (ClipData) arg;
                sSharedClipData = clip;
                persistClipText(clip);
                return;
            }
        }
    }

    private static File getClipboardCacheFile() {
        return new File(BEnvironment.getVBoxRoot(), "virtual_clipboard.txt");
    }

    private static void persistClipText(ClipData clip) {
        try {
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                String text = clip.getItemAt(0).getText().toString();
                File f = getClipboardCacheFile();
                FileUtils.mkdirs(f.getParentFile());
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearPersistedClip() {
        try {
            File f = getClipboardCacheFile();
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static ClipData getFallbackClipData() {
        if (sSharedClipData != null) {
            return sSharedClipData;
        }
        try {
            File f = getClipboardCacheFile();
            if (f.isFile() && f.length() > 0 && f.length() <= 262144) {
                byte[] data = new byte[(int) f.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                int n = fis.read(data);
                fis.close();
                if (n > 0) {
                    String text = new String(data, 0, n, StandardCharsets.UTF_8);
                    sSharedClipData = ClipData.newPlainText("text", text);
                    return sSharedClipData;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("clipboard");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
