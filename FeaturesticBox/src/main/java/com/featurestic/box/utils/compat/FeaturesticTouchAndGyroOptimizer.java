package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Hardware Input Optimizer, Zero-Delay Gyroscope Booster & Power + Volume+ Menu Shortcut Handler:
 * 1. 240Hz Unbuffered Touch Dispatch:
 *    Calls View.requestUnbufferedDispatch(MotionEvent) on the game DecorView so Android
 *    delivers every raw touch sample immediately instead of buffering until VSYNC.
 * 2. Zero-Delay Hardware Gyroscope Booster:
 *    Locks physical MEMS gyroscope at SENSOR_DELAY_FASTEST (0us FIFO batch latency).
 * 3. Power Button + Volume+ Menu Toggle (Replaces 3-Finger Double-Tap):
 *    Completely hides the HUD (View.GONE — zero pixels on screen) or brings back the
 *    Control Center menu when the user presses Power + Volume+ (with AOSP PhoneWindowManager
 *    chord detection + Volume+ hold/chord fallback so the screen does not need to lock).
 */
public final class FeaturesticTouchAndGyroOptimizer {
    private static volatile Boolean sTouchGyroBoostEnabled;
    private static final Set<Window> sHookedWindows =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile boolean sVolUpHeld = false;
    private static volatile boolean sVolDownHeld = false;
    private static volatile boolean sComboTriggeredDuringHold = false;
    private static volatile long sLastVolUpDownUptimeMs = 0L;
    private static volatile long sLastPowerEventUptimeMs = 0L;
    private static volatile long sLastToggleUptimeMs = 0L;

    private static SensorEventListener sZeroDelayGyroListener;
    private static boolean sGyroRegistered = false;
    private static Boolean sHasHardwareGyro;

    private FeaturesticTouchAndGyroOptimizer() {
    }

    public static final int SHORTCUT_POWER_VOL_UP = 0;
    public static final int SHORTCUT_VOL_UP_AND_DOWN = 1;
    public static final int SHORTCUT_SHAKE_PHONE = 2;

    private static volatile Integer sShortcutMode;
    private static SensorEventListener sShakeSensorListener;
    private static boolean sShakeRegistered = false;
    private static long sLastShakeSpikeMs = 0L;
    private static int sShakeCount = 0;

    private static File getShortcutModeFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_menu_shortcut.pref");
    }

    public static int getShortcutMode() {
        if (sShortcutMode != null) return sShortcutMode;
        try {
            File f = getShortcutModeFile();
            if (f.isFile() && f.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[8];
                    int n = fis.read(buf);
                    if (n > 0) {
                        int m = Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
                        if (m >= 0 && m <= 2) {
                            sShortcutMode = m;
                            return m;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sShortcutMode = SHORTCUT_POWER_VOL_UP;
        return SHORTCUT_POWER_VOL_UP;
    }

    public static void setShortcutMode(int mode) {
        int clamped = (mode < 0 || mode > 2) ? SHORTCUT_POWER_VOL_UP : mode;
        sShortcutMode = clamped;
        try {
            File f = getShortcutModeFile();
            FileUtils.mkdirs(f.getParentFile());
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, false)) {
                fos.write(String.valueOf(clamped).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    public static int cycleNextShortcutMode(Activity activity, View hudRootContainer, View controlSheet) {
        int next = (getShortcutMode() + 1) % 3;
        setShortcutMode(next);
        registerShakeShortcutIfNeeded(activity, hudRootContainer, controlSheet);
        return next;
    }

    public static String getShortcutModeLabel(int mode) {
        switch (mode) {
            case SHORTCUT_VOL_UP_AND_DOWN:
                return "Active: Vol+ & Vol− Together (Tap to switch)";
            case SHORTCUT_SHAKE_PHONE:
                return "Active: Shake Phone 2x (Tap to switch)";
            case SHORTCUT_POWER_VOL_UP:
            default:
                return "Active: Power + Volume+ (Tap to switch)";
        }
    }

    public static String getShortcutShortHint() {
        switch (getShortcutMode()) {
            case SHORTCUT_VOL_UP_AND_DOWN:
                return "Vol+ & Vol−";
            case SHORTCUT_SHAKE_PHONE:
                return "Shake Phone 2x";
            case SHORTCUT_POWER_VOL_UP:
            default:
                return "Power + Volume+";
        }
    }

    private static void registerShakeShortcutIfNeeded(
            final Activity activity,
            final View hudRootContainer,
            final View controlSheet) {
        if (activity == null) return;
        try {
            SensorManager sm = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (getShortcutMode() != SHORTCUT_SHAKE_PHONE) {
                if (sShakeRegistered && sShakeSensorListener != null) {
                    sm.unregisterListener(sShakeSensorListener);
                    sShakeRegistered = false;
                }
                return;
            }
            if (sShakeRegistered) return;
            Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel == null) return;
            sShakeSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event == null || event.values == null || event.values.length < 3) return;
                    if (getShortcutMode() != SHORTCUT_SHAKE_PHONE) return;
                    float ax = event.values[0];
                    float ay = event.values[1];
                    float az = event.values[2];
                    double mag = Math.sqrt(ax * ax + ay * ay + az * az);
                    if (mag > 22.5d) {
                        long now = SystemClock.uptimeMillis();
                        if (now - sLastShakeSpikeMs > 180L) {
                            if (now - sLastShakeSpikeMs < 650L) {
                                sShakeCount++;
                            } else {
                                sShakeCount = 1;
                            }
                            sLastShakeSpikeMs = now;
                            if (sShakeCount >= 2) {
                                sShakeCount = 0;
                                triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                            }
                        }
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(sShakeSensorListener, accel, SensorManager.SENSOR_DELAY_GAME);
            sShakeRegistered = true;
        } catch (Throwable ignored) {
        }
    }


    public static boolean hasHardwareGyroscope(Context context) {
        if (sHasHardwareGyro != null) return sHasHardwareGyro;
        try {
            if (context != null) {
                SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
                if (sm != null) {
                    sHasHardwareGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
                    return sHasHardwareGyro;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Eliminates Gyroscope Delay on phones with a physical gyroscope chip:
     * Registers a zero-latency (SENSOR_DELAY_FASTEST, maxReportLatencyUs = 0) listener in
     * the game process so Android SensorService disables hardware FIFO batching delay
     * and streams gyroscope packets at maximum 200Hz-400Hz polling rate.
     */
    public static void engageZeroDelayHardwareGyro(Context context, boolean enable) {
        if (context == null) return;
        try {
            SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            Sensor gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyro == null) {
                sHasHardwareGyro = false;
                return;
            }
            sHasHardwareGyro = true;
            if (!enable) {
                if (sGyroRegistered && sZeroDelayGyroListener != null) {
                    sm.unregisterListener(sZeroDelayGyroListener);
                    sGyroRegistered = false;
                }
                return;
            }
            if (sGyroRegistered) return;
            if (sZeroDelayGyroListener == null) {
                sZeroDelayGyroListener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        // Keeps hardware MEMS gyroscope ODR at SENSOR_DELAY_FASTEST (0ms FIFO latency)
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    }
                };
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                sm.registerListener(sZeroDelayGyroListener, gyro, SensorManager.SENSOR_DELAY_FASTEST, 0);
            } else {
                sm.registerListener(sZeroDelayGyroListener, gyro, SensorManager.SENSOR_DELAY_FASTEST);
            }
            sGyroRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    private static File getDisabledFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_touch_gyro_disabled.flag");
    }

    public static boolean isTouchAndGyroBoostEnabled() {
        if (sTouchGyroBoostEnabled != null) {
            return sTouchGyroBoostEnabled;
        }
        try {
            File disabled = getDisabledFlagFile();
            sTouchGyroBoostEnabled = !(disabled != null && disabled.exists());
            return sTouchGyroBoostEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setTouchAndGyroBoostEnabled(boolean enabled) {
        sTouchGyroBoostEnabled = enabled;
        try {
            File disabled = getDisabledFlagFile();
            FileUtils.mkdirs(disabled.getParentFile());
            if (enabled) {
                if (disabled.exists()) disabled.delete();
            } else {
                disabled.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Installs the Power + Volume+ hardware key listener and 240Hz Unbuffered Touch
     * dispatcher on the game Activity Window. (3-Finger Double-Tap removed so multi-finger
     * claw players never accidentally trigger the menu.)
     */
    public static void installWindowTouchAndGestureHook(
            final Activity activity,
            final View hudRootContainer,
            final View controlSheet) {
        if (activity == null || activity.getWindow() == null) return;
        if (isTouchAndGyroBoostEnabled()) {
            engageZeroDelayHardwareGyro(activity, true);
        }
        registerShakeShortcutIfNeeded(activity, hudRootContainer, controlSheet);
        FeaturesticLogCleaner.startAutoCleanerForGame(activity, activity.getPackageName(), 0);
        FeaturesticMemoryLockGuard.engageMemoryLockForProcess(activity);
        final Window window = activity.getWindow();
        if (sHookedWindows.contains(window)) {
            return;
        }

        // Enable Android 15 / 16 (API 35+) touch frame-rate boost if supported
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                Method m = Window.class.getMethod("setFrameRateBoostOnTouchEnabled", boolean.class);
                m.invoke(window, true);
            } catch (Throwable ignored) {
            }
        }

        final Window.Callback originalCallback = window.getCallback();
        if (originalCallback == null) {
            return;
        }
        sHookedWindows.add(window);

        // Register a lightweight receiver for system Power button / Power+Volume+ chord broadcasts
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            BroadcastReceiver powerChordReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long now = SystemClock.uptimeMillis();
                    sLastPowerEventUptimeMs = now;
                    if (sVolUpHeld || (now - sLastVolUpDownUptimeMs <= 900L)) {
                        sComboTriggeredDuringHold = true;
                        triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(powerChordReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(powerChordReceiver, filter);
            }
        } catch (Throwable ignored) {
        }

        window.setCallback(new Window.Callback() {
            private boolean unbufferedRequested = false;

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event != null) {
                    try {
                        // Request unbuffered 240Hz touch dispatch on the DecorView (no 3-finger tap interference)
                        if (!unbufferedRequested && isTouchAndGyroBoostEnabled()
                                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            View decor = window.getDecorView();
                            if (decor != null && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                                decor.requestUnbufferedDispatch(event);
                                unbufferedRequested = true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return originalCallback.dispatchTouchEvent(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (event != null) {
                    try {
                        if (handlePowerPlusVolumeUpShortcut(event, activity, hudRootContainer, controlSheet)) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return originalCallback.dispatchKeyEvent(event);
            }

            @Override
            public boolean dispatchKeyShortcutEvent(KeyEvent event) {
                return originalCallback.dispatchKeyShortcutEvent(event);
            }

            @Override
            public boolean dispatchTrackballEvent(MotionEvent event) {
                return originalCallback.dispatchTrackballEvent(event);
            }

            @Override
            public boolean dispatchGenericMotionEvent(MotionEvent event) {
                return originalCallback.dispatchGenericMotionEvent(event);
            }

            @Override
            public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
                return originalCallback.dispatchPopulateAccessibilityEvent(event);
            }

            @Override
            public View onCreatePanelView(int featureId) {
                return originalCallback.onCreatePanelView(featureId);
            }

            @Override
            public boolean onCreatePanelMenu(int featureId, Menu menu) {
                return originalCallback.onCreatePanelMenu(featureId, menu);
            }

            @Override
            public boolean onPreparePanel(int featureId, View view, Menu menu) {
                return originalCallback.onPreparePanel(featureId, view, menu);
            }

            @Override
            public boolean onMenuOpened(int featureId, Menu menu) {
                return originalCallback.onMenuOpened(featureId, menu);
            }

            @Override
            public boolean onMenuItemSelected(int featureId, MenuItem item) {
                return originalCallback.onMenuItemSelected(featureId, item);
            }

            @Override
            public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
                originalCallback.onWindowAttributesChanged(attrs);
            }

            @Override
            public void onContentChanged() {
                originalCallback.onContentChanged();
            }

            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                try {
                    long now = SystemClock.uptimeMillis();
                    // When the user presses Power + Volume+, Android PhoneWindowManager intercepts
                    // the chord and briefly removes window focus while Volume+ is held/recent.
                    if (!hasFocus && (sVolUpHeld || (now - sLastVolUpDownUptimeMs <= 850L))) {
                        sComboTriggeredDuringHold = true;
                        triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                    }
                } catch (Throwable ignored) {
                }
                originalCallback.onWindowFocusChanged(hasFocus);
            }

            @Override
            public void onAttachedToWindow() {
                originalCallback.onAttachedToWindow();
            }

            @Override
            public void onDetachedFromWindow() {
                originalCallback.onDetachedFromWindow();
            }

            @Override
            public void onPanelClosed(int featureId, Menu menu) {
                originalCallback.onPanelClosed(featureId, menu);
            }

            @Override
            public boolean onSearchRequested() {
                return originalCallback.onSearchRequested();
            }

            @Override
            public boolean onSearchRequested(SearchEvent searchEvent) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    return originalCallback.onSearchRequested(searchEvent);
                }
                return false;
            }

            @Override
            public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
                return originalCallback.onWindowStartingActionMode(callback);
            }

            @Override
            public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    return originalCallback.onWindowStartingActionMode(callback, type);
                }
                return null;
            }

            @Override
            public void onActionModeStarted(ActionMode mode) {
                originalCallback.onActionModeStarted(mode);
            }

            @Override
            public void onActionModeFinished(ActionMode mode) {
                originalCallback.onActionModeFinished(mode);
            }
        });
    }

    private static boolean handlePowerPlusVolumeUpShortcut(
            KeyEvent event,
            Activity activity,
            View hudRootContainer,
            View controlSheet) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        long now = SystemClock.uptimeMillis();

        // 1. Direct Power / Stem / Camera key event paired with Volume+
        if (keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY
                || keyCode == KeyEvent.KEYCODE_CAMERA) {
            sLastPowerEventUptimeMs = now;
            if (sVolUpHeld || (now - sLastVolUpDownUptimeMs <= 900L)) {
                sComboTriggeredDuringHold = true;
                triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                return true;
            }
            return false;
        }

        // 2. Volume Down paired with Volume Up (secondary chord fallback)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                sVolDownHeld = true;
                if (sVolUpHeld) {
                    sComboTriggeredDuringHold = true;
                    triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                sVolDownHeld = false;
            }
            return false;
        }

        // 3. Volume Up (Primary trigger with Power button chord + 0.55s hold fallback)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    sVolUpHeld = true;
                    sComboTriggeredDuringHold = false;
                    sLastVolUpDownUptimeMs = now;
                }
                // Check if Power event or Volume Down occurred right before/with Volume+
                if (sVolDownHeld || (now - sLastPowerEventUptimeMs <= 900L)) {
                    sComboTriggeredDuringHold = true;
                    triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                    return true;
                }
                // If user holds Volume+ (e.g. while pressing Power+Volume+ or holding Volume+ 0.55s),
                // toggle immediately before the phone screen locks!
                if (!sComboTriggeredDuringHold
                        && event.getRepeatCount() >= 1
                        && (now - sLastVolUpDownUptimeMs >= 500L)) {
                    sComboTriggeredDuringHold = true;
                    triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                    return true;
                }
                if (sComboTriggeredDuringHold) {
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean wasCombo = sComboTriggeredDuringHold;
                // AOSP PhoneWindowManager sets FLAG_CANCELED on KEYCODE_VOLUME_UP when Power+Volume+ chord is intercepted!
                if (!wasCombo && (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
                    wasCombo = true;
                    triggerToggleWithDebounce(activity, hudRootContainer, controlSheet);
                }
                sVolUpHeld = false;
                sComboTriggeredDuringHold = false;
                if (wasCombo) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void triggerToggleWithDebounce(
            final Activity activity,
            final View hudRootContainer,
            final View controlSheet) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastToggleUptimeMs < 850L) {
            return;
        }
        sLastToggleUptimeMs = now;
        if (activity != null) {
            activity.runOnUiThread(() -> toggleHudVisibility(activity, hudRootContainer, controlSheet));
        }
    }

    private static void toggleHudVisibility(
            Activity activity, View hudRootContainer, View controlSheet) {
        if (hudRootContainer == null) return;
        if (hudRootContainer.getVisibility() == View.VISIBLE) {
            if (controlSheet != null) {
                controlSheet.setVisibility(View.GONE);
            }
            hudRootContainer.setVisibility(View.GONE);
            Toast.makeText(activity,
                    "Control Center Hidden (Press " + getShortcutShortHint() + " to show)",
                    Toast.LENGTH_SHORT).show();
        } else {
            hudRootContainer.setAlpha(1.0f);
            hudRootContainer.setVisibility(View.VISIBLE);
            if (controlSheet != null) {
                controlSheet.setVisibility(View.VISIBLE);
            }
            Toast.makeText(activity,
                    "Control Center Opened (" + getShortcutShortHint() + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
