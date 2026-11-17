package com.featurestic.box.utils;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

public class AttributionSourceUtils {
    private static final String TAG = "AttributionSourceUtils";

    public static void fixAttributionSourceInArgs(Object[] args) {
        if (args == null) return;

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                try {
                    fixAttributionSourceUid(arg);
                } catch (Throwable e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in args: " + e.getMessage());
                }
            }
        }

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("Bundle")) {
                try {
                    fixAttributionSourceInBundle(arg);
                } catch (Throwable e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in Bundle: " + e.getMessage());
                }
            }
        }
    }

    public static void fixAttributionSourceUid(Object attributionSource) {
        try {
            if (attributionSource == null) return;
            Class<?> attributionSourceClass = attributionSource.getClass();

            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            for (String fieldName : uidFieldNames) {
                try {
                    Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    uidField.set(attributionSource, FeaturesticBoxCore.getHostUid());
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }

            try {
                Method setUidMethod = attributionSourceClass.getDeclaredMethod("setUid", int.class);
                setUidMethod.setAccessible(true);
                setUidMethod.invoke(attributionSource, FeaturesticBoxCore.getHostUid());
            } catch (Throwable ignored) {
            }

            String[] packageFieldNames = {"mPackageName", "packageName", "mSourcePackage", "sourcePackage"};
            for (String fieldName : packageFieldNames) {
                try {
                    Field packageField = attributionSourceClass.getDeclaredField(fieldName);
                    packageField.setAccessible(true);
                    packageField.set(attributionSource, FeaturesticBoxCore.getHostPkg());
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static void fixAttributionSourceInBundle(Object bundle) {
        try {
            if (bundle == null) return;
            Method keySetMethod = bundle.getClass().getMethod("keySet");
            Set<String> keys = (Set<String>) keySetMethod.invoke(bundle);
            if (keys == null) return;

            for (String key : keys) {
                try {
                    Method getMethod = bundle.getClass().getMethod("get", String.class);
                    Object value = getMethod.invoke(bundle, key);
                    if (value != null && value.getClass().getName().contains("AttributionSource")) {
                        fixAttributionSourceUid(value);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Error fixing AttributionSource in Bundle: " + e.getMessage());
        }
    }

    public static Object createSafeAttributionSource() {
        try {
            Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
            Object attributionSource = null;
            try {
                Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                attributionSource = constructor.newInstance(FeaturesticBoxCore.getHostUid(), FeaturesticBoxCore.getHostPkg());
            } catch (Throwable e) {
                try {
                    Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    attributionSource = constructor.newInstance();
                    fixAttributionSourceUid(attributionSource);
                } catch (Throwable e2) {
                    Slog.w(TAG, "Could not create safe AttributionSource: " + e2.getMessage());
                    return null;
                }
            }
            return attributionSource;
        } catch (Throwable e) {
            Slog.w(TAG, "Error creating safe AttributionSource: " + e.getMessage());
            return null;
        }
    }

    public static boolean validateAttributionSource(Object attributionSource) {
        try {
            if (attributionSource == null) return false;
            Class<?> attributionSourceClass = attributionSource.getClass();
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};

            for (String fieldName : uidFieldNames) {
                try {
                    Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    Object uidValue = uidField.get(attributionSource);
                    if (uidValue instanceof Integer) {
                        int uid = (Integer) uidValue;
                        if (uid > 0) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            fixAttributionSourceUid(attributionSource);
            return true;
        } catch (Throwable e) {
            Slog.w(TAG, "Error validating AttributionSource: " + e.getMessage());
            return false;
        }
    }
}
