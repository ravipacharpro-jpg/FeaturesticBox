package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Optional "Clean Proof Mode" Legit Watermark & Live Battery Telemetry Helper.
 *
 * - 100% Optional & OFF by default (MODE_HIDDEN = 0).
 * - When turned ON by the user from the Apple Control Center slider (or tapped to cycle
 *   presets), renders a subtle, legit-looking OEM gaming telemetry bar inside the main
 *   Activity DecorView (so it DOES appear in screenshots/recordings while Streamproof hides
 *   the real ESP, Mod Name, and Control Center!).
 * - Can be dragged anywhere on screen or hidden completely (View.GONE) in 1 tap.
 */
@Keep
public final class FeaturesticCleanProofWatermark {
    public static final int STYLE_OFF = 0;
    public static final int STYLE_OEM_FPS_COUNTER = 1;
    public static final int STYLE_ROG_GAME_GENIE = 2;
    public static final int STYLE_APPLE_METAL_HUD = 3;
    public static final int STYLE_CUSTOM_TEXT = 4;

    private static volatile Integer sCachedStyle;
    private static volatile String sCustomWatermarkText = "";
    private static volatile long sLastBatterySampleMs = 0L;
    private static volatile float sCachedBatteryTempC = 36.5f;
    private static volatile int sCachedBatteryPct = 100;
    private static final Map<Activity, TextView> sAttachedBadges =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FeaturesticCleanProofWatermark() {
    }

    private static File getStyleConfigFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_clean_watermark.style");
    }

    public static int getCurrentStyle() {
        if (sCachedStyle != null) return sCachedStyle;
        try {
            File f = getStyleConfigFile();
            if (f.isFile() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[16];
                    int n = fis.read(buf);
                    if (n > 0) {
                        int val = Integer.parseInt(new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                        if (val >= STYLE_OFF && val <= STYLE_CUSTOM_TEXT) {
                            sCachedStyle = val;
                            return val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedStyle = STYLE_OFF;
        return STYLE_OFF;
    }

    public static void setStyle(int style, Activity activeActivity) {
        int clamped = (style < STYLE_OFF || style > STYLE_CUSTOM_TEXT) ? STYLE_OFF : style;
        sCachedStyle = clamped;
        try {
            File f = getStyleConfigFile();
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(String.valueOf(clamped).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        updateBadgeOnActivity(activeActivity);
    }

    public static int cycleNextActiveStyle(Activity activeActivity) {
        int cur = getCurrentStyle();
        int next = (cur <= STYLE_OFF || cur >= STYLE_CUSTOM_TEXT)
                ? STYLE_OEM_FPS_COUNTER : (cur + 1);
        setStyle(next, activeActivity);
        return next;
    }

    public static void setCustomWatermarkText(String customText, Activity activeActivity) {
        sCustomWatermarkText = customText != null ? customText.trim() : "";
        if (!sCustomWatermarkText.isEmpty() && getCurrentStyle() != STYLE_OFF) {
            setStyle(STYLE_CUSTOM_TEXT, activeActivity);
        } else {
            updateBadgeOnActivity(activeActivity);
        }
    }

    public static String getStyleShortLabel(int style) {
        switch (style) {
            case STYLE_OEM_FPS_COUNTER:
                return "Stock FPS Bar";
            case STYLE_ROG_GAME_GENIE:
                return "ROG Game Genie";
            case STYLE_APPLE_METAL_HUD:
                return "Apple Metal HUD";
            case STYLE_CUSTOM_TEXT:
                return "Custom Tag";
            case STYLE_OFF:
            default:
                return "Off (Hidden)";
        }
    }

    public static void onGameActivityResumed(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (getCurrentStyle() != STYLE_OFF) {
            updateBadgeOnActivity(activity);
        }
    }

    public static void updateBadgeOnActivity(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            try {
                int style = getCurrentStyle();
                TextView badge = sAttachedBadges.get(activity);
                if (style == STYLE_OFF) {
                    if (badge != null) {
                        badge.setVisibility(View.GONE);
                    }
                    return;
                }
                if (badge == null) {
                    badge = createDraggableProofBadge(activity);
                    if (badge == null) return;
                    sAttachedBadges.put(activity, badge);
                }
                badge.setVisibility(View.VISIBLE);
                int fps = ThermalAndFrameGuard.getTargetFps();
                int ping = LivePingMonitor.getCurrentPingMs();
                float tempC = getBatteryTemperatureCelsius(activity);
                badge.setText(formatProofText(style, fps, ping, tempC));
            } catch (Throwable ignored) {
            }
        });
    }

    public static void onLiveTelemetryTick(Activity activity, int liveFps, int pingMs) {
        if (activity == null || getCurrentStyle() == STYLE_OFF) return;
        TextView badge = sAttachedBadges.get(activity);
        if (badge != null && badge.getVisibility() == View.VISIBLE) {
            float tempC = getBatteryTemperatureCelsius(activity);
            badge.setText(formatProofText(getCurrentStyle(), liveFps, pingMs, tempC));
        }
    }

    private static String formatProofText(int style, int fps, int pingMs, float tempC) {
        String tempStr = String.format(Locale.US, "%.1f°C", tempC);
        switch (style) {
            case STYLE_ROG_GAME_GENIE:
                return "ROG GAME GENIE  |  " + fps + " FPS  |  " + pingMs + "ms  |  " + tempStr;
            case STYLE_APPLE_METAL_HUD:
                float frameMs = fps > 0 ? (1000f / fps) : 8.3f;
                return String.format(Locale.US, "Metal HUD  •  %d FPS (%.1f ms)  •  %dms", fps, frameMs, pingMs);
            case STYLE_CUSTOM_TEXT:
                if (sCustomWatermarkText != null && !sCustomWatermarkText.isEmpty()) {
                    return sCustomWatermarkText + "  •  " + fps + " FPS  •  " + pingMs + "ms";
                }
                return "VERIFIED DEVICE  •  " + fps + " FPS  •  " + pingMs + "ms  •  " + tempStr;
            case STYLE_OEM_FPS_COUNTER:
            default:
                return "FPS: " + fps + "   PING: " + pingMs + "ms   TEMP: " + tempStr;
        }
    }

    private static TextView createDraggableProofBadge(final Activity activity) {
        if (activity.getWindow() == null || !(activity.getWindow().getDecorView() instanceof FrameLayout)) {
            return null;
        }
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        final TextView tv = new TextView(activity);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(Color.parseColor("#D9F5F5F7"));
        int padH = dp(activity, 9);
        int padV = dp(activity, 4);
        tv.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#8C000000"));
        bg.setCornerRadius(dp(activity, 8));
        bg.setStroke(1, Color.parseColor("#26FFFFFF"));
        tv.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(activity, 10);
        lp.rightMargin = dp(activity, 16);
        decor.addView(tv, lp);

        final int slop = dp(activity, 5);
        tv.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startRight, startTop;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startRight = flp.rightMargin;
                        startTop = flp.topMargin;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            dragged = true;
                        }
                        if (dragged) {
                            flp.rightMargin = Math.max(0, startRight - dx);
                            flp.topMargin = Math.max(0, startTop + dy);
                            v.setLayoutParams(flp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            cycleNextActiveStyle(activity);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        return tv;
    }

    private static void refreshBatteryCacheIfNeeded(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastBatterySampleMs < 15_000L && sLastBatterySampleMs != 0L) {
            return;
        }
        sLastBatterySampleMs = now;
        try {
            Context ctx = context != null ? context.getApplicationContext() : FeaturesticBoxCore.getContext();
            if (ctx == null) ctx = context;
            if (ctx != null) {
                Intent intent = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (intent != null) {
                    int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 365);
                    if (tenths > 0) {
                        sCachedBatteryTempC = tenths / 10.0f;
                    }
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    if (level >= 0 && scale > 0) {
                        sCachedBatteryPct = Math.round((level * 100f) / scale);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static float getBatteryTemperatureCelsius(Context context) {
        refreshBatteryCacheIfNeeded(context);
        return sCachedBatteryTempC;
    }

    public static int getBatteryPercentage(Context context) {
        refreshBatteryCacheIfNeeded(context);
        return sCachedBatteryPct;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
