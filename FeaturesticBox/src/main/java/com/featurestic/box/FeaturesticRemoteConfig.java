package com.featurestic.box;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Keep;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.compat.DeviceProfileCompat;
import com.featurestic.box.utils.compat.ThermalAndFrameGuard;

/**
 * 1-Link Remote Cloud JSON Controller for FeaturesticBox.
 *
 * Host a single JSON file on GitHub Raw, Pastebin, or your own server and set its URL in Loader:
 *   FeaturesticBoxCore.get().setCloudConfigJsonUrl("https://your-server.com/config.json");
 *
 * Supported JSON keys (all optional):
 * {
 *   "plugin_url_64": "https://your-server.com/arm64/libFeaturesticPlugin.so",
 *   "plugin_url_32": "https://your-server.com/arm32/libFeaturesticPlugin.so",
 *   "plugin_sha256": "",
 *   "target_fps": 120,
 *   "device_preset": "ROG_PHONE_8",
 *   "maintenance_mode": false,
 *   "maintenance_message": "Server under maintenance for new BGMI update. Wait 30 mins!",
 *   "announcement": "Welcome to FeaturesticBox v2.0 - 120 FPS Active!"
 * }
 */
@Keep
public final class FeaturesticRemoteConfig {
    private static final String PREFS_NAME = "featurestic_remote_json_config";
    private static volatile String sConfigJsonUrl = "";
    private static volatile boolean sMaintenanceBlocked = false;
    private static volatile String sMaintenanceMessage = "Loader is currently under maintenance.";

    private static final ExecutorService CONFIG_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Featurestic-RemoteConfig");
        t.setDaemon(true);
        return t;
    });

    private FeaturesticRemoteConfig() {
    }

    public static void setCloudConfigJsonUrl(String jsonUrl) {
        sConfigJsonUrl = jsonUrl != null ? jsonUrl.trim() : "";
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString("config_json_url", sConfigJsonUrl)
                        .apply();
            }
        } catch (Throwable ignored) {
        }
        syncRemoteConfigAsync(FeaturesticBoxCore.getContext());
    }

    public static boolean isMaintenanceBlocked() {
        return sMaintenanceBlocked;
    }

    public static String getMaintenanceMessage() {
        return sMaintenanceMessage;
    }

    public static void syncRemoteConfigAsync(final Context context) {
        final Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        String url = sConfigJsonUrl;
        if ((url == null || url.isEmpty()) && ctx != null) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                url = sp.getString("config_json_url", "");
            } catch (Throwable ignored) {
            }
        }
        if (url == null || !url.startsWith("http")) {
            return;
        }
        final String finalUrl = url;
        CONFIG_POOL.execute(() -> fetchAndApplyJson(ctx, finalUrl));
    }

    private static void fetchAndApplyJson(Context context, String jsonUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(jsonUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(10_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "FeaturesticBox-RemoteConfig/2.0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject json = new JSONObject(sb.toString());

            // 1. Maintenance Mode check
            sMaintenanceBlocked = json.optBoolean("maintenance_mode", false);
            String maintMsg = json.optString("maintenance_message", "");
            if (!maintMsg.isEmpty()) {
                sMaintenanceMessage = maintMsg;
            }

            // 2. Remote FPS & Device Preset override
            int targetFps = json.optInt("target_fps", 0);
            if (targetFps == 60 || targetFps == 90 || targetFps == 120) {
                ThermalAndFrameGuard.setTargetFps(targetFps);
            }
            String preset = json.optString("device_preset", "");
            if (!preset.isEmpty()) {
                DeviceProfileCompat.setPreset(preset);
            }

            // 3. Remote .so OTA URLs
            String url64 = json.optString("plugin_url_64", json.optString("plugin_url", ""));
            String url32 = json.optString("plugin_url_32", url64);
            String sha256 = json.optString("plugin_sha256", "");
            if (!url64.isEmpty() || !url32.isEmpty()) {
                FeaturesticCloudPluginLoader.setCloudPluginUrls(url64, url32, sha256);
            }

            // 4. Optional announcement toast (only shown when non-empty & changed)
            final String announcement = json.optString("announcement", "").trim();
            if (!announcement.isEmpty() && context != null) {
                SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String lastShown = sp.getString("last_announcement", "");
                if (!announcement.equals(lastShown)) {
                    sp.edit().putString("last_announcement", announcement).apply();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Toast.makeText(context, announcement, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
