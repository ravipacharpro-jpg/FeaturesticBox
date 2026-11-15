package com.featurestic.box.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.utils.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";

    private volatile boolean isRunning = false;
    private volatile boolean mLaunchDispatched = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private VideoView mVideoView;

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

        // Immersive Fullscreen Mode
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().setBackgroundDrawableResource(android.R.color.black);
            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            decorView.setSystemUiVisibility(uiOptions);
        } catch (Throwable ignored) {
        }

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

        File videoFile = extractIntroVideoIfNeeded();
        if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
            playIntroAndLaunch(videoFile, launchIntent, userId);
        } else {
            // Fallback: immediate launch if video is missing
            dispatchTargetLaunch(launchIntent, userId);
        }
    }

    private File extractIntroVideoIfNeeded() {
        try {
            File target = new File(getFilesDir(), "intro_4s.mp4");
            if (target.exists() && target.length() >= 100000) {
                return target;
            }
            // Check assets in host context or current context
            InputStream in = null;
            try {
                in = getAssets().open("intro_4s.mp4");
            } catch (Throwable t1) {
                try {
                    in = FeaturesticBoxCore.getContext().getAssets().open("intro_4s.mp4");
                } catch (Throwable ignored) {
                }
            }
            if (in == null) {
                return null;
            }
            File temp = new File(getFilesDir(), "intro_4s.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (temp.length() > 0) {
                target.delete();
                temp.renameTo(target);
                return target;
            }
        } catch (Throwable t) {
            Slog.e(TAG, "extractIntroVideoIfNeeded error", t);
        }
        return null;
    }

    private void playIntroAndLaunch(File videoFile, Intent launchIntent, int userId) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        mVideoView = new VideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        );
        mVideoView.setLayoutParams(lp);
        root.addView(mVideoView);
        setContentView(root);

        mVideoView.setVideoPath(videoFile.getAbsolutePath());

        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                try {
                    mp.setLooping(false);
                } catch (Throwable ignored) {}
                mVideoView.start();
            }
        });

        mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                dispatchTargetLaunch(launchIntent, userId);
            }
        });

        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                dispatchTargetLaunch(launchIntent, userId);
                return true;
            }
        });

        // 4-second hard dispatch safety timer
        mHandler.postDelayed(() -> {
            dispatchTargetLaunch(launchIntent, userId);
        }, 4100L);

        // Max safety finish
        mHandler.postDelayed(() -> {
            if (!isFinishing()) {
                finish();
            }
        }, 9000L);
    }

    private void dispatchTargetLaunch(Intent launchIntent, int userId) {
        if (mLaunchDispatched) {
            return;
        }
        mLaunchDispatched = true;
        new Thread(() -> {
            try {
                FeaturesticBoxCore.getBActivityManager().startActivity(launchIntent, userId);
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to start target activity", t);
                mHandler.post(() -> {
                    if (!isFinishing()) {
                        finish();
                    }
                });
            }
        }, "Featurestic-Video-Launch").start();
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
        if (mVideoView != null) {
            try {
                mVideoView.stopPlayback();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
