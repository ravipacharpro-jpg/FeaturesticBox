package com.featurestic.box.core.system.pm.installer;

import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.FileUtils;

public class RemoveUserExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        FileUtils.deleteDir(BEnvironment.getDataDir(packageName, userId));
        FileUtils.deleteDir(BEnvironment.getDeDataDir(packageName, userId));
        return 0;
    }
}
