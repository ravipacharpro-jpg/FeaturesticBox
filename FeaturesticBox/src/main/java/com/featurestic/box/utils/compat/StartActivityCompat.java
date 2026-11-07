package com.featurestic.box.utils.compat;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public class StartActivityCompat {
    private static int index = 0;
    private static int appThreadIndex;
    private static int callingPageIndex;
    private static int callingFeatureIdIndex;
    private static int intentIndex;
    private static int resolvedTypeIndex;
    private static int resultToIndex;
    private static int resultWhoIndex;
    private static int requestCodeIndex;
    private static int flagsIndex;
    private static int profilerInfoIndex;
    private static int optionsIndex;

    static {
        if (BuildCompat.isR()) {
            appThreadIndex = index++;
            callingPageIndex = index++;
            callingFeatureIdIndex = index++;
            intentIndex = index++;
            resolvedTypeIndex = index++;
            resultToIndex = index++;
            resultWhoIndex = index++;
            requestCodeIndex = index++;
            flagsIndex = index++;
            profilerInfoIndex = index++;
            optionsIndex = index++;
        } else {
            appThreadIndex = index++;
            callingPageIndex = index++;
            intentIndex = index++;
            resolvedTypeIndex = index++;
            resultToIndex = index++;
            resultWhoIndex = index++;
            requestCodeIndex = index++;
            flagsIndex = index++;
            profilerInfoIndex = index++;
            optionsIndex = index++;
        }
    }

    private static int resolveIntentSlot(Object[] args) {
        if (args == null) return -1;
        if (intentIndex >= 0 && intentIndex < args.length && args[intentIndex] instanceof Intent) {
            return intentIndex;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Intent) {
                return i;
            }
        }
        return -1;
    }

    public static Object getIApplicationThread(Object[] args) {
        if (args == null || appThreadIndex >= args.length) {
            return null;
        }
        return args[appThreadIndex];
    }

    public static String getCallingPackage(Object[] args) {
        if (args == null || callingPageIndex >= args.length) {
            return null;
        }
        return args[callingPageIndex] instanceof String ? (String) args[callingPageIndex] : null;
    }

    public static Intent getIntent(Object[] args) {
        int slot = resolveIntentSlot(args);
        return slot >= 0 ? (Intent) args[slot] : null;
    }

    public static String getResolvedType(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 1 : resolvedTypeIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return null;
        }
        return args[idx] instanceof String ? (String) args[idx] : null;
    }

    public static IBinder getResultTo(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 2 : resultToIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return null;
        }
        return args[idx] instanceof IBinder ? (IBinder) args[idx] : null;
    }

    public static String getResultWho(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 3 : resultWhoIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return null;
        }
        return args[idx] instanceof String ? (String) args[idx] : null;
    }

    public static int getRequestCode(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 4 : requestCodeIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return -1;
        }
        return args[idx] instanceof Integer ? (Integer) args[idx] : -1;
    }

    public static int getFlags(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 5 : flagsIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return 0;
        }
        return args[idx] instanceof Integer ? (Integer) args[idx] : 0;
    }

    public static Object getProfilerInfo(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 6 : profilerInfoIndex;
        if (args == null || idx < 0 || idx >= args.length) {
            return null;
        }
        return args[idx];
    }

    public static Bundle getOptions(Object[] args) {
        int slot = resolveIntentSlot(args);
        int idx = slot >= 0 ? slot + 7 : optionsIndex;
        if (args != null && idx >= 0 && idx < args.length && args[idx] instanceof Bundle) {
            return (Bundle) args[idx];
        }
        if (args != null) {
            for (int i = args.length - 1; i > slot; i--) {
                if (args[i] instanceof Bundle) {
                    return (Bundle) args[i];
                }
            }
        }
        return null;
    }

    public static int getAppThreadIndex() { return appThreadIndex; }
    public static void setAppThreadIndex(int v) { StartActivityCompat.appThreadIndex = v; }
    public static int getCallingPageIndex() { return callingPageIndex; }
    public static void setCallingPageIndex(int v) { StartActivityCompat.callingPageIndex = v; }
    public static int getIntentIndex() { return intentIndex; }
    public static void setIntentIndex(int v) { StartActivityCompat.intentIndex = v; }
    public static int getResolvedTypeIndex() { return resolvedTypeIndex; }
    public static void setResolvedTypeIndex(int v) { StartActivityCompat.resolvedTypeIndex = v; }
    public static int getResultToIndex() { return resultToIndex; }
    public static void setResultToIndex(int v) { StartActivityCompat.resultToIndex = v; }
    public static int getResultWhoIndex() { return resultWhoIndex; }
    public static void setResultWhoIndex(int v) { StartActivityCompat.resultWhoIndex = v; }
    public static int getRequestCodeIndex() { return requestCodeIndex; }
    public static void setRequestCodeIndex(int v) { StartActivityCompat.requestCodeIndex = v; }
    public static int getFlagsIndex() { return flagsIndex; }
    public static void setFlagsIndex(int v) { StartActivityCompat.flagsIndex = v; }
    public static int getProfilerInfoIndex() { return profilerInfoIndex; }
    public static void setProfilerInfoIndex(int v) { StartActivityCompat.profilerInfoIndex = v; }
    public static int getOptionsIndex() { return optionsIndex; }
    public static void setOptionsIndex(int v) { StartActivityCompat.optionsIndex = v; }
}
