package com.featurestic.box.core.system.pm.installer;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.FileUtils;

public class RemoveAppExecutor implements Executor {
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        FileUtils.deleteDir(BEnvironment.getAppDir(ps.pkg.packageName));
        return 0;
    }
}
