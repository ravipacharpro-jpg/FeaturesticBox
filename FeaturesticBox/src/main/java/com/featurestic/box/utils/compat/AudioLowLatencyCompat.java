package com.featurestic.box.utils.compat;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.core.RNative;

/**
 * Bluetooth TWS Earbuds & In-Game Voice Mic Low-Latency Audio Engine.
 *
 * 1. Configures Android AAudio MMAP (aaudio.mmap_policy=2, aaudio.hw_burst_min_usec=1000)
 *    and disables deep-buffer media delay (audio.deep_buffer.media=false) so gun-fire
 *    and footstep audio in BGMI/PUBG/CODM has minimal latency on Bluetooth & speakers.
 * 2. Stabilizes Bluetooth A2DP/BLE audio routing when the in-game microphone is active.
 */
@Obfuscate
public final class AudioLowLatencyCompat {
    private static volatile boolean sEnabled = true;

    private AudioLowLatencyCompat() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void initForGameProcess(Context context) {
        if (!sEnabled) {
            return;
        }
        try {
            // Enable AAudio MMAP exclusive low-latency audio path & disable 80ms deep-buffer delay
            RNative.setNativeProp("aaudio.mmap_policy", "2");
            RNative.setNativeProp("aaudio.mmap_exclusive_policy", "2");
            RNative.setNativeProp("aaudio.hw_burst_min_usec", "1000");
            RNative.setNativeProp("audio.deep_buffer.media", "false");
            RNative.setNativeProp("vendor.audio.feature.deepbuffer_as_primary.enable", "false");
            RNative.setNativeProp("persist.bluetooth.a2dp_offload.disabled", "false");
        } catch (Throwable ignored) {
        }

        if (context == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setParameters("audio_UG_low_latency=1;game_mode=1;bt_game_mode=1");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        // Ensure spatial/stereo media stream stays active without aggressive SCO ducking
                        am.setParameters("BT_SCO_SWB=on");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
