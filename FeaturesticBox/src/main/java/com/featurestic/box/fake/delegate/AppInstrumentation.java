package com.featurestic.box.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.Log;
import black.com.cosmos.apm.framework.page.BRActivityLifeCycleHelper$ApplicationInstrumentation;
import black.dalvik.system.BRBaseDexClassLoader;
import java.lang.reflect.Field;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import java.lang.reflect.Method;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.compat.auth.TwitterLegacyTlsCompat;
import com.featurestic.box.fake.hook.HookManager;
import com.featurestic.box.fake.hook.IInjectHook;
import com.featurestic.box.fake.service.HCallbackStub;
import com.featurestic.box.fake.service.IActivityClientProxy;
import com.featurestic.box.utils.HackAppUtils;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.compat.ActivityCompat;
import com.featurestic.box.utils.compat.ActivityManagerCompat;
import com.featurestic.box.utils.compat.ContextCompat;
import com.featurestic.box.FeaturesticOverlayManager;
import com.featurestic.box.utils.compat.FeaturesticBatteryAndOemGuard;
import com.featurestic.box.utils.compat.NetworkLowLatencyOptimizer;
import com.featurestic.box.utils.compat.WebViewCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {
    
	private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final String RIFLE_APP_CLASS = "com.cosmos.apm.framework.page.ActivityLifeCycleHelper$ApplicationInstrumentation";
	private static AppInstrumentation sAppInstrumentation;
	private ClassLoader delegateAppClassLoader;

	private AppInstrumentation() {}

	public static AppInstrumentation get() {
		if (sAppInstrumentation == null) {
			synchronized (AppInstrumentation.class) {
				if (sAppInstrumentation == null) {
					sAppInstrumentation = new AppInstrumentation();
				}
			}
		}
		return sAppInstrumentation;
	}

	public ClassLoader getDelegateAppClassLoader() {
		return delegateAppClassLoader;
	}

	@Override
	public void injectHook() {
		try {
			Instrumentation mInstrumentation = getCurrInstrumentation();
			if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) return;
			mBaseInstrumentation = (Instrumentation) mInstrumentation;
			BRActivityThread.get(FeaturesticBoxCore.mainThread())._set_mInstrumentation(this);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void fixRifleHook() {
		Instrumentation mInstrumentation = getCurrInstrumentation();
		if (mInstrumentation instanceof AppInstrumentation) {
			return;
		}
		if (RIFLE_APP_CLASS.equals(mInstrumentation.getClass().getName())) {
			AppInstrumentation appInstrumentation = (AppInstrumentation) BRActivityLifeCycleHelper$ApplicationInstrumentation.get(mInstrumentation).mBase();
			if (appInstrumentation != null) mBaseInstrumentation = appInstrumentation.mBaseInstrumentation;
			BRActivityThread.get(FeaturesticBoxCore.mainThread())._set_mInstrumentation(this);
		}
	}

	public void fixInstrumentationAfterApplicationOnCreate() {
		Instrumentation mInstrumentation = getCurrInstrumentation();
		if (mInstrumentation instanceof AppInstrumentation) {
			return;
		}
		Class clazz = mInstrumentation.getClass();
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (Instrumentation.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				try {
					Object obj = field.get(mInstrumentation);
					if ((obj instanceof AppInstrumentation)) {
						Instrumentation mBaseInstrumentation = ((AppInstrumentation) obj).mBaseInstrumentation;
						field.set(mInstrumentation, mBaseInstrumentation);
						((AppInstrumentation) obj).mBaseInstrumentation = mInstrumentation;
						BRActivityThread.get(FeaturesticBoxCore.mainThread())._set_mInstrumentation(obj);
					}
				} catch (Exception ignored) {
				}
				break;
			}
		}
	}

	public Instrumentation getCurrInstrumentation() {
		Object currentActivityThread = FeaturesticBoxCore.mainThread();
		return BRActivityThread.get(currentActivityThread).mInstrumentation();
	}

	@Override
	public boolean isBadEnv() {
		return !checkInstrumentation(getCurrInstrumentation());
	}

	private boolean checkInstrumentation(Instrumentation instrumentation) {
		if (instrumentation instanceof AppInstrumentation) {
			return true;
		}
		Class<?> clazz = instrumentation.getClass();
		if (Instrumentation.class.equals(clazz)) {
			return false;
		}
		do {
			assert clazz != null;
			Field[] fields = clazz.getDeclaredFields();
			for (Field field : fields) {
				if (Instrumentation.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					try {
						Object obj = field.get(instrumentation);
						if ((obj instanceof AppInstrumentation)) {
							return true;
						}
					} catch (Exception e) {
						return false;
					}
				}
			}
			clazz = clazz.getSuperclass();
		} while (!Instrumentation.class.equals(clazz));
		return false;
	}

	private void checkHCallback() {
		HookManager.get().checkEnv(HCallbackStub.class);
	}

	private void checkActivity(Activity activity) {
		HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
		checkHCallback();
		HookManager.get().checkEnv(IActivityClientProxy.class);
		ActivityInfo info = BRActivity.get(activity).mActivityInfo();
		ContextCompat.fix(activity);
		ActivityCompat.fix(activity);
		if (info != null) {
			if (info.theme != 0) {
				activity.getTheme().applyStyle(info.theme, true);
			}
			ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
		}
	}

	@Override
	public Application newApplication(ClassLoader cl, String className, Context context)
	throws InstantiationException, IllegalAccessException, ClassNotFoundException {
		ContextCompat.fix(context);
		//fixSharedLibraryLoaders(cl);
		BActivityThread.currentActivityThread().loadXposed(context);
		delegateAppClassLoader = context.getClassLoader();
		return super.newApplication(cl, className, context);
	}

	private void fixSharedLibraryLoaders(ClassLoader cl) {
		try {
			Object classLoaders = BRBaseDexClassLoader.get(ContextCompat.class.getClassLoader()).sharedLibraryLoaders();
			BRBaseDexClassLoader.get(cl)._set_sharedLibraryLoaders(classLoaders);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
		checkActivity(activity);
		TwitterLegacyTlsCompat.armBeforeCreate(activity);
		super.callActivityOnCreate(activity, icicle, persistentState);
		TwitterLegacyTlsCompat.patchAfterCreate(activity);
	}

	@Override
	public void callActivityOnCreate(Activity activity, Bundle icicle) {
		checkActivity(activity);
		TwitterLegacyTlsCompat.armBeforeCreate(activity);
		super.callActivityOnCreate(activity, icicle);
		TwitterLegacyTlsCompat.patchAfterCreate(activity);
	}

	@Override
	public void callApplicationOnCreate(Application app) {
		checkHCallback();
		super.callApplicationOnCreate(app);
	}

	@Override
	public void callActivityOnPause(Activity activity) {
		super.callActivityOnPause(activity);
		WebViewCompat.enableAndFlushCookiesIfActive();
	}

	@Override
	public void callActivityOnStop(Activity activity) {
		super.callActivityOnStop(activity);
		WebViewCompat.enableAndFlushCookiesIfActive();
	}

	@Override
	public void callActivityOnResume(Activity activity) {
		super.callActivityOnResume(activity);
		NetworkLowLatencyOptimizer.acquireWifiLowLatencyLock(activity);
		FeaturesticBatteryAndOemGuard.onGameResumed(activity);
		FeaturesticOverlayManager.onActivityResumed(activity);
	}

	@Override
	public void callActivityOnDestroy(Activity activity) {
		FeaturesticOverlayManager.onActivityDestroyed(activity);
		WebViewCompat.enableAndFlushCookiesIfActive();
		super.callActivityOnDestroy(activity);
	}
    
    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
