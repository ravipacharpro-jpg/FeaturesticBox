package com.featurestic.box.utils;

import android.os.Parcelable;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;

public class MethodParameterUtils {

    public static <T> T getFirstParam(Object[] args, Class<T> tClass) {
        if (args == null) {
            return null;
        }
        int index = ArrayUtils.indexOfFirst(args, tClass);
        if (index != -1) {
            return (T) args[index];
        }
        return null;
    }

    public static String replaceFirstAppPkg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if ((BActivityThread.isThreadInit() && value.equals(BActivityThread.getAppPackageName())) || FeaturesticBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = FeaturesticBoxCore.getHostPkg();
                    return value;
                }
            }
        }
        return null;
    }

    public static void replaceAllAppPkg(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null)
                continue;
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if ((BActivityThread.isThreadInit() && value.equals(BActivityThread.getAppPackageName())) || FeaturesticBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = FeaturesticBoxCore.getHostPkg();
                }
            }
        }
    }

    private static boolean isVirtualUid(int uid) {
        return uid == BActivityThread.getBUid()
                || (BActivityThread.getUid() > 0 && uid == BActivityThread.getUid())
                || uid == BActivityThread.getBAppId();
    }

    public static void replaceFirstUid(Object[] args) {
        if (args == null)
            return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int uid = (int) args[i];
                if (isVirtualUid(uid)) {
                    args[i] = FeaturesticBoxCore.getHostUid();
                }
            }
        }
    }

    public static void replaceLastUid(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, Integer.class);
        if (index != -1) {
            int uid = (int) args[index];
            if (isVirtualUid(uid)) {
                args[index] = FeaturesticBoxCore.getHostUid();
            }
        }
    }

    public static String replaceLastAppPkg(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, String.class);
        if (index != -1) {
            String pkg = (String) args[index];
            if ((BActivityThread.isThreadInit() && pkg.equals(BActivityThread.getAppPackageName())) || FeaturesticBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = FeaturesticBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }
    
    public static void replaceLastUserId(Object[] args) {
        int index = args.length - 1;
        if (index >= 0 && args[index] instanceof Integer) {
            args[index] = FeaturesticBoxCore.getHostUserId();
        }
    }
    
    public static String replaceSequenceAppPkg(Object[] args, int sequence) {
        int index = ArrayUtils.indexOf(args, String.class, sequence);
        if (index != -1) {
            String pkg = (String) args[index];
            if ((BActivityThread.isThreadInit() && pkg.equals(BActivityThread.getAppPackageName())) || FeaturesticBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = FeaturesticBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static int getParamsIndex(Class[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            Class obj = args[i];
            if (obj.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    public static int getIndex(Object[] args, Class<?> type) {
        return getIndex(args, type, 0);
    }

    public static int getIndex(Object[] args, Class<?> type, int start) {
        for (int i = start; i < args.length; i++) {
            Object obj = args[i];
            if (obj != null && obj.getClass() == type) {
                return i;
            }
            if (type.isInstance(obj)) {
                return i;
            }
        }
        return -1;
    }

    public static Class<?>[] getAllInterface(Class clazz) {
        HashSet<Class<?>> classes = new HashSet<>();
        getAllInterfaces(clazz, classes);
        Class<?>[] result = new Class[classes.size()];
        classes.toArray(result);
        return result;
    }


    public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
        Class<?>[] classes = clazz.getInterfaces();
        if (classes.length != 0) {
            interfaceCollection.addAll(Arrays.asList(classes));
        }
        if (clazz.getSuperclass() != Object.class) {
            getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
        }
    }
    
    public static String getString(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) return null;
        Object obj = args[index];
        return obj != null ? obj.toString() : null;
    }
    
    public static int toInt(Object obj){
        if(obj instanceof Long){
            return ((Long) obj).intValue();
        }
        return (int)obj;
    }
    

}
