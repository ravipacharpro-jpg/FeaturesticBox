package com.featurestic.box.core;

import android.database.sqlite.SQLiteException;
import android.os.DeadObjectException;
import android.os.Looper;
import android.os.RemoteException;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    private static boolean isSwallowableBackgroundException(Thread t, Throwable e) {
        if (t == null || e == null) return false;
        if (e instanceof OutOfMemoryError || e instanceof StackOverflowError) {
            return false;
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null && t == mainLooper.getThread()) {
            return false;
        }
        String tName = t.getName() != null ? t.getName().toLowerCase() : "";
        if (tName.contains("firebase") || tName.contains("scion") || tName.contains("measurement")
                || tName.contains("gms") || tName.contains("bugly") || tName.contains("appsflyer")
                || tName.contains("adjust") || tName.contains("adworker") || tName.contains("analytics")) {
            return true;
        }
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof SecurityException
                    || cur instanceof RemoteException
                    || cur instanceof DeadObjectException
                    || cur instanceof IllegalStateException
                    || cur instanceof IllegalArgumentException
                    || cur instanceof SQLiteException
                    || cur instanceof NullPointerException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (isSwallowableBackgroundException(t, e)) {
            Slog.w(TAG, "Shielded non-fatal background exception on thread: " + (t != null ? t.getName() : "unknown"));
            return;
        }
        if (FeaturesticBoxCore.get().getExceptionHandler() != null) {
            FeaturesticBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }
}
