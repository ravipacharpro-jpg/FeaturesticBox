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
import android.view.View;
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
    private static final int REQUEST_GOOGLE_NATIVE_PICKER = 0x5044;
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
    private String googleScopes;

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
            googleScopes = launchIntent.getStringExtra(
                    ExternalAuthRouter.EXTRA_GOOGLE_SCOPES);
            if (getWindow() != null) {
                getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            if (savedInstanceState == null) {
                launchMicroGGoogleSignInFlow();
            }
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
        boolean discordFlow = isDiscordHost(authUri);
        legacyTwitterFlow = twitterFlow && hasQueryParameter(authUri, "oauth_token");
        boolean loaderLinkedSocial = false;
        try {
            android.content.SharedPreferences socialPrefs = getSharedPreferences("featurestic_social_accounts", MODE_PRIVATE);
            if (facebookFlow && socialPrefs.getBoolean("linked_facebook", false)) loaderLinkedSocial = true;
            if (twitterFlow && socialPrefs.getBoolean("linked_twitter", false)) loaderLinkedSocial = true;
            if (discordFlow && socialPrefs.getBoolean("linked_discord", false)) loaderLinkedSocial = true;
        } catch (Throwable ignored) {
        }
        boolean useEmbeddedWebView = loaderLinkedSocial
                || EMBEDDED_WEBVIEW_PROVIDER.equals(authProvider)
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

    private void launchMicroGGoogleSignInFlow() {
        try {
            com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord active =
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.getActiveAccount(this);
            if (active != null && active.email != null && active.masterToken != null) {
                Log.i(TAG, "Auto-signing in with Loader active Google account: " + active.email);
                performMicroGTokenExchangeAndFinish(active);
                return;
            }
            launchMicroGEmbeddedSetupWebView(null);
        } catch (Throwable t) {
            launchMicroGEmbeddedSetupWebView(null);
        }
    }

    private void configureGooglePopupWindow(boolean tallForWebView) {
        try {
            if (getWindow() == null) return;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int targetW = tallForWebView
                    ? Math.min(dp(440), (int) (dm.widthPixels * 0.92f))
                    : Math.min(dp(380), (int) (dm.widthPixels * 0.90f));
            int targetH = tallForWebView
                    ? (int) (dm.heightPixels * 0.94f)
                    : Math.min(dp(420), (int) (dm.heightPixels * 0.88f));
            getWindow().setLayout(targetW, targetH);
            getWindow().setGravity(Gravity.CENTER);
        } catch (Throwable ignored) {
        }
    }

    private void showMicroGAccountPicker(
            final java.util.List<com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord> accounts) {
        try {
            configureGooglePopupWindow(false);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(18), dp(18), dp(18), dp(16));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#181A20"));
            cardBg.setCornerRadius(dp(22));
            cardBg.setStroke(dp(1), Color.parseColor("#2E3240"));
            root.setBackground(cardBg);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView gBadge = new TextView(this);
            gBadge.setText("G");
            gBadge.setTextColor(Color.WHITE);
            gBadge.setTextSize(17f);
            gBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            gBadge.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gCircle = new android.graphics.drawable.GradientDrawable();
            gCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gCircle.setColor(Color.parseColor("#4285F4"));
            gBadge.setBackground(gCircle);
            header.addView(gBadge, new LinearLayout.LayoutParams(dp(34), dp(34)));

            LinearLayout titleCol = new LinearLayout(this);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tcLp.setMargins(dp(12), 0, dp(8), 0);

            TextView titleTv = new TextView(this);
            titleTv.setText("Choose an account");
            titleTv.setTextColor(Color.WHITE);
            titleTv.setTextSize(15.5f);
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleCol.addView(titleTv);

            TextView subTv = new TextView(this);
            subTv.setText("to continue to Battlegrounds Mobile India");
            subTv.setTextColor(Color.parseColor("#9AA0A6"));
            subTv.setTextSize(12f);
            titleCol.addView(subTv);
            header.addView(titleCol, tcLp);

            TextView closeBtn = new TextView(this);
            closeBtn.setText("✕");
            closeBtn.setTextColor(Color.parseColor("#9AA0A6"));
            closeBtn.setTextSize(17f);
            closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
            closeBtn.setOnClickListener(v -> cancelEmbeddedWebView());
            header.addView(closeBtn);
            root.addView(header);

            View div = new View(this);
            div.setBackgroundColor(Color.parseColor("#2A2E3A"));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.setMargins(0, dp(12), 0, dp(10));
            root.addView(div, divLp);

            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout listLayout = new LinearLayout(this);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            int[] avatarColors = new int[]{
                    Color.parseColor("#4285F4"),
                    Color.parseColor("#EA4335"),
                    Color.parseColor("#34A853"),
                    Color.parseColor("#FBBC05")
            };

            for (int i = 0; i < accounts.size(); i++) {
                final com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord acc = accounts.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(10), dp(10), dp(10));

                android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                rowBg.setColor(Color.parseColor("#232732"));
                rowBg.setCornerRadius(dp(14));
                rowBg.setStroke(dp(1), Color.parseColor("#323747"));
                row.setBackground(rowBg);

                TextView avatar = new TextView(this);
                String initial = (acc.displayName != null && !acc.displayName.isEmpty())
                        ? acc.displayName.substring(0, 1).toUpperCase(Locale.US)
                        : acc.email.substring(0, 1).toUpperCase(Locale.US);
                avatar.setText(initial);
                avatar.setTextColor(Color.WHITE);
                avatar.setTextSize(15f);
                avatar.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                avatar.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable avBg = new android.graphics.drawable.GradientDrawable();
                avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                avBg.setColor(avatarColors[i % avatarColors.length]);
                avatar.setBackground(avBg);
                row.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));

                LinearLayout infoCol = new LinearLayout(this);
                infoCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                icLp.setMargins(dp(12), 0, dp(8), 0);

                TextView nameTv = new TextView(this);
                nameTv.setText(acc.displayName != null && !acc.displayName.isEmpty()
                        ? acc.displayName : acc.email.split("@")[0]);
                nameTv.setTextColor(Color.WHITE);
                nameTv.setTextSize(14f);
                nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                nameTv.setSingleLine(true);
                infoCol.addView(nameTv);

                TextView emailTv = new TextView(this);
                emailTv.setText(acc.email);
                emailTv.setTextColor(Color.parseColor("#9AA0A6"));
                emailTv.setTextSize(12f);
                emailTv.setSingleLine(true);
                infoCol.addView(emailTv);
                row.addView(infoCol, icLp);

                TextView removeBtn = new TextView(this);
                removeBtn.setText("Remove");
                removeBtn.setTextColor(Color.parseColor("#F28B82"));
                removeBtn.setTextSize(11f);
                removeBtn.setPadding(dp(8), dp(6), dp(6), dp(6));
                removeBtn.setOnClickListener(v -> {
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.removeAccount(this, acc.email);
                    launchMicroGGoogleSignInFlow();
                });
                row.addView(removeBtn);

                row.setOnClickListener(v -> performMicroGTokenExchangeAndFinish(acc));

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(8));
                listLayout.addView(row, rowLp);
            }

            scrollView.addView(listLayout);
            root.addView(scrollView);

            TextView addAccountBtn = new TextView(this);
            addAccountBtn.setText("+ Use another Google account");
            addAccountBtn.setTextColor(Color.parseColor("#8AB4F8"));
            addAccountBtn.setTextSize(13.5f);
            addAccountBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            addAccountBtn.setGravity(Gravity.CENTER);
            addAccountBtn.setPadding(dp(12), dp(11), dp(12), dp(11));
            android.graphics.drawable.GradientDrawable addBg = new android.graphics.drawable.GradientDrawable();
            addBg.setColor(Color.parseColor("#202430"));
            addBg.setCornerRadius(dp(12));
            addBg.setStroke(dp(1), Color.parseColor("#3B4256"));
            addAccountBtn.setBackground(addBg);
            addAccountBtn.setOnClickListener(v -> launchMicroGEmbeddedSetupWebView(null));
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addLp.setMargins(0, dp(8), 0, 0);
            root.addView(addAccountBtn, addLp);

            setContentView(root);
        } catch (Throwable t) {
            launchMicroGEmbeddedSetupWebView(null);
        }
    }

    private void showMicroGLoadingView(String title, String subtitle) {
        try {
            configureGooglePopupWindow(false);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(24), dp(28), dp(24), dp(28));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#181A20"));
            cardBg.setCornerRadius(dp(22));
            cardBg.setStroke(dp(1), Color.parseColor("#2E3240"));
            root.setBackground(cardBg);

            android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
            pb.setIndeterminate(true);
            root.addView(pb, new LinearLayout.LayoutParams(dp(42), dp(42)));

            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(Color.WHITE);
            t.setTextSize(15f);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.setMargins(0, dp(14), 0, dp(4));
            root.addView(t, tLp);

            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(Color.parseColor("#9AA0A6"));
            s.setTextSize(12.5f);
            s.setGravity(Gravity.CENTER);
            root.addView(s, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(root);
        } catch (Throwable ignored) {
        }
    }

    private void showMicroGErrorView(String message, final Runnable retryAction) {
        try {
            configureGooglePopupWindow(false);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(dp(22), dp(24), dp(22), dp(22));

            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.parseColor("#181A20"));
            cardBg.setCornerRadius(dp(22));
            cardBg.setStroke(dp(1), Color.parseColor("#4C2B2B"));
            root.setBackground(cardBg);

            TextView t = new TextView(this);
            t.setText("Google Sign-In Error");
            t.setTextColor(Color.parseColor("#F28B82"));
            t.setTextSize(16f);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            root.addView(t);

            TextView msgTv = new TextView(this);
            msgTv.setText(message != null ? message : "Authentication failed. Please try again.");
            msgTv.setTextColor(Color.parseColor("#DADCE0"));
            msgTv.setTextSize(12.5f);
            msgTv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.setMargins(0, dp(10), 0, dp(18));
            root.addView(msgTv, mLp);

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.CENTER);

            TextView retryBtn = new TextView(this);
            retryBtn.setText("Try Again");
            retryBtn.setTextColor(Color.WHITE);
            retryBtn.setTextSize(13.5f);
            retryBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            retryBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
            android.graphics.drawable.GradientDrawable rBg = new android.graphics.drawable.GradientDrawable();
            rBg.setColor(Color.parseColor("#4285F4"));
            rBg.setCornerRadius(dp(12));
            retryBtn.setBackground(rBg);
            retryBtn.setOnClickListener(v -> {
                if (retryAction != null) retryAction.run();
                else launchMicroGEmbeddedSetupWebView(null);
            });
            btnRow.addView(retryBtn);

            TextView cancelBtn = new TextView(this);
            cancelBtn.setText("Cancel");
            cancelBtn.setTextColor(Color.parseColor("#9AA0A6"));
            cancelBtn.setTextSize(13.5f);
            cancelBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
            cancelBtn.setOnClickListener(v -> cancelEmbeddedWebView());
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.setMargins(dp(10), 0, 0, 0);
            btnRow.addView(cancelBtn, cLp);

            root.addView(btnRow);
            setContentView(root);
        } catch (Throwable ignored) {
            cancelEmbeddedWebView();
        }
    }

    private void performMicroGTokenExchangeAndFinish(
            final com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord account) {
        if (callbackDispatched) return;
        showMicroGLoadingView("Signing in with Google...", account.email);
        new Thread(() -> {
            final com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.TokenBundle bundle =
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.performAppSignIn(
                            getApplicationContext(),
                            account,
                            virtualPackage,
                            serverClientId,
                            googleScopes);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed() || callbackDispatched) return;
                if (bundle != null && bundle.idToken != null && bundle.idToken.contains(".")) {
                    deliverMicroGSuccessResult(bundle);
                } else if (bundle != null && bundle.needsReAuth) {
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.removeAccount(
                            getApplicationContext(), account.email);
                    android.widget.Toast.makeText(
                            VirtualOAuthBridgeActivity.this,
                            "Session expired, please sign in again.",
                            android.widget.Toast.LENGTH_SHORT).show();
                    launchMicroGEmbeddedSetupWebView(account.email);
                } else {
                    String err = bundle != null && bundle.error != null
                            ? bundle.error : "Could not acquire Google ID token";
                    showMicroGErrorView(err, () -> launchMicroGEmbeddedSetupWebView(account.email));
                }
            });
        }, "Featurestic-MicroGTokenExchange").start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void launchMicroGEmbeddedSetupWebView(final String reAuthEmail) {
        try {
            configureGooglePopupWindow(true);

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.WHITE);
            cardBg.setCornerRadius(dp(18));
            cardBg.setStroke(dp(1), Color.parseColor("#2A2E3D"));
            root.setBackground(cardBg);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                root.setClipToOutline(true);
            }

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setBackgroundColor(Color.parseColor("#181A20"));
            header.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView gBadge = new TextView(this);
            gBadge.setText("G");
            gBadge.setTextColor(Color.WHITE);
            gBadge.setTextSize(13f);
            gBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            gBadge.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gCircle = new android.graphics.drawable.GradientDrawable();
            gCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gCircle.setColor(Color.parseColor("#4285F4"));
            gBadge.setBackground(gCircle);
            header.addView(gBadge, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView titleTv = new TextView(this);
            titleTv.setText("Sign in with Google");
            titleTv.setTextColor(Color.WHITE);
            titleTv.setTextSize(13.5f);
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tLp.setMargins(dp(10), 0, dp(8), 0);
            header.addView(titleTv, tLp);

            final java.util.List<com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord> saved =
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.getSavedAccounts(this);
            if (saved != null && !saved.isEmpty()) {
                TextView savedBtn = new TextView(this);
                savedBtn.setText("Accounts");
                savedBtn.setTextColor(Color.parseColor("#8AB4F8"));
                savedBtn.setTextSize(12f);
                savedBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
                savedBtn.setOnClickListener(v -> showMicroGAccountPicker(saved));
                header.addView(savedBtn);
            }

            TextView closeBtn = new TextView(this);
            closeBtn.setText("✕");
            closeBtn.setTextColor(Color.parseColor("#9AA0A6"));
            closeBtn.setTextSize(16f);
            closeBtn.setPadding(dp(8), dp(2), dp(4), dp(2));
            closeBtn.setOnClickListener(v -> cancelEmbeddedWebView());
            header.addView(closeBtn);
            root.addView(header);

            final android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setIndeterminate(true);
            progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            root.addView(progressBar);

            if (embeddedWebView != null) {
                try {
                    embeddedWebView.destroy();
                } catch (Throwable ignored) {
                }
            }
            final WebView wv = new WebView(this);
            embeddedWebView = wv;
            wv.setFocusable(true);
            wv.setFocusableInTouchMode(true);
            wv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(false);
            ws.setSupportMultipleWindows(false);
            ws.setSaveFormData(false);
            ws.setAllowFileAccess(false);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setSupportZoom(false);
            ws.setJavaScriptCanOpenWindowsAutomatically(false);
            String baseUa = ws.getUserAgentString();
            if (baseUa == null) {
                baseUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36";
            }
            if (!baseUa.contains("MinuteMaid")) {
                ws.setUserAgentString(baseUa + com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.MAGIC_USER_AGENT_SUFFIX);
            }

            final android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (cm != null) {
                cm.setAcceptCookie(true);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    cm.setAcceptThirdPartyCookies(wv, true);
                }
            }

            final boolean[] exchanging = new boolean[]{false};
            final Runnable[] completeFromCookiesRef = new Runnable[1];
            completeFromCookiesRef[0] = () -> {
                if (exchanging[0] || callbackDispatched) return;
                String oauthToken = extractOAuthTokenCookie(cm);
                if (oauthToken == null || oauthToken.isEmpty()) {
                    return;
                }
                exchanging[0] = true;
                mainHandler.post(() -> {
                    showMicroGLoadingView("Authorizing with Google...", "Fetching official token for BGMI");
                    new Thread(() -> {
                        try {
                            com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord rec =
                                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine
                                            .exchangeOAuthCookieForMasterToken(getApplicationContext(), oauthToken);
                            com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.TokenBundle bundle =
                                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.performAppSignIn(
                                            getApplicationContext(),
                                            rec,
                                            virtualPackage,
                                            serverClientId,
                                            googleScopes);
                            mainHandler.post(() -> {
                                if (isFinishing() || isDestroyed() || callbackDispatched) return;
                                if (bundle != null && bundle.idToken != null && bundle.idToken.contains(".")) {
                                    deliverMicroGSuccessResult(bundle);
                                } else {
                                    String err = bundle != null && bundle.error != null
                                            ? bundle.error : "Google did not return an ID token";
                                    showMicroGErrorView(err, () -> launchMicroGEmbeddedSetupWebView(rec.email));
                                }
                            });
                        } catch (final Throwable t) {
                            mainHandler.post(() -> {
                                if (isFinishing() || isDestroyed() || callbackDispatched) return;
                                showMicroGErrorView(
                                        "Google auth error: " + t.getMessage(),
                                        () -> launchMicroGEmbeddedSetupWebView(reAuthEmail));
                            });
                        }
                    }, "Featurestic-MicroGMasterExchange").start();
                });
            };

            wv.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public void closeView() {
                    mainHandler.post(completeFromCookiesRef[0]);
                }

                @android.webkit.JavascriptInterface
                public void showView() {
                    mainHandler.post(() -> {
                        progressBar.setVisibility(View.GONE);
                        wv.setVisibility(View.VISIBLE);
                    });
                }

                @android.webkit.JavascriptInterface
                public void skipLogin() {
                    mainHandler.post(() -> cancelEmbeddedWebView());
                }

                @android.webkit.JavascriptInterface
                public String getAccounts() {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    try {
                        for (com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord r :
                                com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.getSavedAccounts(VirtualOAuthBridgeActivity.this)) {
                            arr.put(r.email);
                        }
                    } catch (Throwable ignored) {
                    }
                    return arr.toString();
                }

                @android.webkit.JavascriptInterface
                public String getAllowedDomains() {
                    return "[]";
                }

                @android.webkit.JavascriptInterface
                public String getAndroidId() {
                    return com.featurestic.box.compat.auth.MicroGGoogleAuthEngine
                            .resolveAndroidIdHex(VirtualOAuthBridgeActivity.this);
                }

                @android.webkit.JavascriptInterface
                public int getAuthModuleVersionCode() {
                    return com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.GMS_VERSION_CODE;
                }

                @android.webkit.JavascriptInterface
                public int getPlayServicesVersionCode() {
                    return com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.GMS_VERSION_CODE;
                }

                @android.webkit.JavascriptInterface
                public int getBuildVersionSdk() {
                    return android.os.Build.VERSION.SDK_INT;
                }

                @android.webkit.JavascriptInterface
                public int getDeviceContactsCount() {
                    return -1;
                }

                @android.webkit.JavascriptInterface
                public int getDeviceDataVersionInfo() {
                    return 1;
                }

                @android.webkit.JavascriptInterface
                public String getFactoryResetChallenges() {
                    return "[]";
                }

                @android.webkit.JavascriptInterface
                public String getPhoneNumber() {
                    return null;
                }

                @android.webkit.JavascriptInterface
                public String getSimSerial() {
                    return null;
                }

                @android.webkit.JavascriptInterface
                public int getSimState() {
                    return 0;
                }

                @android.webkit.JavascriptInterface
                public boolean hasPhoneNumber() {
                    return false;
                }

                @android.webkit.JavascriptInterface
                public boolean hasTelephony() {
                    return false;
                }

                @android.webkit.JavascriptInterface
                public boolean isUserOwner() {
                    return true;
                }

                @android.webkit.JavascriptInterface
                public String fetchVerifiedPhoneNumber() {
                    return null;
                }

                @android.webkit.JavascriptInterface
                public void getDroidGuardResult(String s) {
                    mainHandler.post(() -> {
                        try {
                            wv.evaluateJavascript("try{if(window.setDgResult){window.setDgResult('');}}catch(e){}", null);
                        } catch (Throwable ignored) {
                        }
                    });
                }

                @android.webkit.JavascriptInterface
                public void addAccount(String json) {
                }

                @android.webkit.JavascriptInterface
                public void attemptLogin(String accountName, String password) {
                }

                @android.webkit.JavascriptInterface
                public void backupSyncOptIn(String accountName) {
                }

                @android.webkit.JavascriptInterface
                public void clearOldLoginAttempts() {
                }

                @android.webkit.JavascriptInterface
                public void fetchIIDToken(String entity) {
                }

                @android.webkit.JavascriptInterface
                public void goBack() {
                }

                @android.webkit.JavascriptInterface
                public void hideKeyboard() {
                    mainHandler.post(() -> {
                        try {
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            if (imm != null) imm.hideSoftInputFromWindow(wv.getWindowToken(), 0);
                        } catch (Throwable ignored) {
                        }
                    });
                }

                @android.webkit.JavascriptInterface
                public void showKeyboard() {
                    mainHandler.post(() -> {
                        try {
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(wv, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }

                @android.webkit.JavascriptInterface
                public void launchEmergencyDialer() {
                }

                @android.webkit.JavascriptInterface
                public void log(String s) {
                }

                @android.webkit.JavascriptInterface
                public void notifyOnTermsOfServiceAccepted() {
                }

                @android.webkit.JavascriptInterface
                public void setAccountIdentifier(String accountName) {
                }

                @android.webkit.JavascriptInterface
                public void setAllActionsEnabled(boolean z) {
                }

                @android.webkit.JavascriptInterface
                public void setBackButtonEnabled(boolean z) {
                }

                @android.webkit.JavascriptInterface
                public void setNewAccountCreated() {
                }

                @android.webkit.JavascriptInterface
                public void setPrimaryActionEnabled(boolean z) {
                }

                @android.webkit.JavascriptInterface
                public void setPrimaryActionLabel(String str, int i) {
                }

                @android.webkit.JavascriptInterface
                public void setSecondaryActionEnabled(boolean z) {
                }

                @android.webkit.JavascriptInterface
                public void setSecondaryActionLabel(String str, int i) {
                }

                @android.webkit.JavascriptInterface
                public void startAfw() {
                }

                @android.webkit.JavascriptInterface
                public void startFido2SignRequest(String request) {
                }

                @android.webkit.JavascriptInterface
                public void cancelFido2SignRequest() {
                }

                @android.webkit.JavascriptInterface
                public void sendFido2SkUiEvent(String event) {
                }
            }, "mm");

            wv.setWebChromeClient(new android.webkit.WebChromeClient());
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    progressBar.setVisibility(View.GONE);
                    if (url == null || exchanging[0] || callbackDispatched) return;
                    try {
                        Uri uri = Uri.parse(url);
                        if ("close".equals(uri.getFragment())
                                || url.startsWith(com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.GOOGLE_SUITE_URL)
                                || url.startsWith(com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.PROGRAMMATIC_AUTH_URL)) {
                            completeFromCookiesRef[0].run();
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (extractOAuthTokenCookie(cm) != null) {
                        completeFromCookiesRef[0].run();
                    }
                }
            });

            root.addView(wv);
            setContentView(root);

            final String setupUrl = com.featurestic.box.compat.auth.MicroGGoogleAuthEngine
                    .buildEmbeddedSetupUrl(Locale.getDefault(), reAuthEmail);
            if (cm != null) {
                try {
                    cm.setCookie("https://accounts.google.com", "oauth_token=; Max-Age=0; Path=/");
                    cm.setCookie(com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL, "oauth_token=; Max-Age=0; Path=/");
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        cm.flush();
                    }
                } catch (Throwable ignored) {
                }
            }
            wv.loadUrl(setupUrl);
        } catch (Throwable t) {
            showMicroGErrorView("WebView error: " + t.getMessage(), null);
        }
    }

    private String extractOAuthTokenCookie(android.webkit.CookieManager cm) {
        if (cm == null) return null;
        String[] urls = new String[]{
                com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL,
                com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.PROGRAMMATIC_AUTH_URL,
                com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.EMBEDDED_RE_AUTH_URL,
                "https://accounts.google.com"
        };
        for (String u : urls) {
            try {
                String cookies = cm.getCookie(u);
                if (cookies == null || cookies.isEmpty()) continue;
                String[] parts = cookies.split(";");
                for (String p : parts) {
                    String trimmed = p.trim();
                    if (trimmed.startsWith("oauth_token=")) {
                        String val = trimmed.substring("oauth_token=".length()).trim();
                        if (!val.isEmpty()) return val;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void deliverMicroGSuccessResult(
            com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.TokenBundle bundle) {
        if (callbackDispatched) return;
        callbackDispatched = true;
        try {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_EMAIL, bundle.email);
            if (bundle.displayName != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_NAME, bundle.displayName);
            }
            if (bundle.googleUserId != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_SUB, bundle.googleUserId);
            }
            if (bundle.idToken != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_ID_TOKEN, bundle.idToken);
            }
            if (bundle.serverAuthCode != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_AUTH_CODE, bundle.serverAuthCode);
            }
            if (bundle.givenName != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_GIVEN_NAME, bundle.givenName);
            }
            if (bundle.familyName != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_FAMILY_NAME, bundle.familyName);
            }
            if (bundle.photoUrl != null) {
                resultIntent.putExtra(com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_PHOTO_URL, bundle.photoUrl);
            }
            completeBridgeResult(RESULT_OK, resultIntent);
        } catch (Throwable t) {
            completeBridgeResult(RESULT_CANCELED, null);
        }
    }

    private void finishGoogleWebSignInWithProfile(String email, String displayName) {
        handlePickedGoogleEmailWithMicroG(email);
    }

    private void handlePickedGoogleEmailWithMicroG(String email) {
        if (email == null || !email.contains("@")) {
            launchMicroGGoogleSignInFlow();
            return;
        }
        String normalized = email.trim();
        try {
            java.util.List<com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord> saved =
                    com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.getSavedAccounts(this);
            if (saved != null) {
                for (com.featurestic.box.compat.auth.MicroGGoogleAuthEngine.AccountRecord rec : saved) {
                    if (rec != null && rec.email != null && rec.email.equalsIgnoreCase(normalized)) {
                        Log.i(TAG, "Matched saved MicroG account for " + normalized + " — 1-tap real token exchange!");
                        performMicroGTokenExchangeAndFinish(rec);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "First-time MicroG link for " + normalized + " — launching pre-filled EmbeddedSetup");
        launchMicroGEmbeddedSetupWebView(normalized);
    }

    private static String extractEmailFromIntentParcel(Intent data) {
        if (data == null) return null;
        String[] directKeys = new String[]{
                "authAccount",
                "accountName",
                android.accounts.AccountManager.KEY_ACCOUNT_NAME,
                com.featurestic.box.compat.auth.GoogleSignInTokenHelper.EXTRA_WEB_EMAIL
        };
        for (String key : directKeys) {
            try {
                String v = data.getStringExtra(key);
                if (isValidUserEmail(v)) {
                    return v.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        android.os.Parcel parcel = null;
        try {
            parcel = android.os.Parcel.obtain();
            data.writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            if (bytes != null && bytes.length > 0) {
                java.util.regex.Pattern emailPat = java.util.regex.Pattern.compile(
                        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
                // Android Parcel encodes Strings in UTF-16LE (and sometimes UTF-8 on newer APIs)
                String[] charsets = new String[]{"UTF-16LE", "UTF-8"};
                for (String cs : charsets) {
                    try {
                        String decoded = new String(bytes, cs);
                        java.util.regex.Matcher m = emailPat.matcher(decoded);
                        while (m.find()) {
                            String candidate = m.group();
                            if (isValidUserEmail(candidate)) {
                                return candidate.trim();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (parcel != null) {
                try {
                    parcel.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isValidUserEmail(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.US);
        if (!t.contains("@") || t.length() < 6 || t.length() > 120) return false;
        if (t.endsWith(".googleusercontent.com")
                || t.endsWith(".gserviceaccount.com")
                || t.contains("com.google.android.gms")) {
            return false;
        }
        return true;
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

        if (requestCode == REQUEST_GOOGLE_NATIVE_PICKER) {
            if (resultCode == RESULT_CANCELED && data == null) {
                completeBridgeResult(RESULT_CANCELED, null);
                return;
            }
            String chosenEmail = extractEmailFromIntentParcel(data);
            Log.i(TAG, "Official GMS Continue picker returned resultCode=" + resultCode
                    + ", extractedEmail=" + chosenEmail);
            if (chosenEmail != null && chosenEmail.contains("@")) {
                handlePickedGoogleEmailWithMicroG(chosenEmail);
                return;
            }
            if (resultCode == RESULT_CANCELED) {
                completeBridgeResult(RESULT_CANCELED, null);
                return;
            }
            launchMicroGGoogleSignInFlow();
            return;
        }

        if (requestCode == REQUEST_CHOOSE_GOOGLE_ACCOUNT) {
            if (resultCode == RESULT_OK && data != null) {
                String accountName = data.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME);
                if (accountName != null && accountName.contains("@")) {
                    handlePickedGoogleEmailWithMicroG(accountName.trim());
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
            // Pass GMS Google Sign-In result untouched to guest process (com.pubg.imobile)
            // so guest classloader can unmarshal authentic GoogleSignInAccount without host corruption
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
                String scheme = lower(callbackUri.getScheme());
                if (scheme.startsWith("itop") || scheme.startsWith("imobile") || scheme.startsWith("igproxy")) {
                    callback.setComponent(new ComponentName(
                            virtualPackage,
                            "com.itop.imsdk.android.base.login.customtabs.ITOPCustomTabActivity"));
                } else {
                    return false;
                }
            } else {
                callback.setComponent(new ComponentName(
                        resolved.activityInfo.packageName,
                        resolved.activityInfo.name));
            }
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
                callback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
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
                callback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                BActivityManager.get().startActivity(callback, targetUserId);
                Log.i(TAG, "facebook stage=custom_tab_activity_handoff delivered=true");
                return true;
            } catch (Throwable ignored) {
                Log.i(TAG, "facebook stage=custom_tab_activity_handoff delivered=false");
            }
        }

        if (virtualActivityExists(
                targetPackage, "com.itop.imsdk.android.base.login.customtabs.ITOPCustomTabActivity", targetUserId)) {
            try {
                Intent callback = new Intent(Intent.ACTION_VIEW, callbackUri);
                callback.setComponent(new ComponentName(
                        targetPackage, "com.itop.imsdk.android.base.login.customtabs.ITOPCustomTabActivity"));
                callback.addCategory(Intent.CATEGORY_DEFAULT);
                callback.addCategory(Intent.CATEGORY_BROWSABLE);
                callback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                BActivityManager.get().startActivity(callback, targetUserId);
                Log.i(TAG, "facebook stage=itop_custom_tab_handoff delivered=true");
                return true;
            } catch (Throwable ignored) {
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
                || scheme.startsWith("fb")
                || TwitterOAuthSessionStore.isHostCaptureSupported(redirectUri)
                || scheme.startsWith("itop")
                || scheme.startsWith("imobile")
                || scheme.startsWith("igproxy")
                || "discord".equals(scheme) || scheme.startsWith("discord")
                || "twitter".equals(scheme) || "x".equals(scheme) || scheme.startsWith("twitter")
                || scheme.startsWith("com.googleusercontent.apps")
                || "line".equals(scheme) || scheme.startsWith("line")
                || "vk".equals(scheme) || scheme.startsWith("vk")) {
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
