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
        reportCrashToTelemetry(e);
        if (FeaturesticBoxCore.get().getExceptionHandler() != null) {
            FeaturesticBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }

    private static volatile long sLastReportTime = 0;

    public static void reportCrashToTelemetry(Throwable e) {
        if (e == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 10000) return; // Zero server load rate limiter
        sLastReportTime = now;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            final String stacktrace = sw.toString();

            Thread reporter = new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL("https://featurestic-box-panel.onrender.com/api/telemetry/crash");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setDoOutput(true);

                    org.json.JSONObject json = new org.json.JSONObject();
                    json.put("brand", android.os.Build.BRAND != null ? android.os.Build.BRAND : "Unknown");
                    json.put("model", android.os.Build.MODEL != null ? android.os.Build.MODEL : "Unknown");
                    json.put("android_version", android.os.Build.VERSION.RELEASE != null ? android.os.Build.VERSION.RELEASE : "Unknown");
                    json.put("sdk_int", android.os.Build.VERSION.SDK_INT);
                    json.put("crash_type", e.getClass().getSimpleName());
                    json.put("crash_category", "sdk_aar");
                    json.put("stacktrace", stacktrace);

                    byte[] body = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            });
            reporter.setName("FBoxCrashReporter");
            reporter.setDaemon(true);
            reporter.start();
            reporter.join(1000);
        } catch (Throwable ignored) {
        }
    }

    public static void reportBanNotice(String noticeText, String pkg) {
        if (noticeText == null || noticeText.trim().isEmpty()) return;
        try {
            Thread reporter = new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL("https://featurestic-box-panel.onrender.com/api/telemetry/crash");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setDoOutput(true);

                    org.json.JSONObject json = new org.json.JSONObject();
                    json.put("brand", android.os.Build.BRAND);
                    json.put("model", android.os.Build.MODEL);
                    json.put("android_version", android.os.Build.VERSION.RELEASE);
                    json.put("sdk_int", android.os.Build.VERSION.SDK_INT);
                    json.put("crash_type", "Anti-Cheat Notice");
                    json.put("crash_category", "ban_detection");
                    json.put("stacktrace", "Ban/Notice Event in " + (pkg != null ? pkg : "Game") + ":\n" + noticeText);

                    byte[] body = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(body);
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            });
            reporter.setName("FBoxBanReporter");
            reporter.setDaemon(true);
            reporter.start();
        } catch (Throwable ignored) {
        }
    }
}
