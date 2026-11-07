package com.featurestic.box.utils.compat;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

public class ContentProviderCompat {
    private static final String TAG = "ContentProviderCompat";

    public static Bundle call(Context context, String authority, String method, String arg, Bundle extras) {
        try {
            Uri uri = Uri.parse("content://" + authority);
            return call(context, uri, method, arg, extras, 5);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Bundle call(Context context, Uri uri, String method, String arg, Bundle extras, int retryCount) {
        int attempts = Math.max(1, retryCount);
        for (int attempt = 0; attempt < 2; attempt++) {
            ContentProviderClient client = acquireContentProviderClientRetry(context, uri, attempts);
            try {
                if (client == null) {
                    return null;
                }
                return client.call(method, arg, extras);
            } catch (RemoteException e) {
                SystemClock.sleep(50L);
            } catch (Throwable e) {
                return null;
            } finally {
                releaseQuietly(client);
            }
        }
        return null;
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, Uri uri) {
        try {
            return context.getContentResolver().acquireUnstableContentProviderClient(uri);
        } catch (Throwable e) {
            return null;
        }
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, Uri uri, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, uri);
        if (client == null && retryCount > 0) {
            int maxAttempts = Math.max(retryCount * 3, 8);
            int retry = 0;
            while (retry < maxAttempts && client == null) {
                SystemClock.sleep(60L);
                retry++;
                client = acquireContentProviderClient(context, uri);
            }
        }
        return client;
    }

    public static ContentProviderClient acquireContentProviderClientRetry(Context context, String name, int retryCount) {
        ContentProviderClient client = acquireContentProviderClient(context, name);
        if (client == null && retryCount > 0) {
            int maxAttempts = Math.max(retryCount * 3, 8);
            int retry = 0;
            while (retry < maxAttempts && client == null) {
                SystemClock.sleep(60L);
                retry++;
                client = acquireContentProviderClient(context, name);
            }
        }
        return client;
    }

    private static ContentProviderClient acquireContentProviderClient(Context context, String name) {
        try {
            return context.getContentResolver().acquireUnstableContentProviderClient(name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void releaseQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                if (VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    client.close();
                } else {
                    client.release();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
