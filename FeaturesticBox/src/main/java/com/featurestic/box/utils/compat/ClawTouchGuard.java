package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * Automatic 4-Finger Claw Mistouch, Edge Back-Gesture & Notification-Shade Guard.
 *
 * Automatically activates on every virtual game Activity:
 * 1. Locks IMMERSIVE_STICKY fullscreen + BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so top-edge
 *    4-finger claw fire/scope buttons never accidentally pull down the status bar.
 * 2. Registers Android 10-16 (API 29-36) System Gesture Exclusion Rects along the left
 *    and right edges so fast aiming/peeking never triggers the system Back exit gesture.
 * 3. Locks FLAG_KEEP_SCREEN_ON so the display never dims mid-match.
 */
@Obfuscate
public final class ClawTouchGuard {
    private static volatile boolean sEnabled = true;

    private ClawTouchGuard() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    @SuppressWarnings("deprecation")
    public static void applyAutoEdgeProtection(final Activity activity) {
        if (!sEnabled || activity == null || activity.isFinishing()) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) return;

            // Guarantee gameplay screenshots & screen recording always work (never black screen)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            final View decor = window.getDecorView();
            if (decor == null) return;

            int flags = decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            decor.setSystemUiVisibility(flags);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                decor.post(() -> {
                    try {
                        int w = decor.getWidth();
                        int h = decor.getHeight();
                        if (w <= 0 || h <= 0) return;
                        int edgeWidth = Math.max(48, (int) (w * 0.06f));
                        List<Rect> exclusions = new ArrayList<>(2);
                        // Exclude left and right claw grip/fire zones from system Back gesture
                        exclusions.add(new Rect(0, 0, edgeWidth, h));
                        exclusions.add(new Rect(w - edgeWidth, 0, w, h));
                        decor.setSystemGestureExclusionRects(exclusions);
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }
}
