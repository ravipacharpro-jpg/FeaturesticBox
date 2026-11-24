package com.featurestic.box.core;

import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private static final String TELEMETRY_URL = "https://featurestic-box-panel.vercel.app/api/telemetry/crash";
    private final Thread.UncaughtExceptionHandler mDefaultHandler;
    private static final AtomicBoolean sWatchdogStarted = new AtomicBoolean(false);
    private static final AtomicBoolean sOnlineReported = new AtomicBoolean(false);

    public static void create() {
        new CrashHandler();
        startAnrWatchdog("FBoxVirtualEngine");
        reportProcessOnline("FeaturesticBox");
    }

    public static void startAnrWatchdog(final String processTag) {
        if (!sWatchdogStarted.compareAndSet(false, true)) return;
        Thread watchdog = new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            final int[] tick = new int[]{0};
            final Runnable tickRunnable = () -> tick[0]++;
            long lastAnrReport = 0;

            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                int curTick = tick[0];
                mainHandler.post(tickRunnable);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                // If tick didn't advance in 5000ms after posting, the main looper is blocked!
                if (tick[0] == curTick) {
                    long now = System.currentTimeMillis();
                    if (now - lastAnrReport > 45000) { // Limit to 1 ANR report per 45 seconds
                        lastAnrReport = now;
                        try {
                            StringBuilder sb = new StringBuilder();
                            sb.append("=== AUTOMATED ANR DETECTION (UI THREAD BLOCKED) ===\n");
                            sb.append("Process: ").append(processTag != null ? processTag : "unknown").append("\n");
                            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

                            sb.append("--- MAIN THREAD STACKTRACE ---\n");
                            Thread mainThread = Looper.getMainLooper().getThread();
                            for (StackTraceElement ste : mainThread.getStackTrace()) {
                                sb.append("    at ").append(ste.toString()).append("\n");
                            }

                            sb.append("\n--- ALL THREADS DUMP ---\n");
                            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                                Thread t = entry.getKey();
                                if (t == mainThread) continue;
                                sb.append("Thread: ").append(t.getName()).append(" (state=").append(t.getState()).append(")\n");
                                for (StackTraceElement ste : entry.getValue()) {
                                    sb.append("    at ").append(ste.toString()).append("\n");
                                }
                            }

                            sb.append("\n=== RECENT LOGCAT (200 Lines) ===\n");
                            sb.append(collectRecentLogcat(200));

                            postTelemetryPayload("ANR_THREAD_STALL", "anr_watchdog", sb.toString(), null);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "FBoxAnrWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public static void reportProcessOnline(final String tag) {
        if (!sOnlineReported.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("=== PROCESS AUTO-START REPORT ===\n");
                sb.append("Process: ").append(tag != null ? tag : "unknown").append("\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");
                sb.append("=== RECENT LOGCAT BUFFER (150 Lines) ===\n");
                sb.append(collectRecentLogcat(150));

                postTelemetryPayload("Process_Start", "session", sb.toString(), null);
            } catch (Throwable ignored) {
            }
        }, "FBoxProcessOnline").start();
    }

    public static void reportProcessDied(final String processName, final int pid) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("=== PROCESS TERMINATION / DIED EVENT ===\n");
                sb.append("Process: ").append(processName).append(" (PID: ").append(pid).append(")\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
                sb.append("=== FINAL LOGCAT BUFFER (200 Lines) ===\n");
                sb.append(collectRecentLogcat(200));

                postTelemetryPayload("Process_Died", "crash", sb.toString(), null);
            } catch (Throwable ignored) {
            }
        }, "FBoxProcessDied").start();
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
        try {
            reportCrashToTelemetrySync(e);
        } catch (Throwable ignored) {}
        if (FeaturesticBoxCore.get().getExceptionHandler() != null) {
            FeaturesticBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }

    private static volatile long sLastReportTime = 0;

    /**
     * Dumps the last N lines of logcat for this process with strict timeout.
     */
    public static String collectRecentLogcat(int maxLines) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "[Logcat deferred to background to prevent UI ANR]";
        }
        final StringBuilder sb = new StringBuilder();
        Thread dumpThread = new Thread(() -> {
            Process p = null;
            try {
                int lines = Math.max(20, Math.min(maxLines, 250));
                p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", String.valueOf(lines), "-v", "time"});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {}
                }
            }
        }, "FBoxLogcatDump");
        dumpThread.setDaemon(true);
        dumpThread.start();
        try {
            dumpThread.join(2500);
            if (dumpThread.isAlive()) {
                dumpThread.interrupt();
            }
        } catch (Throwable ignored) {}
        return sb.length() > 0 ? sb.toString() : "[Logcat buffer empty or permission restricted]";
    }

    /**
     * Generic asynchronous payload dispatch to the central Vercel telemetry panel.
     */
    public static void postTelemetryPayload(String crashType, String crashCategory, String payloadText, Runnable onComplete) {
        if (payloadText == null || payloadText.trim().isEmpty()) return;
        Thread reporter = new Thread(() -> {
            try {
                URL url = new URL(TELEMETRY_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("brand", Build.BRAND != null ? Build.BRAND : "Unknown");
                json.put("model", Build.MODEL != null ? Build.MODEL : "Unknown");
                json.put("android_version", Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "Unknown");
                json.put("sdk_int", Build.VERSION.SDK_INT);
                try {
                    android.content.Context ctx = FeaturesticBoxCore.getContext();
                    if (ctx != null) {
                        android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                        if (pi != null && pi.versionName != null) {
                            json.put("app_version", pi.versionName);
                        }
                        android.content.SharedPreferences sp = ctx.getSharedPreferences("featurestic_apple_auth", android.content.Context.MODE_PRIVATE);
                        String k = sp.getString("saved_license_key", null);
                        if (k != null && !k.trim().isEmpty()) {
                            json.put("user_key", k.trim());
                        }
                    }
                } catch (Throwable ignored) {}
                json.put("crash_type", crashType != null ? crashType : "Runtime Incident");
                json.put("crash_category", crashCategory != null ? crashCategory : "sdk_aar");
                json.put("stacktrace", payloadText);

                byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Throwable ignored) {
            } finally {
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Throwable ignored) {}
                }
            }
        });
        reporter.setName("FBoxTelemetryReporter");
        reporter.setDaemon(true);
        reporter.start();
    }

    public static void reportCrashToTelemetrySync(final Throwable e) {
        if (e == null) return;
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stacktrace = sw.toString();

            String logcat = collectRecentLogcat(150);
            String fullPayload = "=== FATAL JAVA EXCEPTION ===\n" + stacktrace +
                    "\n\n=== RECENT LOGCAT BUFFER ===\n" + logcat;

            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            postTelemetryPayload(e.getClass().getSimpleName(), "sdk_aar", fullPayload, latch::countDown);
            try {
                latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    public static void reportCrashToTelemetry(final Throwable e) {
        if (e == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 5000) return; // Prevent spamming
        sLastReportTime = now;
        new Thread(() -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                String stacktrace = sw.toString();

                String logcat = collectRecentLogcat(150);
                String fullPayload = "=== JAVA EXCEPTION ===\n" + stacktrace +
                        "\n\n=== RECENT LOGCAT BUFFER ===\n" + logcat;

                postTelemetryPayload(e.getClass().getSimpleName(), "sdk_aar", fullPayload, null);
            } catch (Throwable ignored) {
            }
        }, "FBoxCrashReportThread").start();
    }

    public static void reportNativeCrash(String signalName, long pc, long faultAddr, String threadName, String info) {
        long now = System.currentTimeMillis();
        if (now - sLastReportTime < 5000) return;
        sLastReportTime = now;
        try {
            String logcat = collectRecentLogcat(150);
            StringBuilder sb = new StringBuilder();
            sb.append("=== NATIVE SIGNAL INCIDENT ===\n");
            sb.append("Signal: ").append(signalName).append("\n");
            sb.append("Thread: ").append(threadName).append("\n");
            sb.append("PC: 0x").append(Long.toHexString(pc)).append("\n");
            sb.append("Fault Addr: 0x").append(Long.toHexString(faultAddr)).append("\n");
            if (info != null && !info.isEmpty()) {
                sb.append("Info: ").append(info).append("\n");
            }
            sb.append("\n=== RECENT LOGCAT BUFFER ===\n");
            sb.append(logcat);

            postTelemetryPayload("Native_" + signalName, "master_so", sb.toString(), null);
        } catch (Throwable ignored) {
        }
    }

    public static void reportOAuthEvent(String provider, String stage, boolean success, String details) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("=== OAUTH TELEMETRY EVENT ===\n");
            sb.append("Provider: ").append(provider).append("\n");
            sb.append("Stage: ").append(stage).append("\n");
            sb.append("Status: ").append(success ? "SUCCESS" : "FAILED").append("\n");
            sb.append("Details: ").append(details != null ? details : "").append("\n");
            if (!success) {
                sb.append("\n=== RECENT LOGCAT BUFFER ===\n");
                sb.append(collectRecentLogcat(100));
            }
            postTelemetryPayload("OAuth_" + provider + "_" + (success ? "OK" : "FAIL"), "oauth_telemetry", sb.toString(), null);
        } catch (Throwable ignored) {
        }
    }

    public static void reportDiagnosticLogs(String userNote, Runnable onComplete) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("=== MANUAL DIAGNOSTIC REPORT ===\n");
                sb.append("User Note: ").append(userNote != null ? userNote : "Manual log export").append("\n");
                sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
                sb.append("=== ACTIVE THREADS DUMP ===\n");
                try {
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        Thread t = entry.getKey();
                        sb.append("Thread [").append(t.getName()).append("] state=").append(t.getState()).append("\n");
                        for (StackTraceElement ste : entry.getValue()) {
                            sb.append("    at ").append(ste.toString()).append("\n");
                        }
                    }
                } catch (Throwable ignored) {}
                sb.append("\n=== RECENT LOGCAT BUFFER (250 Lines) ===\n");
                sb.append(collectRecentLogcat(250));

                postTelemetryPayload("Manual_Diagnostic", "diagnostic", sb.toString(), onComplete);
            } catch (Throwable t) {
                if (onComplete != null) onComplete.run();
            }
        }, "FBoxDiagnosticReport").start();
    }

    public static void reportBanNotice(String noticeText, String pkg) {
        if (noticeText == null || noticeText.trim().isEmpty()) return;
        try {
            String fullPayload = "Ban/Notice Event in " + (pkg != null ? pkg : "Game") + ":\n" + noticeText +
                    "\n\n=== RECENT LOGCAT BUFFER ===\n" + collectRecentLogcat(100);
            postTelemetryPayload("Anti-Cheat Notice", "ban_detection", fullPayload, null);
        } catch (Throwable ignored) {
        }
    }
}
