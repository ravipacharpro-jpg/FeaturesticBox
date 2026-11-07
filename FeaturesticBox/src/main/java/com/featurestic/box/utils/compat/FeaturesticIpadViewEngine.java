package com.featurestic.box.utils.compat;

import android.app.Activity;

/**
 * Stub retained for binary compatibility after removing iPad View per user request.
 */
public final class FeaturesticIpadViewEngine {
    public static final int MODE_OFF = 0;
    public static final int MODE_IPAD_AIR_16_10 = 1;
    public static final int MODE_IPAD_PRO_4_3 = 2;
    public static final int MODE_IPAD_ULTRA_5_4 = 3;

    private FeaturesticIpadViewEngine() {
    }

    public static boolean isIpadViewEnabled() {
        return false;
    }

    public static void setIpadViewEnabled(boolean enabled, Activity activeActivity) {
    }

    public static int getZoomOutPercent() {
        return 0;
    }

    public static void setZoomOutPercent(int percent, Activity activeActivity) {
    }

    public static int getCurrentMode() {
        return MODE_OFF;
    }

    public static void setMode(int mode) {
    }

    public static String getLiveSliderSummary() {
        return "Disabled";
    }

    public static String getModeShortLabel(int mode) {
        return "Off";
    }

    public static String getModeFullDescription(int mode) {
        return "Off";
    }

    public static void applyToActivity(Activity activity) {
    }
}
