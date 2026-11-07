package com.featurestic.box.utils.compat;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * Per-Slot Country / Region & SIM Carrier Spoofer (PUBG Korea / Japan / Singapore / India / Global).
 *
 * Spoofs SIM Country ISO, Network Country ISO, MCC/MNC Operator Numeric, and Carrier Name
 * across both ITelephonyManagerProxy binder hooks and native gsm.* SystemProperties so
 * regional games (like PUBG Mobile Korea / JP) and regional events unlock without a VPN.
 */
@Keep
public final class FeaturesticRegionSpoofer {
    public static final int REGION_AUTO_DEFAULT = 0;
    public static final int REGION_KOREA_KR = 1;
    public static final int REGION_JAPAN_JP = 2;
    public static final int REGION_SINGAPORE_SG = 3;
    public static final int REGION_INDIA_IN = 4;
    public static final int REGION_GLOBAL_US = 5;

    private static volatile Integer sCachedRegion;

    private FeaturesticRegionSpoofer() {
    }

    private static File getRegionConfigFile() {
        return new File(BEnvironment.getSystemDir(), "featurestic_region_spoofer.mode");
    }

    public static int getCurrentRegion() {
        if (sCachedRegion != null) return sCachedRegion;
        try {
            File f = getRegionConfigFile();
            if (f.isFile() && f.length() > 0) {
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[16];
                    int n = fis.read(buf);
                    if (n > 0) {
                        int r = Integer.parseInt(new String(buf, 0, n, StandardCharsets.UTF_8).trim());
                        if (r >= REGION_AUTO_DEFAULT && r <= REGION_GLOBAL_US) {
                            sCachedRegion = r;
                            return r;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sCachedRegion = REGION_AUTO_DEFAULT;
        return REGION_AUTO_DEFAULT;
    }

    public static void setRegion(int region) {
        int clamped = (region < REGION_AUTO_DEFAULT || region > REGION_GLOBAL_US)
                ? REGION_AUTO_DEFAULT : region;
        sCachedRegion = clamped;
        try {
            File f = getRegionConfigFile();
            FileUtils.mkdirs(f.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(String.valueOf(clamped).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        applyNativeSimProperties();
    }

    public static int cycleNextRegion() {
        int next = (getCurrentRegion() + 1) % 6;
        setRegion(next);
        return next;
    }

    public static boolean isRegionSpoofActive() {
        return getCurrentRegion() != REGION_AUTO_DEFAULT;
    }

    public static String getRegionDisplayLabel(int region) {
        switch (region) {
            case REGION_KOREA_KR:
                return "Korea (KR • SK Telecom — PUBG KR)";
            case REGION_JAPAN_JP:
                return "Japan (JP • NTT Docomo)";
            case REGION_SINGAPORE_SG:
                return "Singapore (SG • Singtel Asia)";
            case REGION_INDIA_IN:
                return "India (IN • Jio True 5G)";
            case REGION_GLOBAL_US:
                return "Global / USA (US • T-Mobile)";
            case REGION_AUTO_DEFAULT:
            default:
                return "Auto (Host SIM Default)";
        }
    }

    public static String getSpoofedCountryIso() {
        switch (getCurrentRegion()) {
            case REGION_KOREA_KR:
                return "kr";
            case REGION_JAPAN_JP:
                return "jp";
            case REGION_SINGAPORE_SG:
                return "sg";
            case REGION_INDIA_IN:
                return "in";
            case REGION_GLOBAL_US:
                return "us";
            default:
                return null;
        }
    }

    public static String getSpoofedMccMnc() {
        switch (getCurrentRegion()) {
            case REGION_KOREA_KR:
                return "45005"; // SK Telecom South Korea
            case REGION_JAPAN_JP:
                return "44010"; // NTT Docomo Japan
            case REGION_SINGAPORE_SG:
                return "52501"; // Singtel Singapore
            case REGION_INDIA_IN:
                return "405874"; // Jio India
            case REGION_GLOBAL_US:
                return "310260"; // T-Mobile USA
            default:
                return null;
        }
    }

    public static String getSpoofedCarrierName() {
        switch (getCurrentRegion()) {
            case REGION_KOREA_KR:
                return "SKTelecom";
            case REGION_JAPAN_JP:
                return "NTT DOCOMO";
            case REGION_SINGAPORE_SG:
                return "Singtel";
            case REGION_INDIA_IN:
                return "Jio 5G";
            case REGION_GLOBAL_US:
                return "T-Mobile";
            default:
                return null;
        }
    }

    public static void applyNativeSimProperties() {
        String iso = getSpoofedCountryIso();
        String mccmnc = getSpoofedMccMnc();
        String carrier = getSpoofedCarrierName();
        if (iso == null || mccmnc == null || carrier == null) return;
        try {
            RNative.setNativeProp("gsm.sim.operator.iso-country", iso);
            RNative.setNativeProp("gsm.operator.iso-country", iso);
            RNative.setNativeProp("gsm.sim.operator.numeric", mccmnc);
            RNative.setNativeProp("gsm.operator.numeric", mccmnc);
            RNative.setNativeProp("gsm.sim.operator.alpha", carrier);
            RNative.setNativeProp("gsm.operator.alpha", carrier);
        } catch (Throwable ignored) {
        }
    }
}
