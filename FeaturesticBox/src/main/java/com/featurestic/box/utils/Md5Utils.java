package com.featurestic.box.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;

public class Md5Utils {

    private static final char[] hexDigits = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
    private static final String DEVICE_ID_PREFS = "featuresticbox_virtual_device_identity";
    private static final String KEY_SALT = "virtual_device_salt";
    private static volatile String sCachedHostSeed;

    public static String md5(String input) {
        if (input == null)
            return null;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] inputByteArray = input.getBytes("utf-8");
            messageDigest.update(inputByteArray);
            byte[] resultByteArray = messageDigest.digest();
            return byteArrayToHex(resultByteArray);
        } catch (Exception e) {
            return null;
        }
    }

    public static String md5(File file) {
        try {
            if (!file.isFile()) {
                return null;
            }
            FileInputStream in = new FileInputStream(file);
            String result = md5(in);
            in.close();
            return result;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String md5(InputStream in) {
        try {
            MessageDigest messagedigest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[1024];
            int read = 0;
            while ((read = in.read(buffer)) != -1) {
                messagedigest.update(buffer, 0, read);
            }
            in.close();
            return byteArrayToHex(messagedigest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String byteArrayToHex(byte[] byteArray) {
        char[] resultCharArray = new char[byteArray.length * 2];
        int index = 0;
        for (byte b : byteArray) {
            resultCharArray[index++] = hexDigits[b >>> 4 & 0xf];
            resultCharArray[index++] = hexDigits[b & 0xf];
        }
        return new String(resultCharArray);
    }

    public static String resetVirtualDeviceIdentity() {
        int userId = 0;
        try {
            if (BActivityThread.isThreadInit()) {
                userId = BActivityThread.getUserId();
            }
        } catch (Throwable ignored) {
        }
        return resetVirtualDeviceIdentity(userId);
    }

    public static String resetVirtualDeviceIdentity(int userId) {
        String newSalt = UUID.randomUUID().toString();
        sCachedHostSeed = null;
        try {
            File saltFile = new File(BEnvironment.getSystemDir(), "identity_salt_u" + Math.max(0, userId) + ".conf");
            FileUtils.mkdirs(saltFile.getParentFile());
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(saltFile)) {
                fos.write(newSalt.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null) {
                SharedPreferences sp = ctx.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE);
                sp.edit().putString(KEY_SALT + "_" + Math.max(0, userId), newSalt).commit();
            }
        } catch (Throwable ignored) {
        }
        return fakeAndroidId(userId);
    }

    private static String readUserSalt(int userId) {
        try {
            File saltFile = new File(BEnvironment.getSystemDir(), "identity_salt_u" + Math.max(0, userId) + ".conf");
            if (saltFile.isFile() && saltFile.length() > 0 && saltFile.length() <= 256) {
                byte[] buf = new byte[(int) saltFile.length()];
                try (FileInputStream fis = new FileInputStream(saltFile)) {
                    int n = fis.read(buf);
                    if (n > 0) return new String(buf, 0, n, "UTF-8").trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String fakeAndroidId(int userId) {
        String userSalt = readUserSalt(userId);
        String seed = getDeviceSeed() + ":u=" + userId + ":s=" + userSalt;
        String h = md5(seed);
        return (h != null && h.length() >= 16) ? h.substring(0, 16) : "8f4a2c91e7b03d65";
    }

    private static String getDeviceSeed() {
        String hostSeed = sCachedHostSeed;
        if (hostSeed == null) {
            hostSeed = Build.BOARD + ":" + Build.BRAND + ":" + Build.DEVICE + ":" + Build.MODEL + ":" + Build.TIME;
            try {
                Context ctx = FeaturesticBoxCore.getContext();
                if (ctx != null) {
                    long installMarker = ctx.getFilesDir() != null ? ctx.getFilesDir().lastModified() : 0L;
                    SharedPreferences sp = ctx.getSharedPreferences(DEVICE_ID_PREFS, Context.MODE_PRIVATE);
                    String salt = sp.getString(KEY_SALT, "");
                    hostSeed = hostSeed + ":" + ctx.getPackageName() + ":" + FeaturesticBoxCore.getHostUid() + ":" + installMarker + ":" + salt;
                }
            } catch (Throwable ignored) {
            }
            sCachedHostSeed = hostSeed;
        }
        int userId = 0;
        String appPkg = "";
        try {
            if (BActivityThread.isThreadInit()) {
                userId = BActivityThread.getUserId();
                String p = BActivityThread.getAppPackageName();
                if (p != null) appPkg = p;
            }
        } catch (Throwable ignored) {
        }
        String userSalt = readUserSalt(userId);
        String hash = md5(hostSeed + ":" + userId + ":" + appPkg + ":" + userSalt);
        return (hash != null && hash.length() == 32) ? hash : "8f4a2c91e7b03d65a4129c8e7f6b5a10";
    }

    public static String fakeAndroidId() {
        return getDeviceSeed().substring(0, 16);
    }

    public static String fakeImei() {
        String hash = getDeviceSeed();
        StringBuilder sb = new StringBuilder(15);
        sb.append("86");
        for (int i = 0; i < 12; i++) {
            int digit = Character.digit(hash.charAt(i), 16) % 10;
            sb.append(digit);
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = sb.charAt(i) - '0';
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        int checkDigit = (10 - (sum % 10)) % 10;
        sb.append(checkDigit);
        return sb.toString();
    }

    public static String fakeImsi() {
        String hash = getDeviceSeed();
        StringBuilder sb = new StringBuilder(15);
        sb.append("40445");
        for (int i = 12; i < 22; i++) {
            int digit = Character.digit(hash.charAt(i), 16) % 10;
            sb.append(digit);
        }
        return sb.toString();
    }

    public static String fakeSerial() {
        return getDeviceSeed().substring(0, 12).toUpperCase(Locale.ROOT);
    }

    public static String fakeMacAddress() {
        String h = getDeviceSeed();
        return String.format(Locale.ROOT, "02:%s:%s:%s:%s:%s",
                h.substring(0, 2), h.substring(2, 4), h.substring(4, 6),
                h.substring(6, 8), h.substring(8, 10));
    }

    public static String fakeWifiSsid() {
        String h = getDeviceSeed().substring(10, 14).toUpperCase(Locale.ROOT);
        return "WLAN_5G_" + h;
    }

    public static String fakeGsfId() {
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        return fakeGsfId(userId);
    }

    public static String fakeGsfId(int userId) {
        String userSalt = readUserSalt(userId);
        String h = md5(getDeviceSeed() + ":gsf:u=" + userId + ":s=" + userSalt);
        return (h != null && h.length() >= 16) ? "3" + h.substring(1, 16) : "3a94c81e7b02d651";
    }

    public static String fakeAdvertisingId() {
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        return fakeAdvertisingId(userId);
    }

    public static String fakeAdvertisingId(int userId) {
        String userSalt = readUserSalt(userId);
        String h = md5(getDeviceSeed() + ":adid:u=" + userId + ":s=" + userSalt);
        if (h == null || h.length() < 32) {
            return "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        }
        return h.substring(0, 8) + "-"
                + h.substring(8, 12) + "-4"
                + h.substring(13, 16) + "-a"
                + h.substring(17, 20) + "-"
                + h.substring(20, 32);
    }

    public static String fakeMediaDrmId() {
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        return fakeMediaDrmId(userId);
    }

    public static String fakeMediaDrmId(int userId) {
        String userSalt = readUserSalt(userId);
        String h = md5(getDeviceSeed() + ":drm:u=" + userId + ":s=" + userSalt);
        return (h != null && h.length() == 32) ? h : "9b2e4c71a8f03d65e1429c8a7f6b5e21";
    }

    public static String fakeBootId() {
        int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
        return fakeBootId(userId);
    }

    public static String fakeBootId(int userId) {
        String userSalt = readUserSalt(userId);
        String h = md5(getDeviceSeed() + ":boot:u=" + userId + ":s=" + userSalt);
        if (h == null || h.length() < 32) {
            return "7c9e6679-7425-40de-944b-e07fc1f90ae7";
        }
        return h.substring(0, 8) + "-"
                + h.substring(8, 12) + "-4"
                + h.substring(13, 16) + "-9"
                + h.substring(17, 20) + "-"
                + h.substring(20, 32);
    }
}
