package com.featurestic.box.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.os.BRIPowerManagerStub;
import black.android.os.BRServiceManager;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.service.base.ValueMethodProxy;
import com.featurestic.box.utils.MethodParameterUtils;

public class IPowerManagerProxy extends BinderInvocationStub {
    public IPowerManagerProxy() {
        super(BRServiceManager.get().getService(Context.POWER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIPowerManagerStub.get().asInterface(BRServiceManager.get().getService(Context.POWER_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.POWER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceAllAppPkg(args);
        return super.invoke(proxy, method, args);
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("acquireWakeLock", 0));
        addMethodHook(new ValueMethodProxy("acquireWakeLockAsync", 0));
        addMethodHook(new ValueMethodProxy("acquireWakeLockWithUid", 0));
        addMethodHook(new ValueMethodProxy("releaseWakeLock", 0));
        addMethodHook(new ValueMethodProxy("releaseWakeLockAsync", 0));
        addMethodHook(new ValueMethodProxy("updateWakeLockWorkSource", 0));
        addMethodHook(new ValueMethodProxy("updateWakeLockCallback", 0));
        addMethodHook(new ValueMethodProxy("isWakeLockLevelSupported", true));
        addMethodHook(new ValueMethodProxy("isIgnoringBatteryOptimizations", true));
        addMethodHook(new ValueMethodProxy("getCurrentThermalStatus", 0));
        addMethodHook(new ValueMethodProxy("getThermalHeadroom", 0.15f));
        addMethodHook(new ValueMethodProxy("addThermalStatusListener", true));
        addMethodHook(new ValueMethodProxy("addThermalStatusListenerWithPackage", true));
        addMethodHook(new ValueMethodProxy("registerThermalEventListener", true));
        addMethodHook(new ValueMethodProxy("registerThermalEventListenerWithType", true));
        addMethodHook(new ValueMethodProxy("isPowerSaveMode", false));
        addMethodHook(new ValueMethodProxy("isLowPowerStandbyEnabled", false));
        addMethodHook(new ValueMethodProxy("isDeviceIdleMode", false));
        addMethodHook(new ValueMethodProxy("isLightDeviceIdleMode", false));
    }
}
