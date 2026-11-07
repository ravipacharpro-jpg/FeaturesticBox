package com.featurestic.box.utils.compat;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.security.Security;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;

/**
 * Low-Network & Weak Wi-Fi / Mobile Data Turbo Optimizer.
 *
 * 1. Locks Wi-Fi hardware into Android 10-16 WIFI_MODE_FULL_LOW_LATENCY (disabling
 *    chipset DTIM power-save sleep that causes 40-180ms ping spikes on weak Wi-Fi).
 * 2. Injects TCP_NODELAY (disables Nagle's algorithm for 0ms packet send),
 *    SO_KEEPALIVE (prevents NAT drops on slow mobile/Wi-Fi links), IP_TOS low-delay
 *    DSCP marking (prioritizes game & auth packets on Wi-Fi WMM routers), and
 *    256KB socket buffer floors on guest TCP/UDP sockets.
 * 3. Configures JVM DNS caching (600s positive TTL, 0s negative TTL so transient
 *    packet drops on weak internet never cache a failed lookup) and HTTP keep-alive
 *    pooling, plus parallel background DNS pre-warming for auth & game hosts.
 */
@Obfuscate
public final class NetworkLowLatencyOptimizer {
    private static volatile boolean sEnabled = true;
    private static final AtomicBoolean sProcessInitialized = new AtomicBoolean(false);
    private static WifiManager.WifiLock sLowLatencyWifiLock;

    private static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    private static final int WIFI_MODE_FULL_LOW_LATENCY = 4;
    private static final int IPTOS_LOWDELAY_GAMING = 0x28;
    private static final int MIN_SOCKET_BUFFER_BYTES = 262_144; // 256 KB

    private static final String[] PREWARM_HOSTS = new String[]{
            "accounts.google.com",
            "oauth2.googleapis.com",
            "www.googleapis.com",
            "m.facebook.com",
            "graph.facebook.com",
            "api.twitter.com",
            "twitter.com",
            "x.com",
            "discord.com",
            "discordapp.com",
            "accounts.krafton.com",
            "auth.garena.com"
    };

    private static final ExecutorService DNS_PREWARM_POOL =
            Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "Featurestic-DnsTurbo");
                t.setDaemon(true);
                return t;
            });

    private NetworkLowLatencyOptimizer() {
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
        if (!enabled) {
            releaseWifiLowLatencyLock();
        }
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /**
     * Initializes JVM-wide DNS caching, HTTP keep-alive connection pooling,
     * and non-blocking parallel DNS pre-warming for the virtual process.
     */
    public static void initForProcess(Context context) {
        if (!sEnabled || !sProcessInitialized.compareAndSet(false, true)) {
            return;
        }
        try {
            // Cache successful DNS lookups for 10 minutes; never cache failed
            // lookups (0s) so weak Wi-Fi / mobile packet drops retry immediately.
            Security.setProperty("networkaddress.cache.ttl", "600");
            Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Throwable ignored) {
        }

        try {
            System.setProperty("http.keepAlive", "true");
            System.setProperty("http.maxConnections", "32");
            System.setProperty("http.keepAliveDuration", "300000");
            System.setProperty("sun.net.inetaddr.ttl", "600");
            System.setProperty("sun.net.inetaddr.negative.ttl", "0");
        } catch (Throwable ignored) {
        }

        for (final String host : PREWARM_HOSTS) {
            DNS_PREWARM_POOL.execute(() -> {
                try {
                    InetAddress.getAllByName(host);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    /**
     * Acquires Android's hardware WIFI_MODE_FULL_LOW_LATENCY lock while a game
     * or auth Activity is in the foreground so weak Wi-Fi never enters power-save sleep.
     */
    public static synchronized void acquireWifiLowLatencyLock(Context context) {
        if (!sEnabled) {
            return;
        }
        try {
            if (sLowLatencyWifiLock != null && sLowLatencyWifiLock.isHeld()) {
                return;
            }
            Context appCtx = context != null ? context.getApplicationContext() : FeaturesticBoxCore.getContext();
            if (appCtx == null) {
                return;
            }
            WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? WIFI_MODE_FULL_LOW_LATENCY
                    : WIFI_MODE_FULL_HIGH_PERF;
            sLowLatencyWifiLock = wm.createWifiLock(lockMode, "Featurestic:LowLatencyLock");
            sLowLatencyWifiLock.setReferenceCounted(false);
            sLowLatencyWifiLock.acquire();
        } catch (Throwable ignored) {
        }
        enableDualNetworkStandby(context);
    }

    public static synchronized void releaseWifiLowLatencyLock() {
        try {
            if (sLowLatencyWifiLock != null && sLowLatencyWifiLock.isHeld()) {
                sLowLatencyWifiLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Tunes newly created AF_INET / AF_INET6 TCP and UDP sockets for weak / high-jitter
     * connections: disables Nagle buffering (TCP_NODELAY), enables keep-alive, sets
     * WMM low-delay DSCP/TOS priority, and ensures adequate kernel send/receive buffers.
     */
    public static void optimizeSocketOnCreation(FileDescriptor fd, Object[] socketArgs) {
        if (!sEnabled || fd == null || !fd.valid()) {
            return;
        }
        try {
            int domain = socketArgs != null && socketArgs.length > 0 && socketArgs[0] instanceof Integer
                    ? (Integer) socketArgs[0] : -1;
            int type = socketArgs != null && socketArgs.length > 1 && socketArgs[1] instanceof Integer
                    ? (Integer) socketArgs[1] : -1;
            if (domain != OsConstants.AF_INET && domain != OsConstants.AF_INET6) {
                return;
            }
            boolean isStream = (type & 0xF) == OsConstants.SOCK_STREAM;
            boolean isDgram = (type & 0xF) == OsConstants.SOCK_DGRAM;
            if (!isStream && !isDgram) {
                return;
            }

            if (isStream) {
                try {
                    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1);
                } catch (Throwable ignored) {
                }
                try {
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_KEEPALIVE, 1);
                } catch (Throwable ignored) {
                }
            }

            try {
                Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TOS, IPTOS_LOWDELAY_GAMING);
            } catch (Throwable ignored) {
            }

            try {
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, MIN_SOCKET_BUFFER_BYTES);
            } catch (Throwable ignored) {
            }
            try {
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, MIN_SOCKET_BUFFER_BYTES);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    public static void optimizeSocketOnConnect(FileDescriptor fd) {
        if (!sEnabled || fd == null || !fd.valid()) {
            return;
        }
        try {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1);
        } catch (Throwable ignored) {
        }
        try {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_IP, OsConstants.IP_TOS, IPTOS_LOWDELAY_GAMING);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Filters out heavy third-party analytics/telemetry trackers during OAuth
     * WebView login so 100% of weak bandwidth is dedicated to the login flow.
     */
    public static boolean shouldBlockLowBandwidthTracker(String url) {
        if (!sEnabled || url == null) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.contains("google-analytics.com/")
                || lower.contains("googletagmanager.com/")
                || lower.contains("doubleclick.net/")
                || lower.contains("connect.facebook.net/en_us/fbevents.js")
                || lower.contains("browser.sentry-cdn.com/")
                || lower.contains("static.hotjar.com/")
                || lower.contains("clarity.ms/");
    }

    private static volatile android.net.ConnectivityManager.NetworkCallback sCellularStandbyCallback;
    private static volatile android.net.Network sStandbyCellularNetwork;

    /**
     * Requests a warm standby Cellular data path (TRANSPORT_CELLULAR) while on Wi-Fi
     * so if Wi-Fi drops or spikes mid-match, packets and reconnects hand over seamlessly
     * without waiting 3-5 seconds for cellular radio wake-up.
     */
    public static synchronized void enableDualNetworkStandby(Context context) {
        if (!sEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        if (sCellularStandbyCallback != null) {
            return;
        }
        try {
            Context appCtx = context != null ? context.getApplicationContext() : FeaturesticBoxCore.getContext();
            if (appCtx == null) {
                return;
            }
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            sCellularStandbyCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    sStandbyCellularNetwork = network;
                }

                @Override
                public void onLost(android.net.Network network) {
                    if (sStandbyCellularNetwork == network) {
                        sStandbyCellularNetwork = null;
                    }
                }
            };
            cm.requestNetwork(request, sCellularStandbyCallback);
        } catch (Throwable ignored) {
            sCellularStandbyCallback = null;
        }
    }

    public static android.net.Network getStandbyCellularNetwork() {
        return sStandbyCellularNetwork;
    }

    /**
     * 1-Tap Network Turbo Boost: locks Wi-Fi into Low-Latency mode, activates
     * Dual-Network Cellular Standby, and refreshes parallel DNS pre-warming.
     */
    public static void boostAndWarmNow(Context context) {
        sEnabled = true;
        acquireWifiLowLatencyLock(context);
        enableDualNetworkStandby(context);
        for (final String host : PREWARM_HOSTS) {
            DNS_PREWARM_POOL.execute(() -> {
                try {
                    InetAddress.getAllByName(host);
                } catch (Throwable ignored) {
                }
            });
        }
    }
}
