package com.featurestic.box.core.system;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.BundleCompat;

/**
 * Created by @FeaturesticBox on 3/31/21.
 */
public class SystemCallProvider extends ContentProvider {
    public static final String TAG = "SystemCallProvider";

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        if (context != null) {
            try {
                FeaturesticBoxCore.get().ensureInitialized(context);
            } catch (Throwable ignored) {
            }
        }
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        try {
            if (getContext() != null) {
                FeaturesticBoxCore.get().ensureInitialized(getContext());
            }
        } catch (Throwable ignored) {
        }
        return initSystem();
    }

    private boolean initSystem() {
        FeaturesticBoxSystem.getSystem().startup();
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        try {
            if (getContext() != null) {
                FeaturesticBoxCore.get().ensureInitialized(getContext());
            }
        } catch (Throwable ignored) {
        }
        Slog.d(TAG, "call: " + method + ", " + extras);
        if ("VM".equals(method)) {
            Bundle bundle = new Bundle();
            if (extras != null) {
                String name = extras.getString("_G_|_server_name_");
                BundleCompat.putBinder(bundle, "_G_|_server_", ServiceManager.getService(name));
            }
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
