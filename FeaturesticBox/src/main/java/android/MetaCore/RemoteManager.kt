package android.MetaCore

import android.content.Context
import org.lsposed.lsparanoid.Obfuscate
import com.featurestic.box.FeaturesticBoxCore
import com.featurestic.box.core.env.BEnvironment
import java.io.File

@Obfuscate
class RemoteManager private constructor() : IRemoteManager.Stub() {

    companion object {
        @JvmField
        val JUNIT_JAR = File(BEnvironment.getCacheDir(), "junit.apk")

        @JvmField
        val EMPTY_JAR = File(BEnvironment.getCacheDir(), "empty.apk")

        @Volatile
        private var instance: RemoteManager? = null

        @JvmField
        @Volatile
        var sEnableDaemonService: Boolean = true

        @JvmField
        @Volatile
        var sHideRoot: Boolean = true

        @JvmField
        @Volatile
        var sHideXposed: Boolean = true

        @Volatile
        private var activationInProgress: Boolean = false

        @Volatile
        private var lastActivationSucceeded: Boolean = true

        @JvmStatic
        fun getInstance(): RemoteManager {
            return instance ?: synchronized(this) {
                instance ?: RemoteManager().also { instance = it }
            }
        }
    }

    override fun activateSdk(userkey: String?) {
        activationInProgress = false
        lastActivationSucceeded = true
        sEnableDaemonService = true
        sHideRoot = true
        sHideXposed = true
        nk.setHidden("online")
        nk.Msg = "✅ FeaturesticBox Activated (Keyless Testing Mode)"
        try {
            val ctx = FeaturesticBoxCore.getContext()
            ctx?.getSharedPreferences(nk.PREFERENCE_NAME, Context.MODE_PRIVATE)?.edit()
                ?.putBoolean("activated", true)
                ?.putString("expiry", "2099-12-31 23:59:59")
                ?.putLong("lease_expires_at", 4102444800L)
                ?.putString("server_status", "online")
                ?.apply()
        } catch (_: Throwable) {
        }
    }

    fun awaitActivation(timeoutMillis: Long): Boolean {
        activateSdk(null)
        return true
    }

    fun isActivationInProgress(): Boolean {
        return false
    }

    override fun getActivatedSdk(): Boolean {
        nk.Msg = "✅ SDK IS ACTIVATED"
        return true
    }

    override fun getServerMessage(): String {
        return nk.getServerMessage()
    }

    override fun getNetwork(): Boolean {
        nk.Msg = "✅ Network: Connected"
        return true
    }
}
