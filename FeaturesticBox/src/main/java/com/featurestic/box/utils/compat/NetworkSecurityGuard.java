package com.featurestic.box.utils.compat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.os.Build;

import java.io.File;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Anti-MITM / Anti-Sniffer / Proxy & Packet Capture Security Shield (#2).
 * Detects active HTTP/SOCKS proxies, user-installed MITM CA certificates,
 * and packet-capture / SSL-unpinning tools (HttpCanary, Reqable, ProxyPin, PCAPdroid,
 * Charles, BurpSuite, TrustMeAlready, JustTrustMe).
 */
public final class NetworkSecurityGuard {
    private static volatile Boolean sAntiSnifferEnabled;

    private static final String[] SNIFFER_PACKAGES = new String[]{
            "com.guoshi.httpcanary",
            "com.guoshi.httpcanary.premium",
            "com.reqable.android",
            "com.network.proxy",
            "com.emanuelef.remote_capture",
            "app.greyshirts.sslcapture",
            "jp.co.taosoftware.android.packetcapture",
            "org.sandrob.drony",
            "com.minhui.networkcapture",
            "com.egern.android",
            " de.robv.android.xposed.installer".trim(),
            "zhenxi.trustmealready",
            " fury.justtrustme".trim()
    };

    private NetworkSecurityGuard() {
    }

    private static File getDisabledFlagFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_antisniffer_disabled.flag");
    }

    public static boolean isAntiSnifferEnabled() {
        if (sAntiSnifferEnabled != null) return sAntiSnifferEnabled;
        try {
            File f = getDisabledFlagFile();
            sAntiSnifferEnabled = !(f != null && f.exists());
            return sAntiSnifferEnabled;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void setAntiSnifferEnabled(boolean enabled) {
        sAntiSnifferEnabled = enabled;
        try {
            File f = getDisabledFlagFile();
            FileUtils.mkdirs(f.getParentFile());
            if (enabled) {
                if (f.exists()) f.delete();
            } else {
                f.createNewFile();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isNetworkEnvironmentSafe(Context context) {
        if (!isAntiSnifferEnabled()) return true;
        return getDetectedSecurityThreat(context).isEmpty();
    }

    public static String getDetectedSecurityThreat(Context context) {
        if (!isAntiSnifferEnabled()) return "";
        try {
            if (!RNative.isRuntimeSecure()) {
                return "DEBUGGER_OR_FRIDA_DETECTED";
            }
        } catch (Throwable ignored) {
        }

        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (hasSystemProxyConfigured(ctx)) {
            return "HTTP_OR_SOCKS_PROXY_DETECTED";
        }

        if (hasUserAddedMitmCertificates()) {
            return "USER_MITM_CA_CERT_DETECTED";
        }

        String sniffer = findInstalledSnifferPackage(ctx);
        if (sniffer != null && isVpnTunInterfaceActive()) {
            return "PACKET_SNIFFER_ACTIVE:" + sniffer;
        }
        return "";
    }

    public static boolean hasSystemProxyConfigured() {
        return hasSystemProxyConfigured(FeaturesticBoxCore.getContext());
    }

    public static boolean hasSystemProxyConfigured(Context context) {
        try {
            String[] keys = new String[]{
                    "http.proxyHost",
                    "https.proxyHost",
                    "socksProxyHost"
            };
            for (String k : keys) {
                String val = System.getProperty(k);
                if (val != null && !val.trim().isEmpty()) {
                    return true;
                }
            }
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    ProxyInfo pi = cm.getDefaultProxy();
                    if (pi != null && pi.getHost() != null && !pi.getHost().trim().isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasUserAddedMitmCertificates() {
        try {
            File userCertsDir = new File("/data/misc/user/0/cacerts-added");
            if (userCertsDir.isDirectory()) {
                String[] files = userCertsDir.list();
                return files != null && files.length > 0;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String findInstalledSnifferPackage(Context context) {
        if (context == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            for (String pkg : SNIFFER_PACKAGES) {
                try {
                    if (pm.getPackageInfo(pkg, 0) != null) {
                        return pkg;
                    }
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isVpnTunInterfaceActive() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            for (NetworkInterface nif : Collections.list(interfaces)) {
                if (nif != null && nif.isUp()) {
                    String name = nif.getName();
                    if (name != null && (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap"))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
