package com.featurestic.box;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.Md5Utils;
import com.featurestic.box.utils.compat.CrashShieldAndResourceHealer;
import com.featurestic.box.utils.compat.FeaturesticAutopilotEngine;
import com.featurestic.box.utils.compat.FeaturesticEsportsCoreEngines;
import com.featurestic.box.utils.compat.FeaturesticGraphicsOptimizer;
import com.featurestic.box.utils.compat.FeaturesticIpadViewEngine;
import com.featurestic.box.utils.compat.FeaturesticRegionSpoofer;
import com.featurestic.box.utils.compat.FeaturesticZeroCopyLinker;
import com.featurestic.box.utils.compat.FeaturesticCleanProofWatermark;
import com.featurestic.box.utils.compat.FeaturesticCompanionToolManager;
import com.featurestic.box.utils.compat.FeaturesticStreamproofEngine;
import com.featurestic.box.utils.compat.FeaturesticTouchAndGyroOptimizer;
import com.featurestic.box.utils.compat.LivePingMonitor;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;
import com.featurestic.box.utils.compat.StorageDietOptimizer;
import com.featurestic.box.utils.compat.ThermalAndFrameGuard;
import com.featurestic.box.utils.compat.WebViewCompat;

/**
 * Apple iOS 18 Dynamic Island & visionOS Control Center HUD with Authentic Apple
 * ON/OFF Slider Toggle Switches (UISwitch) and Custom SF-Symbol Vector Icons.
 *
 * - Zero Emojis: All icons rendered with custom anti-aliased vector geometry.
 * - Authentic Apple UISwitch Slider Toggles (#30D158 Apple System Green when ON,
 *   #39393D dark-mode track when OFF, smooth animated circular white thumb).
 * - 3-Finger Double-Tap Gesture anywhere on screen to completely hide (View.GONE)
 *   or restore the HUD for 100% clean screen recording and live streaming.
 */
@Keep
public final class FeaturesticOverlayManager {
    public interface OverlayViewFactory {
        View createOverlayView(Activity activity);
    }

    // Custom Apple SF-Symbol Vector Icon IDs
    private static final int ICON_WAVEFORM = 1;
    private static final int ICON_GAUGE_FPS = 2;
    private static final int ICON_IPAD_VIEW = 3;
    private static final int ICON_THERMAL_SHIELD = 4;
    private static final int ICON_NET_TURBO = 5;
    private static final int ICON_SWITCH_ID = 6;
    private static final int ICON_RAM_CHIP = 7;
    private static final int ICON_STEALTH_EYE = 8;
    private static final int ICON_GUEST_RESET = 9;
    private static final int ICON_DEVICE_ID = 10;
    private static final int ICON_DIAGNOSTICS = 11;
    private static final int ICON_CHEVRON = 12;
    private static final int ICON_TOUCH_GYRO = 13;
    private static final int ICON_AUDIO_FOOTSTEP = 14;
    private static final int ICON_KEEP_ALIVE_LOCK = 15;
    private static final int ICON_CLAW_EDGE = 16;

    private static volatile OverlayViewFactory sCustomFactory;
    private static volatile Boolean sCachedFpsFlag;
    private static final Map<Activity, View> sAttachedOverlays =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FeaturesticOverlayManager() {
    }

    private static volatile Integer sCachedUiScalePct;

    private static File getUiScaleConfigFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_ui_scale.pref");
    }

    private static int getSavedUiScalePercent() {
        if (sCachedUiScalePct != null) return sCachedUiScalePct;
        try {
            File f = getUiScaleConfigFile();
            if (f.isFile() && f.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[16];
                    int n = fis.read(buf);
                    if (n > 0) {
                        int val = Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
                        sCachedUiScalePct = Math.max(60, Math.min(115, val));
                        return sCachedUiScalePct;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedUiScalePct = 85; // Default compact 85% scale so all categories fit on landscape phone screens!
        return 85;
    }

    private static void setSavedUiScalePercent(int pct) {
        int clamped = Math.max(60, Math.min(115, pct));
        sCachedUiScalePct = clamped;
        try {
            File f = getUiScaleConfigFile();
            FileUtils.mkdirs(f.getParentFile());
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                fos.write(String.valueOf(clamped).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyUiScaleToMenu(
            Activity activity, View rootContainer, View controlSheet, ScrollView scrollView, int scalePct) {
        try {
            float ratio = scalePct / 100.0f;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int sheetW = Math.min(Math.round(dp(activity, 286) * ratio), (int) (screenW * 0.85f));
            int scrollH = Math.min(Math.round(dp(activity, 225) * ratio), (int) (screenH * 0.55f));

            ViewGroup.LayoutParams sLp = controlSheet.getLayoutParams();
            if (sLp != null) {
                sLp.width = sheetW;
                controlSheet.setLayoutParams(sLp);
            }
            ViewGroup.LayoutParams scLp = scrollView.getLayoutParams();
            if (scLp != null) {
                scLp.height = scrollH;
                scrollView.setLayoutParams(scLp);
            }
            rootContainer.setPivotX(0f);
            rootContainer.setPivotY(0f);
            rootContainer.setScaleX(ratio);
            rootContainer.setScaleY(ratio);
        } catch (Throwable ignored) {
        }
    }

    private static File getFpsFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_fps_hud.flag");
    }

    private static File getFpsDisabledFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_fps_hud_disabled.flag");
    }

    public static void setBuiltInFpsOverlayEnabled(boolean enabled) {
        sCachedFpsFlag = enabled;
        try {
            File flag = getFpsFlagFile();
            File disabledFlag = getFpsDisabledFile();
            FileUtils.mkdirs(flag.getParentFile());
            if (enabled) {
                flag.createNewFile();
                if (disabledFlag.exists()) disabledFlag.delete();
            } else {
                if (flag.exists()) flag.delete();
                disabledFlag.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isBuiltInFpsOverlayEnabled() {
        if (sCachedFpsFlag != null) return sCachedFpsFlag;
        try {
            File disabledFlag = getFpsDisabledFile();
            if (disabledFlag != null && disabledFlag.exists()) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setCustomOverlayFactory(OverlayViewFactory factory) {
        sCustomFactory = factory;
    }

    public static void onActivityResumed(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String actName = activity.getClass().getName();
        if (actName.contains("VirtualOAuthBridgeActivity")
                || actName.contains("TwitterNativeAuthBridgeActivity")
                || actName.contains("OAuthCallbackActivity")
                || actName.contains("SignInHubActivity")
                || actName.contains("FacebookActivity")
                || actName.contains("CustomTab")
                || actName.contains("OAuthActivity")
                || actName.contains("TwitterWebActivity")
                || actName.contains("LauncherActivity")) {
            return;
        }
        ThermalAndFrameGuard.applyToActivityWindow(activity);
        FeaturesticIpadViewEngine.applyToActivity(activity);
        FeaturesticEsportsCoreEngines.onGameActivityResumed(activity);
        FeaturesticStreamproofEngine.onGameActivityResumed(activity);
        FeaturesticCleanProofWatermark.onGameActivityResumed(activity);
        FeaturesticAutopilotEngine.onGameActivityResumed(activity);
        if (sAttachedOverlays.containsKey(activity)) {
            return;
        }
        final OverlayViewFactory factory = sCustomFactory;
        final boolean showFps = isBuiltInFpsOverlayEnabled();
        if (factory == null && !showFps) {
            return;
        }
        try {
            activity.getWindow().getDecorView().post(() -> {
                if (activity.isFinishing() || sAttachedOverlays.containsKey(activity)) return;
                try {
                    View overlayView = factory != null
                            ? factory.createOverlayView(activity)
                            : createAppleDynamicIslandHud(activity);
                    if (overlayView == null) return;
                    attachToActivityDecor(activity, overlayView);
                    sAttachedOverlays.put(activity, overlayView);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        FeaturesticAutopilotEngine.onGameActivityDestroyed();
        if (activity == null) return;
        View view = sAttachedOverlays.remove(activity);
        if (view != null) {
            try {
                WindowManager wm = activity.getWindowManager();
                if (wm != null && view.getLayoutParams() instanceof WindowManager.LayoutParams) {
                    wm.removeViewImmediate(view);
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void attachToActivityDecor(Activity activity, View overlayView) {
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        FeaturesticStreamproofEngine.registerStreamproofOverlayView(overlayView);

        // Attach HUD as an independent TYPE_APPLICATION_PANEL Sub-Window tied to the Activity token.
        // Because it is its own Window in SurfaceFlinger, setting FLAG_SECURE on this sub-window
        // makes the HUD 100% invisible in Screenshots & Screen Recordings while the Game Window
        // underneath records 100% normally without any black screen!
        try {
            android.os.IBinder token = decor.getWindowToken();
            WindowManager wm = activity.getWindowManager();
            if (token != null && wm != null) {
                int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                if (FeaturesticStreamproofEngine.isStreamproofEnabled()) {
                    flags |= WindowManager.LayoutParams.FLAG_SECURE;
                }
                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        flags,
                        android.graphics.PixelFormat.TRANSLUCENT);
                wlp.gravity = Gravity.TOP | Gravity.START;
                wlp.x = dp(activity, 18);
                wlp.y = dp(activity, 30);
                wlp.token = token;
                wm.addView(overlayView, wlp);
                return;
            }
        } catch (Throwable ignored) {
        }

        if (decor instanceof FrameLayout) {
            FrameLayout root = (FrameLayout) decor;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            params.leftMargin = dp(activity, 18);
            params.topMargin = dp(activity, 30);
            root.addView(overlayView, params);
        }
    }

    private static View createAppleDynamicIslandHud(final Activity activity) {
        final Typeface sfMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        final Typeface sfSemibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
        final Typeface sfRegular = Typeface.create("sans-serif", Typeface.NORMAL);

        final LinearLayout rootContainer = new LinearLayout(activity);
        rootContainer.setOrientation(LinearLayout.VERTICAL);

        // 1. Apple Dynamic Island Collapsed Capsule
        final LinearLayout islandCapsule = new LinearLayout(activity);
        islandCapsule.setOrientation(LinearLayout.HORIZONTAL);
        islandCapsule.setGravity(Gravity.CENTER_VERTICAL);
        int pillPadH = dp(activity, 12);
        int pillPadV = dp(activity, 7);
        islandCapsule.setPadding(pillPadH, pillPadV, pillPadH, pillPadV);

        GradientDrawable islandBg = new GradientDrawable();
        islandBg.setColor(Color.parseColor("#EC141416"));
        islandBg.setCornerRadius(dp(activity, 22));
        islandBg.setStroke(dp(activity, 1), Color.parseColor("#36FFFFFF"));
        islandCapsule.setBackground(islandBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            islandCapsule.setElevation(dp(activity, 8));
        }

        final SfSymbolIconView waveIcon = new SfSymbolIconView(
                activity, ICON_WAVEFORM, Color.parseColor("#0A84FF"), dp(activity, 15));
        islandCapsule.addView(waveIcon);

        final TextView telemetryText = new TextView(activity);
        telemetryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        telemetryText.setTypeface(sfSemibold);
        telemetryText.setTextColor(Color.parseColor("#F5F5F7"));
        telemetryText.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        telemetryText.setText("-- FPS • --ms • --°C");
        islandCapsule.addView(telemetryText);

        final View pingStatusDot = new View(activity);
        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(Color.parseColor("#30D158"));
        pingStatusDot.setBackground(dotDrawable);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(activity, 7), dp(activity, 7));
        dotLp.rightMargin = dp(activity, 6);
        islandCapsule.addView(pingStatusDot, dotLp);

        final SfSymbolIconView chevronIcon = new SfSymbolIconView(
                activity, ICON_CHEVRON, Color.parseColor("#8E8E93"), dp(activity, 11));
        islandCapsule.addView(chevronIcon);

        // 2. Apple iOS 18 Control Center Platter
        final LinearLayout controlSheet = new LinearLayout(activity);
        controlSheet.setOrientation(LinearLayout.VERTICAL);
        controlSheet.setVisibility(View.GONE);
        int sheetPad = dp(activity, 12);
        controlSheet.setPadding(sheetPad, sheetPad, sheetPad, sheetPad);

        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.parseColor("#F21C1C1E"));
        sheetBg.setCornerRadius(dp(activity, 24));
        sheetBg.setStroke(dp(activity, 1), Color.parseColor("#2EFFFFFF"));
        controlSheet.setBackground(sheetBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            controlSheet.setElevation(dp(activity, 16));
        }

        LinearLayout.LayoutParams sheetLp = new LinearLayout.LayoutParams(
                dp(activity, 294), ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.topMargin = dp(activity, 8);

        // Header Row
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 8));

        TextView headerTitle = new TextView(activity);
        headerTitle.setText("Control Center");
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        headerTitle.setTypeface(sfSemibold);
        headerTitle.setTextColor(Color.WHITE);
        headerRow.addView(headerTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int slotId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        float initTemp = FeaturesticCleanProofWatermark.getBatteryTemperatureCelsius(activity);
        int initBatt = FeaturesticCleanProofWatermark.getBatteryPercentage(activity);
        headerTitle.setText(String.format(java.util.Locale.US, "Control  •  %.0f°C  %d%%", initTemp, initBatt));

        // Apple Style UI Size Adjuster: [ - ]  85%  [ + ] so user can shrink/enlarge menu on any screen!
        LinearLayout sizeControlPill = new LinearLayout(activity);
        sizeControlPill.setOrientation(LinearLayout.HORIZONTAL);
        sizeControlPill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable slotPillBg = new GradientDrawable();
        slotPillBg.setColor(Color.parseColor("#2C2C2E"));
        slotPillBg.setCornerRadius(dp(activity, 10));
        sizeControlPill.setBackground(slotPillBg);

        TextView minusBtn = new TextView(activity);
        minusBtn.setText(" − ");
        minusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        minusBtn.setTypeface(sfSemibold);
        minusBtn.setTextColor(Color.parseColor("#0A84FF"));
        minusBtn.setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 6), dp(activity, 2));

        final TextView scaleLabelTv = new TextView(activity);
        int currentScalePct = getSavedUiScalePercent();
        scaleLabelTv.setText(currentScalePct + "%");
        scaleLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        scaleLabelTv.setTypeface(sfMedium);
        scaleLabelTv.setTextColor(Color.parseColor("#F5F5F7"));

        TextView plusBtn = new TextView(activity);
        plusBtn.setText(" + ");
        plusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        plusBtn.setTypeface(sfSemibold);
        plusBtn.setTextColor(Color.parseColor("#0A84FF"));
        plusBtn.setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 8), dp(activity, 2));

        sizeControlPill.addView(minusBtn);
        sizeControlPill.addView(scaleLabelTv);
        sizeControlPill.addView(plusBtn);
        headerRow.addView(sizeControlPill);
        controlSheet.addView(headerRow);

        // Scrollable Content Area (clamped to 54% of screen height so bottom items NEVER go outside the screen!)
        final ScrollView scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int maxSheetScrollH = Math.min(dp(activity, 215), (int) (screenH * 0.54f));

        applyUiScaleToMenu(activity, rootContainer, controlSheet, scrollView, currentScalePct);

        minusBtn.setOnClickListener(v -> {
            int nextPct = Math.max(60, getSavedUiScalePercent() - 10);
            setSavedUiScalePercent(nextPct);
            scaleLabelTv.setText(nextPct + "%");
            applyUiScaleToMenu(activity, rootContainer, controlSheet, scrollView, nextPct);
        });
        plusBtn.setOnClickListener(v -> {
            int nextPct = Math.min(115, getSavedUiScalePercent() + 10);
            setSavedUiScalePercent(nextPct);
            scaleLabelTv.setText(nextPct + "%");
            applyUiScaleToMenu(activity, rootContainer, controlSheet, scrollView, nextPct);
        });

        LinearLayout scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout[] categoryBodies = new LinearLayout[5];
        final SfSymbolIconView[] categoryChevrons = new SfSymbolIconView[5];

        // =========================================================================
        // CATEGORY 1: Display, 120Hz & iPad Wide View
        // =========================================================================
        LinearLayout cat1Body = createCategoryBodyContainer(activity);
        categoryBodies[0] = cat1Body;

        final TextView[] fpsSubRef = new TextView[1];
        cat1Body.addView(createIosSliderToggleRow(
                activity,
                ICON_GAUGE_FPS,
                "#0A84FF",
                "120Hz High Frame Rate",
                "Target: " + ThermalAndFrameGuard.getTargetFps() + " FPS (Tap text for 90/60)",
                ThermalAndFrameGuard.getTargetFps() >= 90,
                sfMedium,
                sfRegular,
                fpsSubRef,
                isChecked -> {
                    int nextFps = isChecked ? 120 : 60;
                    ThermalAndFrameGuard.setTargetFps(nextFps);
                    ThermalAndFrameGuard.applyToActivityWindow(activity);
                    FeaturesticIpadViewEngine.applyToActivity(activity);
                    if (fpsSubRef[0] != null) {
                        fpsSubRef[0].setText("Target: " + nextFps + " FPS (Tap text for 90/60)");
                    }
                    Toast.makeText(activity,
                            isChecked ? "120 FPS Ultra Extreme Mode ON" : "60 FPS Standard Mode",
                            Toast.LENGTH_SHORT).show();
                },
                () -> {
                    int cur = ThermalAndFrameGuard.getTargetFps();
                    int next = (cur == 120) ? 90 : (cur == 90 ? 60 : 120);
                    ThermalAndFrameGuard.setTargetFps(next);
                    ThermalAndFrameGuard.applyToActivityWindow(activity);
                    FeaturesticIpadViewEngine.applyToActivity(activity);
                    if (fpsSubRef[0] != null) {
                        fpsSubRef[0].setText("Target: " + next + " FPS (Tap text for 90/60)");
                    }
                    Toast.makeText(activity, "Frame Rate set to " + next + " FPS", Toast.LENGTH_SHORT).show();
                }));
        cat1Body.addView(createHairlineDivider(activity));



        final TextView[] gfxSubRef = new TextView[1];
        final AppleToggleSwitchView[] gfxSwitchRef = new AppleToggleSwitchView[1];
        int initGfxPreset = FeaturesticGraphicsOptimizer.getCurrentPreset();
        cat1Body.addView(createIosSliderToggleRowWithSwitchRef(
                activity,
                ICON_RAM_CHIP,
                "#30D158",
                "Esports Graphics Boost",
                FeaturesticGraphicsOptimizer.getPresetLabel(initGfxPreset) + " (Tap to cycle)",
                initGfxPreset != FeaturesticGraphicsOptimizer.PRESET_DEFAULT,
                sfMedium,
                sfRegular,
                gfxSubRef,
                gfxSwitchRef,
                isChecked -> {
                    int nextPreset = isChecked
                            ? FeaturesticGraphicsOptimizer.PRESET_ESPORTS_NO_SHADOW
                            : FeaturesticGraphicsOptimizer.PRESET_DEFAULT;
                    FeaturesticGraphicsOptimizer.setPreset(nextPreset, activity);
                    if (gfxSubRef[0] != null) {
                        gfxSubRef[0].setText(FeaturesticGraphicsOptimizer.getPresetLabel(nextPreset) + " (Tap to cycle)");
                    }
                    Toast.makeText(activity,
                            FeaturesticGraphicsOptimizer.getPresetLabel(nextPreset),
                            Toast.LENGTH_SHORT).show();
                },
                () -> {
                    int nextPreset = FeaturesticGraphicsOptimizer.cycleNextPreset(activity);
                    if (gfxSubRef[0] != null) {
                        gfxSubRef[0].setText(FeaturesticGraphicsOptimizer.getPresetLabel(nextPreset) + " (Tap to cycle)");
                    }
                    if (gfxSwitchRef[0] != null) {
                        gfxSwitchRef[0].setCheckedAnimated(
                                nextPreset != FeaturesticGraphicsOptimizer.PRESET_DEFAULT);
                    }
                    Toast.makeText(activity,
                            FeaturesticGraphicsOptimizer.getPresetLabel(nextPreset),
                            Toast.LENGTH_SHORT).show();
                }));

        scrollContent.addView(createAppleCategorySection(
                activity, 0, ICON_IPAD_VIEW, "#5E5CE6",
                "Display & 120Hz Graphics",
                "120Hz Frame Rate • Esports No-Shadow",
                sfSemibold, sfRegular, cat1Body, categoryBodies, categoryChevrons, true));

        // =========================================================================
        // CATEGORY 2: Streamproof & Recording Privacy
        // =========================================================================
        LinearLayout cat2Body = createCategoryBodyContainer(activity);
        categoryBodies[1] = cat2Body;

        final TextView[] streamproofSubRef = new TextView[1];
        cat2Body.addView(createIosSliderToggleRow(
                activity,
                ICON_STEALTH_EYE,
                "#30D158",
                "Hide ESP & Mod Name (Rec/SS)",
                FeaturesticStreamproofEngine.isStreamproofEnabled()
                        ? "Active • Invisible in Rec & Screenshot" : "Off • Visible in Rec & Screenshot",
                FeaturesticStreamproofEngine.isStreamproofEnabled(),
                sfMedium,
                sfRegular,
                streamproofSubRef,
                isChecked -> {
                    FeaturesticStreamproofEngine.setStreamproofEnabled(isChecked, activity);
                    if (streamproofSubRef[0] != null) {
                        streamproofSubRef[0].setText(isChecked
                                ? "Active • Invisible in Rec & Screenshot"
                                : "Off • Visible in Rec & Screenshot");
                    }
                    Toast.makeText(activity,
                            isChecked ? "Streamproof ON: ESP, Mod Name & Menu hidden from Rec/Screenshot!"
                                    : "Streamproof OFF",
                            Toast.LENGTH_SHORT).show();
                },
                null));
        cat2Body.addView(createHairlineDivider(activity));

        final TextView[] watermarkSubRef = new TextView[1];
        final AppleToggleSwitchView[] watermarkSwitchRef = new AppleToggleSwitchView[1];
        int initWmStyle = FeaturesticCleanProofWatermark.getCurrentStyle();
        cat2Body.addView(createIosSliderToggleRowWithSwitchRef(
                activity,
                ICON_DIAGNOSTICS,
                "#64D2FF",
                "Clean Proof Watermark",
                "Style: " + FeaturesticCleanProofWatermark.getStyleShortLabel(initWmStyle) + " (Tap to change)",
                initWmStyle != FeaturesticCleanProofWatermark.STYLE_OFF,
                sfMedium,
                sfRegular,
                watermarkSubRef,
                watermarkSwitchRef,
                isChecked -> {
                    int nextStyle = isChecked
                            ? FeaturesticCleanProofWatermark.STYLE_OEM_FPS_COUNTER
                            : FeaturesticCleanProofWatermark.STYLE_OFF;
                    FeaturesticCleanProofWatermark.setStyle(nextStyle, activity);
                    if (watermarkSubRef[0] != null) {
                        watermarkSubRef[0].setText("Style: "
                                + FeaturesticCleanProofWatermark.getStyleShortLabel(nextStyle) + " (Tap to change)");
                    }
                },
                () -> {
                    int nextStyle = FeaturesticCleanProofWatermark.cycleNextActiveStyle(activity);
                    if (watermarkSubRef[0] != null) {
                        watermarkSubRef[0].setText("Style: "
                                + FeaturesticCleanProofWatermark.getStyleShortLabel(nextStyle) + " (Tap to change)");
                    }
                    if (watermarkSwitchRef[0] != null) {
                        watermarkSwitchRef[0].setCheckedAnimated(
                                nextStyle != FeaturesticCleanProofWatermark.STYLE_OFF);
                    }
                }));
        cat2Body.addView(createHairlineDivider(activity));

        cat2Body.addView(createIosActionRow(
                activity,
                ICON_STEALTH_EYE,
                "#0A84FF",
                "Hide HUD Completely",
                "Press " + FeaturesticTouchAndGyroOptimizer.getShortcutShortHint() + " to Show/Hide",
                sfMedium,
                sfRegular,
                v -> {
                    controlSheet.setVisibility(View.GONE);
                    rootContainer.setVisibility(View.GONE);
                    Toast.makeText(activity,
                            "HUD Completely Hidden • Press " + FeaturesticTouchAndGyroOptimizer.getShortcutShortHint() + " to open",
                            Toast.LENGTH_LONG).show();
                }));
        cat2Body.addView(createHairlineDivider(activity));

        // Suggestion #4: Custom Menu Trigger Shortcut Selector (Power+Vol+ / Vol+&Vol- / Shake Phone 2x)
        final TextView[] shortcutSubRef = new TextView[1];
        LinearLayout shortcutRow = (LinearLayout) createIosActionRow(
                activity,
                ICON_TOUCH_GYRO,
                "#5E5CE6",
                "Menu Trigger Shortcut",
                FeaturesticTouchAndGyroOptimizer.getShortcutModeLabel(FeaturesticTouchAndGyroOptimizer.getShortcutMode()),
                sfMedium,
                sfRegular,
                v -> {
                    int nextMode = FeaturesticTouchAndGyroOptimizer.cycleNextShortcutMode(
                            activity, rootContainer, controlSheet);
                    String label = FeaturesticTouchAndGyroOptimizer.getShortcutModeLabel(nextMode);
                    if (shortcutSubRef[0] != null) {
                        shortcutSubRef[0].setText(label);
                    }
                    Toast.makeText(activity, "Menu Shortcut -> " + label, Toast.LENGTH_SHORT).show();
                });
        if (shortcutRow.getChildCount() >= 2 && shortcutRow.getChildAt(1) instanceof LinearLayout) {
            LinearLayout tc = (LinearLayout) shortcutRow.getChildAt(1);
            if (tc.getChildCount() >= 2 && tc.getChildAt(1) instanceof TextView) {
                shortcutSubRef[0] = (TextView) tc.getChildAt(1);
            }
        }
        cat2Body.addView(shortcutRow);
        cat2Body.addView(createHairlineDivider(activity));

        // Suggestion #2: Real-Time Crash-Dump & Ban-Log Auto-Cleaner
        final TextView[] logCleanSubRef = new TextView[1];
        cat2Body.addView(createIosSliderToggleRow(
                activity,
                ICON_THERMAL_SHIELD,
                "#30D158",
                "Auto Crash & Log Cleaner",
                com.featurestic.box.utils.compat.FeaturesticLogCleaner.isAutoLogCleanerEnabled()
                        ? "Active • Auto-Wipes ANR, Bugly & .dmp Logs"
                        : "Off • Crash Logs Not Wiped",
                com.featurestic.box.utils.compat.FeaturesticLogCleaner.isAutoLogCleanerEnabled(),
                sfMedium,
                sfRegular,
                logCleanSubRef,
                isChecked -> {
                    com.featurestic.box.utils.compat.FeaturesticLogCleaner.setAutoLogCleanerEnabled(isChecked);
                    if (isChecked) {
                        String pkg = BActivityThread.getAppPackageName();
                        int uid = BActivityThread.getUserId();
                        com.featurestic.box.utils.compat.FeaturesticLogCleaner.startAutoCleanerForGame(activity, pkg, uid);
                    }
                    if (logCleanSubRef[0] != null) {
                        logCleanSubRef[0].setText(isChecked
                                ? "Active • Auto-Wipes ANR, Bugly & .dmp Logs"
                                : "Off • Crash Logs Not Wiped");
                    }
                    Toast.makeText(activity,
                            isChecked ? "Auto Crash & Log Cleaner ON (20s Sweep)" : "Auto Log Cleaner OFF",
                            Toast.LENGTH_SHORT).show();
                },
                null));
        cat2Body.addView(createHairlineDivider(activity));

        // #2 Anti-HttpCanary / Reqable / ProxyPin Packet-Sniffer Shield
        final TextView[] antiSniffSubRef = new TextView[1];
        cat2Body.addView(createIosSliderToggleRow(
                activity,
                ICON_KEEP_ALIVE_LOCK,
                "#FF375F",
                "Anti-Sniffer & Proxy Shield",
                com.featurestic.box.utils.compat.NetworkSecurityGuard.isAntiSnifferEnabled()
                        ? "Active • Blocks Reqable, HttpCanary & MITM"
                        : "Off • Network Inspection Allowed",
                com.featurestic.box.utils.compat.NetworkSecurityGuard.isAntiSnifferEnabled(),
                sfMedium,
                sfRegular,
                antiSniffSubRef,
                isChecked -> {
                    com.featurestic.box.utils.compat.NetworkSecurityGuard.setAntiSnifferEnabled(isChecked);
                    if (antiSniffSubRef[0] != null) {
                        antiSniffSubRef[0].setText(isChecked
                                ? "Active • Blocks Reqable, HttpCanary & MITM"
                                : "Off • Network Inspection Allowed");
                    }
                    Toast.makeText(activity,
                            isChecked ? "Anti-Sniffer & MITM Proxy Shield ON!" : "Anti-Sniffer Shield OFF",
                            Toast.LENGTH_SHORT).show();
                },
                null));

        scrollContent.addView(createAppleCategorySection(
                activity, 1, ICON_STEALTH_EYE, "#30D158",
                "Streamproof & Privacy",
                "Hide ESP in Rec/SS • Clean Watermark",
                sfSemibold, sfRegular, cat2Body, categoryBodies, categoryChevrons, false));

        // =========================================================================
        // CATEGORY 3: Touch, Zero-Delay Gyro & Tactical Audio
        // =========================================================================
        LinearLayout cat3Body = createCategoryBodyContainer(activity);
        categoryBodies[2] = cat3Body;

        final boolean hasHwGyro = FeaturesticTouchAndGyroOptimizer.hasHardwareGyroscope(activity);
        final TextView[] touchGyroSubRef = new TextView[1];
        cat3Body.addView(createIosSliderToggleRow(
                activity,
                ICON_TOUCH_GYRO,
                "#30D158",
                "240Hz Touch & Zero-Delay Gyro",
                FeaturesticTouchAndGyroOptimizer.isTouchAndGyroBoostEnabled()
                        ? (hasHwGyro ? "Active • 0ms FIFO Fast Hardware Gyro" : "Active • 240Hz Unbuffered Touch")
                        : "Off • Standard Input",
                FeaturesticTouchAndGyroOptimizer.isTouchAndGyroBoostEnabled(),
                sfMedium,
                sfRegular,
                touchGyroSubRef,
                isChecked -> {
                    FeaturesticTouchAndGyroOptimizer.setTouchAndGyroBoostEnabled(isChecked);
                    FeaturesticTouchAndGyroOptimizer.engageZeroDelayHardwareGyro(activity, isChecked);
                    if (touchGyroSubRef[0] != null) {
                        touchGyroSubRef[0].setText(isChecked
                                ? (hasHwGyro ? "Active • 0ms FIFO Fast Hardware Gyro" : "Active • 240Hz Unbuffered Touch")
                                : "Off • Standard Input");
                    }
                    Toast.makeText(activity,
                            isChecked ? (hasHwGyro ? "240Hz Touch + Zero-Delay Hardware Gyro ON!" : "240Hz Unbuffered Touch ON")
                                    : "Touch & Gyro Boost OFF",
                            Toast.LENGTH_SHORT).show();
                },
                null));
        cat3Body.addView(createHairlineDivider(activity));

        final TextView[] footstepSubRef = new TextView[1];
        cat3Body.addView(createIosSliderToggleRow(
                activity,
                ICON_AUDIO_FOOTSTEP,
                "#FF9F0A",
                "Footstep Audio Boost",
                FeaturesticEsportsCoreEngines.isFootstepAudioBoostEnabled()
                        ? "Active • 1.2–4kHz Clarity EQ" : "Off • Default Game Audio",
                FeaturesticEsportsCoreEngines.isFootstepAudioBoostEnabled(),
                sfMedium,
                sfRegular,
                footstepSubRef,
                isChecked -> {
                    FeaturesticEsportsCoreEngines.setFootstepAudioBoostEnabled(isChecked);
                    if (footstepSubRef[0] != null) {
                        footstepSubRef[0].setText(isChecked
                                ? "Active • 1.2–4kHz Clarity EQ" : "Off • Default Game Audio");
                    }
                },
                null));
        cat3Body.addView(createHairlineDivider(activity));

        final TextView[] thermalSubRef = new TextView[1];
        cat3Body.addView(createIosSliderToggleRow(
                activity,
                ICON_THERMAL_SHIELD,
                "#32ADE6",
                "Thermal Frame Guard",
                ThermalAndFrameGuard.isThermalGuardEnabled()
                        ? "Active • Blocks Thermal Drops" : "Off • OS Default",
                ThermalAndFrameGuard.isThermalGuardEnabled(),
                sfMedium,
                sfRegular,
                thermalSubRef,
                isChecked -> {
                    ThermalAndFrameGuard.setThermalGuardEnabled(isChecked);
                    if (isChecked) ThermalAndFrameGuard.applyToActivityWindow(activity);
                    if (thermalSubRef[0] != null) {
                        thermalSubRef[0].setText(isChecked
                                ? "Active • Blocks Thermal Drops" : "Off • OS Default");
                    }
                },
                null));
        cat3Body.addView(createHairlineDivider(activity));

        final TextView[] clawSubRef = new TextView[1];
        cat3Body.addView(createIosSliderToggleRow(
                activity,
                ICON_CLAW_EDGE,
                "#FF375F",
                "Claw Notification Guard",
                FeaturesticEsportsCoreEngines.isClawEdgeGuardEnabled()
                        ? "Active • Edge Pull-Down Locked" : "Off • Normal Status Bar",
                FeaturesticEsportsCoreEngines.isClawEdgeGuardEnabled(),
                sfMedium,
                sfRegular,
                clawSubRef,
                isChecked -> {
                    FeaturesticEsportsCoreEngines.setClawEdgeGuardEnabled(isChecked);
                    if (isChecked) FeaturesticEsportsCoreEngines.enforceImmersiveClawGuard(activity);
                    if (clawSubRef[0] != null) {
                        clawSubRef[0].setText(isChecked
                                ? "Active • Edge Pull-Down Locked" : "Off • Normal Status Bar");
                    }
                },
                null));
        cat3Body.addView(createHairlineDivider(activity));

        cat3Body.addView(createIosActionRow(
                activity,
                ICON_RAM_CHIP,
                "#BF5AF2",
                "Purge Memory & Shaders",
                "Free RAM & Clean UE4 Caches",
                sfMedium,
                sfRegular,
                v -> {
                    try {
                        System.gc();
                        Runtime.getRuntime().gc();
                        String pkg = BActivityThread.getAppPackageName();
                        int uid = BActivityThread.getUserId();
                        int cleaned = CrashShieldAndResourceHealer.healGameResources(activity, pkg, uid);
                        StorageDietOptimizer.trimJunkAsync(pkg, uid);
                        long freeMb = (Runtime.getRuntime().maxMemory()
                                - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())) / (1024 * 1024);
                        Toast.makeText(activity,
                                "Memory Purged (" + freeMb + " MB Free, " + cleaned + " caches cleaned)",
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }));

        scrollContent.addView(createAppleCategorySection(
                activity, 2, ICON_TOUCH_GYRO, "#FF9F0A",
                "Touch, Zero-Delay Gyro & Audio",
                "240Hz Touch • Fast Gyro • Footstep EQ",
                sfSemibold, sfRegular, cat3Body, categoryBodies, categoryChevrons, false));

        // =========================================================================
        // CATEGORY 4: Network, Gaming DNS & Region Spoofer
        // =========================================================================
        LinearLayout cat4Body = createCategoryBodyContainer(activity);
        categoryBodies[3] = cat4Body;

        final TextView[] netSubRef = new TextView[1];
        cat4Body.addView(createIosSliderToggleRow(
                activity,
                ICON_NET_TURBO,
                "#64D2FF",
                "Net Turbo & Dual-SIM",
                "Active • Wi-Fi Low-Latency Lock",
                true,
                sfMedium,
                sfRegular,
                netSubRef,
                isChecked -> {
                    if (isChecked) {
                        NetworkLowLatencyOptimizer.boostAndWarmNow(activity);
                        if (netSubRef[0] != null) netSubRef[0].setText("Active • Wi-Fi Low-Latency Lock");
                    } else if (netSubRef[0] != null) {
                        netSubRef[0].setText("Standard Network Mode");
                    }
                },
                null));
        cat4Body.addView(createHairlineDivider(activity));

        final TextView[] dnsSubRef = new TextView[1];
        LinearLayout dnsRow = (LinearLayout) createIosActionRow(
                activity,
                ICON_NET_TURBO,
                "#0A84FF",
                "Gaming DNS Switcher",
                FeaturesticCompanionToolManager.getDnsModeLabel(FeaturesticCompanionToolManager.getDnsMode()),
                sfMedium,
                sfRegular,
                v -> {
                    int nextDns = FeaturesticCompanionToolManager.cycleNextDnsMode(activity);
                    if (dnsSubRef[0] != null) {
                        dnsSubRef[0].setText(FeaturesticCompanionToolManager.getDnsModeLabel(nextDns));
                    }
                    Toast.makeText(activity,
                            "DNS Set: " + FeaturesticCompanionToolManager.getDnsModeLabel(nextDns),
                            Toast.LENGTH_SHORT).show();
                });
        if (dnsRow.getChildCount() >= 2 && dnsRow.getChildAt(1) instanceof LinearLayout) {
            LinearLayout tc = (LinearLayout) dnsRow.getChildAt(1);
            if (tc.getChildCount() >= 2 && tc.getChildAt(1) instanceof TextView) {
                dnsSubRef[0] = (TextView) tc.getChildAt(1);
            }
        }
        cat4Body.addView(dnsRow);
        cat4Body.addView(createHairlineDivider(activity));

        final TextView[] regionSubRef = new TextView[1];
        LinearLayout regionRow = (LinearLayout) createIosActionRow(
                activity,
                ICON_NET_TURBO,
                "#5E5CE6",
                "Region & SIM Spoofer",
                FeaturesticRegionSpoofer.getRegionDisplayLabel(FeaturesticRegionSpoofer.getCurrentRegion()),
                sfMedium,
                sfRegular,
                v -> {
                    int nextReg = FeaturesticRegionSpoofer.cycleNextRegion();
                    if (regionSubRef[0] != null) {
                        regionSubRef[0].setText(FeaturesticRegionSpoofer.getRegionDisplayLabel(nextReg));
                    }
                    Toast.makeText(activity,
                            "Region Spoof: " + FeaturesticRegionSpoofer.getRegionDisplayLabel(nextReg),
                            Toast.LENGTH_SHORT).show();
                });
        if (regionRow.getChildCount() >= 2 && regionRow.getChildAt(1) instanceof LinearLayout) {
            LinearLayout tc = (LinearLayout) regionRow.getChildAt(1);
            if (tc.getChildCount() >= 2 && tc.getChildAt(1) instanceof TextView) {
                regionSubRef[0] = (TextView) tc.getChildAt(1);
            }
        }
        cat4Body.addView(regionRow);
        cat4Body.addView(createHairlineDivider(activity));

        final TextView[] keepAliveSubRef = new TextView[1];
        cat4Body.addView(createIosSliderToggleRow(
                activity,
                ICON_KEEP_ALIVE_LOCK,
                "#BF5AF2",
                "Anti-Disconnect Shield",
                FeaturesticEsportsCoreEngines.isAntiDisconnectShieldEnabled()
                        ? "Active • Prevents Background Kick" : "Off • Normal Background",
                FeaturesticEsportsCoreEngines.isAntiDisconnectShieldEnabled(),
                sfMedium,
                sfRegular,
                keepAliveSubRef,
                isChecked -> {
                    FeaturesticEsportsCoreEngines.setAntiDisconnectShieldEnabled(isChecked);
                    if (keepAliveSubRef[0] != null) {
                        keepAliveSubRef[0].setText(isChecked
                                ? "Active • Prevents Background Kick" : "Off • Normal Background");
                    }
                },
                null));

        scrollContent.addView(createAppleCategorySection(
                activity, 3, ICON_NET_TURBO, "#64D2FF",
                "Network, Gaming DNS & Region",
                "1.1.1.1 DNS • PUBG KR Region • Net Turbo",
                sfSemibold, sfRegular, cat4Body, categoryBodies, categoryChevrons, false));

        // =========================================================================
        // CATEGORY 5: Accounts, Multi-Slot & 1-Click Companion Tool
        // =========================================================================
        LinearLayout cat5Body = createCategoryBodyContainer(activity);
        categoryBodies[4] = cat5Body;

        // 1-Click Companion Tool / GG Setup & Virtual Root Launch
        final TextView[] toolSubRef = new TextView[1];
        LinearLayout toolRow = (LinearLayout) createIosActionRow(
                activity,
                ICON_RAM_CHIP,
                "#30D158",
                "1-Click Companion Tool / GG",
                FeaturesticCompanionToolManager.getStatusSubtitle(slotId),
                sfMedium,
                sfRegular,
                v -> FeaturesticCompanionToolManager.handleOneClickSetupOrLaunch(
                        activity, slotId, toolSubRef[0]));
        if (toolRow.getChildCount() >= 2 && toolRow.getChildAt(1) instanceof LinearLayout) {
            LinearLayout tc = (LinearLayout) toolRow.getChildAt(1);
            if (tc.getChildCount() >= 2 && tc.getChildAt(1) instanceof TextView) {
                toolSubRef[0] = (TextView) tc.getChildAt(1);
            }
        }
        cat5Body.addView(toolRow);
        cat5Body.addView(createHairlineDivider(activity));

        // Suggestion #3: GG Anti-Kill Memory Lock (Low-RAM Phone Foreground Priority Shield)
        final TextView[] ggLockSubRef = new TextView[1];
        cat5Body.addView(createIosSliderToggleRow(
                activity,
                ICON_KEEP_ALIVE_LOCK,
                "#BF5AF2",
                "GG Anti-Kill Memory Lock",
                com.featurestic.box.utils.compat.FeaturesticMemoryLockGuard.isMemoryLockEnabled()
                        ? "Active • Foreground Priority & LMK Shield"
                        : "Off • Standard OS Background Priority",
                com.featurestic.box.utils.compat.FeaturesticMemoryLockGuard.isMemoryLockEnabled(),
                sfMedium,
                sfRegular,
                ggLockSubRef,
                isChecked -> {
                    com.featurestic.box.utils.compat.FeaturesticMemoryLockGuard.setMemoryLockEnabled(isChecked);
                    if (ggLockSubRef[0] != null) {
                        ggLockSubRef[0].setText(isChecked
                                ? "Active • Foreground Priority & LMK Shield"
                                : "Off • Standard OS Background Priority");
                    }
                    Toast.makeText(activity,
                            isChecked ? "GG Anti-Kill Memory Lock ON!" : "GG Memory Lock OFF",
                            Toast.LENGTH_SHORT).show();
                },
                null));
        cat5Body.addView(createHairlineDivider(activity));

        // 1-Tap Multi-Slot Account Switcher (Slot 0 -> Slot 1 -> Slot 2)
        cat5Body.addView(createIosActionRow(
                activity,
                ICON_SWITCH_ID,
                "#5E5CE6",
                "Switch Parallel Account Slot",
                "Active: Slot " + slotId + " • Tap for Next Slot (Zero-Copy OBB)",
                sfMedium,
                sfRegular,
                v -> {
                    String pkg = BActivityThread.getAppPackageName();
                    int nextSlot = (slotId + 1) % 3;
                    Toast.makeText(activity,
                            "Switching to Account Slot " + nextSlot + " (Linking OBB)...",
                            Toast.LENGTH_SHORT).show();
                    FeaturesticAccountSwitcher.switchAccountSlot(pkg, nextSlot, true);
                }));
        cat5Body.addView(createHairlineDivider(activity));

        cat5Body.addView(createIosActionRow(
                activity,
                ICON_SWITCH_ID,
                "#FF9F0A",
                "Switch Social ID",
                "Clear FB, X & Google Web Session",
                sfMedium,
                sfRegular,
                v -> {
                    WebViewCompat.clearAllWebLoginSessions(activity, null);
                    Toast.makeText(activity,
                            "Web Login Session Cleared. Tap Login to enter a New ID.",
                            Toast.LENGTH_LONG).show();
                }));
        cat5Body.addView(createHairlineDivider(activity));

        cat5Body.addView(createIosActionRow(
                activity,
                ICON_GUEST_RESET,
                "#0A84FF",
                "Reset Guest Account",
                "New Guest ID • Keep Maps & OBB",
                sfMedium,
                sfRegular,
                v -> {
                    String pkg = BActivityThread.getAppPackageName();
                    int uid = BActivityThread.getUserId();
                    Toast.makeText(activity, "Resetting Guest ID & Rotating Hardware ID...", Toast.LENGTH_SHORT).show();
                    GuestAccountManager.resetGuestAccountKeepResources(pkg, uid, true);
                }));
        cat5Body.addView(createHairlineDivider(activity));

        cat5Body.addView(createIosActionRow(
                activity,
                ICON_DEVICE_ID,
                "#FF375F",
                "Rotate Hardware ID",
                "New Device Identity for Slot " + slotId,
                sfMedium,
                sfRegular,
                v -> {
                    String newId = Md5Utils.resetVirtualDeviceIdentity(slotId);
                    Toast.makeText(activity,
                            "Slot " + slotId + " Hardware ID Rotated: " + newId.substring(0, 8),
                            Toast.LENGTH_SHORT).show();
                }));
        cat5Body.addView(createHairlineDivider(activity));

        // #5 One-Tap Host Game Update & OBB Sync (Zero Data Loss)
        cat5Body.addView(createIosActionRow(
                activity,
                ICON_RAM_CHIP,
                "#32ADE6",
                "Sync Game Update & OBB",
                "Update APK/OBB from Phone (Keeps Guest ID & Data)",
                sfMedium,
                sfRegular,
                v -> {
                    final String pkg = BActivityThread.getAppPackageName();
                    final int uid = BActivityThread.getUserId();
                    Toast.makeText(activity, "Syncing latest Host APK & OBB for " + pkg + "...", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        boolean synced = com.featurestic.box.utils.compat.FeaturesticUpdateSyncEngine
                                .autoSyncHostGameUpdateIfNeeded(activity, pkg, uid, true);
                        activity.runOnUiThread(() -> Toast.makeText(
                                activity,
                                synced ? "Game Update & OBB Synced (All Data Intact!)" : "OBB & Paks Verified Up-to-Date!",
                                Toast.LENGTH_LONG).show());
                    }, "FeaturesticUpdateSync").start();
                }));
        cat5Body.addView(createHairlineDivider(activity));

        cat5Body.addView(createIosActionRow(
                activity,
                ICON_DIAGNOSTICS,
                "#8E8E93",
                "Copy System Telemetry",
                "Export Live Report & .so Crash Offset",
                sfMedium,
                sfRegular,
                v -> {
                    if (FeaturesticDiagnostics.copyDiagnosticReportToClipboard(activity)) {
                        Toast.makeText(activity, "System Telemetry Copied to Clipboard", Toast.LENGTH_SHORT).show();
                    }
                }));

        scrollContent.addView(createAppleCategorySection(
                activity, 4, ICON_SWITCH_ID, "#0A84FF",
                "Accounts, Slots & Companion Tool",
                "1-Click GG/Tool • Multi-Slot • Social ID",
                sfSemibold, sfRegular, cat5Body, categoryBodies, categoryChevrons, false));

        scrollView.addView(scrollContent);

        controlSheet.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxSheetScrollH));

        rootContainer.addView(islandCapsule);
        rootContainer.addView(controlSheet, sheetLp);

        enableDragAndTapToggle(islandCapsule, rootContainer, controlSheet, telemetryText, chevronIcon, activity);
        FeaturesticTouchAndGyroOptimizer.installWindowTouchAndGestureHook(activity, rootContainer, controlSheet);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            final Choreographer choreographer = Choreographer.getInstance();
            final Choreographer.FrameCallback callback = new Choreographer.FrameCallback() {
                private long lastWindowNs = 0L;
                private int frameCount = 0;

                @Override
                public void doFrame(long frameTimeNanos) {
                    if (activity.isFinishing() || !islandCapsule.isAttachedToWindow()) {
                        return;
                    }
                    if (lastWindowNs == 0L) {
                        lastWindowNs = frameTimeNanos;
                    }
                    frameCount++;
                    long elapsedNs = frameTimeNanos - lastWindowNs;
                    if (elapsedNs >= 500_000_000L) {
                        int fps = Math.round((frameCount * 1_000_000_000f) / elapsedNs);
                        FeaturesticAutopilotEngine.onLiveFpsSample(activity, fps);
                        int pingMs = LivePingMonitor.getCurrentPingMs();
                        float liveTempC = FeaturesticCleanProofWatermark.getBatteryTemperatureCelsius(activity);
                        telemetryText.setText(fps + " FPS • " + pingMs + "ms • " + Math.round(liveTempC) + "°C");
                        FeaturesticCleanProofWatermark.onLiveTelemetryTick(activity, fps, pingMs);
                        ThermalAndFrameGuard.onThermalTelemetryTick(activity, liveTempC, fps);

                        int dotColor = pingMs <= 65 ? Color.parseColor("#30D158")
                                : (pingMs <= 130 ? Color.parseColor("#FFD60A") : Color.parseColor("#FF453A"));
                        if (pingStatusDot.getBackground() instanceof GradientDrawable) {
                            ((GradientDrawable) pingStatusDot.getBackground()).setColor(dotColor);
                        }
                        frameCount = 0;
                        lastWindowNs = frameTimeNanos;
                    }
                    choreographer.postFrameCallback(this);
                }
            };
            islandCapsule.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    choreographer.postFrameCallback(callback);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    choreographer.removeFrameCallback(callback);
                }
            });
        }
        return rootContainer;
    }

    private static LinearLayout createCategoryBodyContainer(Context context) {
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable divTop = new GradientDrawable();
        divTop.setColor(Color.parseColor("#242426"));
        divTop.setCornerRadius(dp(context, 14));
        body.setBackground(divTop);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 6);
        lp.rightMargin = dp(context, 6);
        lp.bottomMargin = dp(context, 6);
        body.setLayoutParams(lp);
        return body;
    }

    private static View createAppleCategorySection(
            Context context,
            final int categoryIndex,
            int iconType,
            String accentHex,
            String title,
            String subtitle,
            Typeface titleFont,
            Typeface subFont,
            final LinearLayout bodyContainer,
            final LinearLayout[] allBodies,
            final SfSymbolIconView[] allChevrons,
            boolean initiallyExpanded) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#2C2C2E"));
        cardBg.setCornerRadius(dp(context, 16));
        cardBg.setStroke(1, Color.parseColor("#1FFFFFFF"));
        wrapper.setBackground(cardBg);

        LinearLayout.LayoutParams wrapperLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (categoryIndex > 0) {
            wrapperLp.topMargin = dp(context, 8);
        }
        wrapper.setLayoutParams(wrapperLp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(context, 12);
        int padV = dp(context, 10);
        header.setPadding(padH, padV, padH, padV);

        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor(accentHex));
        badgeBg.setCornerRadius(dp(context, 8));
        badge.setBackground(badgeBg);
        int bSize = dp(context, 28);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(bSize, bSize);
        badgeLp.rightMargin = dp(context, 10);

        SfSymbolIconView icon = new SfSymbolIconView(context, iconType, Color.WHITE, dp(context, 16));
        badge.addView(icon, new FrameLayout.LayoutParams(
                dp(context, 16), dp(context, 16), Gravity.CENTER));
        header.addView(badge, badgeLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleTv.setTypeface(titleFont);
        titleTv.setTextColor(Color.WHITE);

        TextView subTv = new TextView(context);
        subTv.setText(subtitle);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        subTv.setTypeface(subFont);
        subTv.setTextColor(Color.parseColor("#98989D"));

        textCol.addView(titleTv);
        textCol.addView(subTv);
        header.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SfSymbolIconView chevron = new SfSymbolIconView(
                context, ICON_CHEVRON, Color.parseColor("#8E8E93"), dp(context, 12));
        chevron.setRotation(initiallyExpanded ? 180f : 0f);
        allChevrons[categoryIndex] = chevron;
        header.addView(chevron);

        bodyContainer.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);

        header.setOnClickListener(v -> {
            boolean willOpen = bodyContainer.getVisibility() != View.VISIBLE;
            for (int i = 0; i < allBodies.length; i++) {
                if (allBodies[i] != null) {
                    allBodies[i].setVisibility(i == categoryIndex && willOpen ? View.VISIBLE : View.GONE);
                }
                if (allChevrons[i] != null) {
                    allChevrons[i].setRotation(i == categoryIndex && willOpen ? 180f : 0f);
                }
            }
        });

        wrapper.addView(header);
        wrapper.addView(bodyContainer);
        return wrapper;
    }

    private interface OnSliderValueChangeListener {
        void onValueChanged(int percent);
    }

    private static LinearLayout createAppleIpadZoomSliderRow(
            Context context,
            Typeface mediumFont,
            Typeface regularFont,
            final OnSliderValueChangeListener listener) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 14);
        int padB = dp(context, 10);
        box.setPadding(padH, dp(context, 2), padH, padB);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);

        TextView leftLbl = new TextView(context);
        leftLbl.setText("Mobile (0%)");
        leftLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        leftLbl.setTypeface(regularFont);
        leftLbl.setTextColor(Color.parseColor("#8E8E93"));
        labels.addView(leftLbl, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView rightLbl = new TextView(context);
        int initPct = FeaturesticIpadViewEngine.getZoomOutPercent();
        rightLbl.setText("Max iPad Zoom-Out (" + initPct + "%)");
        rightLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        rightLbl.setTypeface(mediumFont);
        rightLbl.setTextColor(Color.parseColor("#0A84FF"));
        labels.addView(rightLbl);

        box.addView(labels);

        AppleContinuousSliderView slider = new AppleContinuousSliderView(
                context,
                initPct,
                pct -> {
                    rightLbl.setText("Max iPad Zoom-Out (" + pct + "%)");
                    if (listener != null) {
                        listener.onValueChanged(pct);
                    }
                });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 28));
        sLp.topMargin = dp(context, 2);
        box.addView(slider, sLp);
        return box;
    }

    /**
     * Authentic Apple iOS 18 UISlider Continuous Adjustment Bar (0% to 100%).
     */
    private static final class AppleContinuousSliderView extends View {
        private int percent;
        private final Paint bgTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final OnSliderValueChangeListener listener;

        AppleContinuousSliderView(Context context, int initialPercent, OnSliderValueChangeListener listener) {
            super(context);
            this.percent = Math.max(0, Math.min(100, initialPercent));
            this.listener = listener;

            bgTrackPaint.setColor(Color.parseColor("#3A3A3C"));
            bgTrackPaint.setStyle(Paint.Style.FILL);

            activeTrackPaint.setColor(Color.parseColor("#0A84FF"));
            activeTrackPaint.setStyle(Paint.Style.FILL);

            thumbPaint.setColor(Color.WHITE);
            thumbPaint.setStyle(Paint.Style.FILL);

            thumbBorderPaint.setColor(Color.parseColor("#33000000"));
            thumbBorderPaint.setStyle(Paint.Style.STROKE);
            thumbBorderPaint.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float thumbRadius = h * 0.38f;
            float trackHeight = Math.max(4f, h * 0.18f);
            float trackTop = (h - trackHeight) / 2f;
            float trackBottom = trackTop + trackHeight;
            float left = thumbRadius;
            float right = w - thumbRadius;
            float usableW = Math.max(1f, right - left);
            float thumbX = left + (usableW * (percent / 100.0f));

            // Inactive full track
            rect.set(left, trackTop, right, trackBottom);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, bgTrackPaint);

            // Active blue fill track
            rect.set(left, trackTop, thumbX, trackBottom);
            canvas.drawRoundRect(rect, trackHeight / 2f, trackHeight / 2f, activeTrackPaint);

            // Crisp iOS circular white thumb
            canvas.drawCircle(thumbX, h / 2f, thumbRadius, thumbPaint);
            canvas.drawCircle(thumbX, h / 2f, thumbRadius, thumbBorderPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                float w = getWidth();
                float h = getHeight();
                float pad = h * 0.38f;
                float usable = Math.max(1f, w - pad * 2f);
                float ratio = (event.getX() - pad) / usable;
                int newPct = Math.round(Math.max(0f, Math.min(1f, ratio)) * 100f);
                if (newPct != percent || action == MotionEvent.ACTION_UP) {
                    percent = newPct;
                    invalidate();
                    if (listener != null) {
                        listener.onValueChanged(percent);
                    }
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private interface OnToggleChangeListener {
        void onToggleChanged(boolean isChecked);
    }

    private static View createIosSliderToggleRow(
            Context context,
            int iconType,
            String accentHex,
            String title,
            String subtitle,
            boolean initialChecked,
            Typeface titleFont,
            Typeface subFont,
            TextView[] subOut,
            OnToggleChangeListener toggleListener,
            Runnable onTextTapAction) {
        return createIosSliderToggleRowWithSwitchRef(
                context, iconType, accentHex, title, subtitle, initialChecked,
                titleFont, subFont, subOut, null, toggleListener, onTextTapAction);
    }

    private static View createIosSliderToggleRowWithSwitchRef(
            Context context,
            int iconType,
            String accentHex,
            String title,
            String subtitle,
            boolean initialChecked,
            Typeface titleFont,
            Typeface subFont,
            TextView[] subOut,
            AppleToggleSwitchView[] switchOut,
            final OnToggleChangeListener toggleListener,
            final Runnable onTextTapAction) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(context, 12);
        int padV = dp(context, 9);
        row.setPadding(padH, padV, padH, padV);

        FrameLayout iconBadge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor(accentHex));
        badgeBg.setCornerRadius(dp(context, 7));
        iconBadge.setBackground(badgeBg);
        int boxSize = dp(context, 26);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        badgeLp.rightMargin = dp(context, 10);

        SfSymbolIconView icon = new SfSymbolIconView(context, iconType, Color.WHITE, dp(context, 15));
        iconBadge.addView(icon, new FrameLayout.LayoutParams(
                dp(context, 15), dp(context, 15), Gravity.CENTER));
        row.addView(iconBadge, badgeLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleTv.setTypeface(titleFont);
        titleTv.setTextColor(Color.parseColor("#F5F5F7"));

        TextView subTv = new TextView(context);
        subTv.setText(subtitle);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        subTv.setTypeface(subFont);
        subTv.setTextColor(Color.parseColor("#8E8E93"));

        if (subOut != null && subOut.length > 0) {
            subOut[0] = subTv;
        }

        textCol.addView(titleTv);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final AppleToggleSwitchView toggleSwitch = new AppleToggleSwitchView(context, initialChecked);
        if (switchOut != null && switchOut.length > 0) {
            switchOut[0] = toggleSwitch;
        }
        toggleSwitch.setOnClickListener(v -> {
            boolean next = !toggleSwitch.isChecked();
            toggleSwitch.setCheckedAnimated(next);
            if (toggleListener != null) {
                toggleListener.onToggleChanged(next);
            }
        });
        row.addView(toggleSwitch);

        if (onTextTapAction != null) {
            textCol.setOnClickListener(v -> onTextTapAction.run());
            iconBadge.setOnClickListener(v -> onTextTapAction.run());
        } else {
            row.setOnClickListener(v -> {
                boolean next = !toggleSwitch.isChecked();
                toggleSwitch.setCheckedAnimated(next);
                if (toggleListener != null) {
                    toggleListener.onToggleChanged(next);
                }
            });
        }
        return row;
    }

    private static View createIosActionRow(
            Context context,
            int iconType,
            String accentHex,
            String title,
            String subtitle,
            Typeface titleFont,
            Typeface subFont,
            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(context, 12);
        int padV = dp(context, 9);
        row.setPadding(padH, padV, padH, padV);
        row.setOnClickListener(listener);

        FrameLayout iconBadge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor(accentHex));
        badgeBg.setCornerRadius(dp(context, 7));
        iconBadge.setBackground(badgeBg);
        int boxSize = dp(context, 26);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        badgeLp.rightMargin = dp(context, 10);

        SfSymbolIconView icon = new SfSymbolIconView(context, iconType, Color.WHITE, dp(context, 15));
        iconBadge.addView(icon, new FrameLayout.LayoutParams(
                dp(context, 15), dp(context, 15), Gravity.CENTER));
        row.addView(iconBadge, badgeLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleTv.setTypeface(titleFont);
        titleTv.setTextColor(Color.parseColor("#F5F5F7"));

        TextView subTv = new TextView(context);
        subTv.setText(subtitle);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        subTv.setTypeface(subFont);
        subTv.setTextColor(Color.parseColor("#8E8E93"));

        textCol.addView(titleTv);
        textCol.addView(subTv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SfSymbolIconView rightChevron = new SfSymbolIconView(
                context, ICON_CHEVRON, Color.parseColor("#636366"), dp(context, 10));
        rightChevron.setRotation(-90f);
        row.addView(rightChevron);
        return row;
    }

    private static View createHairlineDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Color.parseColor("#38383A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(context, 48);
        divider.setLayoutParams(lp);
        return divider;
    }

    /**
     * Authentic Apple iOS 18 UISwitch Slider Toggle Component.
     * Features smooth thumb translation animation and iOS System Green (#30D158) active track.
     */
    private static final class AppleToggleSwitchView extends View {
        private boolean checked;
        private float progress;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private ValueAnimator animator;

        AppleToggleSwitchView(Context context, boolean initialChecked) {
            super(context);
            this.checked = initialChecked;
            this.progress = initialChecked ? 1.0f : 0.0f;
            thumbPaint.setColor(Color.WHITE);
            thumbPaint.setStyle(Paint.Style.FILL);

            int widthPx = dp(context, 44);
            int heightPx = dp(context, 25);
            setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));
        }

        boolean isChecked() {
            return checked;
        }

        void setCheckedAnimated(boolean targetChecked) {
            this.checked = targetChecked;
            float target = targetChecked ? 1.0f : 0.0f;
            if (animator != null && animator.isRunning()) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180L);
            animator.addUpdateListener(animation -> {
                progress = (Float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            int offColor = Color.parseColor("#39393D");
            int onColor = Color.parseColor("#30D158");
            trackPaint.setColor(blendColor(offColor, onColor, progress));

            float radius = h / 2f;
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

            float pad = h * 0.09f;
            float thumbRadius = (h - pad * 2f) / 2f;
            float startCx = pad + thumbRadius;
            float endCx = w - pad - thumbRadius;
            float cx = startCx + (endCx - startCx) * progress;
            float cy = h / 2f;

            canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);
        }

        private static int blendColor(int from, int to, float ratio) {
            float inv = 1f - ratio;
            int r = (int) (Color.red(from) * inv + Color.red(to) * ratio);
            int g = (int) (Color.green(from) * inv + Color.green(to) * ratio);
            int b = (int) (Color.blue(from) * inv + Color.blue(to) * ratio);
            return Color.rgb(r, g, b);
        }
    }

    private static void enableDragAndTapToggle(
            final View handleView,
            final View containerView,
            final View drawerView,
            final View telemetryText,
            final View chevronView,
            final Context context) {
        final int touchSlop = dp(context, 6);
        handleView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startLeft, startTop;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ViewGroup.LayoutParams rawLp = containerView.getLayoutParams();
                final boolean isWindowLp = rawLp instanceof WindowManager.LayoutParams;
                if (!isWindowLp && !(rawLp instanceof FrameLayout.LayoutParams)) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        if (isWindowLp) {
                            WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) rawLp;
                            startLeft = wlp.x;
                            startTop = wlp.y;
                        } else {
                            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) rawLp;
                            startLeft = flp.leftMargin;
                            startTop = flp.topMargin;
                        }
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                            moved = true;
                        }
                        if (moved) {
                            if (isWindowLp) {
                                WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) rawLp;
                                wlp.x = Math.max(0, startLeft + dx);
                                wlp.y = Math.max(0, startTop + dy);
                                try {
                                    WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                                    if (wm != null && containerView.isAttachedToWindow()) {
                                        wm.updateViewLayout(containerView, wlp);
                                    }
                                } catch (Throwable ignored) {
                                }
                            } else {
                                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) rawLp;
                                flp.leftMargin = Math.max(0, startLeft + dx);
                                flp.topMargin = Math.max(0, startTop + dy);
                                containerView.setLayoutParams(flp);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            handleView.setAlpha(1.0f);
                            telemetryText.setVisibility(View.VISIBLE);
                            chevronView.setVisibility(View.VISIBLE);
                            boolean willOpen = drawerView.getVisibility() != View.VISIBLE;
                            drawerView.setVisibility(willOpen ? View.VISIBLE : View.GONE);
                            chevronView.setRotation(willOpen ? 180f : 0f);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Custom Apple SF-Symbol Geometric Vector Icon Renderer.
     * Renders crisp, resolution-independent vector strokes with zero emojis.
     */
    private static final class SfSymbolIconView extends View {
        private final int iconType;
        private final Paint strokePaint;
        private final Paint fillPaint;
        private final RectF ovalRect = new RectF();
        private final Path path = new Path();

        SfSymbolIconView(Context context, int iconType, int color, int sizePx) {
            super(context);
            this.iconType = iconType;
            float strokeW = Math.max(2f, sizePx * 0.115f);

            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(color);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeW);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(color);
            fillPaint.setStyle(Paint.Style.FILL);

            setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            path.reset();

            switch (iconType) {
                case ICON_WAVEFORM: {
                    float[] xs = {0.18f, 0.39f, 0.61f, 0.82f};
                    float[] tops = {0.35f, 0.16f, 0.26f, 0.38f};
                    float[] bots = {0.65f, 0.84f, 0.74f, 0.62f};
                    for (int i = 0; i < 4; i++) {
                        c.drawLine(w * xs[i], h * tops[i], w * xs[i], h * bots[i], strokePaint);
                    }
                    break;
                }
                case ICON_GAUGE_FPS: {
                    float pad = w * 0.14f;
                    ovalRect.set(pad, pad, w - pad, h - pad);
                    c.drawArc(ovalRect, 150f, 240f, false, strokePaint);
                    c.drawLine(w * 0.5f, h * 0.55f, w * 0.74f, h * 0.30f, strokePaint);
                    c.drawCircle(w * 0.5f, h * 0.55f, w * 0.08f, fillPaint);
                    break;
                }
                case ICON_IPAD_VIEW: {
                    ovalRect.set(w * 0.14f, h * 0.22f, w * 0.86f, h * 0.78f);
                    c.drawRoundRect(ovalRect, w * 0.10f, h * 0.10f, strokePaint);
                    c.drawLine(w * 0.34f, h * 0.38f, w * 0.26f, h * 0.38f, strokePaint);
                    c.drawLine(w * 0.26f, h * 0.38f, w * 0.26f, h * 0.48f, strokePaint);
                    c.drawLine(w * 0.66f, h * 0.62f, w * 0.74f, h * 0.62f, strokePaint);
                    c.drawLine(w * 0.74f, h * 0.62f, w * 0.74f, h * 0.52f, strokePaint);
                    break;
                }
                case ICON_THERMAL_SHIELD: {
                    path.moveTo(w * 0.5f, h * 0.12f);
                    path.lineTo(w * 0.82f, h * 0.25f);
                    path.lineTo(w * 0.82f, h * 0.52f);
                    path.quadTo(w * 0.82f, h * 0.78f, w * 0.5f, h * 0.88f);
                    path.quadTo(w * 0.18f, h * 0.78f, w * 0.18f, h * 0.52f);
                    path.lineTo(w * 0.18f, h * 0.25f);
                    path.close();
                    c.drawPath(path, strokePaint);
                    c.drawLine(w * 0.5f, h * 0.32f, w * 0.5f, h * 0.68f, strokePaint);
                    c.drawLine(w * 0.35f, h * 0.50f, w * 0.65f, h * 0.50f, strokePaint);
                    break;
                }
                case ICON_NET_TURBO: {
                    c.drawCircle(w * 0.5f, h * 0.56f, w * 0.08f, fillPaint);
                    c.drawLine(w * 0.5f, h * 0.62f, w * 0.5f, h * 0.86f, strokePaint);
                    ovalRect.set(w * 0.26f, h * 0.28f, w * 0.74f, h * 0.76f);
                    c.drawArc(ovalRect, 210f, 120f, false, strokePaint);
                    ovalRect.set(w * 0.12f, h * 0.14f, w * 0.88f, h * 0.88f);
                    c.drawArc(ovalRect, 215f, 110f, false, strokePaint);
                    break;
                }
                case ICON_SWITCH_ID: {
                    c.drawCircle(w * 0.5f, h * 0.34f, w * 0.17f, strokePaint);
                    ovalRect.set(w * 0.22f, h * 0.56f, w * 0.78f, h * 0.96f);
                    c.drawArc(ovalRect, 195f, 150f, false, strokePaint);
                    break;
                }
                case ICON_RAM_CHIP: {
                    ovalRect.set(w * 0.26f, h * 0.26f, w * 0.74f, h * 0.74f);
                    c.drawRoundRect(ovalRect, w * 0.08f, h * 0.08f, strokePaint);
                    c.drawLine(w * 0.4f, h * 0.12f, w * 0.4f, h * 0.26f, strokePaint);
                    c.drawLine(w * 0.6f, h * 0.12f, w * 0.6f, h * 0.26f, strokePaint);
                    c.drawLine(w * 0.4f, h * 0.74f, w * 0.4f, h * 0.88f, strokePaint);
                    c.drawLine(w * 0.6f, h * 0.74f, w * 0.6f, h * 0.88f, strokePaint);
                    c.drawLine(w * 0.12f, h * 0.4f, w * 0.26f, h * 0.4f, strokePaint);
                    c.drawLine(w * 0.12f, h * 0.6f, w * 0.26f, h * 0.6f, strokePaint);
                    c.drawLine(w * 0.74f, h * 0.4f, w * 0.88f, h * 0.4f, strokePaint);
                    c.drawLine(w * 0.74f, h * 0.6f, w * 0.88f, h * 0.6f, strokePaint);
                    break;
                }
                case ICON_STEALTH_EYE: {
                    path.moveTo(w * 0.14f, h * 0.5f);
                    path.quadTo(w * 0.5f, h * 0.20f, w * 0.86f, h * 0.5f);
                    path.quadTo(w * 0.5f, h * 0.80f, w * 0.14f, h * 0.5f);
                    c.drawPath(path, strokePaint);
                    c.drawCircle(w * 0.5f, h * 0.5f, w * 0.10f, fillPaint);
                    c.drawLine(w * 0.20f, h * 0.80f, w * 0.80f, h * 0.20f, strokePaint);
                    break;
                }
                case ICON_GUEST_RESET: {
                    ovalRect.set(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f);
                    c.drawArc(ovalRect, 200f, 130f, false, strokePaint);
                    c.drawArc(ovalRect, 20f, 130f, false, strokePaint);
                    c.drawLine(w * 0.78f, h * 0.34f, w * 0.84f, h * 0.20f, strokePaint);
                    c.drawLine(w * 0.78f, h * 0.34f, w * 0.64f, h * 0.32f, strokePaint);
                    c.drawLine(w * 0.22f, h * 0.66f, w * 0.16f, h * 0.80f, strokePaint);
                    c.drawLine(w * 0.22f, h * 0.66f, w * 0.36f, h * 0.68f, strokePaint);
                    break;
                }
                case ICON_DEVICE_ID: {
                    ovalRect.set(w * 0.18f, h * 0.18f, w * 0.82f, h * 0.82f);
                    c.drawArc(ovalRect, 180f, 180f, false, strokePaint);
                    ovalRect.set(w * 0.32f, h * 0.32f, w * 0.68f, h * 0.78f);
                    c.drawArc(ovalRect, 180f, 180f, false, strokePaint);
                    c.drawLine(w * 0.5f, h * 0.46f, w * 0.5f, h * 0.80f, strokePaint);
                    break;
                }
                case ICON_DIAGNOSTICS: {
                    ovalRect.set(w * 0.22f, h * 0.14f, w * 0.78f, h * 0.86f);
                    c.drawRoundRect(ovalRect, w * 0.08f, h * 0.08f, strokePaint);
                    c.drawLine(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.34f, strokePaint);
                    c.drawLine(w * 0.34f, h * 0.52f, w * 0.66f, h * 0.52f, strokePaint);
                    c.drawLine(w * 0.34f, h * 0.68f, w * 0.54f, h * 0.68f, strokePaint);
                    break;
                }
                case ICON_TOUCH_GYRO: {
                    c.drawCircle(w * 0.5f, h * 0.5f, w * 0.34f, strokePaint);
                    ovalRect.set(w * 0.16f, h * 0.34f, w * 0.84f, h * 0.66f);
                    c.drawOval(ovalRect, strokePaint);
                    c.drawCircle(w * 0.5f, h * 0.5f, w * 0.08f, fillPaint);
                    break;
                }
                case ICON_AUDIO_FOOTSTEP: {
                    // SF Symbol: speaker.wave.2.fill
                    path.moveTo(w * 0.18f, h * 0.40f);
                    path.lineTo(w * 0.34f, h * 0.40f);
                    path.lineTo(w * 0.52f, h * 0.24f);
                    path.lineTo(w * 0.52f, h * 0.76f);
                    path.lineTo(w * 0.34f, h * 0.60f);
                    path.lineTo(w * 0.18f, h * 0.60f);
                    path.close();
                    c.drawPath(path, strokePaint);
                    ovalRect.set(w * 0.44f, h * 0.30f, w * 0.72f, h * 0.70f);
                    c.drawArc(ovalRect, -50f, 100f, false, strokePaint);
                    ovalRect.set(w * 0.36f, h * 0.18f, w * 0.86f, h * 0.82f);
                    c.drawArc(ovalRect, -50f, 100f, false, strokePaint);
                    break;
                }
                case ICON_KEEP_ALIVE_LOCK: {
                    // SF Symbol: bolt.shield / lock.shield
                    ovalRect.set(w * 0.24f, h * 0.44f, w * 0.76f, h * 0.84f);
                    c.drawRoundRect(ovalRect, w * 0.08f, h * 0.08f, strokePaint);
                    ovalRect.set(w * 0.33f, h * 0.18f, w * 0.67f, h * 0.54f);
                    c.drawArc(ovalRect, 180f, 180f, false, strokePaint);
                    c.drawCircle(w * 0.5f, h * 0.64f, w * 0.07f, fillPaint);
                    break;
                }
                case ICON_CLAW_EDGE: {
                    // SF Symbol: rectangle.inset.filled / screen edge lock
                    ovalRect.set(w * 0.14f, h * 0.20f, w * 0.86f, h * 0.80f);
                    c.drawRoundRect(ovalRect, w * 0.10f, h * 0.10f, strokePaint);
                    c.drawLine(w * 0.30f, h * 0.34f, w * 0.70f, h * 0.34f, strokePaint);
                    c.drawCircle(w * 0.5f, h * 0.56f, w * 0.09f, fillPaint);
                    break;
                }
                case ICON_CHEVRON:
                default: {
                    path.moveTo(w * 0.20f, h * 0.36f);
                    path.lineTo(w * 0.50f, h * 0.68f);
                    path.lineTo(w * 0.80f, h * 0.36f);
                    c.drawPath(path, strokePaint);
                    break;
                }
            }
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
