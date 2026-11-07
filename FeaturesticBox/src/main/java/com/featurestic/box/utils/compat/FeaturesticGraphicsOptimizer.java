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
 * Presets:
 * - PRESET_DEFAULT (0): Default Game Graphics
 * - PRESET_ESPORTS_NO_SHADOW (1): Competitive No-Shadow + Zero-Blur + High Enemy Visibility + 120 FPS
 * - PRESET_ULTRA_SHARP_HDR (2): Ultra Crisp Sharpening + 4x MSAA + 120 FPS HDR Unlock
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
        applyGraphicsPresetAsync(pkg, userId, clamped);
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

    public static void applyGraphicsPresetAsync(
            final String packageName, final int userId, final int preset) {
        if (packageName == null || packageName.isEmpty()) return;
        new Thread(() -> {
            try {
                String nl = String.valueOf((char) 10);
                String relUe4 = "files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android";
                List<File> dirs = new ArrayList<>();
                File vExt = BEnvironment.getExternalUserAppDataDir(userId, packageName);
                if (vExt != null) dirs.add(new File(vExt, relUe4));
                File hostExt = new File("/sdcard/Android/data/" + packageName, relUe4);
                if (hostExt.getParentFile() != null && hostExt.getParentFile().exists()) dirs.add(hostExt);

                for (File dir : dirs) {
                    if (dir == null) continue;
                    FileUtils.mkdirs(dir);
                    File enjoyFile = new File(dir, "EnjoyCJZC.ini");
                    int fpsVal = ThermalAndFrameGuard.getTargetFps();
                    boolean ipadOn = FeaturesticIpadViewEngine.isIpadViewEnabled();
                    int ipadPct = FeaturesticIpadViewEngine.getZoomOutPercent();

                    StringBuilder sb = new StringBuilder(768);
                    sb.append("[UserCustom DeviceProfile]").append(nl);
                    sb.append("+CVars=r.PUBGMaxSupportQualityLevel=3").append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSDef=").append(fpsVal).append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSLow=").append(fpsVal).append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSMid=").append(fpsVal).append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSHigh=").append(fpsVal).append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSHDR=").append(fpsVal).append(nl);
                    sb.append("+CVars=r.PUBGDeviceFPSUltralHigh=").append(fpsVal).append(nl);

                    if (ipadOn && ipadPct > 0) {
                        sb.append("+CVars=r.AspectRatioAxisConstraint=1").append(nl);
                        float fov = 80.0f + (ipadPct * 0.45f);
                        sb.append("+CVars=r.DefaultFeature.FOV=").append(fov).append(nl);
                    }

                    if (preset == PRESET_ESPORTS_NO_SHADOW) {
                        sb.append("+CVars=r.ShadowQuality=0").append(nl);
                        sb.append("+CVars=r.Shadow.MaxResolution=64").append(nl);
                        sb.append("+CVars=r.BloomQuality=0").append(nl);
                        sb.append("+CVars=r.DepthOfFieldQuality=0").append(nl);
                        sb.append("+CVars=r.LightShaftQuality=0").append(nl);
                        sb.append("+CVars=r.RefractionQuality=0").append(nl);
                        sb.append("+CVars=r.EmitterSpawnRateScale=0.5").append(nl);
                        sb.append("+CVars=r.Tonemapper.Sharpen=1.0").append(nl);
                    } else if (preset == PRESET_ULTRA_SHARP_HDR) {
                        sb.append("+CVars=r.ShadowQuality=2").append(nl);
                        sb.append("+CVars=r.PostProcessAAQuality=4").append(nl);
                        sb.append("+CVars=r.Tonemapper.Sharpen=1.4").append(nl);
                        sb.append("+CVars=r.MaxAnisotropy=8").append(nl);
                    }

                    try (FileOutputStream fos = new FileOutputStream(enjoyFile, false)) {
                        fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Throwable ignored) {
            }
        }, "Featurestic-GfxOptimizer").start();
    }
}
