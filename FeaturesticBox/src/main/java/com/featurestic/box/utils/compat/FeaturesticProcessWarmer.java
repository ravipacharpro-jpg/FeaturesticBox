package com.featurestic.box.utils.compat;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.system.ServiceManager;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.utils.provider.ProviderCall;

/**
 * 0.2s Instant Game Launch Pre-Warmer.
 *
 * Pre-warms the :featuresticbox_daemon server Binder channel, PackageManager/ActivityManager/UserManager
 * proxies, and DNS caches in a background thread as soon as the Host Loader starts so pressing
 * "Launch Game" opens the game immediately with zero cold-start Binder latency.
 */
@Obfuscate
public final class FeaturesticProcessWarmer {
    private static final AtomicBoolean sWarmed = new AtomicBoolean(false);
    private static final ExecutorService WARM_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Featurestic-PreWarmer");
        t.setDaemon(true);
        return t;
    });

    private FeaturesticProcessWarmer() {
    }

    public static void preWarmInBackground(final Context context) {
        if (!sWarmed.compareAndSet(false, true)) {
            return;
        }
        WARM_POOL.execute(() -> {
            try {
                // 1. Wake up :featuresticbox_daemon ContentProvider & core Binder services
                ProviderCall.callSafely(ProxyManifest.getBindProvider(), "VM", null, null);
                FeaturesticBoxCore.get().getService(ServiceManager.ACTIVITY_MANAGER);
                FeaturesticBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER);
                FeaturesticBoxCore.get().getService(ServiceManager.USER_MANAGER);
                FeaturesticBoxCore.get().getService(ServiceManager.STORAGE_MANAGER);
                FeaturesticBoxCore.getBPackageManager().getInstalledApplications(0, 0);
            } catch (Throwable ignored) {
            }
            try {
                // 2. Pre-warm DNS & Low-Latency Network routes
                NetworkLowLatencyOptimizer.initForProcess(context);
            } catch (Throwable ignored) {
            }
        });
    }
}
