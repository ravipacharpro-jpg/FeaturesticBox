package com.featurestic.box;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.Keep;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.utils.Md5Utils;
import com.featurestic.box.utils.compat.AudioLowLatencyCompat;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;
import com.featurestic.box.utils.compat.NetworkSecurityGuard;
import com.featurestic.box.utils.compat.ThermalAndFrameGuard;

@Keep
public final class FeaturesticDiagnostics {
    private static volatile String sLastCapturedError = "None";

    private FeaturesticDiagnostics() {
    }

    private static String readLastNativeCrashOffset() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/featurestic_last_crash.log");
            if (f.isFile() && f.length() > 0) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                    String line = br.readLine();
                    if (line != null && !line.trim().isEmpty()) return line.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "None (0 Native Crashes)";
    }

    public static void recordError(String contextTag, Throwable t) {
        if (t == null) return;
        sLastCapturedError = (contextTag != null ? contextTag + ": " : "")
                + t.getClass().getSimpleName() + " - " + t.getMessage();
    }

    public static String buildDiagnosticReport(Context context) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        String gamePkg = BActivityThread.isThreadInit() && BActivityThread.getAppPackageName() != null
                ? BActivityThread.getAppPackageName() : "HostLoader";
        long pageSize = 4096L;
        try {
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
        }
        long maxMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long usedMemMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        String threat = NetworkSecurityGuard.getDetectedSecurityThreat(ctx);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder(512);
        sb.append("=== FEATURESTICBOX DIAGNOSTIC REPORT ===\n");
        sb.append("Time: ").append(ts).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Arch: ").append(FeaturesticBoxCore.is64Bit() ? "arm64-v8a (64-bit)" : "armeabi-v7a (32-bit)")
                .append(" | PageSize: ").append(pageSize / 1024).append("KB\n");
        sb.append("ActivePkg: ").append(gamePkg)
                .append(" | Slot: #").append(userId)
                .append(" (").append(FeaturesticAccountSwitcher.getAccountSlotLabel(userId)).append(")\n");
        sb.append("VirtualAndroidID: ").append(Md5Utils.fakeAndroidId(userId)).append("\n");
        sb.append("TargetFPS: ").append(ThermalAndFrameGuard.getTargetFps()).append(" FPS")
                .append(" | ThermalGuard: ").append(ThermalAndFrameGuard.isThermalGuardEnabled() ? "ON" : "OFF").append("\n");
        sb.append("NetTurbo: ").append(NetworkLowLatencyOptimizer.isEnabled() ? "ON" : "OFF")
                .append(" | DualSimStandby: ").append(NetworkLowLatencyOptimizer.getStandbyCellularNetwork() != null ? "READY" : "STANDBY").append("\n");
        sb.append("AudioLowLatency: ").append(AudioLowLatencyCompat.isEnabled() ? "ON (MMAP)" : "OFF").append("\n");
        sb.append("RAM: ").append(usedMemMb).append("MB / ").append(maxMemMb).append("MB\n");
        sb.append("SecurityCheck: ").append(threat.isEmpty() ? "SAFE (CLEAN)" : threat).append("\n");
        sb.append("LastError: ").append(sLastCapturedError).append("\n");
        sb.append("========================================");
        return sb.toString();
    }

    public static boolean copyDiagnosticReportToClipboard(Context context) {
        try {
            Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
            if (ctx == null) return false;
            String report = buildDiagnosticReport(ctx);
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("FeaturesticBox Diagnostics", report));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
