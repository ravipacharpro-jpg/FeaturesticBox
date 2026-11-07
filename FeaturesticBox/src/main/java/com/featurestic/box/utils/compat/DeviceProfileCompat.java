package com.featurestic.box.utils.compat;

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Properties;

import com.featurestic.box.core.RNative;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.utils.FileUtils;

/**
 * 90 / 120 FPS Flagship Gaming Device Profile Unlocker.
 * Synchronizes both Java android.os.Build fields and C++ SystemProperties
 * (ro.product.model, ro.product.manufacturer, ro.product.brand, etc.) across
 * virtual game processes.
 */
public final class DeviceProfileCompat {
    public static final String PRESET_ROG_PHONE_8 = "ROG_PHONE_8";
    public static final String PRESET_REDMAGIC_9_PRO = "REDMAGIC_9_PRO";
    public static final String PRESET_S24_ULTRA = "S24_ULTRA";
    public static final String PRESET_ONEPLUS_12 = "ONEPLUS_12";
    public static final String PRESET_IQOO_12 = "IQOO_12";
    public static final String PRESET_XIAOMI_14_PRO = "XIAOMI_14_PRO";
    public static final String PRESET_GALAXY_TAB_S9_ULTRA = "TAB_S9_ULTRA";

    private DeviceProfileCompat() {
    }

    public static boolean setPreset(String presetName) {
        if (presetName == null) return false;
        String key = presetName.trim().toUpperCase(Locale.US);
        switch (key) {
            case PRESET_ROG_PHONE_8:
            case "ASUS_ROG_8":
                return setCustomProfile("asus", "asus", "ASUS_AI2401_A", "AI2401", "WW_AI2401", "pineapple");
            case PRESET_REDMAGIC_9_PRO:
                return setCustomProfile("ZTE", "nubia", "NX769J", "NX769J", "NX769J", "pineapple");
            case PRESET_S24_ULTRA:
            case "SAMSUNG_S24_ULTRA":
                return setCustomProfile("samsung", "samsung", "SM-S928B", "e3q", "e3qxeea", "pineapple");
            case PRESET_ONEPLUS_12:
                return setCustomProfile("OnePlus", "OnePlus", "CPH2581", "OP5929L1", "CPH2581", "pineapple");
            case PRESET_IQOO_12:
                return setCustomProfile("vivo", "iQOO", "V2307A", "PD2307", "PD2307", "pineapple");
            case PRESET_XIAOMI_14_PRO:
                return setCustomProfile("Xiaomi", "Xiaomi", "23116PN5BC", "shennong", "shennong", "pineapple");
            case PRESET_GALAXY_TAB_S9_ULTRA:
            case "IPAD_PRO":
                return setCustomProfile("samsung", "samsung", "SM-X910", "gts9u", "gts9uwifi", "kalama");
            default:
                return false;
        }
    }

    public static boolean setCustomProfile(
            String manufacturer, String brand, String model, String device, String product, String board) {
        if (model == null || model.trim().isEmpty()) return false;
        try {
            Properties props = new Properties();
            props.setProperty("manufacturer", manufacturer != null ? manufacturer : "asus");
            props.setProperty("brand", brand != null ? brand : "asus");
            props.setProperty("model", model.trim());
            props.setProperty("device", device != null ? device : "AI2401");
            props.setProperty("product", product != null ? product : "WW_AI2401");
            props.setProperty("board", board != null ? board : "pineapple");
            File conf = BEnvironment.getFakeDeviceConf();
            FileUtils.mkdirs(conf.getParentFile());
            try (FileOutputStream fos = new FileOutputStream(conf)) {
                props.store(fos, "FeaturesticBox Device Profile");
            }
            applyProps(props);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void applyActiveProfile() {
        try {
            File conf = BEnvironment.getFakeDeviceConf();
            if (conf != null && conf.isFile() && conf.length() > 0) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(conf)) {
                    props.load(fis);
                }
                applyProps(props);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyProps(Properties props) {
        if (props == null) return;
        String manufacturer = props.getProperty("manufacturer");
        String brand = props.getProperty("brand");
        String model = props.getProperty("model");
        String device = props.getProperty("device");
        String product = props.getProperty("product");
        String board = props.getProperty("board");

        setBuildField("MANUFACTURER", manufacturer);
        setBuildField("BRAND", brand);
        setBuildField("MODEL", model);
        setBuildField("DEVICE", device);
        setBuildField("PRODUCT", product);
        setBuildField("BOARD", board);

        try {
            if (manufacturer != null) {
                RNative.setNativeProp("ro.product.manufacturer", manufacturer);
                RNative.setNativeProp("ro.product.system.manufacturer", manufacturer);
                RNative.setNativeProp("ro.product.vendor.manufacturer", manufacturer);
            }
            if (brand != null) {
                RNative.setNativeProp("ro.product.brand", brand);
                RNative.setNativeProp("ro.product.system.brand", brand);
                RNative.setNativeProp("ro.product.vendor.brand", brand);
            }
            if (model != null) {
                RNative.setNativeProp("ro.product.model", model);
                RNative.setNativeProp("ro.product.system.model", model);
                RNative.setNativeProp("ro.product.vendor.model", model);
                RNative.setNativeProp("ro.product.odm.model", model);
            }
            if (device != null) {
                RNative.setNativeProp("ro.product.device", device);
                RNative.setNativeProp("ro.product.system.device", device);
                RNative.setNativeProp("ro.product.vendor.device", device);
            }
            if (product != null) {
                RNative.setNativeProp("ro.product.name", product);
                RNative.setNativeProp("ro.product.system.name", product);
            }
            if (board != null) {
                RNative.setNativeProp("ro.product.board", board);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void setBuildField(String fieldName, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            Field field = Build.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable ignored) {
        }
    }
}
