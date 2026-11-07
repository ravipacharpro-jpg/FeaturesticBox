package com.featurestic.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Keep;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.featurestic.box.FeaturesticBoxCore;

/**
 * Smart Remote Config, Maintenance Kill-Switch & Game Version Safety Guard.
 *
 * Allows the developer to host a lightweight JSON file (e.g. on GitHub Gist / Cloudflare / Server)
 * to remotely control:
 * - "sdk_enabled": true/false
 * - "maintenance_mode": true/false
 * - "blocked_game_versions": ["3.6.0", "3.7.0"] (prevents users from running outdated offsets when a game updates!)
 * - "notice_title": "Maintenance Active"
 * - "notice_message": "New BGMI update detected. Updating offsets for your safety."
 */
@Keep
public final class FeaturesticRemoteConfigGuard {
    private static final String PREF_NAME = "featurestic_remote_config_cache";
    private static volatile String sRemoteConfigUrl = "";
    private static volatile boolean sSdkEnabled = true;
    private static volatile boolean sMaintenanceMode = false;
    private static volatile String sNoticeTitle = "Featurestic Security Notice";
    private static volatile String sNoticeMessage = "";
    private static volatile String sBlockedVersionsCsv = "";

    private FeaturesticRemoteConfigGuard() {
    }

    public static void setRemoteConfigUrl(String url) {
        sRemoteConfigUrl = url != null ? url.trim() : "";
        if (!sRemoteConfigUrl.isEmpty()) {
            syncRemoteConfigAsync(FeaturesticBoxCore.getContext());
        }
    }

    public static String getRemoteConfigUrl() {
        return sRemoteConfigUrl;
    }

    public static void syncRemoteConfigAsync(final Context context) {
        final String urlStr = sRemoteConfigUrl;
        if (urlStr == null || !urlStr.startsWith("https://")) {
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
                loadCachedState(ctx);
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    JSONObject json = new JSONObject(sb.toString());
                    sSdkEnabled = json.optBoolean("sdk_enabled", true);
                    sMaintenanceMode = json.optBoolean("maintenance_mode", false);
                    sNoticeTitle = json.optString("notice_title", "Featurestic Security Notice");
                    sNoticeMessage = json.optString("notice_message", "");

                    StringBuilder blocked = new StringBuilder();
                    JSONArray arr = json.optJSONArray("blocked_game_versions");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String v = arr.optString(i, "").trim();
                            if (!v.isEmpty()) {
                                if (blocked.length() > 0) blocked.append(",");
                                blocked.append(v);
                            }
                        }
                    }
                    sBlockedVersionsCsv = blocked.toString();
                    saveCachedState(ctx);
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "Featurestic-RemoteConfig").start();
    }

    public static boolean isLaunchAllowedForGame(Context context, String gamePackage, int userId) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        loadCachedState(ctx);
        if (!sSdkEnabled || sMaintenanceMode) {
            return false;
        }
        if (gamePackage != null && !sBlockedVersionsCsv.isEmpty()) {
            try {
                PackageInfo pi = FeaturesticBoxCore.getBPackageManager().getPackageInfo(gamePackage, 0, userId);
                if (pi != null && pi.versionName != null) {
                    String ver = pi.versionName.trim();
                    for (String b : sBlockedVersionsCsv.split(",")) {
                        if (ver.equalsIgnoreCase(b.trim())) {
                            return false;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    public static void showMaintenanceNoticeIfNeeded(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                String msg = !sNoticeMessage.isEmpty()
                        ? sNoticeMessage
                        : "Safe Mode / Maintenance is currently active to protect your account after a game update. Please wait for the updated release.";
                new AlertDialog.Builder(activity)
                        .setTitle(sNoticeTitle)
                        .setMessage(msg)
                        .setCancelable(false)
                        .setPositiveButton("Close Safely", (d, w) -> activity.finish())
                        .show();
            } catch (Throwable ignored) {
                activity.finish();
            }
        });
    }

    private static void saveCachedState(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .putBoolean("sdk_enabled", sSdkEnabled)
                    .putBoolean("maintenance_mode", sMaintenanceMode)
                    .putString("notice_title", sNoticeTitle)
                    .putString("notice_message", sNoticeMessage)
                    .putString("blocked_versions", sBlockedVersionsCsv)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void loadCachedState(Context ctx) {
        if (ctx == null || sRemoteConfigUrl.isEmpty()) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sSdkEnabled = sp.getBoolean("sdk_enabled", true);
            sMaintenanceMode = sp.getBoolean("maintenance_mode", false);
            sNoticeTitle = sp.getString("notice_title", "Featurestic Security Notice");
            sNoticeMessage = sp.getString("notice_message", "");
            sBlockedVersionsCsv = sp.getString("blocked_versions", "");
        } catch (Throwable ignored) {
        }
    }
}
