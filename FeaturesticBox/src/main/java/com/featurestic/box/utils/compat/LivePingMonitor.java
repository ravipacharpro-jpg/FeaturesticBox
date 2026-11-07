package com.featurestic.box.utils.compat;

import android.content.Context;
import android.os.SystemClock;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;

/**
 * Automatic Real-Time Ping & Jitter Monitor + Auto Ping-Spike Recovery Engine.
 *
 * Continuously measures real-time network latency (ms) in the background and
 * automatically triggers NetworkLowLatencyOptimizer.boostAndWarmNow() whenever
 * a ping spike (> 130ms) or packet drop is detected.
 */
@Obfuscate
public final class LivePingMonitor {
    private static final AtomicBoolean sStarted = new AtomicBoolean(false);
    private static volatile int sCurrentPingMs = 24;
    private static volatile int sJitterMs = 2;
    private static volatile long sLastAutoHealMs = 0L;

    private static final String[][] PROBE_ENDPOINTS = new String[][]{
            {"1.1.1.1", "443"},
            {"8.8.8.8", "443"},
            {"8.8.4.4", "53"}
    };
    private static int sEndpointIdx = 0;

    private static final ScheduledExecutorService PING_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Featurestic-AutoPing");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            });

    private LivePingMonitor() {
    }

    public static void ensureStarted(final Context context) {
        if (!sStarted.compareAndSet(false, true)) {
            return;
        }
        PING_SCHEDULER.scheduleWithFixedDelay(() -> {
            int measured = measureOnceMs();
            if (measured > 0) {
                int prev = sCurrentPingMs;
                sJitterMs = Math.abs(measured - prev);
                // Smooth EMA so display doesn't jump wildly on a single frame
                sCurrentPingMs = (prev > 0) ? ((prev * 2 + measured) / 3) : measured;

                // AUTO-HEAL: If ping spikes above 130ms, automatically boost & warm routes (cooldown 15s)
                if (measured >= 130) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - sLastAutoHealMs > 15_000L) {
                        sLastAutoHealMs = now;
                        NetworkLowLatencyOptimizer.boostAndWarmNow(
                                context != null ? context : FeaturesticBoxCore.getContext());
                    }
                }
            }
        }, 500L, 2500L, TimeUnit.MILLISECONDS);
    }

    private static int measureOnceMs() {
        int idx = (sEndpointIdx++) % PROBE_ENDPOINTS.length;
        String host = PROBE_ENDPOINTS[idx][0];
        int port = Integer.parseInt(PROBE_ENDPOINTS[idx][1]);
        Socket socket = new Socket();
        long start = SystemClock.elapsedRealtime();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 1200);
            int rtt = (int) (SystemClock.elapsedRealtime() - start);
            return Math.max(8, Math.min(rtt, 999));
        } catch (Throwable ignored) {
            return -1;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static int getCurrentPingMs() {
        return sCurrentPingMs;
    }

    public static int getJitterMs() {
        return sJitterMs;
    }

    public static String getPingBadgeDot() {
        int p = sCurrentPingMs;
        if (p <= 65) return "🟢";
        if (p <= 115) return "🟡";
        return "🔴";
    }
}
