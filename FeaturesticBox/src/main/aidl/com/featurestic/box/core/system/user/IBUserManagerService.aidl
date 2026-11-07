// IBUserManagerService.aidl
package com.featurestic.box.core.system.user;

// Declare any non-default types here with import statements
import com.featurestic.box.core.system.user.BUserInfo;
import java.util.List;


interface IBUserManagerService {
    BUserInfo getUserInfo(int userId);
    boolean exists(int userId);
    BUserInfo createUser(int userId);
    List<BUserInfo> getUsers();
    void deleteUser(int userId);
}
