// IRequestPermissionsResult.aidl
package com.featurestic.box.core.system.am;

interface IRequestPermissionsResult {
    boolean onResult(int requestCode,in String[] permissions,in int[] grantResults);
}