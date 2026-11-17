package com.featurestic.box.utils;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class FacebookWebViewViewportFix {
    private static final String TAG = "FacebookViewportFix";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Map<WebView, Boolean> BASE_CONFIGURED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());
    private static final Map<WebView, Long> LAST_FORCE_SCALE =
            Collections.synchronizedMap(new WeakHashMap<WebView, Long>());
    private static final Map<Activity, Boolean> PORTRAIT_APPLIED =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    private static final long[] RESCAN_DELAYS = new long[]{
            0L, 150L, 350L, 700L, 1100L, 1700L, 2600L, 3800L,
            5500L, 8000L, 12000L, 18000L, 26000L, 38000L,
            52000L, 70000L, 90000L
    };

    private FacebookWebViewViewportFix() {
    }

    public static void applyIfNeeded(final Activity activity) {
        if (activity == null || !isLoginActivity(activity)) {
            return;
        }

        forcePortraitForVerification(activity);

        try {
            activity.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            activity.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        } catch (Throwable ignored) {
        }

        for (final long delay : RESCAN_DELAYS) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        forcePortraitForVerification(activity);
                        scanActivity(activity);
                        scanWindowManagerRoots(activity);
                    } catch (Throwable e) {
                        Slog.w(TAG, "scan failed: " + e.getMessage());
                    }
                }
            }, delay);
        }
    }

    private static boolean isLoginActivity(Activity activity) {
        String name = activity.getClass().getName();
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("facebookactivity")
                || lower.contains("imsdkproxyactivity")
                || lower.contains("customtabmainactivity")
                || lower.contains("customtabactivity");
    }

    private static void forcePortraitForVerification(Activity activity) {
        String name = activity.getClass().getName();
        if (name == null) return;
        String lower = name.toLowerCase();
        boolean shouldPortrait = lower.contains("facebookactivity")
                || lower.contains("customtabmainactivity")
                || lower.contains("customtabactivity");
        if (!shouldPortrait) return;

        synchronized (PORTRAIT_APPLIED) {
            if (Boolean.TRUE.equals(PORTRAIT_APPLIED.get(activity))) {
                return;
            }
            PORTRAIT_APPLIED.put(activity, Boolean.TRUE);
        }

        try {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            Slog.d(TAG, "portrait verification enabled for " + name);
        } catch (Throwable e) {
            Slog.w(TAG, "portrait request failed: " + e.getMessage());
        }
    }

    private static void scanActivity(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            configureTree(decor, isLandscape(activity));
        } catch (Throwable e) {
            Slog.w(TAG, "activity root scan failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void scanWindowManagerRoots(Activity activity) {
        try {
            Class<?> clazz = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);

            Field viewsField = clazz.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object value = viewsField.get(global);
            if (value instanceof List) {
                boolean landscape = isLandscape(activity);
                for (Object item : (List<Object>) value) {
                    if (item instanceof View) {
                        configureTree((View) item, landscape);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLandscape(Activity activity) {
        try {
            return activity.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void configureTree(View view, boolean landscape) {
        if (view == null) return;

        if (view instanceof WebView) {
            configureWebView((WebView) view, landscape);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureTree(group.getChildAt(i), landscape);
            }
        }
    }

    private static void configureWebView(final WebView webView, final boolean landscape) {
        if (webView == null) return;

        boolean firstTime;
        synchronized (BASE_CONFIGURED) {
            firstTime = !BASE_CONFIGURED.containsKey(webView);
            if (firstTime) {
                BASE_CONFIGURED.put(webView, Boolean.TRUE);
            }
        }

        try {
            if (firstTime) {
                WebSettings settings = webView.getSettings();
                if (settings != null) {
                    settings.setUseWideViewPort(true);
                    settings.setLoadWithOverviewMode(true);
                    settings.setSupportZoom(true);
                    settings.setBuiltInZoomControls(true);
                    settings.setDisplayZoomControls(false);
                }

                webView.setVerticalScrollBarEnabled(true);
                webView.setHorizontalScrollBarEnabled(false);
                webView.setScrollbarFadingEnabled(false);
                webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
                webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                webView.setNestedScrollingEnabled(true);
                webView.setInitialScale(landscape ? 55 : 85);
            }

            try {
                String initialScale = landscape ? "0.55" : "0.85";
                webView.evaluateJavascript(
                        "(function(){try{" +
                        "var h=document.documentElement,b=document.body;" +
                        "if(h){h.style.setProperty('overflow-y','auto','important');" +
                        "h.style.setProperty('height','auto','important');" +
                        "h.style.setProperty('min-height','100vh','important');}" +
                        "if(b){b.style.setProperty('overflow-y','auto','important');" +
                        "b.style.setProperty('height','auto','important');" +
                        "b.style.setProperty('min-height','100vh','important');}" +
                        "var m=document.querySelector('meta[name=viewport]');" +
                        "if(!m){m=document.createElement('meta');m.name='viewport';" +
                        "document.head&&document.head.appendChild(m);}" +
                        "if(m){m.setAttribute('content','width=device-width, initial-scale=" + initialScale +
                        ", maximum-scale=5.0, user-scalable=yes');}" +
                        "}catch(e){}})();", null);
            } catch (Throwable ignored) {
            }

            final long now = android.os.SystemClock.uptimeMillis();
            Long last = LAST_FORCE_SCALE.get(webView);
            if (last == null || now - last >= 3000L) {
                LAST_FORCE_SCALE.put(webView, now);
                webView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            boolean currentlyLandscape = webView.getResources()
                                    .getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                            float current = webView.getScale();
                            final float target = currentlyLandscape ? 0.55f : 0.85f;
                            final float tolerance = currentlyLandscape ? 0.61f : 0.92f;
                            if (current > tolerance) {
                                float factor = target / current;
                                if (factor < 0.01f) factor = 0.01f;
                                if (factor > 100.0f) factor = 100.0f;
                                webView.zoomBy(factor);
                                Slog.d(TAG, "verification fit: " + current + " -> " + target);
                            }
                        } catch (Throwable e) {
                            Slog.w(TAG, "dynamic zoom failed: " + e.getMessage());
                        }
                    }
                });
            }

            Slog.d(TAG, "verification viewport refreshed; landscape=" + landscape);
        } catch (Throwable e) {
            Slog.w(TAG, "WebView configure failed: " + e.getMessage());
        }
    }
}
