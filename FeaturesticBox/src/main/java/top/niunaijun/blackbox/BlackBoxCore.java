package top.niunaijun.blackbox;

import androidx.annotation.Keep;
import com.featurestic.box.FeaturesticBoxCore;

/**
 * Drop-in compatibility alias for Loaders importing top.niunaijun.blackbox.BlackBoxCore.
 */
@Keep
public class BlackBoxCore extends FeaturesticBoxCore {
    private static final BlackBoxCore sInstance = new BlackBoxCore();

    public static FeaturesticBoxCore get() {
        return FeaturesticBoxCore.get();
    }

    public static BlackBoxCore getBlackBoxCore() {
        return sInstance;
    }
}
