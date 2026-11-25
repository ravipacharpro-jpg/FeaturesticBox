package com.featurestic.box.compat.oauth;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.lsposed.lsparanoid.Obfuscate;

/**
 * Exported custom-scheme trampoline for callbacks produced by the real Twitter/X app.
 * Callback data is accepted only when it matches a short-lived native-auth session.
 */
@Obfuscate
public final class TwitterOAuthCallbackActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        Uri callback = intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                ? null : intent.getData();
        if (callback == null) {
            finish();
            return;
        }
        TwitterOAuthSessionStore.Claim claim = TwitterOAuthSessionStore.claim(callback);
        if (claim != null) {
            Intent result = new Intent(Intent.ACTION_VIEW, callback);
            result.addCategory(Intent.CATEGORY_DEFAULT);
            result.addCategory(Intent.CATEGORY_BROWSABLE);

            boolean delivered = TwitterOAuthSessionStore.deliver(claim, RESULT_OK, result);
            if (delivered) {
                TwitterOAuthSessionStore.complete(claim.generation);
            } else {
                TwitterOAuthSessionStore.release(claim.generation);
            }
        } else {
            // Direct relay to guest container when opened from Chrome CustomTab without a session
            try {
                String virtualPkg = com.featurestic.box.app.BActivityThread.getAppPackageName();
                if (virtualPkg == null || virtualPkg.trim().isEmpty()) {
                    if (com.featurestic.box.FeaturesticBoxCore.get().isInstalled("com.pubg.imobile", 0)) {
                        virtualPkg = "com.pubg.imobile";
                    } else {
                        java.util.List<android.content.pm.ApplicationInfo> apps =
                                com.featurestic.box.FeaturesticBoxCore.get().getInstalledApplications(0, 0);
                        if (apps != null) {
                            for (android.content.pm.ApplicationInfo app : apps) {
                                if (app != null && app.packageName != null
                                        && !com.featurestic.box.core.GmsCore.isGoogleAppOrService(app.packageName)) {
                                    virtualPkg = app.packageName;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (virtualPkg != null && !virtualPkg.trim().isEmpty()) {
                    boolean delivered = VirtualOAuthBridgeActivity.dispatchFacebookCallback(
                            virtualPkg, 0, callback);
                    if (!delivered) {
                        Intent forward = new Intent(Intent.ACTION_VIEW, callback);
                        forward.addCategory(Intent.CATEGORY_DEFAULT);
                        forward.addCategory(Intent.CATEGORY_BROWSABLE);
                        forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        forward.setComponent(new android.content.ComponentName(
                                virtualPkg,
                                "com.itop.imsdk.android.base.login.customtabs.ITOPCustomTabActivity"));
                        com.featurestic.box.FeaturesticBoxCore.getBActivityManager().startActivity(forward, 0);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        finish();
    }
}
