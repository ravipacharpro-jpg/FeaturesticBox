package com.featurestic.box.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import com.featurestic.box.core.RNative;

public class SdkLicenseGuard {
    private static final String TAG = "SdkLicenseGuard";

    private static final String PREF_NAME = "fbox_sdk_guard";
    private static final String KEY_SDK_KEY = "sdk_key";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EXP = "exp";
    private static final String KEY_LAST_VERIFIED = "last_verified";

    private static volatile String sSdkKey = null;
    private static volatile String sActivationToken = null;
    private static volatile long sTokenExpiryMs = 0L;
    private static final AtomicBoolean sVerifying = new AtomicBoolean(false);

    private static String getPanelEndpoint() {
        try {
            String endpoint = RNative.getSdkPanelEndpoint();
            if (endpoint != null && !endpoint.trim().isEmpty()) {
                return endpoint.trim();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Native endpoint resolution error: " + t.getMessage());
        }
        return "";
    }

    public static void setSdkKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            sSdkKey = key.trim();
        }
    }

    public static void setSdkKey(Context context, String key) {
        setSdkKey(key);
        if (context != null && key != null && !key.trim().isEmpty()) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_SDK_KEY, key.trim()).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String getSdkKey(Context context) {
        if (sSdkKey != null && !sSdkKey.isEmpty()) {
            return sSdkKey;
        }
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_SDK_KEY, null);
            if (saved != null && !saved.trim().isEmpty()) {
                sSdkKey = saved.trim();
                return sSdkKey;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null) {
                String meta = ai.metaData.getString("featurestic_sdk_key");
                if (meta != null && !meta.trim().isEmpty()) {
                    sSdkKey = meta.trim();
                    return sSdkKey;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String getActivationToken() {
        return sActivationToken;
    }

    public static boolean isLicensed(Context context) {
        if (sActivationToken != null && !sActivationToken.isEmpty()) {
            if (sTokenExpiryMs == 0L || System.currentTimeMillis() < sTokenExpiryMs) {
                return true;
            }
        }
        String key = getSdkKey(context);
        if (key == null || key.isEmpty()) {
            Log.e(TAG, "FeaturesticBox SDK Error: Missing SDK License Key");
            return false;
        }
        // Check cached verification from shared preferences
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String cachedToken = prefs.getString(KEY_TOKEN, null);
                long exp = prefs.getLong(KEY_EXP, 0L);
                long lastVer = prefs.getLong(KEY_LAST_VERIFIED, 0L);
                long now = System.currentTimeMillis();
                if (cachedToken != null && !cachedToken.isEmpty() && exp > now && (now - lastVer) < 86400000L) {
                    sActivationToken = cachedToken;
                    sTokenExpiryMs = exp;
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return verifySync(context, key);
    }

    public static boolean verifySync(Context context, String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            String endpoint = getPanelEndpoint();
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");

            String pkg = context != null ? context.getPackageName() : "unknown";
            String serial = "SDK_CLIENT";
            if (context != null) {
                try {
                    String aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (aid != null && !aid.isEmpty()) {
                        serial = aid;
                    }
                } catch (Throwable ignored) {}
            }

            String postData = "game=SDK&user_key=" + URLEncoder.encode(key, "UTF-8")
                    + "&pkg=" + URLEncoder.encode(pkg, "UTF-8")
                    + "&serial=" + URLEncoder.encode(serial, "UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                return false;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject json = new JSONObject(sb.toString());
            boolean status = json.optBoolean("status", false);
            if (status) {
                JSONObject data = json.optJSONObject("data");
                String token = data != null ? data.optString("token", "") : "";
                if (token.isEmpty()) {
                    token = "FB_" + key + "_" + System.currentTimeMillis();
                }
                sActivationToken = token;
                // Expiry: default 7 days grace cache if not parsed
                sTokenExpiryMs = System.currentTimeMillis() + (7L * 24L * 3600L * 1000L);

                if (context != null) {
                    try {
                        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                        prefs.edit()
                                .putString(KEY_TOKEN, token)
                                .putLong(KEY_EXP, sTokenExpiryMs)
                                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                                .putString(KEY_SDK_KEY, key)
                                .apply();
                    } catch (Throwable ignored) {}
                }
                Log.d(TAG, "FeaturesticBox SDK successfully licensed and interlocked.");
                return true;
            } else {
                String msg = json.optString("message", "Invalid SDK License Key");
                Log.e(TAG, "FeaturesticBox SDK License Error: " + msg);
                sActivationToken = null;
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "FeaturesticBox SDK License: Cannot connect to license server", t);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void verifyAsync(Context context, String key) {
        if (!sVerifying.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                verifySync(context, key);
            } finally {
                sVerifying.set(false);
            }
        }, "Featurestic-License-Check").start();
    }

    public static String getPluginUrl(Context context) {
        return "";
    }
}
