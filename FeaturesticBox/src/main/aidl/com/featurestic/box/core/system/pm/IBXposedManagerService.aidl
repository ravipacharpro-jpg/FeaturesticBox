// IBXposedManagerService.aidl

package com.featurestic.box.core.system.pm;

import java.util.List;
import com.featurestic.box.entity.pm.InstalledModule;

interface IBXposedManagerService {
    boolean isXPEnable();
    void setXPEnable(boolean enable);
    boolean isModuleEnable(String packageName);
    void setModuleEnable(String packageName, boolean enable);
    List<InstalledModule> getInstalledModules();
}