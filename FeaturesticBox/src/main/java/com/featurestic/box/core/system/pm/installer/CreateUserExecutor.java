package com.featurestic.box.core.system.pm.installer;

import java.io.File;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.pm.BPackageSettings;
import com.featurestic.box.entity.pm.InstallOption;
import com.featurestic.box.utils.FileUtils;

public class CreateUserExecutor implements Executor {
    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        String packageName = ps.pkg.packageName;
        File dataDir = BEnvironment.getDataDir(packageName, userId);
        File deDataDir = BEnvironment.getDeDataDir(packageName, userId);
        FileUtils.mkdirs(dataDir);
        FileUtils.mkdirs(deDataDir);
        FileUtils.mkdirs(BEnvironment.getDataCacheDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataDatabasesDir(packageName, userId));
        FileUtils.mkdirs(BEnvironment.getDataFilesDir(packageName, userId));
        FileUtils.mkdirs(new File(dataDir, "shared_prefs"));
        FileUtils.mkdirs(new File(dataDir, "code_cache"));
        FileUtils.mkdirs(BEnvironment.getExternalDataDir(packageName));
        FileUtils.mkdirs(BEnvironment.getExternalDataCacheDir(packageName));
        FileUtils.mkdirs(BEnvironment.getExternalDataFilesDir(packageName));
        FileUtils.mkdirs(BEnvironment.getExternalObbDir(packageName));
        return 0;
    }
}
