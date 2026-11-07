package com.vcore;

import androidx.annotation.Keep;
import com.featurestic.box.FeaturesticBoxCore;

/**
 * Drop-in compatibility alias for Loaders importing com.vcore.BlackBoxCore.
 */
@Keep
public class BlackBoxCore extends FeaturesticBoxCore {
    public static FeaturesticBoxCore get() {
        return FeaturesticBoxCore.get();
    }
}
