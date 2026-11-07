package net_62v.external;

import android.MetaCore.RemoteManager;
import org.lsposed.lsparanoid.Obfuscate;
import com.featurestic.box.core.RNative;
import com.featurestic.box.utils.Md5Utils;

@Obfuscate
public class MetaActivationManager {

    /* ================= ACTIVATE SDK ================= */
    public static void activateSdk(final String userkey) {
        try {
            RemoteManager.getInstance().activateSdk(userkey);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /* ================= GET SERVER MESSAGE ================= */
    public static String getServerMessage() {
        try {
            return RemoteManager.getInstance().getServerMessage();
        } catch (Throwable e) {
            e.printStackTrace();
            return "ERROR: FAILED TO GET SERVER MESSAGE";
        }
    }

    /* ================= CHECK SDK STATUS ================= */
    public static boolean getActivatedStatus() {
        try {
            return RemoteManager.getInstance().getActivatedSdk();
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /* ================= RESET VIRTUAL DEVICE IDENTITY ================= */
    public static String resetVirtualDeviceIdentity() {
        try {
            return Md5Utils.resetVirtualDeviceIdentity();
        } catch (Throwable e) {
            return "";
        }
    }

    /* ================= LOAD CUSTOM NATIVE LIBRARY ================= */
    public static boolean loadCustomLibrary(final String pathOrName) {
        try {
            return RNative.loadCustomLibrary(pathOrName);
        } catch (Throwable e) {
            return false;
        }
    }
}
