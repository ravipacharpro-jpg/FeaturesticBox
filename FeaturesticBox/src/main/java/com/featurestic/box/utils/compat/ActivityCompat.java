package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import black.android.app.BRActivity;
import black.com.android.internal.BRRstyleable;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.utils.DrawableUtils;

public class ActivityCompat {

    public static void fix(Activity activity) {
        try {
            BRActivity.get(activity).mActivityInfo();
        } catch (Throwable ignore) {
        }

        Context baseContext = activity.getBaseContext();

        try {
            TypedArray typedArray = activity.obtainStyledAttributes(BRRstyleable.get().Window());
            if (typedArray != null) {
                if (typedArray.getBoolean(BRRstyleable.get().Window_windowShowWallpaper(), false)) {
                    try {
                        Drawable wallpaper = WallpaperManager.getInstance(activity).getDrawable();
                        if (wallpaper != null) {
                            activity.getWindow().setBackgroundDrawable(wallpaper);
                        }
                    } catch (Throwable ignore) {
                    }
                }

                if (typedArray.getBoolean(BRRstyleable.get().Window_windowFullscreen(), false)) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }

                typedArray.recycle();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        // Enable Fullscreen Notch/Punch-Hole Cutout & High Refresh Rate (90Hz / 120Hz / 144Hz)
        try {
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                boolean changed = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp != null) {
                    if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                        changed = true;
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && lp != null && lp.preferredDisplayModeId == 0) {
                    Display display = activity.getWindowManager() != null
                            ? activity.getWindowManager().getDefaultDisplay() : null;
                    if (display != null) {
                        Display.Mode current = display.getMode();
                        Display.Mode[] supported = display.getSupportedModes();
                        Display.Mode best = current;
                        if (current != null && supported != null) {
                            for (Display.Mode mode : supported) {
                                if (mode != null
                                        && mode.getPhysicalWidth() == current.getPhysicalWidth()
                                        && mode.getPhysicalHeight() == current.getPhysicalHeight()
                                        && mode.getRefreshRate() > best.getRefreshRate()) {
                                    best = mode;
                                }
                            }
                            if (best != null && best.getRefreshRate() > current.getRefreshRate()) {
                                lp.preferredDisplayModeId = best.getModeId();
                                lp.preferredRefreshRate = best.getRefreshRate();
                                changed = true;
                            }
                        }
                    }
                }
                if (changed) {
                    window.setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Intent intent = activity.getIntent();
                ApplicationInfo appInfo = baseContext.getApplicationInfo();
                PackageManager pm = activity.getPackageManager();

                if (intent != null && activity.isTaskRoot()) {
                    String label = TaskDescriptionCompat.getTaskDescriptionLabel(BActivityThread.getUserId(), appInfo.loadLabel(pm));

                    Bitmap icon = null;
                    Drawable drawable = getActivityIcon(activity);
                    if (drawable != null) {
                        ActivityManager am = (ActivityManager) baseContext.getSystemService(Context.ACTIVITY_SERVICE);
                        int iconSize = am.getLauncherLargeIconSize();
                        icon = DrawableUtils.drawableToBitmap(drawable, iconSize, iconSize);
                    }

                    activity.setTaskDescription(new ActivityManager.TaskDescription(label, icon));
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    private static Drawable getActivityIcon(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            Drawable icon = pm.getActivityIcon(activity.getComponentName());
            if (icon != null) return icon;
        } catch (PackageManager.NameNotFoundException ignore) {
        }

        try {
            ApplicationInfo appInfo = activity.getApplicationInfo();
            return appInfo.loadIcon(activity.getPackageManager());
        } catch (Throwable ignore) {
        }

        return null;
    }
}
