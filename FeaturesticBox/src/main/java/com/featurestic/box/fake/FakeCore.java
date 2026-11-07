package com.featurestic.box.fake;

import com.featurestic.box.jnihook.ReflectCore;

/**
 * Created by @FeaturesticBox on 3/7/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class FakeCore {
    public static void init() {
        ReflectCore.set(android.app.ActivityThread.class);
    }
}
