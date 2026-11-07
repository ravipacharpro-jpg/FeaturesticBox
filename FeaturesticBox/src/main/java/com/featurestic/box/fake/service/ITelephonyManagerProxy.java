package com.featurestic.box.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRITelephonyStub;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.entity.location.BCell;
import com.featurestic.box.fake.frameworks.BLocationManager;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.Md5Utils;

public class ITelephonyManagerProxy extends BinderInvocationStub {
	public static final String TAG = "ITelephonyManagerProxy";

	public ITelephonyManagerProxy() {
		super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
	}

	@Override
	protected Object getWho() {
		IBinder telephony = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
		return BRITelephonyStub.get().asInterface(telephony);
	}

	@Override
	protected void inject(Object baseInvocation, Object proxyInvocation) {
		replaceSystemService(Context.TELEPHONY_SERVICE);
	}

	@Override
	public boolean isBadEnv() {
		return false;
	}

	@ProxyMethod("getDeviceId")
	public static class GetDeviceId extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.fakeImei();
		}
	}

	@ProxyMethod("getImeiForSlot")
	public static class getImeiForSlot extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.fakeImei();
		}
	}

	@ProxyMethod("getMeidForSlot")
	public static class GetMeidForSlot extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.fakeImei();
		}
	}

	@ProxyMethod("isUserDataEnabled")
	public static class IsUserDataEnabled extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return true;
		}
	}

	@ProxyMethod("getLine1NumberForDisplay")
	public static class getLine1NumberForDisplay extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return null;
		}
	}

	@ProxyMethod("getSubscriberId")
	public static class GetSubscriberId extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.fakeImsi();
		}
	}

	@ProxyMethod("getDeviceIdWithFeature")
	public static class GetDeviceIdWithFeature extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			return Md5Utils.fakeImei();
		}
	}

	@ProxyMethod("getCellLocation")
	public static class GetCellLocation extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			Log.d(TAG, "getCellLocation");
			if (BLocationManager.isFakeLocationEnable()) {
				BCell cell = BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				if (cell != null) {
					// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
					return null;
				}
			}
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getAllCellInfo")
	public static class GetAllCellInfo extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			if (BLocationManager.isFakeLocationEnable()) {
				List<BCell> cell = BLocationManager.get().getAllCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
				return cell;
			}
			try {
				return method.invoke(who, args);
			} catch (Throwable e) {
				return null;
			}
		}
	}

	@ProxyMethod("getNetworkOperator")
	public static class GetNetworkOperator extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			String spoofed = com.featurestic.box.utils.compat.FeaturesticRegionSpoofer.getSpoofedMccMnc();
			if (spoofed != null) return spoofed;
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getNetworkOperatorForPhone")
	public static class GetNetworkOperatorForPhone extends GetNetworkOperator {}

	@ProxyMethod("getSimOperator")
	public static class GetSimOperator extends GetNetworkOperator {}

	@ProxyMethod("getSimOperatorNumericForPhone")
	public static class GetSimOperatorNumericForPhone extends GetNetworkOperator {}

	@ProxyMethod("getSimCountryIso")
	public static class GetSimCountryIso extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			String spoofed = com.featurestic.box.utils.compat.FeaturesticRegionSpoofer.getSpoofedCountryIso();
			if (spoofed != null) return spoofed;
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getSimCountryIsoForPhone")
	public static class GetSimCountryIsoForPhone extends GetSimCountryIso {}

	@ProxyMethod("getNetworkCountryIso")
	public static class GetNetworkCountryIso extends GetSimCountryIso {}

	@ProxyMethod("getNetworkCountryIsoForPhone")
	public static class GetNetworkCountryIsoForPhone extends GetSimCountryIso {}

	@ProxyMethod("getSimOperatorName")
	public static class GetSimOperatorName extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			String spoofed = com.featurestic.box.utils.compat.FeaturesticRegionSpoofer.getSpoofedCarrierName();
			if (spoofed != null) return spoofed;
			return method.invoke(who, args);
		}
	}

	@ProxyMethod("getSimOperatorNameForPhone")
	public static class GetSimOperatorNameForPhone extends GetSimOperatorName {}

	@ProxyMethod("getNetworkOperatorName")
	public static class GetNetworkOperatorName extends GetSimOperatorName {}

	@ProxyMethod("getNetworkTypeForSubscriber")
	public static class GetNetworkTypeForSubscriber extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			try {
				return method.invoke(who, args);
			} catch (Throwable e) {
				return 0;
			}
		}
	}

	@ProxyMethod("getNeighboringCellInfo")
	public static class GetNeighboringCellInfo extends MethodHook {
		@Override
		protected Object hook(Object who, Method method, Object[] args) throws Throwable {
			Log.d(TAG, "getNeighboringCellInfo");
			if (BLocationManager.isFakeLocationEnable()) {
				List<BCell> cell = BLocationManager.get().getNeighboringCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
				// TODO Transfer BCell to CdmaCellLocation/GsmCellLocation
				return null;
			}
			return method.invoke(who, args);
		}
	}
}
