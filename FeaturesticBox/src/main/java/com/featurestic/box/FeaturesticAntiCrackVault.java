package com.featurestic.box;

import android.MetaCore.AdvancedPopupHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Process;
import android.util.Base64;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.featurestic.box.core.RNative;

/**
 * Multi-Layer Anti-Crack & Anti-Login-Bypass Security Fortress for FeaturesticBox.
 */
@Keep
public final class FeaturesticAntiCrackVault {
    public static final String EXTRA_LAUNCH_HMAC_PROOF = "_fbox_launch_hmac_proof_";
    public static final String EXTRA_LAUNCH_NONCE = "_fbox_launch_nonce_";

    private static final byte[] BOOT_SECRET = new byte[32];
    private static volatile boolean sRequireLoaderLoginGate = false;
    private static volatile String sLockedReleaseCertSha256 = "";
    private static volatile String sActiveSessionProof = "";
    private static volatile String sActiveUserTokenHash = "DEFAULT_DEV_SESSION";

    static {
        new SecureRandom().nextBytes(BOOT_SECRET);
        sActiveSessionProof = computeHmacHex("BOOT_INIT|" + Process.myPid(), sActiveUserTokenHash);
    }

    private FeaturesticAntiCrackVault() {
    }

    public static void lockReleaseSignatureSha256(String expectedSha256Hex) {
        if (expectedSha256Hex == null || expectedSha256Hex.trim().isEmpty()) {
            sLockedReleaseCertSha256 = "";
            return;
        }
        sLockedReleaseCertSha256 = expectedSha256Hex.trim().replace(":", "").toUpperCase();
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx != null && !sLockedReleaseCertSha256.isEmpty()) {
                RNative.lockHostSignature(ctx, sLockedReleaseCertSha256);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void requireLoaderLoginGate(boolean required) {
        sRequireLoaderLoginGate = required;
        if (required) {
            sActiveSessionProof = "";
            sActiveUserTokenHash = "";
            try {
                RNative.setNativeProp("debug.featurestic.login_gate", "1");
            } catch (Throwable ignored) {
            }
        } else {
            sActiveUserTokenHash = "DEFAULT_DEV_SESSION";
            sActiveSessionProof = computeHmacHex("BOOT_INIT|" + Process.myPid(), sActiveUserTokenHash);
            try {
                RNative.setNativeProp("debug.featurestic.login_gate", "0");
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean unlockLoaderLoginGate(Context context, String userKeyOrSessionToken) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (!verifyEnvironmentAndApkIntegrity(ctx)) {
            AdvancedPopupHelper.showAuto();
            return false;
        }
        String token = (userKeyOrSessionToken != null && !userKeyOrSessionToken.trim().isEmpty())
                ? userKeyOrSessionToken.trim() : "AUTHORIZED_USER";
        String certSha = getLiveApkSigningSha256(ctx);
        sActiveUserTokenHash = sha256Hex(token + "|" + certSha);
        sActiveSessionProof = computeHmacHex(
                "UNLOCKED|" + Process.myPid() + "|" + certSha, sActiveUserTokenHash);
        try {
            RNative.setNativeProp("debug.featurestic.login_gate", "UNLOCKED_" + sActiveSessionProof.substring(0, 16));
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean stampLaunchIntentProof(Context context, Intent intent, String targetPkg, int userId) {
        if (intent == null) return false;
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (!isSessionAuthorizedAndIntact(ctx)) {
            AdvancedPopupHelper.showAuto();
            return false;
        }
        String nonce = Long.toHexString(System.nanoTime()) + "_" + userId;
        String certSha = getLiveApkSigningSha256(ctx);
        String proof = computeHmacHex(
                nonce + "|" + (targetPkg != null ? targetPkg : "") + "|" + userId + "|" + certSha,
                sActiveSessionProof);
        intent.putExtra(EXTRA_LAUNCH_NONCE, nonce);
        intent.putExtra(EXTRA_LAUNCH_HMAC_PROOF, proof);
        return true;
    }

    public static boolean verifyLaunchIntentProof(Context context, Intent sourceIntent, String targetPkg, int userId) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (!isSessionAuthorizedAndIntact(ctx)) {
            AdvancedPopupHelper.showAuto();
            return false;
        }
        if (!sRequireLoaderLoginGate) {
            return true;
        }
        if (sourceIntent == null) {
            AdvancedPopupHelper.showAuto();
            return false;
        }
        String nonce = sourceIntent.getStringExtra(EXTRA_LAUNCH_NONCE);
        String proof = sourceIntent.getStringExtra(EXTRA_LAUNCH_HMAC_PROOF);
        if (nonce == null || proof == null || nonce.isEmpty() || proof.isEmpty()) {
            AdvancedPopupHelper.showAuto();
            return false;
        }
        String certSha = getLiveApkSigningSha256(ctx);
        String expected = computeHmacHex(
                nonce + "|" + (targetPkg != null ? targetPkg : "") + "|" + userId + "|" + certSha,
                sActiveSessionProof);
        boolean ok = MessageDigest.isEqual(
                proof.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            AdvancedPopupHelper.showAuto();
        }
        return ok;
    }

    public static boolean isSessionAuthorizedAndIntact(Context context) {
        Context ctx = context != null ? context : FeaturesticBoxCore.getContext();
        if (!verifyEnvironmentAndApkIntegrity(ctx)) {
            return false;
        }
        if (sRequireLoaderLoginGate) {
            if (sActiveSessionProof == null || sActiveSessionProof.isEmpty()
                    || sActiveUserTokenHash == null || sActiveUserTokenHash.isEmpty()) {
                return false;
            }
            String certSha = getLiveApkSigningSha256(ctx);
            String expectedProof = computeHmacHex(
                    "UNLOCKED|" + Process.myPid() + "|" + certSha, sActiveUserTokenHash);
            return MessageDigest.isEqual(
                    sActiveSessionProof.getBytes(StandardCharsets.UTF_8),
                    expectedProof.getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    public static boolean verifyEnvironmentAndApkIntegrity(Context context) {
        if (hasInjectedCrackOrHookLibrary()) {
            return false;
        }

        if (isSignatureKillerInCallStack()) {
            return false;
        }

        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                if (pm != null && Proxy.isProxyClass(pm.getClass())) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
            String liveSha = getLiveApkSigningSha256(context);
            if (!sLockedReleaseCertSha256.isEmpty()
                    && !sLockedReleaseCertSha256.equalsIgnoreCase(liveSha)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasInjectedCrackOrHookLibrary() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("frida-agent")
                        || lower.contains("frida-gadget")
                        || lower.contains("libdexprotector_dump")
                        || lower.contains("drizzle_dumper")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isSignatureKillerInCallStack() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            if (stack == null) return false;
            for (StackTraceElement el : stack) {
                if (el == null) continue;
                String cls = el.getClassName().toLowerCase();
                if (cls.contains("bin.mt.signature")
                        || cls.contains("cc.binmt.signature")
                        || cls.contains("np.manager.signature")
                        || cls.contains("com.android.killer")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    public static String getLiveApkSigningSha256(Context context) {
        if (context == null) return "HOST_UNAVAILABLE";
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo pi = pm.getPackageInfo(pkg, flags);
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
                sigs = pi.signingInfo.getApkContentsSigners();
            } else if (pi != null) {
                sigs = pi.signatures;
            }
            if (sigs != null && sigs.length > 0 && sigs[0] != null) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
                return bytesToHex(digest);
            }
        } catch (Throwable ignored) {
        }
        return "UNSIGNED_DEV";
    }

    private static String computeHmacHex(String data, String keySeed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = sha256Bytes((keySeed != null ? keySeed : "") + "|" + Base64.encodeToString(BOOT_SECRET, Base64.NO_WRAP));
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return bytesToHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            return sha256Hex(data + "|" + keySeed);
        }
    }

    private static byte[] sha256Bytes(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return new byte[32];
        }
    }

    private static String sha256Hex(String s) {
        return bytesToHex(sha256Bytes(s));
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
