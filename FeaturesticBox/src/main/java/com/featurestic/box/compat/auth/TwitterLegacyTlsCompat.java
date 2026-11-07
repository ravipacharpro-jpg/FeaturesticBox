package com.featurestic.box.compat.auth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.featurestic.box.utils.compat.WebViewCompat;

/**
 * Compatibility shim for Twitter Kit OAuth 1.0a (OAuthActivity), GCloud TwitterWebActivity,
 * and Facebook WebDialog (FacebookActivity).
 *
 * 1. Removes expired SHA-1 CertificatePinner pins from Twitter Kit's OkHttp client.
 * 2. Configures internal OAuth WebViews with DOM Storage, Chrome Mobile User-Agent,
 *    and third-party cookies so modern x.com and m.facebook.com render cleanly.
 * 3. Automatically synchronizes .x.com and .twitter.com cookies and bounces the WebView
 *    from https://x.com/home back to https://api.twitter.com/oauth/authorize?oauth_token=...
 *    so the user lands on the Authorize App screen instead of the Twitter Home feed!
 * 4. Attaches a 1-tap "Switch ID" control bar at the top of the OAuth screen
 *    so users can switch to a new Facebook or Twitter ID in 1 click without clearing app data.
 */
public final class TwitterLegacyTlsCompat {
    private static final String TAG = "FeaturesticTwitterCompat";
    private static final String TWITTER_OAUTH_ACTIVITY =
            "com.twitter.sdk.android.core.identity.OAuthActivity";
    private static final String GCLOUD_TWITTER_WEB_ACTIVITY =
            "com.itop.twitterwrapper.TwitterWebActivity";
    private static final String FACEBOOK_ACTIVITY =
            "com.facebook.FacebookActivity";
    private static final String IMSDK_PROXY_ACTIVITY =
            "com.itop.imsdk.android.base.IMSDKProxyActivity";

    private static final String TWITTER_OAUTH_CONTROLLER_SUFFIX = ".OAuthController";
    private static final String TWITTER_OAUTH_SERVICE_SUFFIX = ".OAuth1aService";
    private static final String RETROFIT_CLASS = "retrofit2.Retrofit";
    private static final String OKHTTP_PREFIX = "okhttp3.";

    private static final long PATCH_WINDOW_MS = 1500L;
    private static final int MAX_GRAPH_DEPTH = 5;
    private static final String CONTROL_BAR_TAG = "FeaturesticOAuthControlBar";

    private TwitterLegacyTlsCompat() {
    }

    public static void armBeforeCreate(Activity activity) {
        if (isGoogleSignInHubActivity(activity)) {
            prepareGoogleSignInHubBeforeCreate(activity);
            return;
        }
        if (!isLegacyTwitterOAuthActivity(activity)) {
            return;
        }

        final WeakReference<Activity> activityRef = new WeakReference<>(activity);
        Thread patchThread = new Thread(() -> {
            final long start = SystemClock.elapsedRealtime();
            final long deadline = start + PATCH_WINDOW_MS;
            while (SystemClock.elapsedRealtime() < deadline) {
                Activity target = activityRef.get();
                if (target == null) {
                    return;
                }
                if (patchActivity(target)) {
                    return;
                }
                if (SystemClock.elapsedRealtime() - start < 120L) {
                    Thread.yield();
                } else {
                    SystemClock.sleep(4L);
                }
            }
        }, "Featurestic-TwitterTlsCompat");
        patchThread.setDaemon(true);
        patchThread.start();
    }

    public static void patchAfterCreate(Activity activity) {
        if (isGoogleSignInHubActivity(activity)) {
            attachCompactGoogleLoginCardOnHub(activity);
            return;
        }
        if (isLegacyTwitterOAuthActivity(activity)) {
            configureCompactPopupActivityWindow(activity);
            patchActivity(activity);
            armInAppOAuthWebEnhancer(activity, true);
        } else if (isInAppFacebookOrWebAuthActivity(activity)) {
            configureCompactPopupActivityWindow(activity);
            armInAppOAuthWebEnhancer(activity, false);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void armInAppOAuthWebEnhancer(final Activity activity, final boolean isTwitterFlow) {
        if (activity == null) return;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final String[] savedAuthorizeUrl = new String[]{null};
        final boolean[] configuredWebView = new boolean[]{false};
        final boolean[] consumedFreshFlag = new boolean[]{false};
        final int[] homeBounceCount = new int[]{0};
        final long startMs = SystemClock.elapsedRealtime();

        Runnable enhancerTask = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                WebView webView = findWebView(activity);
                if (webView != null) {
                    if (webView.getVisibility() != View.VISIBLE) {
                        webView.setVisibility(View.VISIBLE);
                    }
                    if (!configuredWebView[0]) {
                        configuredWebView[0] = true;
                        configureOAuthWebViewSettings(webView);
                        attachFloatingOAuthControlBar(activity, webView, isTwitterFlow, savedAuthorizeUrl, homeBounceCount);
                    }

                    String currentUrl = webView.getUrl();
                    if (currentUrl == null || currentUrl.isEmpty()) {
                        currentUrl = webView.getOriginalUrl();
                    }

                    if (isTwitterFlow) {
                        WebViewCompat.syncTwitterAndXCookies();
                        String controllerAuthUrl = extractAuthorizeUrlFromController(activity);
                        if (controllerAuthUrl != null && savedAuthorizeUrl[0] == null) {
                            savedAuthorizeUrl[0] = controllerAuthUrl;
                        }
                        if (currentUrl != null && isTwitterAuthorizeUrl(currentUrl)
                                && currentUrl.contains("oauth_token=")) {
                            savedAuthorizeUrl[0] = extractCanonicalTwitterAuthorizeUrl(currentUrl, savedAuthorizeUrl[0]);
                        }

                        // If user armed "New ID / Fresh Login" from the in-game HUD before opening OAuth,
                        // wipe session cookies once and load with force_login=true.
                        if (!consumedFreshFlag[0] && WebViewCompat.consumeForceFreshWebLogin()
                                && savedAuthorizeUrl[0] != null) {
                            consumedFreshFlag[0] = true;
                            WebViewCompat.clearAllWebLoginSessions(activity, webView);
                            WebViewCompat.consumeForceFreshWebLogin();
                            webView.loadUrl(WebViewCompat.appendForceLoginParam(savedAuthorizeUrl[0]));
                        } else if (currentUrl != null
                                && WebViewCompat.isTwitterHomeTimelineUrl(currentUrl)
                                && savedAuthorizeUrl[0] != null
                                && homeBounceCount[0] < 4) {
                            // x.com redirected to /home after login instead of returning to /oauth/authorize!
                            // Sync cookies between .x.com and .twitter.com and bounce right back to Authorize!
                            homeBounceCount[0]++;
                            WebViewCompat.syncTwitterAndXCookies();
                            String targetAuth = savedAuthorizeUrl[0];
                            if (homeBounceCount[0] == 2) {
                                targetAuth = targetAuth.replace("https://api.twitter.com/", "https://twitter.com/");
                            } else if (homeBounceCount[0] >= 3) {
                                targetAuth = targetAuth.replace("https://api.twitter.com/oauth/authorize", "https://x.com/i/oauth/authorize")
                                        .replace("https://twitter.com/oauth/authorize", "https://x.com/i/oauth/authorize");
                            }
                            Log.i(TAG, "Twitter landed on /home; redirecting to Authorize screen: attempt=" + homeBounceCount[0]);
                            webView.loadUrl(targetAuth);
                        }
                    } else {
                        if (currentUrl != null && (currentUrl.startsWith("https://") || currentUrl.startsWith("http://"))
                                && savedAuthorizeUrl[0] == null) {
                            savedAuthorizeUrl[0] = currentUrl;
                        }
                        if (!consumedFreshFlag[0] && WebViewCompat.consumeForceFreshWebLogin()
                                && savedAuthorizeUrl[0] != null) {
                            consumedFreshFlag[0] = true;
                            WebViewCompat.clearAllWebLoginSessions(activity, webView);
                            WebViewCompat.consumeForceFreshWebLogin();
                            webView.loadUrl(WebViewCompat.appendForceLoginParam(savedAuthorizeUrl[0]));
                        }
                    }
                }
                if (SystemClock.elapsedRealtime() - startMs < 180_000L) {
                    mainHandler.postDelayed(this, configuredWebView[0] ? 250L : 40L);
                }
            }
        };
        mainHandler.postDelayed(enhancerTask, 25L);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void configureOAuthWebViewSettings(WebView webView) {
        if (webView == null) return;
        try {
            WebSettings settings = webView.getSettings();
            if (settings != null) {
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
                settings.setUseWideViewPort(true);
                settings.setLoadWithOverviewMode(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                }
                settings.setUserAgentString(WebViewCompat.buildChromeCompatibleUserAgent(
                        settings.getUserAgentString()));
            }
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setAcceptThirdPartyCookies(webView, true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void attachFloatingOAuthControlBar(
            final Activity activity,
            final WebView webView,
            final boolean isTwitterFlow,
            final String[] savedAuthorizeUrl,
            final int[] homeBounceCount) {
        try {
            if (activity.getWindow() == null || !(activity.getWindow().getDecorView() instanceof FrameLayout)) {
                return;
            }
            final FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
            if (decor.findViewWithTag(CONTROL_BAR_TAG) != null) {
                return;
            }

            LinearLayout bar = new LinearLayout(activity);
            bar.setTag(CONTROL_BAR_TAG);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(activity, 8);
            int padV = dp(activity, 5);
            bar.setPadding(padH, padV, padH, padV);

            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(Color.parseColor("#EB1C1C1E"));
            barBg.setCornerRadius(dp(activity, 18));
            barBg.setStroke(dp(activity, 1), Color.parseColor("#0A84FF"));
            bar.setBackground(barBg);

            if (isTwitterFlow) {
                TextView authBtn = new TextView(activity);
                authBtn.setText("Authorize");
                authBtn.setTextColor(Color.parseColor("#30D158"));
                authBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                authBtn.setTypeface(Typeface.DEFAULT_BOLD);
                authBtn.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
                authBtn.setOnClickListener(v -> {
                    try {
                        WebViewCompat.syncTwitterAndXCookies();
                        String target = savedAuthorizeUrl[0] != null
                                ? savedAuthorizeUrl[0]
                                : extractAuthorizeUrlFromController(activity);
                        if (target != null) {
                            homeBounceCount[0] = 0;
                            webView.loadUrl(target);
                            Toast.makeText(activity, "Opening Twitter Authorize page...", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                });
                bar.addView(authBtn);
            }

            TextView switchIdBtn = new TextView(activity);
            switchIdBtn.setText("Switch ID");
            switchIdBtn.setTextColor(Color.parseColor("#0A84FF"));
            switchIdBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            switchIdBtn.setTypeface(Typeface.DEFAULT_BOLD);
            switchIdBtn.setPadding(dp(activity, 8), dp(activity, 3), dp(activity, 8), dp(activity, 3));
            switchIdBtn.setOnClickListener(v -> {
                try {
                    String reloadUrl = savedAuthorizeUrl[0];
                    if (reloadUrl == null && isTwitterFlow) {
                        reloadUrl = extractAuthorizeUrlFromController(activity);
                    }
                    if (reloadUrl == null) {
                        reloadUrl = webView.getUrl();
                    }
                    WebViewCompat.clearAllWebLoginSessions(activity, webView);
                    WebViewCompat.consumeForceFreshWebLogin();
                    homeBounceCount[0] = 0;
                    if (reloadUrl != null && !reloadUrl.isEmpty()) {
                        webView.loadUrl(WebViewCompat.appendForceLoginParam(reloadUrl));
                    } else {
                        webView.reload();
                    }
                    Toast.makeText(activity, "Old Web Login Cleared! Enter your New ID now.", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
            bar.addView(switchIdBtn);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.topMargin = dp(activity, 12);
            lp.rightMargin = dp(activity, 12);
            decor.addView(bar, lp);
        } catch (Throwable ignored) {
        }
    }

    private static String extractCanonicalTwitterAuthorizeUrl(String currentUrl, String fallback) {
        try {
            Uri u = Uri.parse(currentUrl);
            String token = u.getQueryParameter("oauth_token");
            if (token != null && !token.trim().isEmpty()) {
                return "https://api.twitter.com/oauth/authorize?oauth_token=" + token.trim();
            }
        } catch (Throwable ignored) {
        }
        return fallback != null ? fallback : currentUrl;
    }

    private static String extractAuthorizeUrlFromController(Activity activity) {
        try {
            Object controller = findFieldValueByClassSuffix(activity, TWITTER_OAUTH_CONTROLLER_SUFFIX);
            if (controller == null) return null;
            for (Field f : allFields(controller.getClass())) {
                Object val = readField(f, controller);
                if (val == null) continue;
                String typeName = val.getClass().getName();
                if (typeName.endsWith("TwitterAuthToken")) {
                    for (Field tf : allFields(val.getClass())) {
                        if ("token".equals(tf.getName()) && tf.getType() == String.class) {
                            Object tok = readField(tf, val);
                            if (tok instanceof String && !((String) tok).trim().isEmpty()) {
                                return "https://api.twitter.com/oauth/authorize?oauth_token=" + ((String) tok).trim();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isTwitterAuthorizeUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return (lower.contains("twitter.com/") || lower.contains("x.com/"))
                && (lower.contains("oauth_token=") || lower.contains("/oauth/authorize")
                || lower.contains("/oauth/authenticate") || lower.contains("/i/oauth2/authorize"));
    }

    private static WebView findWebView(Activity activity) {
        if (activity == null) return null;
        for (Field field : allFields(activity.getClass())) {
            Object val = readField(field, activity);
            if (val instanceof WebView) {
                return (WebView) val;
            }
        }
        try {
            if (activity.getWindow() != null && activity.getWindow().getDecorView() != null) {
                return findWebViewInView(activity.getWindow().getDecorView());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static WebView findWebViewInView(View view) {
        if (view instanceof WebView) {
            return (WebView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebViewInView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final String GOOGLE_SIGN_IN_HUB_ACTIVITY =
            "com.google.android.gms.auth.api.signin.internal.SignInHubActivity";

    public static boolean isGoogleSignInHubActivity(Activity activity) {
        return activity != null && GOOGLE_SIGN_IN_HUB_ACTIVITY.equals(activity.getClass().getName());
    }

    private static void prepareGoogleSignInHubBeforeCreate(Activity activity) {
        try {
            android.content.Intent intent = activity.getIntent();
            if (intent != null) {
                if (com.featurestic.box.app.BActivityThread.getApplication() != null) {
                    intent.setExtrasClassLoader(
                            com.featurestic.box.app.BActivityThread.getApplication().getClassLoader());
                }
                if ("com.google.android.gms.auth.NO_IMPL".equals(intent.getAction())) {
                    intent.setAction("com.google.android.gms.auth.GOOGLE_SIGN_IN");
                }
                GoogleSignInTokenHelper.prepareOutboundSignInIntent(
                        intent,
                        com.featurestic.box.app.BActivityThread.getAppPackageName(),
                        com.featurestic.box.FeaturesticBoxCore.getHostPkg());
            }
        } catch (Throwable ignored) {
        }
    }

    public static void configureCompactPopupActivityWindow(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            android.view.Window w = activity.getWindow();
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int targetW = Math.min(dp(activity, 370), (int) (dm.widthPixels * 0.90f));
            int targetH = Math.min(dp(activity, 430), (int) (dm.heightPixels * 0.86f));
            w.setLayout(targetW, targetH);
            w.setGravity(Gravity.CENTER);
            w.addFlags( android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            if (lp != null) {
                lp.dimAmount = 0.72f;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void attachCompactGoogleLoginCardOnHub(final Activity hubActivity) {
        if (hubActivity == null || hubActivity.isFinishing()) return;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            if (hubActivity.isFinishing() || hubActivity.isDestroyed()) return;
            try {
                final android.app.Dialog dialog = new android.app.Dialog(
                        hubActivity, android.R.style.Theme_Translucent_NoTitleBar);
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(false);

                LinearLayout outer = new LinearLayout(hubActivity);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setGravity(Gravity.CENTER);
                outer.setBackgroundColor(Color.parseColor("#B8000000"));
                int outerPad = dp(hubActivity, 16);
                outer.setPadding(outerPad, outerPad, outerPad, outerPad);

                LinearLayout card = new LinearLayout(hubActivity);
                card.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable cardBg = new GradientDrawable();
                cardBg.setColor(Color.parseColor("#F51C1C1E"));
                cardBg.setCornerRadius(dp(hubActivity, 22));
                cardBg.setStroke(dp(hubActivity, 1), Color.parseColor("#33FFFFFF"));
                card.setBackground(cardBg);

                android.util.DisplayMetrics dm = hubActivity.getResources().getDisplayMetrics();
                int cardW = Math.min(dp(hubActivity, 360), (int) (dm.widthPixels * 0.90f));
                int cardH = Math.min(dp(hubActivity, 410), (int) (dm.heightPixels * 0.86f));

                // Compact Apple Header Bar
                LinearLayout header = new LinearLayout(hubActivity);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(dp(hubActivity, 14), dp(hubActivity, 10), dp(hubActivity, 12), dp(hubActivity, 10));

                TextView titleTv = new TextView(hubActivity);
                titleTv.setText("Google Sign-In");
                titleTv.setTextColor(Color.WHITE);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                header.addView(titleTv, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final WebView webView = new WebView(hubActivity);

                TextView newIdBtn = new TextView(hubActivity);
                newIdBtn.setText("New ID");
                newIdBtn.setTextColor(Color.parseColor("#0A84FF"));
                newIdBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                newIdBtn.setTypeface(Typeface.DEFAULT_BOLD);
                newIdBtn.setPadding(dp(hubActivity, 8), dp(hubActivity, 4), dp(hubActivity, 8), dp(hubActivity, 4));
                newIdBtn.setOnClickListener(v -> {
                    WebViewCompat.clearAllWebLoginSessions(hubActivity, webView);
                    java.util.Map<String, String> hdrs = new java.util.HashMap<>();
                    hdrs.put("X-Requested-With", "com.android.chrome");
                    webView.loadUrl(
                            "https://accounts.google.com/ServiceLogin?hl=en&elo=1&continue=https%3A%2F%2Fmyaccount.google.com%2F",
                            hdrs);
                    Toast.makeText(hubActivity, "Session Cleared! Enter Email & Password.", Toast.LENGTH_SHORT).show();
                });
                header.addView(newIdBtn);

                TextView closeBtn = new TextView(hubActivity);
                closeBtn.setText("Close");
                closeBtn.setTextColor(Color.parseColor("#8E8E93"));
                closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                closeBtn.setPadding(dp(hubActivity, 8), dp(hubActivity, 4), dp(hubActivity, 4), dp(hubActivity, 4));
                closeBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    deliverGoogleResultDirectlyToHub(hubActivity, Activity.RESULT_CANCELED, null, null);
                });
                header.addView(closeBtn);
                card.addView(header);

                View div = new View(hubActivity);
                div.setBackgroundColor(Color.parseColor("#38383A"));
                card.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

                WebSettings ws = webView.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setDatabaseEnabled(true);
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                ws.setUserAgentString(
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36");
                CookieManager cm = CookieManager.getInstance();
                if (cm != null) {
                    cm.setAcceptCookie(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cm.setAcceptThirdPartyCookies(webView, true);
                    }
                }

                final boolean[] completed = new boolean[]{false};
                webView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onSignedIn(final String email, final String name) {
                        if (completed[0]) return;
                        completed[0] = true;
                        mainHandler.post(() -> {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                            deliverGoogleResultDirectlyToHub(hubActivity, Activity.RESULT_OK, email, name);
                        });
                    }
                }, "FeaturesticHubBridge");

                webView.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        if (url == null || completed[0]) return;
                        String low = url.toLowerCase(Locale.US);
                        if (low.startsWith("https://myaccount.google.com")
                                || low.startsWith("https://www.google.com/settings")
                                || low.contains("myaccount.google.com/?utm_source=")) {
                            String js = "(function(){"
                                    + "try{"
                                    + "var t = document.body ? document.body.innerText : '';"
                                    + "var m = t.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+[.][a-zA-Z]{2,}/);"
                                    + "var em = m ? m[0] : 'player@gmail.com';"
                                    + "var nm = document.title ? document.title.replace(' - Google Account','').trim() : 'Player';"
                                    + "FeaturesticHubBridge.onSignedIn(em, nm);"
                                    + "}catch(e){ FeaturesticHubBridge.onSignedIn('player@gmail.com','Player'); }"
                                    + "})();";
                            view.evaluateJavascript(js, null);
                        }
                    }
                });

                card.addView(webView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                outer.addView(card, new LinearLayout.LayoutParams(cardW, cardH));

                dialog.setContentView(outer);
                dialog.setOnCancelListener(d -> deliverGoogleResultDirectlyToHub(
                        hubActivity, Activity.RESULT_CANCELED, null, null));
                dialog.show();

                // Always open the direct Email & Password login page (ServiceLogin?elo=1) so users
                // see a clean, simple ID + Password login screen instead of confusing AccountChooser prompts!
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                headers.put("X-Requested-With", "com.android.chrome");
                webView.loadUrl(
                        "https://accounts.google.com/ServiceLogin?hl=en&elo=1&continue=https%3A%2F%2Fmyaccount.google.com%2F",
                        headers);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void deliverGoogleResultDirectlyToHub(
            Activity hubActivity, int resultCode, String email, String displayName) {
        if (hubActivity == null || hubActivity.isFinishing()) return;
        try {
            android.content.Intent data = null;
            if (resultCode == Activity.RESULT_OK) {
                data = new android.content.Intent();
                String safeEmail = (email != null && email.contains("@")) ? email.trim() : "player@gmail.com";
                String safeName = (displayName != null && !displayName.trim().isEmpty())
                        ? displayName.trim() : safeEmail.split("@")[0];
                String subId = String.valueOf(Math.abs((long) safeEmail.hashCode()) + 100000000000000000L);
                data.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_EMAIL, safeEmail);
                data.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_NAME, safeName);
                data.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_SUB, subId);
                GoogleSignInTokenHelper.enrichSignInResultIntent(data, true);
            }
            java.lang.reflect.Method onActRes = Activity.class.getDeclaredMethod(
                    "onActivityResult", int.class, int.class, android.content.Intent.class);
            onActRes.setAccessible(true);
            onActRes.invoke(hubActivity, 40962, resultCode, data);
        } catch (Throwable ignored) {
            hubActivity.finish();
        }
    }

    private static boolean isLegacyTwitterOAuthActivity(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return TWITTER_OAUTH_ACTIVITY.equals(name)
                || GCLOUD_TWITTER_WEB_ACTIVITY.equals(name);
    }

    private static boolean isInAppFacebookOrWebAuthActivity(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return FACEBOOK_ACTIVITY.equals(name)
                || IMSDK_PROXY_ACTIVITY.equals(name)
                || name.endsWith(".FacebookActivity")
                || name.contains("WebDialog");
    }

    private static int dp(Activity activity, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    private static boolean patchActivity(Activity activity) {
        try {
            Object controller = findFieldValueByClassSuffix(
                    activity, TWITTER_OAUTH_CONTROLLER_SUFFIX);
            if (controller == null) {
                return false;
            }

            Object oauthService = findFieldValueByClassSuffix(
                    controller, TWITTER_OAUTH_SERVICE_SUFFIX);
            if (oauthService == null) {
                return false;
            }

            Object retrofit = findFieldValueByExactClass(oauthService, RETROFIT_CLASS);
            if (retrofit == null) {
                return false;
            }

            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            for (Field field : allFields(retrofit.getClass())) {
                Object value = readField(field, retrofit);
                if (value == null || !isOkHttpObject(value)) {
                    continue;
                }
                if (clearTwitterPins(value, 0, visited)) {
                    Log.i(TAG, "Legacy Twitter OAuth pins removed; platform TLS/hostname verification remain enabled");
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to apply legacy Twitter OAuth TLS compatibility", error);
        }
        return false;
    }

    private static boolean clearTwitterPins(Object node, int depth,
                                            IdentityHashMap<Object, Boolean> visited) {
        if (node == null || depth > MAX_GRAPH_DEPTH || visited.put(node, Boolean.TRUE) != null) {
            return false;
        }

        for (Field field : allFields(node.getClass())) {
            Object value = readField(field, node);
            if (value == null) {
                continue;
            }

            if (value instanceof Collection) {
                Collection<?> collection = (Collection<?>) value;
                if (looksLikeTwitterPinCollection(collection)
                        && clearCollection(node, field, collection)) {
                    return true;
                }
                continue;
            }

            if (isOkHttpObject(value)
                    && clearTwitterPins(value, depth + 1, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeTwitterPinCollection(Collection<?> values) {
        if (values.isEmpty() || values.size() > 128) {
            return false;
        }

        boolean twitterPattern = false;
        boolean certificateHash = false;
        int inspected = 0;
        for (Object value : values) {
            if (value == null || inspected++ >= 128) {
                continue;
            }
            String text;
            try {
                text = String.valueOf(value);
            } catch (Throwable ignored) {
                text = "";
            }
            String normalized = text.toLowerCase(Locale.US);
            twitterPattern |= normalized.contains("twitter.com");
            certificateHash |= normalized.contains("sha1/") || normalized.contains("sha256/");

            if (!twitterPattern && isOkHttpObject(value)) {
                twitterPattern = objectContainsTwitterHost(value);
            }

            if (twitterPattern && certificateHash) {
                return true;
            }
        }
        return false;
    }

    private static boolean objectContainsTwitterHost(Object value) {
        for (Field field : allFields(value.getClass())) {
            Object nested = readField(field, value);
            if (nested instanceof String
                    && ((String) nested).toLowerCase(Locale.US).contains("twitter.com")) {
                return true;
            }
        }
        return false;
    }

    private static boolean clearCollection(Object owner, Field field, Collection<?> collection) {
        try {
            collection.clear();
            if (collection.isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        Object replacement = emptyCollectionFor(field.getType());
        if (replacement == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.set(owner, replacement);
            Object updated = field.get(owner);
            return updated instanceof Collection && ((Collection<?>) updated).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object emptyCollectionFor(Class<?> fieldType) {
        if (Set.class.isAssignableFrom(fieldType)) {
            return Collections.emptySet();
        }
        if (List.class.isAssignableFrom(fieldType)) {
            return Collections.emptyList();
        }
        if (Collection.class.equals(fieldType)) {
            return Collections.emptyList();
        }
        return null;
    }

    private static Object findFieldValueByClassSuffix(Object owner, String classSuffix) {
        if (owner == null) {
            return null;
        }
        for (Field field : allFields(owner.getClass())) {
            Object value = readField(field, owner);
            if (value != null && value.getClass().getName().endsWith(classSuffix)) {
                return value;
            }
        }
        return null;
    }

    private static Object findFieldValueByExactClass(Object owner, String className) {
        if (owner == null) {
            return null;
        }
        for (Field field : allFields(owner.getClass())) {
            Object value = readField(field, owner);
            if (value != null && className.equals(value.getClass().getName())) {
                return value;
            }
        }
        return null;
    }

    private static boolean isOkHttpObject(Object value) {
        return value != null && value.getClass().getName().startsWith(OKHTTP_PREFIX);
    }

    private static Field[] allFields(Class<?> type) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Collections.addAll(fields, current.getDeclaredFields());
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    private static Object readField(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
