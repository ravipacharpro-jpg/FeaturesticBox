package com.featurestic.box.core.env;

import android.content.ComponentName;
import android.content.Intent;
import android.MetaCore.RemoteManager;

import java.util.ArrayList;
import java.util.List;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.GmsCore;

public class AppSystemEnv {
    private static final List<String> sSystemPackages = new ArrayList<>();
    private static final List<String> sSuPackages = new ArrayList<>();
    private static final List<String> sXposedPackages = new ArrayList<>();
    private static final List<String> sPreInstallPackages = new ArrayList<>();

    static {
        // Core / AOSP
        sSystemPackages.add("android");
        sSystemPackages.add("com.google.android.webview");
        sSystemPackages.add("com.google.android.webview.dev");
        sSystemPackages.add("com.google.android.webview.beta");
        sSystemPackages.add("com.google.android.webview.canary");
        sSystemPackages.add("com.android.webview");

        // Real Google authentication stack. These packages are never cloned or
        // rewritten: virtual apps may discover and call the copies installed on
        // the phone, so provider-side package/signature verification stays intact.
        sSystemPackages.add("com.google.android.gms");
        sSystemPackages.add("com.google.android.gsf");
        sSystemPackages.add("com.google.android.gsf.login");
        sSystemPackages.add("com.android.vending");
        sSystemPackages.add("com.google.android.play.games");

        // Extra WebView variants
        sSystemPackages.add("com.le.android.webview");
        sSystemPackages.add("com.android.camera");
        sSystemPackages.add("com.android.talkback");
        sSystemPackages.add("com.miui.gallery");

        // MIUI / Xiaomi
        sSystemPackages.add("com.lbe.security.miui");
        sSystemPackages.add("com.miui.contentcatcher");
        sSystemPackages.add("com.miui.catcherpatch");

        // Permission Controllers
        sSystemPackages.add("com.android.permissioncontroller");
        sSystemPackages.add("com.google.android.permissioncontroller");

        // Google Gboard
        sSystemPackages.add("com.google.android.inputmethod.latin");

        // Huawei
        sSystemPackages.add("com.huawei.webview");

        // Oppo / ColorOS & OEM IDs
        sSystemPackages.add("com.heytap.openid");
        sSystemPackages.add("com.coloros.safecenter");

        // Samsung / Asus / Lenovo / ZUI / MSA
        sSystemPackages.add("com.samsung.android.deviceidservice");
        sSystemPackages.add("com.asus.msa.SupplementaryDID");
        sSystemPackages.add("com.zui.deviceidservice");
        sSystemPackages.add("com.mdid.msa");

        // ---- SU / Root apps ----
        sSuPackages.add("com.noshufou.android.su");
        sSuPackages.add("com.noshufou.android.su.elite");
        sSuPackages.add("eu.chainfire.supersu");
        sSuPackages.add("com.koushikdutta.superuser");
        sSuPackages.add("com.thirdparty.superuser");
        sSuPackages.add("com.yellowes.su");
        sSuPackages.add("com.topjohnwu.magisk");
        sSuPackages.add("io.github.vvb2060.magisk");
        sSuPackages.add("io.github.huskydg.magisk");
        sSuPackages.add("me.weishu.kernelsu");
        sSuPackages.add("me.bmax.apatch");
        sSuPackages.add("com.fox2code.mmm");
        sSuPackages.add("com.guoshi.httpcanary");
        sSuPackages.add("com.guoshi.httpcanary.premium");
        sSuPackages.add("com.reqable.android");
        sSuPackages.add("com.emanuelef.remote_capture");
        sSuPackages.add("app.greyshirts.sslcapture");

        // ---- Xposed / Zygisk / LSPosed ----
        sXposedPackages.add("de.robv.android.xposed.installer");
        sXposedPackages.add("org.lsposed.manager");
        sXposedPackages.add("org.meowcat.edxposed.manager");
        sXposedPackages.add("moe.shizuku.privileged.api");

        // Real Twitter / X apps. Do not clone them merely for authentication.

        // Real Facebook / Meta apps/services.
        sSystemPackages.add("com.instagram.android");

        // Google Credential Manager & Authenticator
        sSystemPackages.add("com.android.credentialmanager");
        sSystemPackages.add("com.google.android.credentialmanager");
        sSystemPackages.add("com.google.android.apps.authenticator2");

        // WhatsApp & WhatsApp Business (Login / OTP / Deep-link Handshake)
        sSystemPackages.add("com.whatsapp");
        sSystemPackages.add("com.whatsapp.w4b");

        // Discord
        sSystemPackages.add("com.discord");

        // VK (VKontakte)
        sSystemPackages.add("com.vkontakte.android");
        sSystemPackages.add("com.vk.im");

        // LINE
        sSystemPackages.add("jp.naver.line.android");
        sSystemPackages.add("com.linecorp.linelite");

        // KakaoTalk
        sSystemPackages.add("com.kakao.talk");

        // Telegram
        sSystemPackages.add("org.telegram.messenger");
        sSystemPackages.add("org.telegram.messenger.web");
        sSystemPackages.add("org.thunderdog.challegram");

        // WeChat & QQ
        sSystemPackages.add("com.tencent.mm");
        sSystemPackages.add("com.tencent.mobileqq");
        sSystemPackages.add("com.tencent.tim");

        // Garena
        sSystemPackages.add("com.garena.gas");
        sSystemPackages.add("com.garena.gaslite");

        // TikTok
        sSystemPackages.add("com.zhiliaoapp.musically");
        sSystemPackages.add("com.ss.android.ugc.trill");
        sSystemPackages.add("com.ss.android.ugc.aweme");

        // Twitch / Reddit / Snapchat / Steam
        sSystemPackages.add("tv.twitch.android.app");
        sSystemPackages.add("com.reddit.frontpage");
        sSystemPackages.add("com.snapchat.android");
        sSystemPackages.add("com.valvesoftware.android.steam.community");

        // Microsoft / Xbox Live
        sSystemPackages.add("com.azure.authenticator");
        sSystemPackages.add("com.microsoft.xboxone.smartglass");
        sSystemPackages.add("com.microsoft.xboxone.smartglass.beta");

        // Browsers for Custom Tabs / Auth Tab OAuth
        sSystemPackages.add("com.android.chrome");
        sSystemPackages.add("com.chrome.beta");
        sSystemPackages.add("com.chrome.dev");
        sSystemPackages.add("com.sec.android.app.sbrowser");
        sSystemPackages.add("org.mozilla.firefox");
        sSystemPackages.add("com.microsoft.emmx");
        sSystemPackages.add("com.brave.browser");
        sSystemPackages.add("com.opera.browser");
    }

    public static boolean isOpenPackage(String packageName) {
        return packageName != null && sSystemPackages.contains(packageName);
    }

    public static boolean isOpenPackage(ComponentName componentName) {
        return componentName != null && isOpenPackage(componentName.getPackageName());
    }

    public static boolean isOpenPackage(Intent intent) {
        if (intent == null) {
            return false;
        }
        // Android 16: real GMS remains open for authentication, but measurement
        // must not receive a virtual package identity over a real binder. Returning
        // false here makes the existing virtual service path reject measurement
        // cleanly while every other GMS/Facebook/X auth intent remains open.
        if (GmsCore.isMeasurementIntent(intent)) {
            return false;
        }
        if (isOpenPackage(intent.getComponent())) {
            return true;
        }
        return isOpenPackage(intent.getPackage());
    }

    public static boolean isFeaturesticPackage(String packageName) {
        if (FeaturesticBoxCore.get().setHideRoot() && sSuPackages.contains(packageName)) {
            return true;
        }
        return RemoteManager.sHideXposed && sXposedPackages.contains(packageName);
    }

    public static List<String> getPreInstallPackages() {
        return sPreInstallPackages;
    }
}
