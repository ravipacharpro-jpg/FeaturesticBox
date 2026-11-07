package com.featurestic.box.core.system.pm.installer;

import java.io.File;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.FileUtils;

public class CreatePackageExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        File appDir = BEnvironment.getAppDir(ps.pkg.packageName);
        File baseApk = BEnvironment.getBaseApkDir(ps.pkg.packageName);
        boolean keepExistingAppDir = baseApk.isFile()
                && ps.pkg.baseCodePath != null
                && ps.pkg.baseCodePath.equals(baseApk.getAbsolutePath());
        if (!keepExistingAppDir) {
            FileUtils.deleteDir(appDir);
        }
        FileUtils.mkdirs(appDir);
        FileUtils.mkdirs(BEnvironment.getAppLibDir(ps.pkg.packageName));
        return 0;
    }
}
