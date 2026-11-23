package com.featurestic.box.core.system.os;

import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.StorageVolume;

import java.io.File;
import java.lang.reflect.Field;

import black.android.os.storage.BRStorageManager;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.env.BEnvironment;
import com.featurestic.box.core.system.ISystemService;
import com.featurestic.box.core.system.user.BUserHandle;
import com.featurestic.box.fake.provider.FileProvider;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.utils.compat.BuildCompat;


public class BStorageManagerService extends IBStorageManagerService.Stub implements ISystemService {
    private static final BStorageManagerService sService = new BStorageManagerService();

    public static BStorageManagerService get() {
        return sService;
    }

    public BStorageManagerService() {
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags, int userId) throws RemoteException {
        if (BRStorageManager.get().getVolumeList(0, 0) == null) {
            return null;
        }
        try {
            StorageVolume[] storageVolumes = BRStorageManager.get().getVolumeList(BUserHandle.getUserId(Process.myUid()), 0);
            if (storageVolumes == null)
                return null;
            for (StorageVolume storageVolume : storageVolumes) {
                setStorageVolumePath(storageVolume, BEnvironment.getExternalStorageDirectory());
            }
            return storageVolumes;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static volatile Field sFieldPath = null;
    private static volatile Field sFieldInternalPath = null;
    private static volatile boolean sFieldLookedUp = false;

    private static void setStorageVolumePath(StorageVolume volume, File path) {
        if (volume == null || path == null) return;
        if (!sFieldLookedUp) {
            synchronized (BStorageManagerService.class) {
                if (!sFieldLookedUp) {
                    try {
                        sFieldPath = StorageVolume.class.getDeclaredField("mPath");
                        sFieldPath.setAccessible(true);
                    } catch (Throwable ignored) {}
                    if (BuildCompat.isPie()) {
                        try {
                            sFieldInternalPath = StorageVolume.class.getDeclaredField("mInternalPath");
                            sFieldInternalPath.setAccessible(true);
                        } catch (Throwable ignored) {}
                    }
                    sFieldLookedUp = true;
                }
            }
        }
        try {
            if (sFieldPath != null) {
                sFieldPath.set(volume, path);
            }
        } catch (Throwable ignored) {}
        try {
            if (sFieldInternalPath != null) {
                sFieldInternalPath.set(volume, path);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public Uri getUriForFile(String file) throws RemoteException {
        return FileProvider.getUriForFile(FeaturesticBoxCore.getContext(), ProxyManifest.getProxyFileProvider(), new File(file));
    }

    @Override
    public void systemReady() {

    }
}
