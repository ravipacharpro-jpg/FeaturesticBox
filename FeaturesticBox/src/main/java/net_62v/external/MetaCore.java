package net_62v.external;

import android.content.Context;
import androidx.annotation.Keep;
import com.featurestic.box.FeaturesticBoxCore;

/**
 * Drop-in compatibility bridge for Loaders built on net_62v.external.MetaCore.
 */
@Keep
public class MetaCore {

    public static void attachBaseContext(Context context) {
        FeaturesticBoxCore.get().ensureInitialized(context);
    }

    public static void onCreate(Context context) {
        FeaturesticBoxCore.get().ensureInitialized(context);
    }

    public static void init(Context context) {
        FeaturesticBoxCore.get().ensureInitialized(context);
    }
}
