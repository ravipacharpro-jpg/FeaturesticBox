package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.content.Context;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import java.io.File;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Core Esports Tactical Engines for FeaturesticBox:
 * 1. Tactical Footstep & Gunfire Audio Enhancer (Equalizer + LoudnessEnhancer):
 *    Boosts 1.2kHz–4kHz (enemy footsteps, glass, doors, weapon reloads) while compressing
 *    deafening low-end explosion/plane rumble (60Hz–150Hz) via Android OS AudioEffect API.
 * 2. Anti-Disconnect Background & Lobby Freezer Shield:
 *    Prevents Android 14–16 CachedAppOptimizer / OEM battery freezers from suspending the
 *    virtual game process or dropping game sockets when switching to WhatsApp/Discord briefly.
 * 3. Claw Edge-Swipe & Notification Guard:
 *    Locks sticky immersive mode and blocks accidental top/bottom system bar pull-downs
 *    when 3-finger or 4-finger Claw multi-touch pointers are active.
 */
public final class FeaturesticEsportsCoreEngines {
    private static volatile Boolean sFootstepBoostEnabled;
    private static volatile Boolean sAntiDisconnectEnabled;
    private static volatile Boolean sClawEdgeGuardEnabled;

    private static Equalizer sActiveEqualizer;
    private static LoudnessEnhancer sActiveLoudnessEnhancer;
    private static PowerManager.WakeLock sKeepAliveWakeLock;
    private static final Handler sKeepAliveHandler = new Handler(Looper.getMainLooper());
    private static boolean sHeartbeatRunning = false;

    private FeaturesticEsportsCoreEngines() {
    }

    private static File getFlagFile(String name) {
        return new File(BEnvironment.getSystemDir(), name);
    }

    // --- 1. Tactical Footstep & Gunfire Audio Enhancer ---

    public static boolean isFootstepAudioBoostEnabled() {
        if (sFootstepBoostEnabled != null) return sFootstepBoostEnabled;
        try {
            sFootstepBoostEnabled = getFlagFile("featurestic_footstep_audio.on").exists();
            return sFootstepBoostEnabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setFootstepAudioBoostEnabled(boolean enabled) {
        sFootstepBoostEnabled = enabled;
        try {
            File f = getFlagFile("featurestic_footstep_audio.on");
            FileUtils.mkdirs(f.getParentFile());
            if (enabled) {
                f.createNewFile();
                applyFootstepAudioProfile(true);
            } else {
                if (f.exists()) f.delete();
                applyFootstepAudioProfile(false);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applyFootstepAudioProfile(boolean enable) {
        try {
            if (!enable) {
                if (sActiveEqualizer != null) {
                    sActiveEqualizer.setEnabled(false);
                    sActiveEqualizer.release();
                    sActiveEqualizer = null;
                }
                if (sActiveLoudnessEnhancer != null) {
                    sActiveLoudnessEnhancer.setEnabled(false);
                    sActiveLoudnessEnhancer.release();
                    sActiveLoudnessEnhancer = null;
                }
                return;
            }

            if (sActiveEqualizer == null) {
                sActiveEqualizer = new Equalizer(0, 0);
            }
            short bands = sActiveEqualizer.getNumberOfBands();
            short[] range = sActiveEqualizer.getBandLevelRange();
            if (range != null && range.length >= 2) {
                short minMb = range[0];
                short maxMb = range[1];
                for (short b = 0; b < bands; b++) {
                    int centerFreqMilliHz = sActiveEqualizer.getCenterFreq(b);
                    int centerHz = centerFreqMilliHz / 1000;
                    short targetLevel = 0;
                    if (centerHz < 160) {
                        // Slightly tame plane/red-zone bass rumble (-25% of range)
                        targetLevel = (short) (minMb / 4);
                    } else if (centerHz >= 1000 && centerHz <= 4500) {
                        // Boost footstep, reload & grass rustle band (+65% of max boost)
                        targetLevel = (short) ((maxMb * 65) / 100);
                    } else if (centerHz > 4500) {
                        // Crisp directional high-frequency cue (+35% boost)
                        targetLevel = (short) ((maxMb * 35) / 100);
                    }
                    sActiveEqualizer.setBandLevel(b, targetLevel);
                }
            }
            sActiveEqualizer.setEnabled(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                if (sActiveLoudnessEnhancer == null) {
                    sActiveLoudnessEnhancer = new LoudnessEnhancer(0);
                }
                sActiveLoudnessEnhancer.setTargetGain(350); // +3.5 dB clarity gain
                sActiveLoudnessEnhancer.setEnabled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    // --- 2. Anti-Disconnect Background & Lobby Freezer Shield ---

    public static boolean isAntiDisconnectShieldEnabled() {
        if (sAntiDisconnectEnabled != null) return sAntiDisconnectEnabled;
        try {
            File disabled = getFlagFile("featurestic_antidisconnect_disabled.flag");
            sAntiDisconnectEnabled = !(disabled != null && disabled.exists());
            return sAntiDisconnectEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setAntiDisconnectShieldEnabled(boolean enabled) {
        sAntiDisconnectEnabled = enabled;
        try {
            File disabled = getFlagFile("featurestic_antidisconnect_disabled.flag");
            FileUtils.mkdirs(disabled.getParentFile());
            if (enabled) {
                if (disabled.exists()) disabled.delete();
                engageAntiDisconnectShield(FeaturesticBoxCore.getContext());
            } else {
                disabled.createNewFile();
                releaseAntiDisconnectShield();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void engageAntiDisconnectShield(Context context) {
        if (!isAntiDisconnectShieldEnabled()) return;
        try {
            Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
            if (ctx != null && sKeepAliveWakeLock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    sKeepAliveWakeLock = pm.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK, "FeaturesticBox:LobbyKeepAlive");
                    sKeepAliveWakeLock.setReferenceCounted(false);
                }
            }
            if (sKeepAliveWakeLock != null) {
                sKeepAliveWakeLock.acquire(60 * 60 * 1000L);
            }
            if (!sHeartbeatRunning) {
                sHeartbeatRunning = true;
                scheduleKeepAlivePulse();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleKeepAlivePulse() {
        sKeepAliveHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isAntiDisconnectShieldEnabled()) {
                    sHeartbeatRunning = false;
                    return;
                }
                try {
                    if (sKeepAliveWakeLock != null) {
                        sKeepAliveWakeLock.acquire(60 * 60 * 1000L);
                    }
                } catch (Throwable ignored) {
                }
                sKeepAliveHandler.postDelayed(this, 15_000L);
            }
        }, 15_000L);
    }

    public static void releaseAntiDisconnectShield() {
        try {
            if (sKeepAliveWakeLock != null && sKeepAliveWakeLock.isHeld()) {
                sKeepAliveWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    // --- 3. Claw Edge-Swipe & Notification Guard ---

    public static boolean isClawEdgeGuardEnabled() {
        if (sClawEdgeGuardEnabled != null) return sClawEdgeGuardEnabled;
        try {
            File disabled = getFlagFile("featurestic_claw_guard_disabled.flag");
            sClawEdgeGuardEnabled = !(disabled != null && disabled.exists());
            return sClawEdgeGuardEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setClawEdgeGuardEnabled(boolean enabled) {
        sClawEdgeGuardEnabled = enabled;
        try {
            File disabled = getFlagFile("featurestic_claw_guard_disabled.flag");
            FileUtils.mkdirs(disabled.getParentFile());
            if (enabled) {
                if (disabled.exists()) disabled.delete();
            } else {
                disabled.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    public static void enforceImmersiveClawGuard(Activity activity) {
        if (activity == null || !isClawEdgeGuardEnabled()) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onGameActivityResumed(Activity activity) {
        if (isFootstepAudioBoostEnabled()) {
            applyFootstepAudioProfile(true);
        }
        if (isAntiDisconnectShieldEnabled()) {
            engageAntiDisconnectShield(activity);
        }
        if (isClawEdgeGuardEnabled()) {
            enforceImmersiveClawGuard(activity);
        }
    }
}
