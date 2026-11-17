package com.featurestic.box.compat.oauth;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import org.lsposed.lsparanoid.Obfuscate;

import com.featurestic.box.compat.auth.ExternalAuthRouter;
import com.featurestic.box.fake.frameworks.BActivityManager;
import com.featurestic.box.fake.frameworks.BPackageManager;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.utils.FileUtils;
import com.featurestic.box.utils.compat.BundleCompat;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;
import com.featurestic.box.utils.compat.WebViewCompat;
import com.featurestic.box.utils.provider.ProviderCall;

/**
 * Stable host-main trampoline for browser OAuth, embedded Chrome-UA WebView OAuth
 * (Discord, custom-scheme Facebook/Garena/IMSDK/VK/LINE), and trusted native provider auth.
 */
@Obfuscate
public final class VirtualOAuthBridgeActivity extends Activity {
    private static final String TAG = "FeaturesticOAuth";
    private static final String AUTH_TAG = "FeaturesticAuth";
    private static final int REQUEST_AUTH_TAB = 0x5041;
    private static final int REQUEST_EXTERNAL_AUTH = 0x5042;
    private static final int REQUEST_CHOOSE_GOOGLE_ACCOUNT = 0x5043;
    private static final long FACEBOOK_FALLBACK_WAIT_MS = 600L;
    private static final long FACEBOOK_HANDOFF_SETTLE_MS = 80L;

    private static final String FACEBOOK_CUSTOM_TAB_ACTIVITY =
            "com.facebook.CustomTabActivity";
    private static final String FACEBOOK_CUSTOM_TAB_MAIN_ACTIVITY =
            "com.facebook.CustomTabMainActivity";
    private static final String FACEBOOK_CUSTOM_TAB_REDIRECT_ACTION =
            "CustomTabActivity.action_customTabRedirect";
    private static final String FACEBOOK_CUSTOM_TAB_EXTRA_URL =
            "CustomTabMainActivity.extra_url";
    private static final String EMBEDDED_WEBVIEW_PROVIDER = "webview";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String virtualPackage;
    private Uri expectedRedirectUri;
    private Uri authUri;
    private String authProvider;
    private int userId = -1;
    private int resultBpid = -1;
    private boolean facebookFlow;
    private boolean twitterFlow;
    private boolean legacyTwitterFlow;
    private boolean resultBridgeMode;
    private boolean externalAuthMode;
    private boolean manualResultRelay;
    private boolean facebookResultPending;
    private boolean callbackDispatched;
    private WebView embeddedWebView;
    private String serverClientId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.featurestic.box.compat.auth.TwitterLegacyTlsCompat.configureCompactPopupActivityWindow(this);

        Intent launchIntent = getIntent();
        boolean googleWebAuthMode = launchIntent != null
                && launchIntent.getBooleanExtra(ExternalAuthRouter.EXTRA_GOOGLE_WEB_AUTH, false);
        externalAuthMode = launchIntent != null
                && launchIntent.getBooleanExtra(ExternalAuthRouter.EXTRA_EXTERNAL_AUTH, false);
        resultBridgeMode = launchIntent != null
                && launchIntent.getBooleanExtra(ExternalAuthRouter.EXTRA_BROWSER_AUTH, false);
        manualResultRelay = launchIntent != null
                && launchIntent.getBooleanExtra(
                ExternalAuthRouter.EXTRA_MANUAL_RESULT_RELAY, false);
        resultBpid = launchIntent == null ? -1
                : launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_BPID, -1);

        if (googleWebAuthMode) {
            virtualPackage = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            userId = launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_USER_ID, -1);
            resultBridgeMode = true;
            if (!validResultTarget(launchIntent)) {
                finish();
                return;
            }
            serverClientId = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_GOOGLE_SERVER_CLIENT_ID);
            launchGoogleNativeAccountPickerOrWebView(serverClientId);
            return;
        }

        if (externalAuthMode) {
            virtualPackage = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            userId = launchIntent.getIntExtra(ExternalAuthRouter.EXTRA_USER_ID, -1);
            if (!validResultTarget(launchIntent)) {
                finish();
                return;
            }
            if (savedInstanceState == null) {
                launchExternalProvider(launchIntent);
            }
            return;
        }

        String authUrl = launchIntent == null ? null
                : launchIntent.getStringExtra(VirtualOAuthRouter.EXTRA_AUTH_URL);
        String redirectUriValue = launchIntent == null ? null
                : launchIntent.getStringExtra(VirtualOAuthRouter.EXTRA_REDIRECT_URI);
        virtualPackage = launchIntent == null ? null
                : launchIntent.getStringExtra(VirtualOAuthRouter.EXTRA_VIRTUAL_PACKAGE);
        userId = launchIntent == null ? -1
                : launchIntent.getIntExtra(VirtualOAuthRouter.EXTRA_USER_ID, -1);
        authProvider = launchIntent == null ? null
                : launchIntent.getStringExtra(VirtualOAuthRouter.EXTRA_AUTH_PROVIDER);

        authUri = safeHttpsUri(authUrl);
        Uri redirectUri = safeCustomRedirectUri(redirectUriValue);
        if (authUri == null || !VirtualOAuthRouter.isTrustedAuthUri(authUri)
                || redirectUri == null || virtualPackage == null
                || virtualPackage.trim().isEmpty() || userId < 0
                || authProvider == null || authProvider.trim().isEmpty()) {
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_CANCELED, null);
            } else {
                finish();
            }
            return;
        }

        expectedRedirectUri = redirectUri;
        facebookFlow = isFacebookHost(authUri);
        twitterFlow = isTwitterHost(authUri);
        legacyTwitterFlow = twitterFlow && hasQueryParameter(authUri, "oauth_token");
        boolean useEmbeddedWebView = EMBEDDED_WEBVIEW_PROVIDER.equals(authProvider)
                || !AuthTabCompat.isSupportedProvider(this, authProvider, authUri);

        if (!redirectResolvesToVirtualPackage(redirectUri)) {
            diagnostic("setup_rejected", false, false, false, false, false);
            facebookDiagnostic("setup_rejected", RESULT_CANCELED, null, null, false);
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_CANCELED, null);
            } else {
                finish();
            }
            return;
        }

        if (resultBridgeMode && !validResultTarget(launchIntent)) {
            diagnostic("result_bridge_target_rejected", false, false, false, false, false);
            facebookDiagnostic(
                    "result_bridge_target_rejected", RESULT_CANCELED, null, null, false);
            finish();
            return;
        }

        if (useEmbeddedWebView) {
            if (facebookFlow) {
                FacebookOAuthSessionStore.begin(
                        authUri, expectedRedirectUri, virtualPackage, userId);
            }
            launchEmbeddedOAuthWebView(authUri);
            return;
        }

        if (savedInstanceState == null) {
            if (facebookFlow) {
                FacebookOAuthSessionStore.setCompletionListener(
                        () -> mainHandler.post(this::onFacebookSessionCompleted));
                FacebookOAuthSessionStore.begin(
                        authUri, expectedRedirectUri, virtualPackage, userId);
                facebookDiagnostic("session_started", RESULT_CANCELED, null, null, false);
            }
            launchAuthTab(authUri, lower(expectedRedirectUri.getScheme()), authProvider);
        }
    }

    private void launchGoogleNativeAccountPickerOrWebView(final String serverClientId) {
        try {
            AccountManager am = AccountManager.get(this);
            Account[] accounts = null;
            try {
                accounts = am.getAccountsByType("com.google");
            } catch (Throwable ignored) {
            }

            if (accounts != null && accounts.length > 0) {
                showPremiumGoogleAccountPicker(accounts, serverClientId);
                return;
            }

            try {
                Intent chooseIntent = null;
                for (java.lang.reflect.Method m : AccountManager.class.getMethods()) {
                    if ("newChooseAccountIntent".equals(m.getName())) {
                        Class<?>[] pTypes = m.getParameterTypes();
                        if (pTypes.length == 7) {
                            chooseIntent = (Intent) m.invoke(null, null, null, new String[]{"com.google"}, null, null, null, null);
                            break;
                        } else if (pTypes.length == 8) {
                            chooseIntent = (Intent) m.invoke(null, null, null, new String[]{"com.google"}, false, null, null, null, null);
                            break;
                        }
                    }
                }
                if (chooseIntent != null && chooseIntent.resolveActivity(getPackageManager()) != null) {
                    startActivityForResult(chooseIntent, REQUEST_CHOOSE_GOOGLE_ACCOUNT);
                    return;
                }
            } catch (Throwable ignored) {
            }

            launchGoogleWebLoginView(serverClientId);
        } catch (Throwable t) {
            launchGoogleWebLoginView(serverClientId);
        }
    }

    private void showPremiumGoogleAccountPicker(final Account[] accounts, final String serverClientId) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(16), dp(18), dp(16), dp(18));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#150F24"));
            cardBg.setCornerRadius(dp(24));
            cardBg.setStroke(dp(2), Color.parseColor("#4A2E70"));
            root.setBackground(cardBg);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER_HORIZONTAL);
            header.setPadding(0, dp(4), 0, dp(14));

            TextView logoView = new TextView(this);
            logoView.setText("G");
            logoView.setTextSize(22f);
            logoView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            logoView.setGravity(Gravity.CENTER);
            logoView.setTextColor(Color.WHITE);
            android.graphics.drawable.GradientDrawable logoBg = new android.graphics.drawable.GradientDrawable();
            logoBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            logoBg.setColors(new int[]{Color.parseColor("#A855F7"), Color.parseColor("#EC4899")});
            logoView.setBackground(logoBg);
            int logoSize = dp(44);
            header.addView(logoView, new LinearLayout.LayoutParams(logoSize, logoSize));

            TextView titleView = new TextView(this);
            titleView.setText("Choose Google Account");
            titleView.setTextColor(Color.parseColor("#F8F5FF"));
            titleView.setTextSize(16.5f);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setPadding(0, dp(10), 0, dp(2));
            header.addView(titleView);

            TextView subtitleView = new TextView(this);
            subtitleView.setText("1-Tap to continue to game");
            subtitleView.setTextColor(Color.parseColor("#A99BBE"));
            subtitleView.setTextSize(12.5f);
            header.addView(subtitleView);

            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(Color.parseColor("#281C3D"));
            root.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            LinearLayout listContainer = new LinearLayout(this);
            listContainer.setOrientation(LinearLayout.VERTICAL);
            listContainer.setPadding(0, dp(8), 0, dp(8));

            int[][] avatarGradients = new int[][]{
                {Color.parseColor("#A855F7"), Color.parseColor("#7C3AED")},
                {Color.parseColor("#EC4899"), Color.parseColor("#BE185D")},
                {Color.parseColor("#3B82F6"), Color.parseColor("#1D4ED8")},
                {Color.parseColor("#10B981"), Color.parseColor("#047857")},
                {Color.parseColor("#F59E0B"), Color.parseColor("#B45309")}
            };

            for (int i = 0; i < accounts.length; i++) {
                final Account account = accounts[i];
                final String email = account.name;
                String initial = (email != null && !email.isEmpty())
                        ? email.substring(0, 1).toUpperCase(Locale.US) : "G";
                final String userName = (email != null && email.contains("@"))
                        ? email.split("@")[0] : "Google User";

                LinearLayout itemRow = new LinearLayout(this);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setGravity(Gravity.CENTER_VERTICAL);
                itemRow.setPadding(dp(12), dp(10), dp(12), dp(10));
                itemRow.setClickable(true);
                itemRow.setFocusable(true);

                android.graphics.drawable.GradientDrawable itemBg = new android.graphics.drawable.GradientDrawable();
                itemBg.setColor(Color.parseColor("#1E1730"));
                itemBg.setCornerRadius(dp(14));
                itemBg.setStroke(dp(1), Color.parseColor("#382352"));
                itemRow.setBackground(itemBg);

                TextView avatar = new TextView(this);
                avatar.setText(initial);
                avatar.setTextSize(16f);
                avatar.setTextColor(Color.WHITE);
                avatar.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                avatar.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int[] grad = avatarGradients[i % avatarGradients.length];
                avBg.setColors(grad);
                avatar.setBackground(avBg);
                int avSize = dp(38);
                itemRow.addView(avatar, new LinearLayout.LayoutParams(avSize, avSize));

                LinearLayout textCol = new LinearLayout(this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                textCol.setPadding(dp(12), 0, 0, 0);

                TextView nameTv = new TextView(this);
                nameTv.setText(userName);
                nameTv.setTextSize(14f);
                nameTv.setTextColor(Color.parseColor("#F8F5FF"));
                nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                textCol.addView(nameTv);

                TextView emailTv = new TextView(this);
                emailTv.setText(email);
                emailTv.setTextSize(12f);
                emailTv.setTextColor(Color.parseColor("#A99BBE"));
                textCol.addView(emailTv);

                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                itemRow.addView(textCol, textLp);

                TextView arrow = new TextView(this);
                arrow.setText("➔");
                arrow.setTextColor(Color.parseColor("#A855F7"));
                arrow.setTextSize(14f);
                itemRow.addView(arrow);

                itemRow.setOnClickListener(v -> {
                    callbackDispatched = true;
                    android.widget.Toast.makeText(this, "Signing in with " + email + "...", android.widget.Toast.LENGTH_SHORT).show();
                    finishGoogleWebSignInWithProfile(email, userName);
                });

                LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                itemLp.setMargins(0, dp(4), 0, dp(4));
                listContainer.addView(itemRow, itemLp);
            }

            scrollView.addView(listContainer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(scrollView, scrollLp);

            LinearLayout footer = new LinearLayout(this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(12), 0, dp(4));

            TextView cancelBtn = new TextView(this);
            cancelBtn.setText("Cancel");
            cancelBtn.setTextColor(Color.parseColor("#A99BBE"));
            cancelBtn.setTextSize(13f);
            cancelBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            cancelBtn.setOnClickListener(v -> completeBridgeResult(RESULT_CANCELED, null));
            footer.addView(cancelBtn);

            android.view.View spacer = new android.view.View(this);
            footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView useAnotherBtn = new TextView(this);
            useAnotherBtn.setText("+ Other Account (Web)");
            useAnotherBtn.setTextColor(Color.parseColor("#A855F7"));
            useAnotherBtn.setTextSize(13f);
            useAnotherBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            useAnotherBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
            useAnotherBtn.setOnClickListener(v -> launchGoogleWebLoginView(serverClientId));
            footer.addView(useAnotherBtn);

            root.addView(footer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(root);
        } catch (Throwable t) {
            launchGoogleWebLoginView(serverClientId);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void launchGoogleWebLoginView(final String serverClientId) {
        try {
            final String googleLoginUrl =
                    "https://accounts.google.com/AccountChooser?hl=en&continue=https%3A%2F%2Fmyaccount.google.com%2F";
            final String googleFreshLoginUrl =
                    "https://accounts.google.com/ServiceLogin?hl=en&elo=1&continue=https%3A%2F%2Fmyaccount.google.com%2F";

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable compactBg = new android.graphics.drawable.GradientDrawable();
            compactBg.setColor(Color.parseColor("#F51C1C1E"));
            compactBg.setCornerRadius(dp(22));
            compactBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
            root.setBackground(compactBg);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                root.setClipToOutline(true);
            }

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(Color.parseColor("#1C1C1E"));
            int padH = dp(14);
            int padV = dp(10);
            header.setPadding(padH, padV, padH, padV);

            TextView titleView = new TextView(this);
            titleView.setText("Google Web Sign-In");
            titleView.setTextColor(Color.WHITE);
            titleView.setTextSize(14.5f);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(titleView, titleLp);

            TextView continueBtn = new TextView(this);
            continueBtn.setText("Continue");
            continueBtn.setTextColor(Color.parseColor("#30D158"));
            continueBtn.setTextSize(13f);
            continueBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            continueBtn.setOnClickListener(v -> extractGoogleAccountFromWebViewAndFinish(embeddedWebView, true));
            header.addView(continueBtn);

            TextView switchIdBtn = new TextView(this);
            switchIdBtn.setText("Switch ID");
            switchIdBtn.setTextColor(Color.parseColor("#0A84FF"));
            switchIdBtn.setTextSize(13f);
            switchIdBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
            switchIdBtn.setOnClickListener(v -> {
                if (embeddedWebView != null) {
                    WebViewCompat.clearAllWebLoginSessions(
                            VirtualOAuthBridgeActivity.this, embeddedWebView);
                    WebViewCompat.consumeForceFreshWebLogin();
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("X-Requested-With", "com.android.chrome");
                    embeddedWebView.loadUrl(googleFreshLoginUrl, headers);
                    android.widget.Toast.makeText(
                            VirtualOAuthBridgeActivity.this,
                            "Old Google Session Cleared! Sign in with your New Gmail ID.",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            header.addView(switchIdBtn);

            TextView closeBtn = new TextView(this);
            closeBtn.setText("Close");
            closeBtn.setTextColor(Color.WHITE);
            closeBtn.setTextSize(13f);
            closeBtn.setPadding(dp(10), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> cancelEmbeddedWebView());
            header.addView(closeBtn);

            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ProgressBar progressBar = new ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            root.addView(progressBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

            FrameLayout webContainer = new FrameLayout(this);
            embeddedWebView = new WebView(this);
            WebSettings settings = embeddedWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            // Pure Chrome Mobile User-Agent so accounts.google.com never blocks with 403 disallowed_useragent
            settings.setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36");

            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(embeddedWebView, true);
            }

            embeddedWebView.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void onGoogleProfileExtracted(String email, String name) {
                    if (callbackDispatched) return;
                    if (email != null && email.contains("@")) {
                        callbackDispatched = true;
                        mainHandler.post(() -> finishGoogleWebSignInWithProfile(email.trim(), name));
                    }
                }
            }, "FeaturesticGoogleBridge");

            embeddedWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100
                            ? android.view.View.GONE : android.view.View.VISIBLE);
                }
            });

            embeddedWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (url == null || callbackDispatched) return;
                    String lowerUrl = url.toLowerCase(Locale.US);
                    // Once Google login finishes and redirects to myaccount.google.com (or google.com/account),
                    // extract the signed-in Gmail address & Display Name and return RESULT_OK to the game!
                    if (lowerUrl.startsWith("https://myaccount.google.com")
                            || lowerUrl.startsWith("https://www.google.com/settings")
                            || lowerUrl.contains("myaccount.google.com/?utm_source=")) {
                        extractGoogleAccountFromWebViewAndFinish(view, true);
                    }
                }
            });

            webContainer.addView(embeddedWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(webContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            setContentView(root);

            String startUrl = googleLoginUrl;
            if (WebViewCompat.consumeForceFreshWebLogin()) {
                WebViewCompat.clearAllWebLoginSessions(this, embeddedWebView);
                WebViewCompat.consumeForceFreshWebLogin();
                startUrl = googleFreshLoginUrl;
            }
            java.util.Map<String, String> extraHeaders = new java.util.HashMap<>();
            extraHeaders.put("X-Requested-With", "com.android.chrome");
            embeddedWebView.loadUrl(startUrl, extraHeaders);
        } catch (Throwable ignored) {
            cancelEmbeddedWebView();
        }
    }

    private void extractGoogleAccountFromWebViewAndFinish(final WebView view, final boolean allowFallbackOnComplete) {
        if (view == null || callbackDispatched) return;
        try {
            String js = "(function(){"
                    + "try{"
                    + "var bodyText = document.body ? document.body.innerText : '';"
                    + "var htmlText = document.documentElement ? document.documentElement.innerHTML : '';"
                    + "var match = bodyText.match(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+[.][a-zA-Z]{2,}/);"
                    + "if(!match) match = htmlText.match(/[a-zA-Z0-9._%+-]+@gmail[.]com/);"
                    + "var email = match ? match[0] : '';"
                    + "var name = document.title ? document.title.replace(' - Google Account','').trim() : '';"
                    + "if(email){ FeaturesticGoogleBridge.onGoogleProfileExtracted(email, name); return email; }"
                    + "}catch(e){} return '';"
                    + "})();";
            view.evaluateJavascript(js, value -> {
                if (callbackDispatched) return;
                String cleaned = value != null ? value.replace(String.valueOf((char) 34), "").trim() : "";
                if (cleaned.contains("@")) {
                    callbackDispatched = true;
                    finishGoogleWebSignInWithProfile(cleaned, cleaned.split("@")[0]);
                } else if (allowFallbackOnComplete) {
                    String currentUrl = view.getUrl();
                    if (currentUrl != null && currentUrl.toLowerCase(Locale.US).contains("myaccount.google.com")) {
                        callbackDispatched = true;
                        finishGoogleWebSignInWithProfile("google.web.player@gmail.com", "Google Player");
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void finishGoogleWebSignInWithProfile(String email, String displayName) {
        try {
            WebViewCompat.enableAndFlushCookiesIfActive();
            final String safeEmail = (email != null && email.contains("@")) ? email.trim() : "player@gmail.com";
            final String safeName = (displayName != null && !displayName.trim().isEmpty()
                    && !displayName.contains("Google Account"))
                    ? displayName.trim() : safeEmail.split("@")[0];
            final String subId = String.valueOf(Math.abs((long) safeEmail.hashCode()) + 100000000000000000L);

            new Thread(() -> {
                String realToken = null;
                try {
                    Account acct = new Account(safeEmail, "com.google");
                    String srvClientId = serverClientId != null && !serverClientId.isEmpty()
                            ? serverClientId
                            : com.featurestic.box.compat.auth.GoogleSignInTokenHelper.getLastServerClientId();
                    String scope = (srvClientId != null && !srvClientId.isEmpty())
                            ? "audience:server:client_id:" + srvClientId
                            : "oauth2:email profile openid";
                    Class<?> authUtil = Class.forName("com.google.android.gms.auth.GoogleAuthUtil");
                    java.lang.reflect.Method getToken = authUtil.getMethod("getToken", android.content.Context.class, Account.class, String.class);
                    Object res = getToken.invoke(null, getApplicationContext(), acct, scope);
                    if (res instanceof String && !((String) res).isEmpty()) {
                        realToken = (String) res;
                    }
                } catch (Throwable ignored) {
                }
                final String finalToken = realToken;
                mainHandler.post(() -> {
                    try {
                        Intent resultIntent = new Intent();
                        resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_EMAIL, safeEmail);
                        resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_NAME, safeName);
                        resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_SUB, subId);
                        if (finalToken != null) {
                            resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_ID_TOKEN, finalToken);
                        }
                        com.featurestic.box.compat.auth.GoogleSignInTokenHelper.enrichSignInResultIntent(resultIntent, true);
                        completeBridgeResult(RESULT_OK, resultIntent);
                    } catch (Throwable ignored) {
                        completeBridgeResult(RESULT_CANCELED, null);
                    }
                });
            }, "Featurestic-GoogleWebTokenFetch").start();
        } catch (Throwable ignored) {
            completeBridgeResult(RESULT_CANCELED, null);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void launchEmbeddedOAuthWebView(Uri targetAuthUri) {
        try {
            final boolean isTwitter = twitterFlow || isTwitterHost(targetAuthUri);
            final boolean isFacebook = facebookFlow || isFacebookHost(targetAuthUri);
            final boolean isDiscord = isDiscordHost(targetAuthUri);

            final String brandTitle;
            final String brandSubtitle;
            final String brandIconText;
            final int brandColor;
            final int brandBgColor;

            if (isTwitter) {
                brandTitle = "Sign in with 𝕏";
                brandSubtitle = "Authorize Battlegrounds Mobile India";
                brandIconText = "𝕏";
                brandColor = Color.parseColor("#1D9BF0");
                brandBgColor = Color.parseColor("#000000");
            } else if (isFacebook) {
                brandTitle = "Log in with Facebook";
                brandSubtitle = "Continue to Battlegrounds Mobile India";
                brandIconText = "f";
                brandColor = Color.parseColor("#1877F2");
                brandBgColor = Color.parseColor("#1877F2");
            } else if (isDiscord) {
                brandTitle = "Authorize with Discord";
                brandSubtitle = "Connect to Battlegrounds Mobile India";
                brandIconText = "D";
                brandColor = Color.parseColor("#5865F2");
                brandBgColor = Color.parseColor("#5865F2");
            } else {
                brandTitle = "Sign In";
                brandSubtitle = "Continue to game";
                brandIconText = "★";
                brandColor = Color.parseColor("#A855F7");
                brandBgColor = Color.parseColor("#A855F7");
            }

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#13151B"));
            cardBg.setCornerRadius(dp(20));
            cardBg.setStroke(dp(1), Color.parseColor("#2A2E3D"));
            root.setBackground(cardBg);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                root.setClipToOutline(true);
            }

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(Color.parseColor("#191C24"));
            header.setPadding(dp(14), dp(10), dp(14), dp(10));

            TextView brandIconView = new TextView(this);
            brandIconView.setText(brandIconText);
            brandIconView.setTextSize(17f);
            brandIconView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            brandIconView.setGravity(Gravity.CENTER);
            brandIconView.setTextColor(Color.WHITE);
            android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
            iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            iconBg.setColor(brandBgColor);
            iconBg.setStroke(dp(1), brandColor);
            brandIconView.setBackground(iconBg);
            int iconPx = dp(36);
            header.addView(brandIconView, new LinearLayout.LayoutParams(iconPx, iconPx));

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setPadding(dp(10), 0, dp(10), 0);

            TextView titleTv = new TextView(this);
            titleTv.setText(brandTitle);
            titleTv.setTextColor(Color.WHITE);
            titleTv.setTextSize(14.5f);
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textCol.addView(titleTv);

            TextView subTv = new TextView(this);
            subTv.setText(brandSubtitle);
            subTv.setTextColor(Color.parseColor("#8E92A4"));
            subTv.setTextSize(11f);
            textCol.addView(subTv);

            header.addView(textCol, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final String installedDiscord = isDiscord
                    ? ExternalAuthRouter.resolveInstalledDiscordPackage() : null;
            if (installedDiscord != null) {
                TextView openAppBtn = new TextView(this);
                openAppBtn.setText("App");
                openAppBtn.setTextColor(Color.WHITE);
                openAppBtn.setTextSize(11.5f);
                android.graphics.drawable.GradientDrawable appBtnBg = new android.graphics.drawable.GradientDrawable();
                appBtnBg.setColor(Color.parseColor("#5865F2"));
                appBtnBg.setCornerRadius(dp(12));
                openAppBtn.setBackground(appBtnBg);
                openAppBtn.setPadding(dp(9), dp(4), dp(9), dp(4));
                openAppBtn.setOnClickListener(v -> {
                    try {
                        Intent appIntent = new Intent(Intent.ACTION_VIEW, targetAuthUri);
                        appIntent.addCategory(Intent.CATEGORY_DEFAULT);
                        appIntent.addCategory(Intent.CATEGORY_BROWSABLE);
                        appIntent.setPackage(installedDiscord);
                        appIntent.putExtra(ExternalAuthRouter.EXTRA_DIRECT_PROVIDER_DISPATCH, true);
                        startActivityForResult(appIntent, REQUEST_AUTH_TAB);
                    } catch (Throwable ignored) {
                    }
                });
                LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                appLp.setMargins(0, 0, dp(8), 0);
                header.addView(openAppBtn, appLp);
            }

            TextView switchIdBtn = new TextView(this);
            switchIdBtn.setText("Switch ID");
            switchIdBtn.setTextColor(Color.parseColor("#A99BBE"));
            switchIdBtn.setTextSize(11.5f);
            android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
            pillBg.setColor(Color.parseColor("#262936"));
            pillBg.setCornerRadius(dp(12));
            switchIdBtn.setBackground(pillBg);
            switchIdBtn.setPadding(dp(9), dp(4), dp(9), dp(4));
            switchIdBtn.setOnClickListener(v -> {
                if (embeddedWebView != null && targetAuthUri != null) {
                    WebViewCompat.clearAllWebLoginSessions(VirtualOAuthBridgeActivity.this, embeddedWebView);
                    WebViewCompat.consumeForceFreshWebLogin();
                    String freshUrl = WebViewCompat.appendForceLoginParam(targetAuthUri.toString());
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("X-Requested-With", "com.android.chrome");
                    embeddedWebView.loadUrl(freshUrl, headers);
                    android.widget.Toast.makeText(VirtualOAuthBridgeActivity.this, "Session reset. Enter account credentials.", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.setMargins(0, 0, dp(8), 0);
            header.addView(switchIdBtn, pillLp);

            TextView closeBtn = new TextView(this);
            closeBtn.setText("✕");
            closeBtn.setTextColor(Color.parseColor("#8E92A4"));
            closeBtn.setTextSize(16f);
            closeBtn.setGravity(Gravity.CENTER);
            closeBtn.setPadding(dp(6), dp(4), dp(6), dp(4));
            closeBtn.setOnClickListener(v -> cancelEmbeddedWebView());
            header.addView(closeBtn);

            root.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final ProgressBar progressBar = new ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            root.addView(progressBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

            FrameLayout webContainer = new FrameLayout(this);
            embeddedWebView = new WebView(this);
            WebSettings settings = embeddedWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            settings.setUserAgentString(WebViewCompat.buildChromeCompatibleUserAgent(
                    settings.getUserAgentString()));

            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(embeddedWebView, true);
            }

            embeddedWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100
                            ? android.view.View.GONE : android.view.View.VISIBLE);
                }
            });

            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                settings.setOffscreenPreRaster(true);
            }
            NetworkLowLatencyOptimizer.acquireWifiLowLatencyLock(this);
            final int[] retryCount = new int[]{0};
            final int[] twitterHomeBounceCount = new int[]{0};

            embeddedWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    if (twitterFlow) {
                        WebViewCompat.syncTwitterAndXCookies();
                    }
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (twitterFlow) {
                        WebViewCompat.syncTwitterAndXCookies();
                        if (url != null && WebViewCompat.isTwitterHomeTimelineUrl(url)
                                && targetAuthUri != null && twitterHomeBounceCount[0] < 4) {
                            twitterHomeBounceCount[0]++;
                            String bounceUrl = targetAuthUri.toString();
                            if (twitterHomeBounceCount[0] == 2) {
                                bounceUrl = bounceUrl.replace("https://api.twitter.com/", "https://twitter.com/");
                            } else if (twitterHomeBounceCount[0] >= 3) {
                                bounceUrl = bounceUrl.replace("https://api.twitter.com/oauth/authorize", "https://x.com/i/oauth/authorize")
                                        .replace("https://twitter.com/oauth/authorize", "https://x.com/i/oauth/authorize");
                            }
                            view.loadUrl(bounceUrl);
                        }
                    }
                }
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri url = request != null ? request.getUrl() : null;
                    return handleEmbeddedWebViewNavigation(url);
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleEmbeddedWebViewNavigation(url != null ? Uri.parse(url) : null);
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                        WebView view, WebResourceRequest request) {
                    if (request != null && request.getUrl() != null
                            && NetworkLowLatencyOptimizer.shouldBlockLowBandwidthTracker(
                            request.getUrl().toString())) {
                        return new android.webkit.WebResourceResponse(
                                "text/plain", "UTF-8",
                                new java.io.ByteArrayInputStream(new byte[0]));
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onReceivedError(
                        final WebView view, int errorCode, String description, final String failingUrl) {
                    if (retryCount[0] < 2 && failingUrl != null
                            && (failingUrl.startsWith("https://") || failingUrl.startsWith("http://"))) {
                        retryCount[0]++;
                        mainHandler.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed() && view != null) {
                                view.loadUrl(failingUrl);
                            }
                        }, 500L);
                        return;
                    }
                    super.onReceivedError(view, errorCode, description, failingUrl);
                }
            });

            webContainer.addView(embeddedWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(webContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            setContentView(root);
            embeddedWebView.loadUrl(targetAuthUri.toString());
        } catch (Throwable ignored) {
            cancelEmbeddedWebView();
        }
    }

    private boolean handleEmbeddedWebViewNavigation(Uri uri) {
        if (uri == null || callbackDispatched) {
            return false;
        }
        String scheme = lower(uri.getScheme());
        if ("intent".equals(scheme)) {
            try {
                Intent parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                if (parsed != null && parsed.getData() != null) {
                    uri = parsed.getData();
                    scheme = lower(uri.getScheme());
                }
            } catch (Throwable ignored) {
                return true;
            }
        }
        if ("http".equals(scheme) || "https".equals(scheme)
                || "about".equals(scheme) || "javascript".equals(scheme)
                || "data".equals(scheme) || "blob".equals(scheme)) {
            return false;
        }

        if (matchesExpectedCallback(uri) || redirectResolvesToVirtualPackage(uri)) {
            callbackDispatched = true;
            WebViewCompat.enableAndFlushCookiesIfActive();
            deliverInterceptedCallback(uri);
            return true;
        }

        List<Uri> candidates = DeclaredOAuthCallbackResolver.findCandidates(virtualPackage, userId);
        for (Uri candidate : candidates) {
            if (lower(candidate.getScheme()).equals(scheme)) {
                callbackDispatched = true;
                WebViewCompat.enableAndFlushCookiesIfActive();
                deliverInterceptedCallback(uri);
                return true;
            }
        }
        return false;
    }

    private void deliverInterceptedCallback(Uri callbackUri) {
        if (facebookFlow) {
            dispatchFacebookCallback(virtualPackage, userId, callbackUri);
            Intent fbData = new Intent(Intent.ACTION_VIEW, callbackUri);
            fbData.putExtra(FACEBOOK_CUSTOM_TAB_EXTRA_URL, callbackUri.toString());
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_OK, fbData);
            } else {
                finish();
                overridePendingTransition(0, 0);
            }
            return;
        }

        Intent resultIntent = new Intent(Intent.ACTION_VIEW, callbackUri);
        resultIntent.addCategory(Intent.CATEGORY_DEFAULT);
        resultIntent.addCategory(Intent.CATEGORY_BROWSABLE);

        if (resultBridgeMode) {
            completeBridgeResult(RESULT_OK, resultIntent);
            return;
        }
        dispatchToVirtualPackage(callbackUri);
        finish();
        overridePendingTransition(0, 0);
    }

    private void cancelEmbeddedWebView() {
        if (facebookFlow) {
            FacebookOAuthSessionStore.clear(virtualPackage, userId);
        }
        if (resultBridgeMode) {
            completeBridgeResult(RESULT_CANCELED, null);
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (embeddedWebView != null && embeddedWebView.canGoBack()) {
            embeddedWebView.goBack();
            return;
        }
        if (embeddedWebView != null) {
            cancelEmbeddedWebView();
            return;
        }
        super.onBackPressed();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void launchExternalProvider(Intent bridgeIntent) {
        try {
            IntentSender providerSender = bridgeIntent.getParcelableExtra(
                    ExternalAuthRouter.EXTRA_PROVIDER_INTENT_SENDER);
            if (providerSender != null) {
                if (!ExternalAuthRouter.isTrustedProviderIntentSender(providerSender)) {
                    authDiagnostic("sender_rejected", null, false);
                    completeBridgeResult(RESULT_CANCELED, null);
                    return;
                }

                Intent fillInIntent = bridgeIntent.getParcelableExtra(
                        ExternalAuthRouter.EXTRA_PROVIDER_FILL_IN_INTENT);
                int flagsMask = bridgeIntent.getIntExtra(
                        ExternalAuthRouter.EXTRA_PROVIDER_FLAGS_MASK, 0);
                int flagsValues = bridgeIntent.getIntExtra(
                        ExternalAuthRouter.EXTRA_PROVIDER_FLAGS_VALUES, 0);
                Bundle options = bridgeIntent.getBundleExtra(
                        ExternalAuthRouter.EXTRA_PROVIDER_OPTIONS);

                authDiagnostic("sender_launch", providerSender.getCreatorPackage(), false);
                startIntentSenderForResult(
                        providerSender,
                        REQUEST_EXTERNAL_AUTH,
                        fillInIntent,
                        flagsMask,
                        flagsValues,
                        0,
                        options);
                return;
            }

            Intent providerIntent = bridgeIntent.getParcelableExtra(
                    ExternalAuthRouter.EXTRA_PROVIDER_INTENT);
            String providerPackage = ExternalAuthRouter.getTrustedProviderPackage(providerIntent);
            if (providerPackage == null) {
                authDiagnostic("provider_rejected", null, false);
                completeBridgeResult(RESULT_CANCELED, null);
                return;
            }

            authDiagnostic("provider_launch", providerPackage, false);
            startActivityForResult(providerIntent, REQUEST_EXTERNAL_AUTH);
        } catch (Throwable error) {
            authDiagnostic("provider_launch_failed", null, false);
            completeBridgeResult(RESULT_CANCELED, null);
        }
    }

    private void launchAuthTab(Uri authUri, String redirectScheme, String provider) {
        try {
            Intent authIntent = new Intent(Intent.ACTION_VIEW, authUri);
            authIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            authIntent.setPackage(provider);
            authIntent.putExtra(AuthTabCompat.EXTRA_LAUNCH_AUTH_TAB, true);
            authIntent.putExtra(AuthTabCompat.EXTRA_REDIRECT_SCHEME, redirectScheme);

            Bundle session = new Bundle();
            session.putBinder(AuthTabCompat.EXTRA_CUSTOM_TABS_SESSION, null);
            authIntent.putExtras(session);

            startActivityForResult(authIntent, REQUEST_AUTH_TAB);
        } catch (Throwable ignored) {
            launchEmbeddedOAuthWebView(authUri);
        }
    }

    private void onFacebookSessionCompleted() {
        if (isFinishing() || isDestroyed() || callbackDispatched) {
            return;
        }
        if (FacebookOAuthSessionStore.isCompleted(virtualPackage, userId)) {
            callbackDispatched = true;
            mainHandler.removeCallbacksAndMessages(null);
            Uri completedUri = FacebookOAuthSessionStore.getCompletedCallbackUri(virtualPackage, userId);
            settleFacebookSuccess(completedUri);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        FacebookOAuthSessionStore.setCompletionListener(null);
        if (embeddedWebView != null) {
            try {
                embeddedWebView.stopLoading();
                embeddedWebView.destroy();
            } catch (Throwable ignored) {
            }
            embeddedWebView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CHOOSE_GOOGLE_ACCOUNT) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null && accountName.contains("@")) {
                    callbackDispatched = true;
                    String displayName = accountName.split("@")[0];
                    finishGoogleWebSignInWithProfile(accountName.trim(), displayName);
                    return;
                }
            }
            completeBridgeResult(RESULT_CANCELED, null);
            return;
        }

        if (requestCode == REQUEST_EXTERNAL_AUTH && externalAuthMode) {
            String providerPkg = providerPackageFromBridge();
            if (isFacebookProviderPackage(providerPkg) && data != null) {
                data = sanitizeFacebookNativeResult(resultCode, data);
                if (data.getBooleanExtra("com.featurestic.box.fb_retry_custom_tab", false)) {
                    data.removeExtra("com.featurestic.box.fb_retry_custom_tab");
                    resultCode = RESULT_OK;
                }
            }
            boolean delivered = completeBridgeResult(resultCode, data);
            authDiagnostic("provider_result", providerPkg, delivered);
            return;
        }

        if (requestCode != REQUEST_AUTH_TAB) {
            return;
        }

        if (facebookFlow) {
            handleFacebookAuthResult(resultCode, data);
            return;
        }

        if (data == null || data.getData() == null) {
            diagnostic("auth_not_completed", false, false, false, false, false);
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_CANCELED, null);
            } else {
                finish();
            }
            return;
        }

        Uri callbackUri = data.getData();
        if (!matchesExpectedCallback(callbackUri)) {
            diagnostic("callback_mismatch", false, false, false, false, false);
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_CANCELED, null);
            } else {
                finish();
            }
            return;
        }

        boolean hasToken = hasQueryParameter(callbackUri, "oauth_token");
        boolean hasVerifier = hasQueryParameter(callbackUri, "oauth_verifier");
        boolean hasCode = hasQueryParameter(callbackUri, "code");
        boolean denied = hasQueryParameter(callbackUri, "denied")
                || hasQueryParameter(callbackUri, "error");

        if (legacyTwitterFlow && !denied && (!hasToken || !hasVerifier)) {
            diagnostic("twitter_oauth1_incomplete",
                    hasToken, hasVerifier, hasCode, denied, false);
            if (resultBridgeMode) {
                completeBridgeResult(RESULT_CANCELED, null);
            } else {
                finish();
            }
            return;
        }

        if (resultBridgeMode) {
            boolean delivered = completeBridgeResult(RESULT_OK, data);
            diagnostic(delivered ? "result_bridge_delivered" : "result_bridge_delivery_failed",
                    hasToken, hasVerifier, hasCode, denied, delivered);
            return;
        }

        boolean dispatched = dispatchToVirtualPackage(callbackUri);
        diagnostic(dispatched ? "callback_dispatched" : "callback_unresolved",
                hasToken, hasVerifier, hasCode, denied, dispatched);
        finish();
    }

    private void handleFacebookAuthResult(int resultCode, Intent data) {
        if (facebookResultPending || callbackDispatched || isFinishing()) {
            return;
        }

        if (FacebookOAuthSessionStore.isCompleted(virtualPackage, userId)) {
            onFacebookSessionCompleted();
            return;
        }

        Uri callbackUri = data == null ? null : data.getData();
        if (callbackUri != null && matchesExpectedCallback(callbackUri)) {
            FacebookOAuthSessionStore.Claim claim =
                    FacebookOAuthSessionStore.claim(callbackUri);
            boolean alreadyDelivered = FacebookOAuthSessionStore.isCompleted(
                    virtualPackage, userId);
            boolean delivered = alreadyDelivered;

            if (!delivered && claim != null
                    && virtualPackage.equals(claim.virtualPackage)
                    && userId == claim.userId) {
                delivered = dispatchFacebookCallback(
                        claim.virtualPackage, claim.userId, callbackUri);
                if (delivered) {
                    FacebookOAuthSessionStore.complete(claim.generation);
                } else {
                    FacebookOAuthSessionStore.release(claim.generation);
                }
            }

            facebookDiagnostic(
                    delivered ? "callback_dispatched" : "callback_delivery_failed",
                    resultCode,
                    data,
                    callbackUri,
                    delivered);
            if (delivered) {
                callbackDispatched = true;
                settleFacebookSuccess(callbackUri);
            } else {
                failFacebookResult();
            }
            return;
        }

        if (callbackUri != null) {
            facebookDiagnostic("callback_mismatch", resultCode, data, callbackUri, false);
            failFacebookResult();
            return;
        }

        facebookDiagnostic("auth_not_completed", resultCode, data, callbackUri, false);
        facebookResultPending = true;
        mainHandler.postDelayed(() -> {
            facebookResultPending = false;
            if (isFinishing() || isDestroyed() || callbackDispatched) {
                return;
            }
            boolean delivered = FacebookOAuthSessionStore.isCompleted(
                    virtualPackage, userId);
            Uri completedUri = FacebookOAuthSessionStore.getCompletedCallbackUri(
                    virtualPackage, userId);
            facebookDiagnostic(
                    delivered ? "fallback_completed" : "fallback_timeout",
                    resultCode,
                    data,
                    completedUri,
                    delivered);
            if (delivered) {
                callbackDispatched = true;
                settleFacebookSuccess(completedUri);
            } else {
                failFacebookResult();
            }
        }, FACEBOOK_FALLBACK_WAIT_MS);
    }

    private void settleFacebookSuccess(Uri completedUri) {
        facebookResultPending = true;
        mainHandler.postDelayed(() -> {
            facebookResultPending = false;
            FacebookOAuthSessionStore.setCompletionListener(null);
            FacebookOAuthSessionStore.clear(virtualPackage, userId);
            if (!isFinishing() && !isDestroyed()) {
                if (resultBridgeMode && completedUri != null) {
                    Intent resultData = new Intent(Intent.ACTION_VIEW, completedUri);
                    resultData.putExtra(FACEBOOK_CUSTOM_TAB_EXTRA_URL, completedUri.toString());
                    completeBridgeResult(RESULT_OK, resultData);
                } else {
                    finish();
                    overridePendingTransition(0, 0);
                }
            }
        }, FACEBOOK_HANDOFF_SETTLE_MS);
    }

    private void failFacebookResult() {
        facebookResultPending = false;
        FacebookOAuthSessionStore.setCompletionListener(null);
        FacebookOAuthSessionStore.clear(virtualPackage, userId);
        if (resultBridgeMode) {
            completeBridgeResult(RESULT_CANCELED, null);
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private boolean completeBridgeResult(int resultCode, Intent data) {
        if (!manualResultRelay) {
            try {
                setResult(resultCode, data);
                finish();
                overridePendingTransition(0, 0);
                return true;
            } catch (Throwable ignored) {
                finish();
                return false;
            }
        }

        boolean delivered = relayOriginalActivityResult(resultCode, data);
        finish();
        overridePendingTransition(0, 0);
        return delivered;
    }

    private boolean validResultTarget(Intent launchIntent) {
        if (launchIntent == null || resultBpid < 0 || resultBpid > 24) {
            return false;
        }
        Bundle extras = launchIntent.getExtras();
        if (extras == null) {
            return false;
        }
        IBinder resultTo = extras.getBinder(ExternalAuthRouter.EXTRA_RESULT_BINDER);
        int requestCode = extras.getInt(ExternalAuthRouter.EXTRA_REQUEST_CODE, -1);
        String targetPackage = extras.getString(ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
        return resultTo != null
                && requestCode >= 0
                && targetPackage != null
                && !targetPackage.trim().isEmpty();
    }

    private boolean relayOriginalActivityResult(int resultCode, Intent data) {
        try {
            Intent bridgeIntent = getIntent();
            Bundle extras = bridgeIntent == null ? null : bridgeIntent.getExtras();
            if (extras == null || resultBpid < 0 || resultBpid > 24) {
                return false;
            }

            IBinder resultTo = extras.getBinder(ExternalAuthRouter.EXTRA_RESULT_BINDER);
            String resultWho = extras.getString(ExternalAuthRouter.EXTRA_RESULT_WHO);
            int originalRequestCode = extras.getInt(
                    ExternalAuthRouter.EXTRA_REQUEST_CODE, -1);
            String targetPackage = extras.getString(
                    ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE);
            if (resultTo == null || originalRequestCode < 0
                    || targetPackage == null || targetPackage.trim().isEmpty()) {
                return false;
            }

            Bundle relay = new Bundle();
            BundleCompat.putBinder(
                    relay, ExternalAuthRouter.EXTRA_RESULT_BINDER, resultTo);
            relay.putString(ExternalAuthRouter.EXTRA_RESULT_WHO, resultWho);
            relay.putInt(ExternalAuthRouter.EXTRA_REQUEST_CODE, originalRequestCode);
            relay.putInt(ExternalAuthRouter.EXTRA_RESULT_CODE, resultCode);
            relay.putInt(ExternalAuthRouter.EXTRA_BPID, resultBpid);
            relay.putInt(ExternalAuthRouter.EXTRA_USER_ID, userId);
            relay.putString(ExternalAuthRouter.EXTRA_VIRTUAL_PACKAGE, targetPackage);
            if (data != null) {
                relay.putParcelable(
                        ExternalAuthRouter.EXTRA_RESULT_DATA, new Intent(data));
            }

            Bundle response = ProviderCall.callSafely(
                    ProxyManifest.getProxyAuthorities(resultBpid),
                    ExternalAuthRouter.METHOD_DELIVER_ACTIVITY_RESULT,
                    null,
                    relay);
            return response != null
                    && response.getBoolean(
                    ExternalAuthRouter.EXTRA_RESULT_DELIVERED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFacebookProviderPackage(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase(Locale.US);
        return "com.facebook.katana".equals(lower)
                || "com.facebook.wakizashi".equals(lower)
                || "com.facebook.lite".equals(lower);
    }

    /**
     * Inspects the result returned by the native Facebook app (ProxyAuth).
     * If the native Facebook app succeeded, returns the Intent untouched for
     * 1-tap login. If the host Facebook app returned a key-hash or package-name
     * mismatch error (because the host Loader APK has a different signing cert
     * than the virtual game), rewrites the error code to "service_disabled"
     * (which is in Facebook SDK's ServerProtocol.errorsProxyAuthDisabled),
     * instructing Facebook's LoginClient to immediately and silently advance
     * to CustomTab / WebView login without displaying any error dialog.
     */
    private static Intent sanitizeFacebookNativeResult(int resultCode, Intent data) {
        if (data == null) return null;
        try {
            Bundle extras = data.getExtras();
            if (extras == null) return data;
            String error = extras.getString("error");
            if (error == null) error = extras.getString("error_type");
            if (error == null) error = extras.getString("com.facebook.platform.status.ERROR_TYPE");
            String errorMsg = extras.getString("error_message");
            if (errorMsg == null) errorMsg = extras.getString("error_description");
            if (errorMsg == null) errorMsg = extras.getString("com.facebook.platform.status.ERROR_DESCRIPTION");

            boolean hasToken = extras.containsKey("access_token")
                    || extras.containsKey("com.facebook.platform.extra.ACCESS_TOKEN")
                    || extras.containsKey("signed_request")
                    || extras.containsKey("code");

            if (!hasToken && (error != null || errorMsg != null)) {
                Intent fallback = new Intent(data);
                fallback.putExtra("error", "service_disabled");
                fallback.putExtra("error_type", "service_disabled");
                fallback.removeExtra("error_message");
                fallback.removeExtra("error_description");
                fallback.removeExtra("com.facebook.platform.status.ERROR_TYPE");
                fallback.removeExtra("com.facebook.platform.status.ERROR_DESCRIPTION");
                fallback.putExtra("com.featurestic.box.fb_retry_custom_tab", true);
                return fallback;
            }
        } catch (Throwable ignored) {
        }
        return data;
    }

    private String providerPackageFromBridge() {
        try {
            Intent bridgeIntent = getIntent();
            if (bridgeIntent == null) return null;
            Intent providerIntent = bridgeIntent.getParcelableExtra(
                    ExternalAuthRouter.EXTRA_PROVIDER_INTENT);
            if (providerIntent != null) {
                return ExternalAuthRouter.getTrustedProviderPackage(providerIntent);
            }
            IntentSender sender = bridgeIntent.getParcelableExtra(
                    ExternalAuthRouter.EXTRA_PROVIDER_INTENT_SENDER);
            return sender == null ? null : sender.getCreatorPackage();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean matchesExpectedCallback(Uri callbackUri) {
        return OAuthCallbackValidator.matches(authUri, expectedRedirectUri, callbackUri)
                && redirectResolvesToVirtualPackage(callbackUri);
    }

    private boolean dispatchToVirtualPackage(Uri callbackUri) {
        if (isFacebookHost(authUri)
                && dispatchFacebookCallback(virtualPackage, userId, callbackUri)) {
            return true;
        }

        try {
            Intent callback = new Intent(Intent.ACTION_VIEW, callbackUri);
            callback.addCategory(Intent.CATEGORY_DEFAULT);
            callback.addCategory(Intent.CATEGORY_BROWSABLE);
            callback.setPackage(virtualPackage);
            callback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            ResolveInfo resolved = BPackageManager.get().resolveActivity(
                    callback,
                    FileUtils.FileMode.MODE_IWUSR,
                    null,
                    userId);
            if (resolved == null || resolved.activityInfo == null
                    || !virtualPackage.equals(resolved.activityInfo.packageName)) {
                return false;
            }

            callback.setComponent(new ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name));
            BActivityManager.get().startActivity(callback, userId);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean dispatchFacebookCallback(
            String targetPackage, int targetUserId, Uri callbackUri) {
        if (callbackUri == null || targetPackage == null
                || targetPackage.trim().isEmpty() || targetUserId < 0) {
            return false;
        }

        if (virtualActivityExists(
                targetPackage, FACEBOOK_CUSTOM_TAB_MAIN_ACTIVITY, targetUserId)) {
            try {
                Intent callback = new Intent(FACEBOOK_CUSTOM_TAB_REDIRECT_ACTION, callbackUri);
                callback.setComponent(new ComponentName(
                        targetPackage, FACEBOOK_CUSTOM_TAB_MAIN_ACTIVITY));
                callback.putExtra(FACEBOOK_CUSTOM_TAB_EXTRA_URL, callbackUri.toString());
                callback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                BActivityManager.get().startActivity(callback, targetUserId);
                Log.i(TAG, "facebook stage=custom_tab_main_handoff delivered=true");
                return true;
            } catch (Throwable ignored) {
                Log.i(TAG, "facebook stage=custom_tab_main_handoff delivered=false");
            }
        }

        if (virtualActivityExists(
                targetPackage, FACEBOOK_CUSTOM_TAB_ACTIVITY, targetUserId)) {
            try {
                Intent callback = new Intent(Intent.ACTION_VIEW, callbackUri);
                callback.setComponent(new ComponentName(
                        targetPackage, FACEBOOK_CUSTOM_TAB_ACTIVITY));
                callback.addCategory(Intent.CATEGORY_DEFAULT);
                callback.addCategory(Intent.CATEGORY_BROWSABLE);
                callback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                BActivityManager.get().startActivity(callback, targetUserId);
                Log.i(TAG, "facebook stage=custom_tab_activity_handoff delivered=true");
                return true;
            } catch (Throwable ignored) {
                Log.i(TAG, "facebook stage=custom_tab_activity_handoff delivered=false");
            }
        }
        return false;
    }

    private static boolean virtualActivityExists(
            String packageName, String className, int targetUserId) {
        try {
            ActivityInfo info = BPackageManager.get().getActivityInfo(
                    new ComponentName(packageName, className), 0, targetUserId);
            return info != null
                    && packageName.equals(info.packageName)
                    && className.equals(info.name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean redirectResolvesToVirtualPackage(Uri redirectUri) {
        if (redirectUri == null || virtualPackage == null) return false;
        String scheme = lower(redirectUri.getScheme());
        if ("fbconnect".equals(scheme) || scheme.matches("^fb[0-9]+$")
                || TwitterOAuthSessionStore.isHostCaptureSupported(redirectUri)
                || "discord".equals(scheme) || scheme.startsWith("discord")) {
            return true;
        }
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

    private void diagnostic(String stage,
                            boolean hasToken,
                            boolean hasVerifier,
                            boolean hasCode,
                            boolean denied,
                            boolean dispatched) {
        if (!twitterFlow) {
            return;
        }
        Log.i(TAG, "twitter stage=" + stage
                + " oauth1=" + legacyTwitterFlow
                + " token=" + hasToken
                + " verifier=" + hasVerifier
                + " code=" + hasCode
                + " denied=" + denied
                + " dispatched=" + dispatched);
    }

    private void facebookDiagnostic(String stage,
                                    int resultCode,
                                    Intent data,
                                    Uri callbackUri,
                                    boolean delivered) {
        if (!facebookFlow) {
            return;
        }
        boolean uriPresent = callbackUri != null;
        boolean schemeMatch = uriPresent && expectedRedirectUri != null
                && lower(expectedRedirectUri.getScheme())
                .equals(lower(callbackUri.getScheme()));
        boolean authorityMatch = uriPresent && expectedRedirectUri != null
                && lower(expectedRedirectUri.getEncodedAuthority())
                .equals(lower(callbackUri.getEncodedAuthority()));
        boolean validated = uriPresent
                && OAuthCallbackValidator.matches(authUri, expectedRedirectUri, callbackUri);
        boolean virtualTarget = validated && redirectResolvesToVirtualPackage(callbackUri);
        boolean fallbackCompleted = FacebookOAuthSessionStore.isCompleted(
                virtualPackage, userId);

        Log.i(TAG, "facebook stage=" + stage
                + " result=" + resultCode
                + " data=" + (data != null)
                + " uri=" + uriPresent
                + " scheme_match=" + schemeMatch
                + " authority_match=" + authorityMatch
                + " validated=" + validated
                + " virtual_target=" + virtualTarget
                + " fallback_completed=" + fallbackCompleted
                + " delivered=" + delivered);
    }

    private static void authDiagnostic(String stage, String provider, boolean delivered) {
        String safeProvider = ExternalAuthRouter.isTrustedProviderPackage(provider)
                ? provider : "unknown";
        Log.i(AUTH_TAG, "native stage=" + stage
                + " provider=" + safeProvider
                + " delivered=" + delivered);
    }

    private static boolean hasQueryParameter(Uri uri, String name) {
        if (uri == null || name == null) {
            return false;
        }
        try {
            String value = uri.getQueryParameter(name);
            if (value != null && !value.trim().isEmpty()) {
                return true;
            }
            String fragment = uri.getEncodedFragment();
            return fragment != null && (fragment.contains(name + "="));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDiscordHost(Uri uri) {
        if (uri == null) return false;
        String host = lower(uri.getHost());
        return "discord.com".equals(host)
                || "discordapp.com".equals(host)
                || host.endsWith(".discord.com")
                || host.endsWith(".discordapp.com");
    }

    private static boolean isFacebookHost(Uri uri) {
        if (uri == null) {
            return false;
        }
        String host = lower(uri.getHost());
        return "facebook.com".equals(host)
                || "fb.com".equals(host)
                || host.endsWith(".facebook.com")
                || host.endsWith(".fb.com");
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

    private static Uri safeHttpsUri(String value) {
        if (value == null || value.length() > 16_384) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? uri : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Uri safeCustomRedirectUri(String value) {
        if (value == null || value.length() > 8_192) {
            return null;
        }
        try {
            Uri uri = Uri.parse(value);
            String scheme = lower(uri.getScheme());
            if (scheme.isEmpty()
                    || "http".equals(scheme)
                    || "https".equals(scheme)
                    || "file".equals(scheme)
                    || "content".equals(scheme)
                    || "javascript".equals(scheme)
                    || "data".equals(scheme)
                    || "intent".equals(scheme)
                    || !scheme.matches("^[a-z][a-z0-9+.-]{1,127}$")) {
                return null;
            }
            return uri;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
