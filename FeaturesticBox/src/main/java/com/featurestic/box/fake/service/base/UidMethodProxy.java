package com.featurestic.box.fake.service.base;

import java.lang.reflect.Method;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.fake.hook.MethodHook;

/**
 * Created by FeaturesticBox on 2022/3/5.
 */
public class UidMethodProxy extends MethodHook {
    private final int index;
    private final String name;

    public UidMethodProxy(String name, int index) {
        this.index = index;
        this.name = name;
    }

    @Override
    protected String getMethodName() {
        return name;
    }

    @Override
    protected Object hook(Object who, Method method, Object[] args) throws Throwable {
        int uid = (int) args[index];
        if (uid == BActivityThread.getBUid() || uid == BActivityThread.getBAppId()) {
            args[index] = FeaturesticBoxCore.getHostUid();
        }
        return method.invoke(who, args);
    }
}
