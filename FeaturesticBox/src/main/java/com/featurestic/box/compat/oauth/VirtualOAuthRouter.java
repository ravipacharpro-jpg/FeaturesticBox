package com.featurestic.box.compat.oauth;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.fake.frameworks.BPackageManager;
import com.featurestic.box.utils.FileUtils;

/**
 * Detects browser based OAuth launches made by a virtual/cloned application and
 * reroutes only the authentication browser step through an Android Auth Tab.
 *
 * The SDK never reads provider credentials, cookies, passwords, or access tokens.
 * It only carries the final redirect URI back to the virtual package that declared
 * the redirect intent-filter.
 */
@Obfuscate
public final class VirtualOAuthRouter {
    public static final String EXTRA_AUTH_URL =
            "com.featurestic.box.oauth.AUTH_URL";
    public static final String EXTRA_REDIRECT_URI =
            "com.featurestic.box.oauth.REDIRECT_URI";
    public static final String EXTRA_VIRTUAL_PACKAGE =
            "com.featurestic.box.oauth.VIRTUAL_PACKAGE";
    public static final String EXTRA_USER_ID =
            "com.featurestic.box.oauth.USER_ID";
    public static final String EXTRA_AUTH_PROVIDER =
            "com.featurestic.box.oauth.AUTH_PROVIDER";

    private static final Set<String> AUTH_HOSTS = new HashSet<>(Arrays.asList(
            "accounts.google.com",
            "oauth2.googleapis.com",
            "www.facebook.com",
            "m.facebook.com",
            "web.facebook.com",
            "facebook.com",
            "fb.com",
            "instagram.com",
            "www.instagram.com",
            "api.instagram.com",
            "x.com",
            "www.x.com",
            "mobile.x.com",
            "api.x.com",
            "oauth.x.com",
            "twitter.com",
            "www.twitter.com",
            "mobile.twitter.com",
            "api.twitter.com",
            "oauth.twitter.com",
            "whatsapp.com",
            "www.whatsapp.com",
            "api.whatsapp.com",
            "web.whatsapp.com",
            "wa.me",
            "otpless.com",
            "auth.otpless.app",
            "discord.com",
            "www.discord.com",
            "ptb.discord.com",
            "canary.discord.com",
            "discordapp.com",
            "oauth.vk.com",
            "id.vk.com",
            "vk.com",
            "m.vk.com",
            "login.vk.com",
            "access.line.me",
            "line.me",
            "kauth.kakao.com",
            "accounts.kakao.com",
            "logins.daum.net",
            "oauth.telegram.org",
            "telegram.org",
            "appleid.apple.com",
            "idmsa.apple.com",
            "login.live.com",
            "login.microsoftonline.com",
            "sisu.xboxlive.com",
            "user.auth.xboxlive.com",
            "auth.garena.com",
            "sso.garena.com",
            "connect.garena.com",
            "account.garena.com",
            "www.tiktok.com",
            "open-api.tiktok.com",
            "open.tiktokapis.com",
            "id.twitch.tv",
            "www.twitch.tv",
            "www.reddit.com",
            "old.reddit.com",
            "steamcommunity.com",
            "store.steampowered.com",
            "auth.riotgames.com",
            "login.riotgames.com",
            "www.epicgames.com",
            "id.sonyentertainmentnetwork.com",
            "ca.account.sony.com",
            "account.hoyoverse.com",
            "user.mihoyo.com",
            "accounts.krafton.com",
            "github.com",
            "api.login.yahoo.com",
            "open.weixin.qq.com",
            "graph.qq.com",
            "xui.ptlogin2.qq.com"
    ));

    private static final String[] REDIRECT_QUERY_KEYS = {
            "redirect_uri",
            "redirect_url",
            "callback_uri",
            "callback_url",
            "oauth_callback",
            "redirect",
            "callback",
            "return_to",
            "returnUrl",
            "app_redirect",
            "deep_link_uri"
    };

    private VirtualOAuthRouter() {
    }

    /**
     * Returns an explicit host-side bridge intent when the supplied intent is a
     * supported OAuth browser launch whose custom redirect belongs to the current
     * virtual package. Returns null for every other intent.
     */
    public static Intent createBridgeIntent(Intent source, int userId, String virtualPackage) {
        if (source == null || virtualPackage == null || virtualPackage.trim().isEmpty()) {
            return null;
        }
        if (!Intent.ACTION_VIEW.equals(source.getAction())) {
            return null;
        }
        Uri authUri = source.getData();
        if (!isTrustedAuthUri(authUri)) {
            return null;
        }

        if (isDiscordHost(authUri)) {
            String discordPkg = com.featurestic.box.compat.auth.ExternalAuthRouter.resolveInstalledDiscordPackage();
            if (discordPkg != null) {
                Intent discordIntent = new Intent(Intent.ACTION_VIEW,
                        com.featurestic.box.compat.auth.ExternalAuthRouter.normalizeDiscordAppDeepLinkUri(authUri));
                discordIntent.addCategory(Intent.CATEGORY_DEFAULT);
                discordIntent.addCategory(Intent.CATEGORY_BROWSABLE);
                discordIntent.setPackage(discordPkg);
                discordIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                discordIntent.putExtra(com.featurestic.box.compat.auth.ExternalAuthRouter.EXTRA_DIRECT_PROVIDER_DISPATCH, true);
                return discordIntent;
            }
        }

        Uri redirectUri;
        if (isTwitterHost(authUri)) {
            redirectUri = resolveTwitterRedirectUri(authUri, userId, virtualPackage);
        } else {
            redirectUri = extractRedirectUri(authUri);
            if (!isSupportedCustomRedirect(redirectUri)) {
                Uri inferred = inferDeclaredRedirect(authUri, virtualPackage, userId);
                if (inferred != null) {
                    redirectUri = inferred;
                }
            }
            if (!isSupportedCustomRedirect(redirectUri)) {
                if (FacebookAuthHost.matches(authUri)) {
                    redirectUri = Uri.parse("fbconnect://cct." + virtualPackage);
                } else if (isDiscordHost(authUri)) {
                    redirectUri = Uri.parse("discord://callback");
                } else {
                    return null;
                }
            }
        }
        if (redirectUri == null) {
            return null;
        }

        String scheme = lower(redirectUri.getScheme());
        boolean hostCapturableScheme = "fbconnect".equals(scheme)
                || scheme.matches("^fb[0-9]+$")
                || TwitterOAuthSessionStore.isHostCaptureSupported(redirectUri);
        // Always use the controllable Embedded WebView so Facebook, Twitter/X, and Discord
        // work 100% through Web without key-hash errors and support 1-tap "New ID / Switch Account"!
        String authProvider = "webview";

        Intent bridge = new Intent();
        bridge.setComponent(new ComponentName(
                FeaturesticBoxCore.getHostPkg(),
                VirtualOAuthBridgeActivity.class.getName()));
        bridge.putExtra(EXTRA_AUTH_URL, authUri.toString());
        bridge.putExtra(EXTRA_REDIRECT_URI, redirectUri.toString());
        bridge.putExtra(EXTRA_VIRTUAL_PACKAGE, virtualPackage);
        bridge.putExtra(EXTRA_USER_ID, userId);
        bridge.putExtra(EXTRA_AUTH_PROVIDER, authProvider);
        bridge.addFlags(source.getFlags() & (
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        return bridge;
    }

    public static boolean isTrustedAuthUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = lower(uri.getScheme());
        if (!"https".equals(scheme)) {
            return false;
        }
        String host = lower(uri.getHost());
        if (host.isEmpty()) {
            return false;
        }
        if (AUTH_HOSTS.contains(host)) {
            return true;
        }
        if (host.endsWith(".facebook.com")
                || host.endsWith(".fb.com")
                || host.endsWith(".instagram.com")
                || host.endsWith(".google.com")
                || host.endsWith(".googleapis.com")
                || host.endsWith(".twitter.com")
                || host.endsWith(".x.com")
                || host.endsWith(".whatsapp.com")
                || host.endsWith(".wa.me")
                || host.endsWith(".otpless.com")
                || host.endsWith(".otpless.app")
                || host.endsWith(".discord.com")
                || host.endsWith(".discordapp.com")
                || host.endsWith(".vk.com")
                || host.endsWith(".line.me")
                || host.endsWith(".kakao.com")
                || host.endsWith(".telegram.org")
                || host.endsWith(".apple.com")
                || host.endsWith(".live.com")
                || host.endsWith(".microsoftonline.com")
                || host.endsWith(".xboxlive.com")
                || host.endsWith(".garena.com")
                || host.endsWith(".tiktok.com")
                || host.endsWith(".tiktokapis.com")
                || host.endsWith(".twitch.tv")
                || host.endsWith(".reddit.com")
                || host.endsWith(".steamcommunity.com")
                || host.endsWith(".steampowered.com")
                || host.endsWith(".riotgames.com")
                || host.endsWith(".epicgames.com")
                || host.endsWith(".sonyentertainmentnetwork.com")
                || host.endsWith(".hoyoverse.com")
                || host.endsWith(".mihoyo.com")
                || host.endsWith(".krafton.com")
                || host.endsWith(".github.com")
                || host.endsWith(".yahoo.com")
                || host.endsWith(".qq.com")
                || host.endsWith(".wechat.com")) {
            return true;
        }
        Uri customRedirect = extractRedirectUri(uri);
        return isSupportedCustomRedirect(customRedirect);
    }

    /**
     * Resolves the callback owned by the virtual app for a Twitter/X OAuth URL.
     * This is shared by browser Auth Tab and real Twitter/X application routing.
     */
    public static Uri resolveTwitterRedirectUri(
            Uri authUri, int userId, String virtualPackage) {
        if (!isTwitterHost(authUri)
                || virtualPackage == null || virtualPackage.trim().isEmpty()
                || userId < 0) {
            return null;
        }
        Uri redirectUri = extractRedirectUri(authUri);
        if (redirectUri == null || !isSupportedCustomRedirect(redirectUri)) {
            redirectUri = inferLegacyTwitterRedirect(virtualPackage, userId);
        }
        if (redirectUri == null || !isSupportedCustomRedirect(redirectUri)) {
            // Twitter Kit (OAuthActivity) and GCloud (TwitterWebActivity) in BGMI/PUBG/FreeFire
            // handle twittersdk://callback in Java without declaring a Manifest intent-filter.
            redirectUri = Uri.parse("twittersdk://callback");
        }
        if (TwitterOAuthSessionStore.isHostCaptureSupported(redirectUri)) {
            return redirectUri;
        }
        if (!redirectBelongsToVirtualPackage(redirectUri, virtualPackage, userId)) {
            return Uri.parse("twittersdk://callback");
        }
        return redirectUri;
    }

    private static boolean isDiscordHost(Uri uri) {
        if (uri == null) return false;
        String host = lower(uri.getHost());
        return "discord.com".equals(host)
                || "discordapp.com".equals(host)
                || host.endsWith(".discord.com")
                || host.endsWith(".discordapp.com");
    }

    private static boolean isTwitterHost(Uri uri) {
        if (uri == null) {
            return false;
        }
        String host = lower(uri.getHost());
        return "twitter.com".equals(host)
                || "x.com".equals(host)
                || host.endsWith(".twitter.com")
                || host.endsWith(".x.com");
    }

    private static Uri extractRedirectUri(Uri authUri) {
        for (String key : REDIRECT_QUERY_KEYS) {
            try {
                String value = authUri.getQueryParameter(key);
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }
                String trimmed = value.trim();
                Uri candidate = Uri.parse(trimmed);
                if (candidate.getScheme() != null) {
                    return candidate;
                }

                // Some OAuth clients pre-encode redirect_uri before URL building,
                // leaving one extra percent-encoding layer after query decoding.
                String decoded = Uri.decode(trimmed);
                if (!decoded.equals(trimmed)) {
                    candidate = Uri.parse(decoded);
                    if (candidate.getScheme() != null) {
                        return candidate;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * OAuth 1.0a authorize/authenticate URLs usually contain only oauth_token;
     * oauth_callback was supplied during the earlier request-token exchange.
     * Discover the callback from the cloned APK's own BROWSABLE intent-filters,
     * score Twitter/X-looking candidates, and validate the winner through the
     * virtual PackageManager before using it.
     */
    private static Uri inferDeclaredRedirect(Uri authUri, String virtualPackage, int userId) {
        try {
            List<Uri> candidates = DeclaredOAuthCallbackResolver.findCandidates(
                    virtualPackage, userId);
            String host = lower(authUri == null ? null : authUri.getHost());
            Uri best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Uri candidate : candidates) {
                if (!isSupportedCustomRedirect(candidate)
                        || !redirectBelongsToVirtualPackage(candidate, virtualPackage, userId)) {
                    continue;
                }
                String scheme = lower(candidate.getScheme());
                String full = lower(candidate.toString());
                int score = 10;
                if (host.contains("discord") && (scheme.contains("discord") || full.contains("discord"))) score += 200;
                if ((host.contains("whatsapp") || host.contains("wa.me") || host.contains("otpless"))
                        && (scheme.contains("whatsapp") || scheme.contains("otpless") || full.contains("whatsapp") || full.contains("otpless"))) score += 200;
                if (host.contains("vk.com") && (scheme.startsWith("vk") || full.contains("vk"))) score += 200;
                if (host.contains("line.me") && (scheme.contains("line") || full.contains("line"))) score += 200;
                if (host.contains("kakao") && (scheme.startsWith("kakao") || full.contains("kakao"))) score += 200;
                if (host.contains("telegram") && (scheme.contains("tg") || scheme.contains("telegram"))) score += 200;
                if (host.contains("garena") && (scheme.contains("garena") || scheme.startsWith("gop"))) score += 200;
                if (full.contains("oauth") || full.contains("callback") || full.contains("auth")) score += 40;
                if (virtualPackage != null && scheme.startsWith(lower(virtualPackage))) score += 50;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return bestScore >= 50 ? best : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Uri inferLegacyTwitterRedirect(String virtualPackage, int userId) {
        Uri declared = bestDeclaredTwitterRedirect(virtualPackage, userId);
        if (declared != null) {
            return declared;
        }

        Uri[] candidates = new Uri[]{
                Uri.parse("twittersdk://callback"),
                Uri.parse("twittersdk://authorize"),
                Uri.parse("twitterkit://callback"),
                Uri.parse("twitterkit://authorize"),
                Uri.parse("twitter://callback"),
                Uri.parse("twitter://authorize"),
                Uri.parse("twitterauth://callback"),
                Uri.parse("oauth-twitter://callback"),
                Uri.parse("xauth://callback"),
                Uri.parse("x://callback")
        };
        for (Uri candidate : candidates) {
            if (redirectBelongsToVirtualPackage(candidate, virtualPackage, userId)) {
                return candidate;
            }
        }
        return null;
    }

    private static Uri bestDeclaredTwitterRedirect(String virtualPackage, int userId) {
        try {
            List<Uri> candidates = DeclaredOAuthCallbackResolver.findCandidates(
                    virtualPackage, userId);
            Uri best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Uri candidate : candidates) {
                if (!isSupportedCustomRedirect(candidate)
                        || !redirectBelongsToVirtualPackage(
                        candidate, virtualPackage, userId)) {
                    continue;
                }
                int score = twitterRedirectScore(candidate, virtualPackage);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            // Require positive Twitter/OAuth evidence so a Facebook/Google callback
            // from the same app can never be accidentally selected.
            return bestScore >= 50 ? best : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int twitterRedirectScore(Uri candidate, String virtualPackage) {
        String full = lower(candidate == null ? null : candidate.toString());
        String scheme = lower(candidate == null ? null : candidate.getScheme());
        int score = 0;

        if (scheme.startsWith("twittersdk")) score += 220;
        if (scheme.startsWith("twitterkit")) score += 190;
        if (full.contains("twitter")) score += 130;
        if ("x".equals(scheme) || scheme.startsWith("x-") || scheme.startsWith("x.")) {
            score += 60;
        }
        if (full.contains("oauth")) score += 45;
        if (full.contains("callback")) score += 40;
        if (full.contains("authorize")) score += 25;
        if (virtualPackage != null
                && scheme.startsWith(lower(virtualPackage))) {
            score += 25;
        }

        if (scheme.startsWith("fb") || full.contains("facebook")) score -= 250;
        if (full.contains("google") || full.contains("googleusercontent")) score -= 250;
        return score;
    }

    private static boolean isSupportedCustomRedirect(Uri redirectUri) {
        if (redirectUri == null) {
            return false;
        }
        String scheme = lower(redirectUri.getScheme());
        if (scheme.isEmpty()
                || "http".equals(scheme)
                || "https".equals(scheme)
                || "file".equals(scheme)
                || "content".equals(scheme)
                || "javascript".equals(scheme)
                || "data".equals(scheme)
                || "intent".equals(scheme)) {
            return false;
        }
        return scheme.matches("^[a-z][a-z0-9+.-]{1,127}$");
    }

    private static boolean redirectBelongsToVirtualPackage(
            Uri redirectUri, String virtualPackage, int userId) {
        try {
            Intent callback = new Intent(Intent.ACTION_VIEW, redirectUri);
            callback.addCategory(Intent.CATEGORY_DEFAULT);
            callback.addCategory(Intent.CATEGORY_BROWSABLE);
            callback.setPackage(virtualPackage);
            ResolveInfo resolved = BPackageManager.get().resolveActivity(
                    callback,
                    FileUtils.FileMode.MODE_IWUSR,
                    null,
                    userId);
            return resolved != null
                    && resolved.activityInfo != null
                    && virtualPackage.equals(resolved.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
