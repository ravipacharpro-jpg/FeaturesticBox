package com.featurestic.box.fake.service;

import org.junit.Test;

import java.util.Arrays;

import com.featurestic.box.fake.hook.ProxyMethods;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IActivityManagerPermissionHookTest {
    @Test
    public void hooksLegacyAndAndroid16PermissionMethods() {
        ProxyMethods annotation = IActivityManagerProxy.checkPermission.class
                .getAnnotation(ProxyMethods.class);

        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.value()).contains("checkPermission"));
        assertTrue(Arrays.asList(annotation.value()).contains("checkPermissionForDevice"));
    }
}
