package android.MetaCore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.lsposed.lsparanoid.Obfuscate
import com.featurestic.box.FeaturesticBoxCore

@Obfuscate
class nk {

    companion object {
        @Volatile
        private var is_False: Boolean = true

        @JvmField
        @Volatile
        var Msg: String = "✅ FeaturesticBox Ready (Keyless Testing Mode)"

        const val PREFERENCE_NAME: String = "license_cache"

        @JvmStatic
        fun getActivatedSdk(): Boolean {
            is_False = true
            Msg = "✅ FeaturesticBox Ready (Keyless Testing Mode)"
            return true
        }

        @JvmStatic
        fun clearActivation(reason: String = "Testing mode active") {
            is_False = true
            Msg = "✅ FeaturesticBox Ready (Keyless Testing Mode)"
        }

        @JvmStatic
        fun getServerMessage(): String {
            return Msg
        }

        @JvmStatic
        fun ismsg(msg: String?) {
            if (msg == null) return
            val ctx = FeaturesticBoxCore.getContext() ?: return
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                }
            }
        }

        @JvmStatic
        fun setHidden(status: String?) {
            is_False = true
            Msg = "✅ FeaturesticBox Active"
        }

        @JvmStatic
        fun setHidden(value: Boolean) {
            is_False = true
            Msg = "✅ FeaturesticBox Active"
        }

        @JvmStatic
        fun GAH(): Boolean {
            return true
        }

        @JvmStatic
        fun getUrlHidden(): String {
            return ""
        }

        @JvmStatic
        fun 获取接口地址(): String {
            return ""
        }

        @JvmStatic
        fun isSystemApp(): Boolean {
            is_False = true
            Msg = "✅ FeaturesticBox Active"
            return true
        }

        @JvmStatic
        fun checkExpiryManually(): String {
            return "UNLIMITED (Testing Mode)"
        }

        @JvmStatic
        fun loadSavedStatus() {
            is_False = true
            Msg = "✅ FeaturesticBox Active"
        }
    }
}
