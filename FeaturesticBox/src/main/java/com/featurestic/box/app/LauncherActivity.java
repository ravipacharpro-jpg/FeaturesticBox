package com.featurestic.box.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;

public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    private volatile boolean isRunning = false;
    private volatile boolean mLaunchDispatched = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static void launch(Intent intent, int userId) {
        Intent splash = new Intent();
        splash.setClass(FeaturesticBoxCore.getContext(), LauncherActivity.class);
        splash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        String pkg = intent.getPackage();
        if ((pkg == null || pkg.isEmpty()) && intent.getComponent() != null) {
            pkg = intent.getComponent().getPackageName();
        }
        splash.putExtra(LauncherActivity.KEY_INTENT, intent);
        splash.putExtra(LauncherActivity.KEY_PKG, pkg);
        splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
        FeaturesticBoxCore.getContext().startActivity(splash);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            FeaturesticBoxCore.get().ensureInitialized(this);
        } catch (Throwable ignored) {
        }
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        Intent launchIntent = intent.getParcelableExtra(KEY_INTENT);
        String pkg = intent.getStringExtra(KEY_PKG);
        int userId = intent.getIntExtra(KEY_USER_ID, 0);
        if (launchIntent == null) {
            finish();
            return;
        }
        if ((pkg == null || pkg.isEmpty()) && launchIntent.getComponent() != null) {
            pkg = launchIntent.getComponent().getPackageName();
        }

        PackageInfo packageInfo = pkg != null
                ? FeaturesticBoxCore.getBPackageManager().getPackageInfo(pkg, 0, userId)
                : null;
        Drawable icon = null;
        String appLabel = pkg != null ? pkg : "Virtual Target";
        if (packageInfo != null && packageInfo.applicationInfo != null) {
            try {
                icon = packageInfo.applicationInfo.loadIcon(FeaturesticBoxCore.getPackageManager());
            } catch (Throwable ignored) {
            }
            try {
                CharSequence labelSeq = packageInfo.applicationInfo.loadLabel(FeaturesticBoxCore.getPackageManager());
                if (labelSeq != null && labelSeq.length() > 0) {
                    appLabel = labelSeq.toString();
                }
            } catch (Throwable ignored) {
            }
        }

        buildEnterpriseSplashView(icon, appLabel);

        mHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            new Thread(() -> {
                try {
                    FeaturesticBoxCore.getBActivityManager().startActivity(launchIntent, userId);
                    mLaunchDispatched = true;
                } catch (Throwable t) {
                    Slog.e(TAG, "Failed to start target activity", t);
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            finish();
                        }
                    });
                }
            }, "Featurestic-Splash-Launch").start();
        }, 120L);

        // Fallback safety timeout so splash never hangs indefinitely if launch fails silently
        mHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
        }, 15000L);
    }

    private void buildEnterpriseSplashView(@Nullable Drawable appIcon, String appLabel) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#0B0F19"), Color.parseColor("#111827"), Color.parseColor("#090D16")}
        );
        root.setBackground(bg);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = dp(32);
        int padV = dp(28);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1A2333"));
        cardBg.setCornerRadius(dp(22));
        cardBg.setStroke(dp(1), Color.parseColor("#2E3C56"));
        card.setBackground(cardBg);

        ImageView ivIcon = new ImageView(this);
        int iconSize = dp(76);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.bottomMargin = dp(16);
        ivIcon.setLayoutParams(iconLp);
        if (appIcon != null) {
            ivIcon.setImageDrawable(appIcon);
        }
        card.addView(ivIcon);

        TextView titleView = new TextView(this);
        titleView.setText(appLabel);
        titleView.setTextColor(Color.parseColor("#F9FAFB"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView);

        TextView subView = new TextView(this);
        subView.setText("Featurestic Enterprise Sandbox • Zero-Lag Engine");
        subView.setTextColor(Color.parseColor("#38BDF8"));
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        subView.setLayoutParams(subLp);
        card.addView(subView);

        ProgressBar bar = new ProgressBar(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        bar.setLayoutParams(barLp);
        card.addView(bar);

        TextView statusView = new TextView(this);
        statusView.setText("Mounting isolated VFS & anti-detection shield...");
        statusView.setTextColor(Color.parseColor("#9CA3AF"));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stLp.topMargin = dp(14);
        statusView.setLayoutParams(stLp);
        card.addView(statusView);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.leftMargin = dp(28);
        cardLp.rightMargin = dp(28);
        root.addView(card, cardLp);

        setContentView(root);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mLaunchDispatched) {
            isRunning = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
