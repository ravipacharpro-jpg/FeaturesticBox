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
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import com.featurestic.box.FeaturesticBoxCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class LauncherActivity extends Activity {
    public static final String TAG = "LauncherActivity";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";

    private static java.lang.ref.WeakReference<Activity> sActiveLauncherRef;

    public static Activity getActiveLauncher() {
        java.lang.ref.WeakReference<Activity> ref = sActiveLauncherRef;
        return ref != null ? ref.get() : null;
    }

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
        sActiveLauncherRef = new java.lang.ref.WeakReference<>(this);
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
            // Fallback: minimalist black Rotate Phone layout for 2.5s
            try {
                setContentView(com.featurestic.box.R.layout.activity_launcher);
            } catch (Throwable ignored) {}
            mHandler.postDelayed(() -> {
                dispatchTargetLaunch(launchIntent, userId);
                if (!isFinishing()) {
                    finish();
                    try {
                        overridePendingTransition(0, 0);
                    } catch (Throwable ignored) {}
                }
            }, 2500L);
        }
    }

    private File extractIntroVideoIfNeeded() {
        try {
            File target = new File(getFilesDir(), "intro_rotate_branded.mp4");
            if (target.exists() && target.length() > 50000) {
                return target;
            }
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
            File temp = new File(getFilesDir(), "intro_rotate.tmp");
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
            Log.e(TAG, "extractIntroVideoIfNeeded error", t);
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

        // Tap anywhere to skip straight into game
        root.setOnClickListener(v -> {
            Log.d(TAG, "Screen tapped: fast-forwarding to game...");
            dispatchTargetLaunch(launchIntent, userId);
            if (!isFinishing()) {
                finish();
            }
        });

        setContentView(root);

        mVideoView.setVideoPath(videoFile.getAbsolutePath());

        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                try {
                    mp.setLooping(false);
                } catch (Throwable ignored) {}
                mVideoView.start();
                Log.d(TAG, "Rotate Your Phone intro started (4.5s)");
            }
        });

        // 🔥 PARALLEL ENGINE WARM-UP: Pre-dispatch game launch at 2.5s into the 4.5s video!
        // While player watches rotation, UE4 / OBB / :p0 initializes in background!
        mHandler.postDelayed(() -> {
            Log.d(TAG, "Parallel warm-up: Dispatching game start at 2.5s into video...");
            dispatchTargetLaunch(launchIntent, userId);
        }, 2500L);

        // When 4.5-second video finishes, the game engine is already initialized and ready!
        mVideoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                Log.d(TAG, "Intro video completed (4.5s). Seamlessly handing over to game...");
                mHandler.postDelayed(() -> {
                    if (!isFinishing()) {
                        finish();
                        try {
                            overridePendingTransition(0, 0);
                        } catch (Throwable ignored) {}
                    }
                }, 200L);
            }
        });

        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.e(TAG, "Video error: " + what + ", " + extra + ". Direct launching game...");
                dispatchTargetLaunch(launchIntent, userId);
                if (!isFinishing()) {
                    finish();
                }
                return true;
            }
        });

        // Safety timeout so splash never hangs indefinitely
        mHandler.postDelayed(() -> {
            if (!isFinishing()) {
                Log.d(TAG, "Safety timeout reached at 7.5s, finishing LauncherActivity");
                dispatchTargetLaunch(launchIntent, userId);
                finish();
            }
        }, 7500L);
    }

    private void dispatchTargetLaunch(Intent launchIntent, int userId) {
        if (mLaunchDispatched) {
            return;
        }
        mLaunchDispatched = true;
        new Thread(() -> {
            try {
                Log.d(TAG, "Calling BActivityManager.startActivity for " + launchIntent);
                FeaturesticBoxCore.getBActivityManager().startActivity(launchIntent, userId);
                Log.d(TAG, "BActivityManager.startActivity completed successfully");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to start target activity", t);
            }
        }, "Featurestic-Video-Launch").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause called");
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (sActiveLauncherRef != null && sActiveLauncherRef.get() == this) {
            sActiveLauncherRef = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        if (mVideoView != null) {
            try {
                mVideoView.stopPlayback();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
