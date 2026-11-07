package com.featurestic.box.utils.compat;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.fake.provider.FileProviderHandler;

/**
 * Converts virtual FileProvider / file:// URIs inside ACTION_SEND, ACTION_SEND_MULTIPLE,
 * and ACTION_CHOOSER intents into host-accessible FileProvider URIs so in-game screenshots
 * and shared files can be sent to WhatsApp, Discord, Telegram, and the Android Share Sheet.
 */
public final class ShareIntentCompat {
    private ShareIntentCompat() {
    }

    public static void prepareExternalShareIntent(Intent intent) {
        if (intent == null) return;
        try {
            Context context = BActivityThread.getApplication();
            if (context == null) return;
            rewriteShareIntent(context, intent, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void rewriteShareIntent(Context context, Intent intent, int depth) {
        if (intent == null || depth > 2) return;
        String action = intent.getAction();
        if (Intent.ACTION_CHOOSER.equals(action)) {
            Intent target = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (target != null) {
                rewriteShareIntent(context, target, depth + 1);
                intent.putExtra(Intent.EXTRA_INTENT, target);
            }
            return;
        }

        if (!Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)
                && !Intent.ACTION_VIEW.equals(action)) {
            return;
        }

        boolean convertedAny = false;
        Uri dataUri = intent.getData();
        if (dataUri != null && ("content".equalsIgnoreCase(dataUri.getScheme()) || "file".equalsIgnoreCase(dataUri.getScheme()))) {
            Uri converted = FileProviderHandler.convertFileUri(context, dataUri);
            if (converted != null) {
                intent.setDataAndType(converted, intent.getType());
                convertedAny = true;
            }
        }

        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                Uri converted = FileProviderHandler.convertFileUri(context, stream);
                if (converted != null) {
                    intent.putExtra(Intent.EXTRA_STREAM, converted);
                    intent.setClipData(ClipData.newRawUri("", converted));
                    convertedAny = true;
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null && !streams.isEmpty()) {
                ArrayList<Uri> updated = new ArrayList<>(streams.size());
                for (Uri u : streams) {
                    Uri c = FileProviderHandler.convertFileUri(context, u);
                    if (c != null) {
                        updated.add(c);
                        convertedAny = true;
                    } else {
                        updated.add(u);
                    }
                }
                if (convertedAny) {
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, updated);
                }
            }
        }

        if (convertedAny) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
}
