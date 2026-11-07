package com.featurestic.box.utils.compat;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRIActivityManager;
import black.android.app.BRIActivityManagerL;
import black.android.app.BRIActivityManagerN;
import com.featurestic.box.utils.compat.BuildCompat;

public class ActivityManagerCompat {
	/** Type for IActivityManager.serviceDoneExecuting: anonymous operation */
	public static final int SERVICE_DONE_EXECUTING_ANON = 0;
	/** Type for IActivityManager.serviceDoneExecuting: done with an onStart call */
	public static final int SERVICE_DONE_EXECUTING_START = 1;
	/** Type for IActivityManager.serviceDoneExecuting: done stopping (destroying) service */
	public static final int SERVICE_DONE_EXECUTING_STOP = 2;
	public static final int INTENT_SENDER_BROADCAST = 1;

	/**
	 * Type for IActivityManaqer.getIntentSender: this PendingIntent is
	 * for a startActivity operation.
	 */
	public static final int INTENT_SENDER_ACTIVITY = 2;

	/*Type for IActivityManaqer.getIntentSender: this PendingIntent is
	public static final int INTENT_SENDER_ACTIVITY_RESULT = 3;
	Type for IActivityManaqer.getIntentSender: this PendingIntent is*/
	public static final int INTENT_SENDER_SERVICE = 4;
	/** User operation call: success! */
	public static final int USER_OP_SUCCESS = 0;
	public static final int START_FLAG_DEBUG = 1<<1;
	public static final int START_FLAG_TRACK_ALLOCATION = 1<<2;
	public static final int START_FLAG_NATIVE_DEBUGGING = 1<<3;

	public static boolean finishActivity(IBinder token, int code, Intent data) {
		if (token == null) return false;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				Boolean res = BRIActivityManagerN.get(BRActivityManagerNative.get().getDefault()).finishActivity(token, code, data, 0);
				if (res != null) return res;
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
				Boolean res = BRIActivityManagerL.get(BRActivityManagerNative.get().getDefault()).finishActivity(token, code, data, false);
				if (res != null) return res;
			}
		} catch (Throwable ignored) {
		}
		try {
			Object controller = black.android.app.BRActivityClient.get().getActivityClientController();
			if (controller != null) {
				for (java.lang.reflect.Method m : controller.getClass().getMethods()) {
					if (!"finishActivity".equals(m.getName())) continue;
					Class<?>[] pts = m.getParameterTypes();
					if (pts.length == 4) {
						Object r = m.invoke(controller, token, code, data, 0);
						return r instanceof Boolean ? (Boolean) r : true;
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

    public static void setActivityOrientation(Activity activity, int orientation) {
        try {
            activity.setRequestedOrientation(orientation);
        } catch (Throwable e) {
            e.printStackTrace();
            //samsung is WindowManager.setRequestedOrientation
            Activity parent =  BRActivity.get(activity).mParent();
            while (true) {
				Activity tmp = BRActivity.get(parent).mParent();
				if (tmp != null) {
					parent = tmp;
				} else {
					break;
				}
			}
            IBinder token = BRActivity.get(parent).mToken();
            try {
				BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).setRequestedOrientation(token, orientation);
            }catch (Throwable ex){
                ex.printStackTrace();
            }
        }
    }
}
