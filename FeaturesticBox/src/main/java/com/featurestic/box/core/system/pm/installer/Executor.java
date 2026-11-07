package com.featurestic.box.core.system.pm.installer;

import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;

public interface Executor {
    public static final String TAG = "InstallExecutor";

    int exec(BPackageSettings bPackageSettings, InstallOption installOption, int i);
}
