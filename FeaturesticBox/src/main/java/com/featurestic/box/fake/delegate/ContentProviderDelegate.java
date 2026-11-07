package com.featurestic.box.fake.delegate;

import android.content.ContentProviderClient;
import android.net.Uri;
import android.os.Build;
import android.os.IInterface;
import android.util.ArrayMap;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadProviderClientRecordP;
import black.android.app.BRIActivityManagerContentProviderHolder;
import black.android.content.BRContentProviderHolderOreo;
import black.android.providers.BRSettingsContentProviderHolder;
import black.android.providers.BRSettingsGlobal;
import black.android.providers.BRSettingsNameValueCache;
import black.android.providers.BRSettingsNameValueCacheOreo;
import black.android.providers.BRSettingsSecure;
import black.android.providers.BRSettingsSystem;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.GmsCore;
import com.featurestic.box.fake.service.context.providers.ContentProviderStub;
import com.featurestic.box.fake.service.context.providers.GmsDynamiteProviderStub;
import com.featurestic.box.fake.service.context.providers.SystemProviderStub;
import com.featurestic.box.utils.compat.BuildCompat;

/** Created by @FeaturesticBox on 3/31/21. */
public class ContentProviderDelegate {
    public static final String TAG = "ContentProviderDelegate";
    private static final Set<String> sInjected = new HashSet<>();

    // Keep one stable reference for the process lifetime so ActivityThread keeps
    // the wrapped GMS chimera provider cached after we install the proxy.
    private static volatile ContentProviderClient sGmsDynamiteClient;
    private static volatile ContentProviderClient sGsfGservicesClient;

    public static void update(Object holder, String auth) {
        IInterface iInterface;
        if (BuildCompat.isOreo()) {
            iInterface = BRContentProviderHolderOreo.get(holder).provider();
        } else {
            iInterface = BRIActivityManagerContentProviderHolder.get(holder).provider();
        }

        if (iInterface == null || iInterface instanceof Proxy) {
            return;
        }
        IInterface bContentProvider;
        if (GmsCore.GMS_DYNAMITE_AUTHORITY.equals(auth)) {
            bContentProvider = new GmsDynamiteProviderStub()
                    .wrapper(iInterface, FeaturesticBoxCore.getHostPkg());
        } else if ("settings".equals(auth)) {
            bContentProvider = new SystemProviderStub()
                    .wrapper(iInterface, FeaturesticBoxCore.getHostPkg());
        } else {
            bContentProvider = new ContentProviderStub()
                    .wrapper(iInterface, FeaturesticBoxCore.getHostPkg());
        }
        if (BuildCompat.isOreo()) {
            BRContentProviderHolderOreo.get(holder)._set_provider(bContentProvider);
        } else {
            BRIActivityManagerContentProviderHolder.get(holder)._set_provider(bContentProvider);
        }
    }

    public static void init() {
        clearSettingProvider();

        FeaturesticBoxCore.getContext().getContentResolver().call(
                Uri.parse("content://settings"), "", null, null);

        // Prime GMS Dynamite under the real host identity so the provider exists
        // in ActivityThread's cache and can be wrapped before the virtual
        // Application.onCreate/UE4 startup can explicitly request Analytics.
        if (Build.VERSION.SDK_INT >= 30 && sGmsDynamiteClient == null) {
            try {
                sGmsDynamiteClient = FeaturesticBoxCore.getContext()
                        .getContentResolver()
                        .acquireContentProviderClient(GmsCore.GMS_DYNAMITE_AUTHORITY);
            } catch (Throwable ignored) {
            }
            try {
                if (sGsfGservicesClient == null) {
                    sGsfGservicesClient = FeaturesticBoxCore.getContext()
                            .getContentResolver()
                            .acquireContentProviderClient("com.google.android.gsf.gservices");
                }
            } catch (Throwable ignored) {
            }
        }

        Object activityThread = FeaturesticBoxCore.mainThread();
        ArrayMap<Object, Object> map = (ArrayMap<Object, Object>)
                BRActivityThread.get(activityThread).mProviderMap();

        for (Object value : map.values()) {
            String[] mNames = BRActivityThreadProviderClientRecordP.get(value).mNames();
            if (mNames == null || mNames.length <= 0) {
                continue;
            }
            String providerName = mNames[0];
            final IInterface iInterface = BRActivityThreadProviderClientRecordP.get(value).mProvider();
            if (iInterface == null || iInterface instanceof Proxy) {
                continue;
            }
            sInjected.add(providerName);

            IInterface wrapper = GmsCore.GMS_DYNAMITE_AUTHORITY.equals(providerName)
                    ? new GmsDynamiteProviderStub().wrapper(iInterface, FeaturesticBoxCore.getHostPkg())
                    : ("settings".equals(providerName)
                            ? new SystemProviderStub().wrapper(iInterface, FeaturesticBoxCore.getHostPkg())
                            : new ContentProviderStub().wrapper(iInterface, FeaturesticBoxCore.getHostPkg()));
            BRActivityThreadProviderClientRecordP.get(value)._set_mProvider(wrapper);
            BRActivityThreadProviderClientRecordP.get(value)._set_mNames(new String[]{providerName});
        }
    }

    public static void clearSettingProvider() {
        Object cache;
        cache = BRSettingsSystem.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        cache = BRSettingsSecure.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        if (BRSettingsGlobal.getRealClass() != null) {
            cache = BRSettingsGlobal.get().sNameValueCache();
            if (cache != null) {
                clearContentProvider(cache);
            }
        }
    }

    private static void clearContentProvider(Object cache) {
        if (BuildCompat.isOreo()) {
            Object holder = BRSettingsNameValueCacheOreo.get(cache).mProviderHolder();
            if (holder != null) {
                BRSettingsContentProviderHolder.get(holder)._set_mContentProvider(null);
            }
        } else {
            BRSettingsNameValueCache.get(cache)._set_mContentProvider(null);
        }
    }
}
