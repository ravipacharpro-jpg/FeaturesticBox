package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.entity.pm.InstallResult;
import com.featurestic.box.utils.FileUtils;

/**
 * Built-In 1-Click GG / Companion Tool Setup & Virtual Root Launcher.
 *
 * Opens a compact Apple-style Setup & Play card directly inside the game:
 * - Click 1: Auto-Imports GG from phone / Downloads folder OR downloads in background.
 * - Click 2: Launches GG inside the exact same virtual slot & UID (Virtual Root Mode)
 *   so GG floats right over the game!
 */
@Keep
public final class FeaturesticCompanionToolManager {
    private static final String PREF_NAME = "featurestic_companion_tool_prefs";
    private static volatile String sToolDownloadUrl = "https://github.com/Featurestic/gg-mirror/releases/latest/download/GG-Latest.apk";
    private static volatile String sToolPackageName = "";
    private static volatile String sToolDisplayName = "GameGuardian (Virtual Root)";
    private static volatile boolean sDownloadInProgress = false;
    private static volatile int sDnsMode = 0;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private FeaturesticCompanionToolManager() {
    }

    public static void configureCompanionTool(
            String displayName, String apkDownloadUrl, String packageName) {
        if (displayName != null && !displayName.trim().isEmpty()) {
            sToolDisplayName = displayName.trim();
        }
        if (apkDownloadUrl != null && !apkDownloadUrl.trim().isEmpty()) {
            sToolDownloadUrl = apkDownloadUrl.trim();
        }
        if (packageName != null && !packageName.trim().isEmpty()) {
            sToolPackageName = packageName.trim();
        }
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                sp.edit()
                        .putString("tool_name", sToolDisplayName)
                        .putString("tool_url", sToolDownloadUrl)
                        .putString("tool_pkg", sToolPackageName)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadSavedConfig(Context context) {
        try {
            Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
            if (ctx == null) return;
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String savedUrl = sp.getString("tool_url", "");
            if (savedUrl != null && !savedUrl.isEmpty()) {
                sToolDownloadUrl = savedUrl;
            }
            String savedPkg = sp.getString("tool_pkg", "");
            if (savedPkg != null && !savedPkg.isEmpty()) {
                sToolPackageName = savedPkg;
            }
            String savedName = sp.getString("tool_name", "");
            if (savedName != null && !savedName.isEmpty()) {
                sToolDisplayName = savedName;
            }
        } catch (Throwable ignored) {
        }
    }

    public static String getStatusSubtitle(int userId) {
        loadSavedConfig(null);
        String installedPkg = findInstalledCompanionPackage(userId);
        if (installedPkg != null) {
            return "Ready • Tap to Play in Virtual Root Mode";
        }
        return "Tap for 1-Click Setup & Play";
    }

    public static void handleOneClickSetupOrLaunch(
            final Activity activity, final int userId, final TextView statusSubtitleView) {
        if (activity == null || activity.isFinishing()) return;
        loadSavedConfig(activity);

        // Open the compact Apple-style 1-Click GG Setup & Play Dialog
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setCancelable(true);

        LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setBackgroundColor(Color.parseColor("#B3000000"));
        int pad = dp(activity, 18);
        outer.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#F51C1C1E"));
        cardBg.setCornerRadius(dp(activity, 22));
        cardBg.setStroke(dp(activity, 1), Color.parseColor("#33FFFFFF"));
        card.setBackground(cardBg);

        Typeface boldTf = Typeface.create("sans-serif-medium", Typeface.BOLD);
        Typeface medTf = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        TextView title = new TextView(activity);
        title.setText("GG Virtual Root Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(boldTf);
        card.addView(title);

        final TextView statusTv = new TextView(activity);
        String installedNow = findInstalledCompanionPackage(userId);
        statusTv.setText(installedNow != null
                ? "Status: Installed in Virtual Slot " + userId + " (Root-Equivalent Ready)"
                : "Status: Not set up in Slot " + userId + " yet • Click Setup below");
        statusTv.setTextColor(Color.parseColor("#98989D"));
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusTv.setPadding(0, dp(activity, 6), 0, dp(activity, 10));
        card.addView(statusTv);

        final ProgressBar progressBar = new ProgressBar(
                activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        card.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 6)));

        // Button 1: 1-Click Auto-Setup (Import from Phone / Download)
        TextView btnSetup = createDialogButton(
                activity, "1. One-Click Download / Auto-Setup GG", "#0A84FF", medTf);
        btnSetup.setOnClickListener(v -> {
            // First check if GG is already installed on the host phone or in /sdcard/Download
            String hostGgPkg = findHostInstalledGgPackage(activity);
            if (hostGgPkg != null) {
                statusTv.setText("Found GG on phone (" + hostGgPkg + ") • Cloning into Slot " + userId + "...");
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(50);
                new Thread(() -> {
                    InstallResult res = FeaturesticBoxCore.get().installPackageAsUser(hostGgPkg, userId);
                    sMainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (res != null && res.success) {
                            sToolPackageName = res.packageName;
                            statusTv.setText("Setup Complete! Now tap '2. Start GG & Play' below.");
                            if (statusSubtitleView != null) {
                                statusSubtitleView.setText("Ready • Tap to Play in Virtual Root Mode");
                            }
                        } else {
                            statusTv.setText("Host clone failed • Trying local/cloud APK...");
                        }
                    });
                }).start();
                return;
            }

            File localApk = findLocalCompanionApkFile();
            if (localApk != null && localApk.isFile()) {
                installAndPrepareInDialog(activity, localApk, userId, statusTv, progressBar, statusSubtitleView);
                return;
            }

            downloadInstallInDialog(activity, sToolDownloadUrl, userId, statusTv, progressBar, statusSubtitleView);
        });
        card.addView(btnSetup);

        // Button 2: 1-Click Launch GG in Virtual Root Mode
        TextView btnPlay = createDialogButton(
                activity, "2. Start GG & Play (Virtual Root Mode)", "#30D158", boldTf);
        btnPlay.setOnClickListener(v -> {
            String pkg = findInstalledCompanionPackage(userId);
            if (pkg != null) {
                dialog.dismiss();
                Toast.makeText(activity, "Starting GG in Virtual Root Mode...", Toast.LENGTH_SHORT).show();
                FeaturesticBoxCore.get().launchApk(pkg, userId);
            } else {
                statusTv.setText("Please tap '1. One-Click Download / Auto-Setup GG' first!");
            }
        });
        card.addView(btnPlay);

        // Button 3: Close Dialog
        TextView btnClose = createDialogButton(activity, "Close", "#3A3A3C", medTf);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        card.addView(btnClose);

        int cardW = Math.min(dp(activity, 330),
                (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f));
        outer.addView(card, new LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(outer);
        dialog.show();
    }

    private static TextView createDialogButton(
            Context context, String text, String bgHex, Typeface tf) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(tf);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(context, 12));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String findHostInstalledGgPackage(Context context) {
        if (context == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            List<PackageInfo> list = pm.getInstalledPackages(0);
            if (list == null) return null;
            for (PackageInfo pi : list) {
                if (pi == null || pi.packageName == null) continue;
                String p = pi.packageName.toLowerCase();
                CharSequence lbl = pi.applicationInfo != null ? pi.applicationInfo.loadLabel(pm) : "";
                String labelStr = lbl != null ? lbl.toString().toLowerCase() : "";
                if (p.contains("catch_.me_.if_.you_.can")
                        || p.contains("gameguardian")
                        || labelStr.contains("gameguardian")
                        || (p.startsWith("com.") && p.length() == 20 && labelStr.contains("gg"))) {
                    return pi.packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String findInstalledCompanionPackage(int userId) {
        try {
            if (sToolPackageName != null && !sToolPackageName.isEmpty()
                    && FeaturesticBoxCore.get().isInstalled(sToolPackageName, userId)) {
                return sToolPackageName;
            }
            String currentGame = BActivityThread.isThreadInit() ? BActivityThread.getAppPackageName() : "";
            List<PackageInfo> pkgs = FeaturesticBoxCore.get().getInstalledPackages(0, userId);
            if (pkgs != null) {
                for (PackageInfo pi : pkgs) {
                    if (pi == null || pi.packageName == null) continue;
                    String p = pi.packageName;
                    if (p.equals(currentGame)
                            || p.startsWith("com.google.")
                            || p.startsWith("com.android.")
                            || p.equals(FeaturesticBoxCore.getHostPkg())) {
                        continue;
                    }
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File findLocalCompanionApkFile() {
        try {
            File cacheApk = new File(BEnvironment.getCacheDir(), "featurestic_companion_latest.apk");
            if (cacheApk.isFile() && cacheApk.length() > 100_000) {
                return cacheApk;
            }
            File dlDir = new File("/sdcard/Download");
            if (dlDir.isDirectory()) {
                File[] files = dlDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f != null && f.isFile() && f.getName().endsWith(".apk")) {
                            String lower = f.getName().toLowerCase();
                            if (lower.contains("gg") || lower.contains("guardian")
                                    || lower.contains("companion") || lower.contains("tool")) {
                                return f;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void installAndPrepareInDialog(
            final Activity activity, final File apkFile, final int userId,
            final TextView statusTv, final ProgressBar progressBar, final TextView menuSubTv) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(65);
        statusTv.setText("Installing " + apkFile.getName() + " into Virtual Slot " + userId + "...");
        new Thread(() -> {
            try {
                InstallResult res = FeaturesticBoxCore.get().installPackageAsUser(apkFile, userId);
                sMainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (res != null && res.success && res.packageName != null) {
                        sToolPackageName = res.packageName;
                        statusTv.setText("Setup Complete! Tap '2. Start GG & Play' to launch.");
                        if (menuSubTv != null) {
                            menuSubTv.setText("Ready • Tap to Play in Virtual Root Mode");
                        }
                    } else {
                        statusTv.setText("APK install failed • Check APK file");
                    }
                });
            } catch (Throwable ignored) {
            }
        }, "Featurestic-ToolInstaller").start();
    }

    private static void downloadInstallInDialog(
            final Activity activity, final String urlStr, final int userId,
            final TextView statusTv, final ProgressBar progressBar, final TextView menuSubTv) {
        if (sDownloadInProgress) return;
        sDownloadInProgress = true;
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(5);
        statusTv.setText("Downloading GG in background (0%)...");

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                File outApk = new File(BEnvironment.getCacheDir(), "featurestic_companion_latest.apk");
                FileUtils.mkdirs(outApk.getParentFile());
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                int totalLen = conn.getContentLength();

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outApk, false)) {
                    byte[] buf = new byte[16384];
                    long downloaded = 0;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        downloaded += n;
                        if (totalLen > 0) {
                            final int pct = (int) ((downloaded * 100L) / totalLen);
                            sMainHandler.post(() -> {
                                progressBar.setProgress(pct);
                                statusTv.setText("Downloading GG in background (" + pct + "%)...");
                            });
                        }
                    }
                }
                sDownloadInProgress = false;
                if (outApk.isFile() && outApk.length() > 50_000) {
                    sMainHandler.post(() -> installAndPrepareInDialog(
                            activity, outApk, userId, statusTv, progressBar, menuSubTv));
                } else {
                    sMainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusTv.setText("Tip: Place GG.apk in /sdcard/Download or install GG on phone for 1-sec import!");
                    });
                }
            } catch (Throwable e) {
                sDownloadInProgress = false;
                sMainHandler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusTv.setText("Tip: Install GG on your phone or put GG.apk in /sdcard/Download, then tap 1!");
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "Featurestic-ToolDownloader").start();
    }

    public static int getDnsMode() {
        return sDnsMode;
    }

    public static int cycleNextDnsMode(Context context) {
        sDnsMode = (sDnsMode + 1) % 4;
        String dns1 = null;
        String dns2 = null;
        switch (sDnsMode) {
            case 1:
                dns1 = "1.1.1.1";
                dns2 = "1.0.0.1";
                break;
            case 2:
                dns1 = "8.8.8.8";
                dns2 = "8.8.4.4";
                break;
            case 3:
                dns1 = "9.9.9.9";
                dns2 = "149.112.112.112";
                break;
            default:
                break;
        }
        if (dns1 != null) {
            try {
                RNative.setNativeProp("net.dns1", dns1);
                RNative.setNativeProp("net.dns2", dns2);
                NetworkLowLatencyOptimizer.boostAndWarmNow(context);
            } catch (Throwable ignored) {
            }
        }
        return sDnsMode;
    }

    public static String getDnsModeLabel(int mode) {
        switch (mode) {
            case 1:
                return "Cloudflare Warp DNS (1.1.1.1)";
            case 2:
                return "Google Public DNS (8.8.8.8)";
            case 3:
                return "Quad9 Gaming DNS (9.9.9.9)";
            case 0:
            default:
                return "Auto (ISP Default DNS)";
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
