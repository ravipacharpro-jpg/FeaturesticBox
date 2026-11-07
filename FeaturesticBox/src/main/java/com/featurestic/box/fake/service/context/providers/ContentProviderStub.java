package com.featurestic.box.fake.service.context.providers;

import android.content.ContentValues;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.fake.hook.ClassInvocationStub;
import com.featurestic.box.utils.compat.ContextCompat;

/**
 * Hardened ContentProvider proxy for Android 10 -> 16.
 * Normalizes AttributionSource, callingPackage, and MediaStore ContentValues
 * (owner_package_name) so virtual apps can save screenshots/videos to Gallery
 * without SecurityException or IllegalArgumentException.
 */
public class ContentProviderStub extends ClassInvocationStub implements BContentProvider {
    public static final String TAG = "ContentProviderStub";
    private IInterface mBase;
    private String mAppPkg;

    public IInterface wrapper(final IInterface contentProviderProxy, final String appPkg) {
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            String virtualPkg = null;
            try {
                virtualPkg = BActivityThread.getAppPackageName();
            } catch (Throwable ignored) {
            }
            String attrClassName = BRAttributionSource.getRealClass() != null
                    ? BRAttributionSource.getRealClass().getName() : null;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) continue;
                if (i == 0 && arg instanceof String) {
                    args[0] = mAppPkg;
                } else if (arg instanceof String && virtualPkg != null && virtualPkg.equals(arg)) {
                    args[i] = mAppPkg;
                } else if (attrClassName != null && attrClassName.equals(arg.getClass().getName())) {
                    ContextCompat.fixAttributionSourceState(arg, FeaturesticBoxCore.getHostUid());
                } else if (arg instanceof ContentValues) {
                    sanitizeContentValues((ContentValues) arg, virtualPkg, mAppPkg);
                } else if (arg instanceof ContentValues[]) {
                    for (ContentValues cv : (ContentValues[]) arg) {
                        sanitizeContentValues(cv, virtualPkg, mAppPkg);
                    }
                }
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) {
                if ("query".equals(method.getName()) && isGservicesQuery(args)) {
                    return new MatrixCursor(new String[]{"key", "value"}, 0);
                }
            }
            throw cause != null ? cause : e;
        } catch (SecurityException | IllegalArgumentException e) {
            if ("query".equals(method.getName()) && isGservicesQuery(args)) {
                return new MatrixCursor(new String[]{"key", "value"}, 0);
            }
            throw e;
        }
    }

    private static boolean isGservicesQuery(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg instanceof Uri) {
                String auth = ((Uri) arg).getAuthority();
                if ("com.google.android.gsf.gservices".equals(auth)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void sanitizeContentValues(ContentValues values, String virtualPkg, String hostPkg) {
        if (values == null || hostPkg == null) return;
        try {
            if (values.containsKey("owner_package_name")) {
                String owner = values.getAsString("owner_package_name");
                if (owner != null && (owner.equals(virtualPkg) || !owner.equals(hostPkg))) {
                    values.put("owner_package_name", hostPkg);
                }
            }
            if (values.containsKey("notificationpackage")) {
                values.put("notificationpackage", hostPkg);
                values.remove("notificationclass");
            }
            if (values.containsKey("hint") && virtualPkg != null) {
                String hint = values.getAsString("hint");
                if (hint != null && hint.startsWith("file://") && hint.contains("/Android/data/" + virtualPkg + "/")) {
                    android.content.Context ctx = FeaturesticBoxCore.getContext();
                    java.io.File hostExt = ctx != null ? ctx.getExternalFilesDir("Download") : null;
                    if (hostExt != null) {
                        if (!hostExt.exists()) hostExt.mkdirs();
                        String fileName = android.net.Uri.parse(hint).getLastPathSegment();
                        if (fileName == null || fileName.trim().isEmpty()) fileName = "download_" + System.currentTimeMillis();
                        values.put("hint", android.net.Uri.fromFile(new java.io.File(hostExt, fileName)).toString());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
