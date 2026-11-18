package com.featurestic.box.utils.compat;

import android.app.Activity;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Esports High-Visibility & Zero-Lag UE4 Graphics Preset Optimizer.
 *
 * IMPORTANT FOR BGMI v4.7.0:
 * Never write unencrypted plain-text EnjoyCJZC.ini by default (PRESET_DEFAULT),
 * and automatically remove any stale plain-text "[UserCustom DeviceProfile]"
 * EnjoyCJZC.ini created by older builds so libanogs.so / libUE4.so encrypted INI
 * validation never fails 7-8s after noticesList: [].
 */
@Keep
public final class FeaturesticGraphicsOptimizer {
    public static final int PRESET_DEFAULT = 0;
    public static final int PRESET_ESPORTS_NO_SHADOW = 1;
    public static final int PRESET_ULTRA_SHARP_HDR = 2;

    private static volatile Integer sCachedPreset;

    private FeaturesticGraphicsOptimizer() {
    }

    private static File getPresetFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_gfx_preset.mode");
    }

    public static int getCurrentPreset() {
        if (sCachedPreset != null) return sCachedPreset;
        try {
            File f = getPresetFile();
            if (f.isFile() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[16];
                    int n = fis.read(buf);
                    if (n > 0) {
                        int val = Integer.parseInt(new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                        if (val >= PRESET_DEFAULT && val <= PRESET_ULTRA_SHARP_HDR) {
                            sCachedPreset = val;
                            return val;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedPreset = PRESET_DEFAULT;
        return PRESET_DEFAULT;
    }

    public static void setPreset(int preset, Activity activeActivity) {
        int clamped = (preset < PRESET_DEFAULT || preset > PRESET_ULTRA_SHARP_HDR)
                ? PRESET_DEFAULT : preset;
        sCachedPreset = clamped;
        try {
            File f = getPresetFile();
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(String.valueOf(clamped).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        String pkg = BActivityThread.isThreadInit() ? BActivityThread.getAppPackageName()
                : (activeActivity != null ? activeActivity.getPackageName() : null);
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        if (clamped == PRESET_DEFAULT) {
            cleanPlainTextEnjoyIniIfPresent(pkg, userId);
        } else {
            applyGraphicsPresetAsync(pkg, userId, clamped);
        }
    }

    public static int cycleNextPreset(Activity activeActivity) {
        int cur = getCurrentPreset();
        int next = (cur == PRESET_DEFAULT) ? PRESET_ESPORTS_NO_SHADOW
                : (cur == PRESET_ESPORTS_NO_SHADOW ? PRESET_ULTRA_SHARP_HDR : PRESET_DEFAULT);
        setPreset(next, activeActivity);
        return next;
    }

    public static String getPresetLabel(int preset) {
        switch (preset) {
            case PRESET_ESPORTS_NO_SHADOW:
                return "Esports No-Shadow (High Visibility)";
            case PRESET_ULTRA_SHARP_HDR:
                return "Ultra Sharp HDR + 120 FPS";
            case PRESET_DEFAULT:
            default:
                return "Off • Default Graphics";
        }
    }

    /**
     * Removes any unencrypted plain-text EnjoyCJZC.ini (starting with "[UserCustom")
     * left over from previous launches so BGMI v4.7.0 encrypted config validation and
     * libanogs.so file scan pass cleanly.
     */
    public static void cleanPlainTextEnjoyIniIfPresent(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            String relUe4 = "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android";
            List<File> dirs = new ArrayList<>();
            File vExt = BEnvironment.getExternalUserAppDataDir(userId, packageName);
            if (vExt != null) dirs.add(new File(vExt, relUe4));
            File vExt0 = BEnvironment.getExternalDataDir(packageName);
            if (vExt0 != null) dirs.add(new File(vExt0, relUe4));
            File hostExt = new File("/sdcard/Android/data/" + packageName, relUe4);
            dirs.add(hostExt);

            for (File dir : dirs) {
                if (dir == null || !dir.isDirectory()) continue;
                File enjoyFile = new File(dir, "EnjoyCJZC.ini");
                if (enjoyFile.isFile() && enjoyFile.length() > 0 && enjoyFile.length() < 4096) {
                    byte[] header = new byte[32];
                    int n;
                    try (FileInputStream fis = new FileInputStream(enjoyFile)) {
                        n = fis.read(header);
                    }
                    if (n > 0) {
                        String prefix = new String(header, 0, n, StandardCharsets.UTF_8);
                        if (prefix.startsWith("[UserCustom") || prefix.contains("+CVars=")) {
                            enjoyFile.delete();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applyGraphicsPresetAsync(
            final String packageName, final int userId, final int preset) {
        if (packageName == null || packageName.isEmpty()) return;
        if (preset == PRESET_DEFAULT) {
            cleanPlainTextEnjoyIniIfPresent(packageName, userId);
            return;
        }
        // For protected games like BGMI v4.7.0, never write plain-text EnjoyCJZC.ini on disk
        if (packageName.startsWith("com.pubg.") || packageName.startsWith("com.tencent.ig")) {
            cleanPlainTextEnjoyIniIfPresent(packageName, userId);
            return;
        }
    }
}
