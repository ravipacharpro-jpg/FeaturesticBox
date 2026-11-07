package com.featurestic.box.compat.auth;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.compat.oauth.TwitterNativeAuthBridgeActivity;
import com.featurestic.box.compat.oauth.TwitterOAuthSessionStore;
import com.featurestic.box.compat.oauth.VirtualOAuthBridgeActivity;
import com.featurestic.box.compat.oauth.VirtualOAuthRouter;
import com.featurestic.box.utils.compat.IntentRedirectCompat;

/**
 * Routes native sign-in activities and provider-owned IntentSenders to the real
 * provider app installed on the phone while keeping the activity-result target
 * inside the virtual process.
 *
 * Package/signature identity is never spoofed. Explicit provider intents are
 * accepted directly; implicit intents are resolved with Android's real package
 * manager and accepted only when the resolved app is on the trusted allowlist.
 * IntentSenders are accepted only when Android reports an allow-listed creator
 * package, so a cloned app cannot use this bridge for arbitrary external flows.
 *
 * Twitter/X OAuth is real-app-first. This includes the GCloud/IMSDK
 * `com.itop.twitterwrapper.TwitterWebActivity` observed in stock BGMI: when its
 * launch extras already contain a Twitter/X OAuth URL, the URL is extracted
 * without logging it and offered to the installed official Twitter/X app first.
 * A host callback trampoline preserves the guest's original Activity result for
 * supported legacy custom-scheme redirects. If safe app routing is unavailable,
 * the existing Auth Tab/web flow remains the compatibility fallback.
 *
 * No package identity, signatures, cookies, passwords, or tokens are spoofed.
 *
 * Android 16 compatibility note: provider UI is launched from the host-main
 * bridge rather than from the guest :pN process. A normal startActivityForResult
 * bridge remains attached to Android's original resultTo token, so the host
 * trampoline returns exactly one system-delivered result. Detached IntentSender
 * and Twitter custom-scheme callbacks use the private :pN provider relay.
 */
@Obfuscate
public final class ExternalAuthRouter {
    public static final String EXTRA_EXTERNAL_AUTH =
            "com.featurestic.box.auth.EXTERNAL_AUTH";
    public static final String EXTRA_GOOGLE_WEB_AUTH =
            "com.featurestic.box.auth.GOOGLE_WEB_AUTH";
    public static final String EXTRA_GOOGLE_SERVER_CLIENT_ID =
            "com.featurestic.box.auth.GOOGLE_SERVER_CLIENT_ID";
    public static final String EXTRA_BROWSER_AUTH =
            "com.featurestic.box.auth.BROWSER_AUTH";
    public static final String EXTRA_PROVIDER_INTENT =
            "com.featurestic.box.auth.PROVIDER_INTENT";
    public static final String EXTRA_PROVIDER_INTENT_SENDER =
            "com.featurestic.box.auth.PROVIDER_INTENT_SENDER";
    public static final String EXTRA_PROVIDER_FILL_IN_INTENT =
            "com.featurestic.box.auth.PROVIDER_FILL_IN_INTENT";
    public static final String EXTRA_PROVIDER_FLAGS_MASK =
            "com.featurestic.box.auth.PROVIDER_FLAGS_MASK";
    public static final String EXTRA_PROVIDER_FLAGS_VALUES =
            "com.featurestic.box.auth.PROVIDER_FLAGS_VALUES";
    public static final String EXTRA_PROVIDER_OPTIONS =
            "com.featurestic.box.auth.PROVIDER_OPTIONS";
    public static final String EXTRA_RESULT_BINDER =
            "com.featurestic.box.auth.RESULT_BINDER";
    public static final String EXTRA_RESULT_WHO =
            "com.featurestic.box.auth.RESULT_WHO";
    public static final String EXTRA_REQUEST_CODE =
            "com.featurestic.box.auth.REQUEST_CODE";
    public static final String EXTRA_VIRTUAL_PACKAGE =
            "com.featurestic.box.auth.VIRTUAL_PACKAGE";
    public static final String EXTRA_DIRECT_PROVIDER_DISPATCH =
            "com.featurestic.box.auth.DIRECT_PROVIDER_DISPATCH";
    public static final String EXTRA_BPID =
            "com.featurestic.box.auth.BPID";
    public static final String EXTRA_USER_ID =
            "com.featurestic.box.auth.USER_ID";
    public static final String EXTRA_RESULT_CODE =
            "com.featurestic.box.auth.RESULT_CODE";
    public static final String EXTRA_RESULT_DATA =
            "com.featurestic.box.auth.RESULT_DATA";
    public static final String EXTRA_RESULT_DELIVERED =
            "com.featurestic.box.auth.RESULT_DELIVERED";
    public static final String EXTRA_MANUAL_RESULT_RELAY =
            "com.featurestic.box.auth.MANUAL_RESULT_RELAY";

    public static final String METHOD_DELIVER_ACTIVITY_RESULT =
            "_Black_|_auth_activity_result_";

    private static final String GCLOUD_TWITTER_WEB_ACTIVITY =
            "com.itop.twitterwrapper.TwitterWebActivity";
    private static final String TWITTER_KIT_OAUTH_ACTIVITY =
            "com.twitter.sdk.android.core.identity.OAuthActivity";
    private static final String IMSDK_PROXY_ACTIVITY =
            "com.itop.imsdk.android.base.IMSDKProxyActivity";

    private static final Set<String> TRUSTED_PROVIDER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.play.games",
            "com.android.credentialmanager",
            "com.google.android.credentialmanager",
            "com.google.android.apps.authenticator2",
            "com.instagram.android",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.discord",
            "com.vkontakte.android",
            "com.vk.im",
            "jp.naver.line.android",
            "com.linecorp.linelite",
            "com.kakao.talk",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.garena.gas",
            "com.garena.gaslite",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "tv.twitch.android.app",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.valvesoftware.android.steam.community",
            "com.azure.authenticator",
            "com.microsoft.xboxone.smartglass",
            "com.microsoft.xboxone.smartglass.beta"
    ));

    private static final Set<String> TWITTER_PROVIDER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.x.android"
    ));

    private static final String[] TWITTER_NATIVE_PROVIDER_PACKAGES = new String[]{
            "com.twitter.android.lite"
    };

    private static final Set<String> GOOGLE_DIRECT_AUTH_PACKAGES = new HashSet<>(Arrays.asList(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.play.games",
            "com.android.credentialmanager",
            "com.google.android.credentialmanager",
            "com.google.android.apps.authenticator2"
    ));

    public static boolean isGoogleInteractiveSignInIntent(Intent intent) {
        if (intent == null) return false;
        // Never hijack the guest's own internal SignInHubActivity launch!
        // SignInHubActivity must launch inside :pN so its onCreate() and onActivityResult()
        // execute and save the GoogleSignInAccount into Storage!
        ComponentName comp = intent.getComponent();
        if (comp != null && comp.getClassName() != null
                && comp.getClassName().endsWith("SignInHubActivity")) {
            return false;
        }
        String pkg = comp != null ? comp.getPackageName() : intent.getPackage();
        if (!"com.google.android.gms".equals(pkg)) {
            return false;
        }
        String action = intent.getAction();
        if (action != null) {
            String lowerAct = action.toLowerCase(Locale.US);
            if (lowerAct.contains("google_sign_in")
                    || lowerAct.contains("gms.auth.api.signin")
                    || lowerAct.contains("choose_account")
                    || lowerAct.contains("gms.games.sign_in")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGoogleDirectAuthPackage(String pkg) {
        return pkg != null && GOOGLE_DIRECT_AUTH_PACKAGES.contains(pkg);
    }

    /**
     * Normalizes Google Sign-In / Play Games / AccountPicker intents inside the
     * guest :pN process where the guest APK ClassLoader (including SignInConfiguration)
     * is loaded. This pairs the configuration package with the host caller UID
     * without triggering BadParcelableException in the host process.
     */
    public static void normalizeGoogleAuthIntentInGuest(Intent intent) {
        if (intent == null) return;
        String virtualPkg = BActivityThread.getAppPackageName();
        String hostPkg = FeaturesticBoxCore.getHostPkg();
        if (virtualPkg == null || hostPkg == null || virtualPkg.equals(hostPkg)) {
            return;
        }
        try {
            if (BActivityThread.getApplication() != null
                    && BActivityThread.getApplication().getClassLoader() != null) {
                intent.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
            }
            if (intent.hasExtra("config")) {
                GoogleSignInTokenHelper.prepareOutboundSignInIntent(intent, virtualPkg, hostPkg);
            }
            String[] pkgKeys = new String[]{
                    "callingPackage",
                    "authPackage",
                    "clientPackageName",
                    "androidPackageName",
                    "package_name",
                    "com.google.android.gms.signin.service.realClientPackageName",
                    "com.google.android.gms.games.key.gamePackageName",
                    "com.google.android.gms.common.account.CHOOSE_ACCOUNT_PACKAGE"
            };
            for (String key : pkgKeys) {
                if (intent.hasExtra(key)) {
                    String val = intent.getStringExtra(key);
                    if (virtualPkg.equals(val)) {
                        intent.putExtra(key, hostPkg);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void normalizeParcelableStrings(
            Object obj, String virtualPkg, String hostPkg, int depth) {
        if (obj == null || depth > 3) return;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object val = field.get(obj);
                    if (val instanceof String && virtualPkg.equals(val)) {
                        field.set(obj, hostPkg);
                    } else if (val instanceof android.os.Parcelable
                            && val.getClass().getName().startsWith("com.google.android.gms.")) {
                        normalizeParcelableStrings(val, virtualPkg, hostPkg, depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private ExternalAuthRouter() {
    }

    public static boolean isDirectProviderDispatch(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, false);
    }

    public static void clearDirectProviderDispatch(Intent intent) {
        if (intent != null) {
            intent.removeExtra(EXTRA_DIRECT_PROVIDER_DISPATCH);
        }
    }

    public static boolean isTrustedProviderPackage(String packageName) {
        return packageName != null && TRUSTED_PROVIDER_PACKAGES.contains(packageName);
    }

    public static boolean isTwitterProviderPackage(String packageName) {
        return packageName != null && TWITTER_PROVIDER_PACKAGES.contains(packageName);
    }

    public static boolean isTrustedProviderIntent(Intent intent) {
        return trustedProviderPackage(intent) != null;
    }

    public static String getTrustedProviderPackage(Intent intent) {
        return trustedProviderPackage(intent);
    }

    public static boolean isTrustedProviderIntentSender(IntentSender sender) {
        if (sender == null) {
            return false;
        }
        try {
            return isTrustedProviderPackage(sender.getCreatorPackage());
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static IntentSender wrapIntentSender(Object target) {
        if (target == null) {
            return null;
        }
        if (target instanceof IntentSender) {
            return (IntentSender) target;
        }
        try {
            for (Constructor<?> constructor : IntentSender.class.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 1
                        || !"android.content.IIntentSender".equals(parameterTypes[0].getName())
                        || !parameterTypes[0].isInstance(target)) {
                    continue;
                }
                constructor.setAccessible(true);
                Object wrapped = constructor.newInstance(target);
                return wrapped instanceof IntentSender ? (IntentSender) wrapped : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Intent createResultBridgeIntent(
            Intent source,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        if (source == null || resultTo == null || requestCode < 0
                || virtualPackage == null || virtualPackage.trim().isEmpty()) {
            return null;
        }

        // Stock BGMI/GCloud and Twitter Kit sometimes start an explicit wrapper
        // Activity instead of dispatching the OAuth URL with ACTION_VIEW. If that
        // launch payload already carries a Twitter/X OAuth URL, convert only that
        // one URL to a real-provider ACTION_VIEW. Failure is intentionally
        // non-destructive: returning null below lets the original Activity
        // continue exactly as before.
        // Keep OAuthActivity / TwitterWebActivity inside the WebView (never launch XUrlInterpreterActivity
        // which opens the Twitter/X home feed on OAuth 1.0a URLs).
        if (isTwitterWebAuthIntent(source)) {
            return createTwitterWebResultBridgeIntent(
                    source, resultTo, resultWho, requestCode, virtualPackage);
        }

        if (isDiscordWebAuthIntent(source)) {
            String discordPkg = resolveInstalledDiscordPackage();
            if (discordPkg != null) {
                int bpid = BActivityThread.getAppPid();
                if (bpid >= 0 && bpid <= 24) {
                    Intent discordIntent = new Intent(Intent.ACTION_VIEW,
                            normalizeDiscordAppDeepLinkUri(source.getData()));
                    discordIntent.addCategory(Intent.CATEGORY_DEFAULT);
                    discordIntent.addCategory(Intent.CATEGORY_BROWSABLE);
                    discordIntent.setPackage(discordPkg);
                    discordIntent.putExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, true);
                    Intent bridge = createBaseBridge(
                            resultTo, resultWho, requestCode, virtualPackage, bpid);
                    bridge.putExtra(EXTRA_PROVIDER_INTENT, discordIntent);
                    return prepareBridgeForLaunch(bridge);
                }
            }
        }

        if (isGenericWebOAuthIntent(source)) {
            Intent webBridge = createTwitterWebResultBridgeIntent(
                    source, resultTo, resultWho, requestCode, virtualPackage);
            if (webBridge != null) {
                return webBridge;
            }
        }

        // Route Google Sign-In (GOOGLE_SIGN_IN / CHOOSE_ACCOUNT / Games Sign-In) directly to
        // our Built-In Google Web Login inside VirtualOAuthBridgeActivity so it works 100%
        // through Web without any GMS SHA-1 DEVELOPER_ERROR (10) and supports 1-tap New ID!
        if (isGoogleInteractiveSignInIntent(source)) {
            normalizeGoogleAuthIntentInGuest(source);
            int bpid = BActivityThread.getAppPid();
            if (bpid >= 0 && bpid <= 24) {
                Intent googleWebBridge = createBaseBridge(
                        resultTo, resultWho, requestCode, virtualPackage, bpid);
                googleWebBridge.putExtra(EXTRA_EXTERNAL_AUTH, false);
                googleWebBridge.putExtra(EXTRA_GOOGLE_WEB_AUTH, true);
                String serverClientId = GoogleSignInTokenHelper.getLastServerClientId();
                if (serverClientId != null) {
                    googleWebBridge.putExtra(EXTRA_GOOGLE_SERVER_CLIENT_ID, serverClientId);
                }
                return prepareBridgeForLaunch(googleWebBridge);
            }
        }

        String providerPackage = trustedProviderPackage(source);
        if (providerPackage == null) {
            return null;
        }
        if (isGoogleDirectAuthPackage(providerPackage)) {
            if (source.getComponent() == null && source.getPackage() == null) {
                source.setPackage(providerPackage);
            }
            normalizeGoogleAuthIntentInGuest(source);
            return null;
        }

        int bpid = BActivityThread.getAppPid();
        if (bpid < 0 || bpid > 24) {
            return null;
        }

        Intent providerIntent = new Intent(source);
        if (providerIntent.getComponent() == null && providerIntent.getPackage() == null) {
            providerIntent.setPackage(providerPackage);
        }
        providerIntent.putExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, true);

        Intent bridge = createBaseBridge(
                resultTo, resultWho, requestCode, virtualPackage, bpid);
        bridge.putExtra(EXTRA_PROVIDER_INTENT, providerIntent);
        bridge.addFlags(source.getFlags() & (
                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        return prepareBridgeForLaunch(bridge);
    }

    public static Intent createIntentSenderBridgeIntent(
            IntentSender sender,
            Intent fillInIntent,
            int flagsMask,
            int flagsValues,
            Bundle options,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        if (!isTrustedProviderIntentSender(sender)
                || resultTo == null
                || requestCode < 0
                || virtualPackage == null
                || virtualPackage.trim().isEmpty()) {
            return null;
        }

        int bpid = BActivityThread.getAppPid();
        if (bpid < 0 || bpid > 24) {
            return null;
        }

        Intent bridge = createBaseBridge(
                resultTo, resultWho, requestCode, virtualPackage, bpid);
        bridge.putExtra(EXTRA_MANUAL_RESULT_RELAY, true);
        bridge.putExtra(EXTRA_PROVIDER_INTENT_SENDER, sender);
        if (fillInIntent != null) {
            bridge.putExtra(EXTRA_PROVIDER_FILL_IN_INTENT, new Intent(fillInIntent));
        }
        bridge.putExtra(EXTRA_PROVIDER_FLAGS_MASK, flagsMask);
        bridge.putExtra(EXTRA_PROVIDER_FLAGS_VALUES, flagsValues);
        if (options != null) {
            bridge.putExtra(EXTRA_PROVIDER_OPTIONS, new Bundle(options));
        }
        bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return prepareBridgeForLaunch(bridge);
    }

    private static Intent createBaseBridge(
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage,
            int bpid) {
        Intent bridge = new Intent();
        bridge.setComponent(new ComponentName(
                FeaturesticBoxCore.getHostPkg(),
                VirtualOAuthBridgeActivity.class.getName()));
        bridge.putExtra(EXTRA_EXTERNAL_AUTH, true);
        bridge.putExtra(EXTRA_BPID, bpid);
        bridge.putExtra(EXTRA_USER_ID, BActivityThread.getUserId());
        putResultTarget(bridge, resultTo, resultWho, requestCode, virtualPackage);
        return bridge;
    }

    /**
     * Prefer the real Twitter/X application only when Android's real PackageManager
     * confirms that the installed provider advertises support for this exact OAuth
     * URL and the host can safely capture the guest's declared callback scheme.
     */
    private static Intent createTwitterNativeResultBridgeIntent(
            Intent source,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        Uri authUri = source == null ? null : source.getData();
        if (!isTrustedTwitterOAuthUri(authUri)) {
            return null;
        }

        int userId = BActivityThread.getUserId();
        Uri redirectUri = VirtualOAuthRouter.resolveTwitterRedirectUri(
                authUri, userId, virtualPackage);
        if (redirectUri == null
                || !TwitterOAuthSessionStore.isHostCaptureSupported(redirectUri)) {
            return null;
        }

        String providerPackage = resolveNativeTwitterProvider(source);
        if (providerPackage == null) {
            return null;
        }

        int bpid = BActivityThread.getAppPid();
        if (bpid < 0 || bpid > 24) {
            return null;
        }

        Intent providerIntent = new Intent(source);
        // The source may have been explicitly aimed at a browser. Resolve again
        // against the official provider without retaining that browser component.
        providerIntent.setComponent(null);
        providerIntent.setPackage(providerPackage);
        providerIntent.putExtra(EXTRA_DIRECT_PROVIDER_DISPATCH, true);

        Intent bridge = new Intent();
        bridge.setComponent(new ComponentName(
                FeaturesticBoxCore.getHostPkg(),
                TwitterNativeAuthBridgeActivity.class.getName()));
        bridge.putExtra(EXTRA_BPID, bpid);
        bridge.putExtra(EXTRA_USER_ID, userId);
        bridge.putExtra(EXTRA_PROVIDER_INTENT, providerIntent);
        bridge.putExtra(VirtualOAuthRouter.EXTRA_AUTH_URL, authUri.toString());
        bridge.putExtra(VirtualOAuthRouter.EXTRA_REDIRECT_URI, redirectUri.toString());
        putResultTarget(bridge, resultTo, resultWho, requestCode, virtualPackage);
        bridge.addFlags(source.getFlags() & (
                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        return prepareBridgeForLaunch(bridge);
    }

    public static Uri normalizeTwitterAppDeepLinkUri(Uri uri) {
        if (uri == null) return null;
        String host = uri.getHost();
        if (host != null && "api.twitter.com".equalsIgnoreCase(host)) {
            try {
                return uri.buildUpon().authority("twitter.com").build();
            } catch (Throwable ignored) {
            }
        }
        return uri;
    }

    public static String resolveInstalledTwitterPackage() {
        try {
            PackageManager pm = FeaturesticBoxCore.getContext().getPackageManager();
            for (String pkg : TWITTER_NATIVE_PROVIDER_PACKAGES) {
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (ai != null && ai.enabled) {
                        return pkg;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String resolveInstalledDiscordPackage() {
        try {
            PackageManager pm = FeaturesticBoxCore.getContext().getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo("com.discord", 0);
            if (ai != null && ai.enabled) {
                return "com.discord";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String resolveNativeTwitterProvider(Intent source) {
        if (source == null) return null;
        try {
            PackageManager packageManager = FeaturesticBoxCore.getContext().getPackageManager();
            Uri normalizedUri = normalizeTwitterAppDeepLinkUri(source.getData());
            for (String packageName : TWITTER_NATIVE_PROVIDER_PACKAGES) {
                Intent candidate = new Intent(source);
                if (normalizedUri != null) {
                    candidate.setData(normalizedUri);
                }
                candidate.setComponent(null);
                candidate.setPackage(packageName);
                ResolveInfo resolved = packageManager.resolveActivity(
                        candidate, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null || resolved.activityInfo == null) {
                    resolved = packageManager.resolveActivity(candidate, 0);
                }
                if (resolved != null && resolved.activityInfo != null
                        && packageName.equals(resolved.activityInfo.packageName)
                        && isTwitterProviderPackage(packageName)
                        && resolved.activityInfo.enabled
                        && resolved.activityInfo.exported) {
                    return packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return resolveInstalledTwitterPackage();
    }

    private static Intent extractEmbeddedTwitterAuthIntent(Intent source) {
        if (!isEmbeddedTwitterAuthActivity(source)) {
            return null;
        }

        Uri uri = findTwitterOAuthUri(source.getData(), source.getExtras(), 0);
        if (uri == null) {
            return null;
        }

        Intent auth = new Intent(Intent.ACTION_VIEW, uri);
        auth.addCategory(Intent.CATEGORY_BROWSABLE);
        auth.addCategory(Intent.CATEGORY_DEFAULT);
        auth.setFlags(source.getFlags());
        return auth;
    }

    private static boolean isEmbeddedTwitterAuthActivity(Intent source) {
        if (source == null) return false;
        ComponentName component = source.getComponent();
        return component != null
                && isEmbeddedTwitterAuthClass(component.getClassName());
    }

    private static boolean isEmbeddedTwitterAuthClass(String className) {
        return GCLOUD_TWITTER_WEB_ACTIVITY.equals(className)
                || TWITTER_KIT_OAUTH_ACTIVITY.equals(className)
                || IMSDK_PROXY_ACTIVITY.equals(className);
    }

    /**
     * Search only known Twitter auth wrapper launch payloads for an already
     * created Twitter/X OAuth URL. The value is never persisted or logged.
     * Recursion is intentionally shallow to avoid walking arbitrary app object
     * graphs.
     */
    private static Uri findTwitterOAuthUri(Uri direct, Bundle extras, int depth) {
        if (isTrustedTwitterOAuthUri(direct)) {
            return direct;
        }
        if (extras == null || depth > 3) {
            return null;
        }

        try {
            for (String key : extras.keySet()) {
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable ignored) {
                    continue;
                }

                Uri found = twitterOAuthUriFromValue(value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Uri twitterOAuthUriFromValue(Object value, int depth) {
        if (value == null || depth > 4) return null;

        if (value instanceof Uri) {
            Uri uri = (Uri) value;
            return isTrustedTwitterOAuthUri(uri) ? uri : null;
        }

        if (value instanceof CharSequence) {
            try {
                Uri uri = Uri.parse(value.toString().trim());
                return isTrustedTwitterOAuthUri(uri) ? uri : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        if (value instanceof Bundle) {
            return findTwitterOAuthUri(null, (Bundle) value, depth);
        }

        if (value instanceof Intent) {
            Intent nested = (Intent) value;
            return findTwitterOAuthUri(nested.getData(), nested.getExtras(), depth);
        }

        if (value instanceof String[]) {
            for (String item : (String[]) value) {
                Uri found = twitterOAuthUriFromValue(item, depth + 1);
                if (found != null) return found;
            }
        }

        if (value instanceof CharSequence[]) {
            for (CharSequence item : (CharSequence[]) value) {
                Uri found = twitterOAuthUriFromValue(item, depth + 1);
                if (found != null) return found;
            }
        }

        return null;
    }

    private static Intent createTwitterWebResultBridgeIntent(
            Intent source,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        try {
            Intent template = VirtualOAuthRouter.createBridgeIntent(
                    source, BActivityThread.getUserId(), virtualPackage);
            if (template == null) {
                return null;
            }

            int bpid = BActivityThread.getAppPid();
            if (bpid < 0 || bpid > 24) {
                return null;
            }

            String authUrl = template.getStringExtra(VirtualOAuthRouter.EXTRA_AUTH_URL);
            String redirectUri = template.getStringExtra(VirtualOAuthRouter.EXTRA_REDIRECT_URI);
            String authProvider = template.getStringExtra(VirtualOAuthRouter.EXTRA_AUTH_PROVIDER);
            int userId = template.getIntExtra(VirtualOAuthRouter.EXTRA_USER_ID, -1);
            if (authUrl == null || redirectUri == null || authProvider == null || userId < 0) {
                return null;
            }

            Intent bridge = new Intent();
            bridge.setComponent(new ComponentName(
                    FeaturesticBoxCore.getHostPkg(),
                    VirtualOAuthBridgeActivity.class.getName()));
            bridge.putExtra(EXTRA_BROWSER_AUTH, true);
            bridge.putExtra(EXTRA_BPID, bpid);
            bridge.putExtra(EXTRA_USER_ID, userId);
            bridge.putExtra(VirtualOAuthRouter.EXTRA_AUTH_URL, authUrl);
            bridge.putExtra(VirtualOAuthRouter.EXTRA_REDIRECT_URI, redirectUri);
            bridge.putExtra(VirtualOAuthRouter.EXTRA_AUTH_PROVIDER, authProvider);
            bridge.putExtra(VirtualOAuthRouter.EXTRA_VIRTUAL_PACKAGE, virtualPackage);
            bridge.putExtra(VirtualOAuthRouter.EXTRA_USER_ID, userId);
            putResultTarget(bridge, resultTo, resultWho, requestCode, virtualPackage);
            bridge.addFlags(source.getFlags() & (
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return prepareBridgeForLaunch(bridge);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Intent prepareBridgeForLaunch(Intent bridge) {
        IntentRedirectCompat.collectNestedIntentKeys(bridge);
        return bridge;
    }

    private static void putResultTarget(
            Intent bridge,
            IBinder resultTo,
            String resultWho,
            int requestCode,
            String virtualPackage) {
        bridge.putExtra(EXTRA_RESULT_WHO, resultWho);
        bridge.putExtra(EXTRA_REQUEST_CODE, requestCode);
        bridge.putExtra(EXTRA_VIRTUAL_PACKAGE, virtualPackage);
        Bundle binderBundle = new Bundle();
        binderBundle.putBinder(EXTRA_RESULT_BINDER, resultTo);
        bridge.putExtras(binderBundle);
    }

    private static boolean isTwitterWebAuthIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri uri = intent.getData();
        return isTwitterHttpsUri(uri);
    }

    private static boolean isDiscordWebAuthIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri uri = intent.getData();
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.US);
        return ("discord.com".equals(host) || "discordapp.com".equals(host)
                || host.endsWith(".discord.com") || host.endsWith(".discordapp.com"))
                && uri.getPath() != null && uri.getPath().contains("oauth2/authorize");
    }

    public static Uri normalizeDiscordAppDeepLinkUri(Uri uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (path != null && path.startsWith("/api/oauth2/authorize")) {
            try {
                return uri.buildUpon().path("/oauth2/authorize").build();
            } catch (Throwable ignored) {
            }
        }
        return uri;
    }

    private static boolean isGenericWebOAuthIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri uri = intent.getData();
        return VirtualOAuthRouter.isTrustedAuthUri(uri);
    }

    public static boolean isTrustedTwitterOAuthUri(Uri uri) {
        return isTwitterHttpsUri(uri) && isLikelyTwitterOAuthUri(uri);
    }

    private static boolean isTwitterHttpsUri(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        host = host == null ? "" : host.toLowerCase(Locale.US);
        return "twitter.com".equals(host)
                || "x.com".equals(host)
                || host.endsWith(".twitter.com")
                || host.endsWith(".x.com");
    }

    private static boolean isLikelyTwitterOAuthUri(Uri uri) {
        if (uri == null) return false;
        String path = uri.getPath();
        String query = uri.getQuery();
        path = path == null ? "" : path.toLowerCase(Locale.US);
        query = query == null ? "" : query.toLowerCase(Locale.US);
        return path.contains("oauth")
                || path.contains("authorize")
                || path.contains("authenticate")
                || query.contains("oauth_token=")
                || query.contains("client_id=");
    }

    private static String trustedProviderPackage(Intent intent) {
        if (intent == null) {
            return null;
        }

        ComponentName component = intent.getComponent();
        String explicitPackage = component != null
                ? component.getPackageName() : intent.getPackage();
        if (explicitPackage != null) {
            return isTrustedProviderPackage(explicitPackage) ? explicitPackage : null;
        }

        try {
            PackageManager packageManager = FeaturesticBoxCore.getContext().getPackageManager();
            ResolveInfo resolved = packageManager.resolveActivity(
                    new Intent(intent), PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved != null && resolved.activityInfo != null) {
                String resolvedPackage = resolved.activityInfo.packageName;
                if (isTrustedProviderPackage(resolvedPackage)) {
                    return resolvedPackage;
                }
            }
            List<ResolveInfo> candidates = packageManager.queryIntentActivities(
                    new Intent(intent), 0);
            if (candidates != null) {
                for (ResolveInfo info : candidates) {
                    if (info != null && info.activityInfo != null
                            && isTrustedProviderPackage(info.activityInfo.packageName)) {
                        return info.activityInfo.packageName;
                    }
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
