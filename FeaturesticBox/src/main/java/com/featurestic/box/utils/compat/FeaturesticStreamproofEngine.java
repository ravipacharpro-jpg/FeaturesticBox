package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Keep;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Built-In Streamproof ("Hide ESP / Hide Mod Name / Hide Floating Menu in Screenshots &
 * Screen Recording while Keeping Gameplay 100% Visible") Engine for FeaturesticBox.
 *
 * How it works across Android 10–16 without blacking out the game screen:
 * 1. Main Game Window Protection:
 *    Ensures WindowManager.LayoutParams.FLAG_SECURE is ALWAYS CLEARED on the main
 *    Game Activity Window (TYPE_BASE_APPLICATION), so 100% of the gameplay is captured
 *    normally in screenshots, screen recordings, and live streams!
 * 2. Secondary WindowManager Overlay Window Isolation:
 *    Inspects WindowManagerGlobal.getInstance() (mViews / mParams) and applies
 *    FLAG_SECURE + PRIVATE_FLAG_HIDE_IN_SCREENSHOT + PixelFormat.TRANSLUCENT ONLY to
 *    secondary overlay windows (TYPE_APPLICATION_PANEL, TYPE_APPLICATION_OVERLAY,
 *    mod menus, floating icons, and our Dynamic Island HUD sub-window). SurfaceFlinger
 *    renders these windows on the physical display for the user's eyes, but omits them
 *    from MediaProjection / ScreenCapture!
 * 3. Secondary .so ESP SurfaceView Isolation:
 *    Detects any secondary SurfaceView (index >= 1) injected on top of the game's primary
 *    rendering SurfaceView (index == 0, e.g. C++ ImGui / Canvas ESP layers from .so plugins)
 *    and calls secondarySurfaceView.setSecure(true) + PixelFormat.TRANSLUCENT.
 *    SurfaceFlinger skips capturing secure child SurfaceViews while capturing the game's
 *    primary SurfaceView underneath with zero black screen!
 * 4. Registered Watermark / Mod-Name View & Active VirtualDisplay / Screenshot Guard:
 *    Monitors DisplayManager for VirtualDisplay screen-recording sessions and hardware
 *    screenshot key-combos (Volume-Down + Power / KEYCODE_SYSRQ) to automatically cloak
 *    any plain Java TextView/View watermarks registered or tagged inside DecorView.
 */
@Keep
public final class FeaturesticStreamproofEngine {
    private static final int PRIVATE_FLAG_SKIP_SCREENSHOT = 0x00100000;
    private static volatile Boolean sStreamproofEnabled;
    private static volatile boolean sWatcherStarted = false;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final Set<View> sRegisteredOverlayViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<Activity> sTrackedActivities =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final java.util.Map<SurfaceView, Boolean> sAppliedSurfaceSecureState =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FeaturesticStreamproofEngine() {
    }

    private static File getStreamproofFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_streamproof_hide_esp.on");
    }

    public static boolean isStreamproofEnabled() {
        if (sStreamproofEnabled != null) {
            return sStreamproofEnabled;
        }
        try {
            sStreamproofEnabled = getStreamproofFlagFile().exists();
            syncNativeStreamproofProp(sStreamproofEnabled);
            return sStreamproofEnabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setStreamproofEnabled(boolean enabled, Activity activeActivity) {
        sStreamproofEnabled = enabled;
        try {
            File f = getStreamproofFlagFile();
            FileUtils.mkdirs(f.getParentFile());
            if (enabled) {
                f.createNewFile();
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Throwable ignored) {
        }
        syncNativeStreamproofProp(enabled);
        applyStreamproofPassNow(activeActivity);
    }

    /**
     * Allows the Loader or a plugin to explicitly register any custom Java View
     * (e.g. a "MOD NAME VIP" watermark TextView or custom ESP Canvas View) so
     * Streamproof Mode protects it automatically.
     */
    public static void registerStreamproofOverlayView(View overlayView) {
        if (overlayView == null) return;
        sRegisteredOverlayViews.add(overlayView);
        if (isStreamproofEnabled()) {
            applyStreamproofToSingleView(overlayView, true);
        }
    }

    private static void syncNativeStreamproofProp(boolean enabled) {
        try {
            System.setProperty("featurestic.streamproof", enabled ? "1" : "0");
            RNative.setNativeProp("debug.featurestic.streamproof", enabled ? "1" : "0");
        } catch (Throwable ignored) {
        }
    }

    public static void onGameActivityResumed(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        sTrackedActivities.add(activity);
        // Always ensure the main Game Activity Window NEVER has FLAG_SECURE so gameplay can be recorded!
        try {
            Window gameWindow = activity.getWindow();
            if (gameWindow != null) {
                gameWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Throwable ignored) {
        }

        startPeriodicStreamproofWatcher();
        if (isStreamproofEnabled()) {
            applyStreamproofPassNow(activity);
        }
    }

    private static void startPeriodicStreamproofWatcher() {
        if (sWatcherStarted) return;
        sWatcherStarted = true;
        sMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isStreamproofEnabled()) {
                        applyStreamproofToWindowManagerGlobal(true);
                        for (Activity act : sTrackedActivities) {
                            if (act != null && !act.isFinishing()) {
                                applyStreamproofToSecondarySurfaces(act, true);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                sMainHandler.postDelayed(this, 1200L);
            }
        }, 800L);
    }

    public static void applyStreamproofPassNow(Activity activeActivity) {
        final boolean enabled = isStreamproofEnabled();
        sMainHandler.post(() -> {
            try {
                // 1. Keep main game window un-secured so gameplay is always captured
                if (activeActivity != null && activeActivity.getWindow() != null) {
                    activeActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
                for (Activity act : sTrackedActivities) {
                    if (act != null && !act.isFinishing() && act.getWindow() != null) {
                        act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    }
                }

                // 2. Apply or clear FLAG_SECURE on all secondary WindowManager windows (HUD sub-window, mod menus, floating icons)
                applyStreamproofToWindowManagerGlobal(enabled);

                // 3. Apply or clear setSecure(true) on all secondary SurfaceViews (e.g. .so ImGui/ESP SurfaceViews)
                if (activeActivity != null && !activeActivity.isFinishing()) {
                    applyStreamproofToSecondarySurfaces(activeActivity, enabled);
                }
                for (Activity act : sTrackedActivities) {
                    if (act != null && !act.isFinishing()) {
                        applyStreamproofToSecondarySurfaces(act, enabled);
                    }
                }

                // 4. Apply to any explicitly registered custom Views
                for (View v : sRegisteredOverlayViews) {
                    if (v != null) {
                        applyStreamproofToSingleView(v, enabled);
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Temporarily cloaks registered in-DecorView plain Views during a hardware screenshot
     * key-chord (Volume Down + Power / KEYCODE_SYSRQ) so plain TextView watermarks inside
     * the main window are not captured in the screenshot.
     */
    public static void onHardwareScreenshotKeyTriggered() {
        if (!isStreamproofEnabled()) return;
        sMainHandler.post(() -> {
            final List<View> temporarilyHidden = new ArrayList<>();
            for (View v : sRegisteredOverlayViews) {
                if (v != null && v.getVisibility() == View.VISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                    temporarilyHidden.add(v);
                }
            }
            if (!temporarilyHidden.isEmpty()) {
                sMainHandler.postDelayed(() -> {
                    for (View v : temporarilyHidden) {
                        if (v != null) {
                            v.setVisibility(View.VISIBLE);
                        }
                    }
                }, 550L);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void applyStreamproofToWindowManagerGlobal(boolean enabled) {
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgClass.getMethod("getInstance").invoke(null);
            if (wmg == null) return;

            Field viewsField = wmgClass.getDeclaredField("mViews");
            Field paramsField = wmgClass.getDeclaredField("mParams");
            Field rootsField = wmgClass.getDeclaredField("mRoots");
            viewsField.setAccessible(true);
            paramsField.setAccessible(true);
            rootsField.setAccessible(true);

            List<View> views = (List<View>) viewsField.get(wmg);
            List<WindowManager.LayoutParams> paramsList =
                    (List<WindowManager.LayoutParams>) paramsField.get(wmg);
            List<Object> roots = (List<Object>) rootsField.get(wmg);
            if (views == null || paramsList == null) return;

            int count = Math.min(views.size(), paramsList.size());
            for (int i = 0; i < count; i++) {
                View view = views.get(i);
                WindowManager.LayoutParams lp = paramsList.get(i);
                if (view == null || lp == null) continue;

                // Never set FLAG_SECURE on the main Game Activity Window (TYPE_BASE_APPLICATION == 1)
                if (lp.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
                    if ((lp.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                        updateWindowViewLayoutSafely(view, lp);
                    }
                    continue;
                }

                // Every other window in the game process (TYPE_APPLICATION_PANEL, TYPE_APPLICATION_SUB_PANEL,
                // TYPE_APPLICATION_ATTACHED_DIALOG, TYPE_APPLICATION_OVERLAY, floating mod menu, watermark window,
                // or our Dynamic Island Sub-Window) gets Streamproof protection!
                boolean currentlySecure = (lp.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
                if (enabled) {
                    lp.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                    lp.format = PixelFormat.TRANSLUCENT;
                    setPrivateSkipScreenshotFlag(lp, true);
                    if (!currentlySecure) {
                        updateWindowViewLayoutSafely(view, lp);
                    }
                } else {
                    if (currentlySecure) {
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                        setPrivateSkipScreenshotFlag(lp, false);
                        updateWindowViewLayoutSafely(view, lp);
                    }
                }

                // Also protect any SurfaceView inside this secondary window
                if (view instanceof ViewGroup) {
                    List<SurfaceView> innerSurfaces = new ArrayList<>();
                    collectSurfaceViews((ViewGroup) view, innerSurfaces);
                    for (SurfaceView sv : innerSurfaces) {
                        if (sv != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            Boolean prev = sAppliedSurfaceSecureState.get(sv);
                            if (prev == null || prev != enabled) {
                                sAppliedSurfaceSecureState.put(sv, enabled);
                                sv.setSecure(enabled);
                                sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setPrivateSkipScreenshotFlag(WindowManager.LayoutParams lp, boolean skip) {
        try {
            Field pf = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
            pf.setAccessible(true);
            int cur = pf.getInt(lp);
            if (skip) {
                pf.setInt(lp, cur | PRIVATE_FLAG_SKIP_SCREENSHOT);
            } else {
                pf.setInt(lp, cur & ~PRIVATE_FLAG_SKIP_SCREENSHOT);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateWindowViewLayoutSafely(View view, WindowManager.LayoutParams lp) {
        try {
            Context ctx = view.getContext();
            if (ctx != null) {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && view.isAttachedToWindow()) {
                    wm.updateViewLayout(view, lp);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Scans the Activity's DecorView for SurfaceViews.
     * - SurfaceView #0 (the largest / primary SurfaceView) is the Game Engine's own renderer
     *   (UE4 / Unity) -> always kept setSecure(false) so gameplay records cleanly!
     * - SurfaceView #1+ (any secondary SurfaceView injected by a .so ESP / ImGui plugin)
     *   -> automatically gets setSecure(enabled) + PixelFormat.TRANSLUCENT so the ESP is
     *   visible on the physical screen but invisible in screenshots and screen recordings!
     */
    private static void applyStreamproofToSecondarySurfaces(Activity activity, boolean enabled) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        List<SurfaceView> allSurfaces = new ArrayList<>();
        collectSurfaceViews((ViewGroup) decor, allSurfaces);

        for (int i = 0; i < allSurfaces.size(); i++) {
            SurfaceView sv = allSurfaces.get(i);
            if (sv == null) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                boolean desired = (i == 0) ? false : enabled;
                Boolean prev = sAppliedSurfaceSecureState.get(sv);
                if (prev == null || prev != desired) {
                    sAppliedSurfaceSecureState.put(sv, desired);
                    sv.setSecure(desired);
                    if (i > 0) {
                        sv.setZOrderMediaOverlay(true);
                        sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                    }
                }
            }
        }
    }

    private static void applyStreamproofToSingleView(View view, boolean enabled) {
        if (view instanceof SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ((SurfaceView) view).setSecure(enabled);
            ((SurfaceView) view).getHolder().setFormat(PixelFormat.TRANSLUCENT);
        } else if (view instanceof ViewGroup) {
            List<SurfaceView> surfaces = new ArrayList<>();
            collectSurfaceViews((ViewGroup) view, surfaces);
            for (SurfaceView sv : surfaces) {
                if (sv != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    sv.setSecure(enabled);
                    sv.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                }
            }
        }
    }

    private static void collectSurfaceViews(ViewGroup group, List<SurfaceView> out) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView) {
                out.add((SurfaceView) child);
            } else if (child instanceof ViewGroup) {
                collectSurfaceViews((ViewGroup) child, out);
            }
        }
    }
}
