package com.featurestic.box.utils.compat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.core.env.BEnvironment;

/**
 * Hardened WebView lifecycle, Chrome-compatible User-Agent sanitizer,
 * Twitter/X cross-domain (.x.com <-> .twitter.com) cookie synchronizer, &
 * 1-Tap "New ID / Switch Account" Web Session Cleaner for virtual apps.
 */
public final class WebViewCompat {
    private static final String[] LOCK_FILES = {
            "SingletonLock",
            "SingletonCookie",
            "SingletonSocket"
    };

    private static final AtomicLong LAST_COOKIE_FLUSH_MS = new AtomicLong(0L);
    private static final long COOKIE_FLUSH_DEBOUNCE_MS = 1_500L;
    private static final AtomicBoolean FORCE_FRESH_WEB_LOGIN = new AtomicBoolean(false);
    private static final AtomicBoolean FORCE_FRESH_GOOGLE_PICKER = new AtomicBoolean(false);

    private static final ExecutorService COOKIE_FLUSH_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Featurestic-CookieFlush");
                t.setDaemon(true);
                return t;
            });

    private WebViewCompat() {
    }

    /**
     * Removes Android embedded WebView tokens ("; wv" and "Version/4.0 ") so
     * OAuth providers (Facebook WebDialog, Google, Discord, Twitter) treat the
     * WebView as standard Chrome Mobile instead of blocking with disallowed_useragent.
     */
    public static String buildChromeCompatibleUserAgent(String rawUa) {
        if (rawUa == null || rawUa.trim().isEmpty()) {
            return "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL
                    + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
        }
        return rawUa
                .replace("; wv)", ")")
                .replace("; wv;", ";")
                .replace("Version/4.0 ", "");
    }

    public static void setForceFreshWebLoginNextTime(boolean force) {
        FORCE_FRESH_WEB_LOGIN.set(force);
        if (force) {
            FORCE_FRESH_GOOGLE_PICKER.set(true);
        }
    }

    public static boolean consumeForceFreshWebLogin() {
        return FORCE_FRESH_WEB_LOGIN.getAndSet(false);
    }

    public static boolean isForceFreshWebLoginArmed() {
        return FORCE_FRESH_WEB_LOGIN.get();
    }

    public static boolean consumeForceFreshGoogleAccountPicker() {
        return FORCE_FRESH_GOOGLE_PICKER.getAndSet(false);
    }

    /**
     * Synchronizes authentication cookies between https://x.com and https://twitter.com
     * (and https://api.twitter.com).
     *
     * Why this is critical:
     * Modern Twitter login runs on https://x.com/i/flow/login and sets auth_token, ct0,
     * twid, and kdt on domain .x.com. However, legacy OAuth 1.0a games (BGMI/PUBG/FreeFire)
     * load https://api.twitter.com/oauth/authorize?oauth_token=... which checks cookies
     * on .twitter.com. Without syncing cookies between .x.com and .twitter.com, x.com
     * drops the user onto https://x.com/home instead of the Authorize App screen!
     */
    public static void syncTwitterAndXCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return;
            cm.setAcceptCookie(true);

            String xCookies = cm.getCookie("https://x.com");
            String twCookies = cm.getCookie("https://twitter.com");

            if (xCookies != null && !xCookies.isEmpty()) {
                mirrorCookieHeader(cm, xCookies, "https://twitter.com", ".twitter.com");
                mirrorCookieHeader(cm, xCookies, "https://api.twitter.com", ".twitter.com");
            }
            if (twCookies != null && !twCookies.isEmpty()) {
                mirrorCookieHeader(cm, twCookies, "https://x.com", ".x.com");
            }
            cm.flush();
        } catch (Throwable ignored) {
        }
    }

    private static void mirrorCookieHeader(
            CookieManager cm, String rawCookieHeader, String targetUrl, String targetDomain) {
        String[] pairs = rawCookieHeader.split(";");
        for (String rawPair : pairs) {
            if (rawPair == null) continue;
            String pair = rawPair.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty() || value.isEmpty()) continue;
            cm.setCookie(targetUrl,
                    name + "=" + value + "; Domain=" + targetDomain + "; Path=/; Secure");
        }
    }

    /**
     * Appends provider-specific parameters that force the OAuth web page to show
     * the account login / account switcher form instead of auto-continuing with an old ID.
     */
    public static String appendForceLoginParam(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return rawUrl;
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            Uri.Builder builder = uri.buildUpon();
            if (host.contains("twitter.com") || host.contains("x.com")) {
                if (uri.getQueryParameter("force_login") == null) {
                    builder.appendQueryParameter("force_login", "true");
                }
                return builder.build().toString();
            }
            if (host.contains("facebook.com") || host.contains("fb.com")) {
                if (uri.getQueryParameter("prompt") == null) {
                    builder.appendQueryParameter("prompt", "login");
                }
                return builder.build().toString();
            }
            if (host.contains("google.com") || host.contains("discord.com") || host.contains("discordapp.com")) {
                if (uri.getQueryParameter("prompt") == null) {
                    builder.appendQueryParameter("prompt", "select_account");
                }
                return builder.build().toString();
            }
        } catch (Throwable ignored) {
        }
        return rawUrl;
    }

    /**
     * Returns true if the URL is the Twitter/X Home timeline rather than an OAuth login
     * or authorize endpoint. Used to bounce the WebView back to /oauth/authorize?oauth_token=...
     * after x.com/i/flow/login finishes logging in.
     */
    public static boolean isTwitterHomeTimelineUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            if (!host.equals("x.com") && !host.equals("twitter.com")
                    && !host.endsWith(".x.com") && !host.endsWith(".twitter.com")) {
                return false;
            }
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.US);
            if (path.contains("/oauth") || path.contains("/i/flow") || path.contains("/login")
                    || path.contains("/account/login_verification")
                    || path.contains("/i/oauth2")
                    || query.contains("oauth_token=")) {
                return false;
            }
            return path.equals("/home")
                    || path.startsWith("/home/")
                    || path.equals("/explore")
                    || (path.isEmpty() || path.equals("/")) && !query.contains("oauth");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 1-Tap Web Login Session Cleaner ("New ID / Switch Account").
     * Wipes all WebView cookies (Facebook, Twitter/X, Discord, Google, VK, Krafton),
     * clears HTML5 localStorage/IndexedDB, and clears cached SDK OAuth tokens in the
     * active game without touching OBB or map resources.
     */
    public static void clearAllWebLoginSessions(Context context, WebView activeWebView) {
        setForceFreshWebLoginNextTime(true);
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.removeAllCookies(null);
                    cm.removeSessionCookies(null);
                    cm.flush();
                } else {
                    cm.removeAllCookie();
                    cm.removeSessionCookie();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            if (context != null) {
                CookieSyncManager.createInstance(context);
                CookieSyncManager.getInstance().sync();
            }
        } catch (Throwable ignored) {
        }

        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }

        if (activeWebView != null) {
            try {
                activeWebView.clearCache(true);
                activeWebView.clearHistory();
                activeWebView.clearFormData();
                activeWebView.evaluateJavascript(
                        "try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
            } catch (Throwable ignored) {
            }
        }

        try {
            Context appCtx = BActivityThread.getApplication();
            if (appCtx == null) appCtx = context;
            if (appCtx != null) {
                String[] authPrefNames = new String[]{
                        "com.facebook.login.AuthorizationClient.WebViewAuthHandler.TOKEN_STORE_KEY",
                        "com.facebook.AccessTokenManager.SharedPreferences",
                        "com.facebook.ProfileManager.SharedPreferences",
                        "com.google.android.gms.signin",
                        "TwitterAuthClient",
                        "com.twitter.sdk.android.core:TwitterCore:session_store"
                };
                for (String prefName : authPrefNames) {
                    try {
                        SharedPreferences sp = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                        if (sp != null) {
                            sp.edit().clear().apply();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            String pkg = BActivityThread.isThreadInit() ? BActivityThread.getAppPackageName() : null;
            int userId = BActivityThread.isThreadInit() ? BActivityThread.getUserId() : 0;
            if (pkg != null && !pkg.isEmpty()) {
                clearSavedOAuthFilesForPackage(pkg, userId);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clearSavedOAuthFilesForPackage(String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return;
        try {
            File dataDir = BEnvironment.getDataDir(packageName, userId);
            if (dataDir == null || !dataDir.isDirectory()) return;
            File filesDir = new File(dataDir, "files");
            File itopLogin = new File(filesDir, "itop_login.txt");
            if (itopLogin.isFile()) {
                itopLogin.delete();
            }
            File[] children = dataDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null && child.isDirectory() && child.getName().startsWith("app_webview")) {
                        deleteWebViewCookieFilesRecursive(child, 0);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteWebViewCookieFilesRecursive(File dir, int depth) {
        if (dir == null || depth > 4 || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f == null) continue;
            if (f.isDirectory()) {
                String dName = f.getName().toLowerCase(Locale.US);
                if (dName.contains("local storage") || dName.contains("session storage") || dName.contains("indexeddb")) {
                    deleteDirectoryContents(f);
                } else {
                    deleteWebViewCookieFilesRecursive(f, depth + 1);
                }
            } else {
                String name = f.getName().toLowerCase(Locale.US);
                if (name.startsWith("cookies")) {
                    f.delete();
                }
            }
        }
    }

    private static void deleteDirectoryContents(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                deleteDirectoryContents(f);
            }
            f.delete();
        }
    }

    public static void prepareBeforeApplicationCreate(
            Context context, int userId, String packageName, String processName) {
        cleanStaleWebViewLocks(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String pkg = safeToken(packageName);
            String proc = safeToken(processName);
            if (!pkg.equals(proc)) {
                setSuffixIfUninitialized("u" + userId + "_" + pkg + "_" + proc);
            }
        }
    }

    public static void ensureAfterApplicationCreate(
            Context context, int userId, String packageName, String processName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String pkg = safeToken(packageName);
            String proc = safeToken(processName);
            setSuffixIfUninitialized("u" + userId + "_" + pkg + "_" + proc);
        }
    }

    public static void enableAndFlushCookiesIfActive() {
        if (!isWebViewProviderLoaded()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long prev = LAST_COOKIE_FLUSH_MS.get();
        if (now - prev < COOKIE_FLUSH_DEBOUNCE_MS) {
            return;
        }
        if (!LAST_COOKIE_FLUSH_MS.compareAndSet(prev, now)) {
            return;
        }
        COOKIE_FLUSH_EXECUTOR.execute(() -> {
            try {
                CookieManager cm = CookieManager.getInstance();
                if (cm != null) {
                    cm.setAcceptCookie(true);
                    cm.flush();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void setSuffixIfUninitialized(String suffix) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || suffix == null || suffix.isEmpty()) {
            return;
        }
        try {
            if (isWebViewProviderLoaded() || isDataDirectorySuffixAlreadySet()) {
                return;
            }
            WebView.setDataDirectorySuffix(suffix);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isWebViewProviderLoaded() {
        try {
            Class<?> factory = Class.forName("android.webkit.WebViewFactory");
            Field providerField = factory.getDeclaredField("sProviderInstance");
            providerField.setAccessible(true);
            return providerField.get(null) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDataDirectorySuffixAlreadySet() {
        try {
            Class<?> factory = Class.forName("android.webkit.WebViewFactory");
            Field suffixField = factory.getDeclaredField("sDataDirectorySuffix");
            suffixField.setAccessible(true);
            return suffixField.get(null) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cleanStaleWebViewLocks(Context context) {
        if (context == null) {
            return;
        }
        try {
            File dataDir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? context.getDataDir()
                    : (context.getFilesDir() != null ? context.getFilesDir().getParentFile() : null);
            if (dataDir == null || !dataDir.isDirectory()) {
                return;
            }
            File[] children = dataDir.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (child != null && child.isDirectory() && child.getName().startsWith("app_webview")) {
                    for (String lockName : LOCK_FILES) {
                        File lockFile = new File(child, lockName);
                        if (lockFile.exists() && lockFile.length() == 0L) {
                            lockFile.delete();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "default";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
