package android.MetaCore

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

object AdvancedPopupHelper {

    private const val TAG = "AdvancedPopupHelper"

    private const val AUTO_CLOSE_SECONDS = 12
    private const val MAX_WIDTH_DP = 410
    private const val MAX_HEIGHT_DP = 660

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentDialog: WeakReference<Dialog>? = null

    @Volatile
    private var currentWebView: WeakReference<WebView>? = null

    private val showing = AtomicBoolean(false)

    // ------------------------------------------------------------------------
    // PUBLIC
    // ------------------------------------------------------------------------

    @JvmStatic
    fun showAuto() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showAutoInternal()
        } else {
            mainHandler.post {
                showAutoInternal()
            }
        }
    }

    @JvmStatic
    fun dismiss() {
        mainHandler.post {
            dismissInternal()
        }
    }

    @JvmStatic
    fun isShowing(): Boolean {
        return try {
            currentDialog?.get()?.isShowing == true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------------
    // ACTIVITY
    // ------------------------------------------------------------------------

    private fun showAutoInternal() {
        try {
            val activity = getTopActivity() ?: return

            if (activity.isFinishing) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isDestroyed) return
            }

            showPopup(activity)

        } catch (_: Throwable) {
            // Popup must never crash the host.
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getTopActivity(): Activity? {
        return try {

            val activityThreadClass =
                Class.forName("android.app.ActivityThread")

            val currentActivityThread =
                activityThreadClass
                    .getDeclaredMethod("currentActivityThread")
                    .apply {
                        isAccessible = true
                    }
                    .invoke(null)
                    ?: return null

            val activitiesField =
                activityThreadClass
                    .getDeclaredField("mActivities")
                    .apply {
                        isAccessible = true
                    }

            val activities =
                activitiesField.get(currentActivityThread) as? Map<*, *>
                    ?: return null

            var fallbackActivity: Activity? = null

            for (recordObject in activities.values) {

                val record = recordObject ?: continue

                val activity = getActivityFromRecord(record) ?: continue

                if (fallbackActivity == null) {
                    fallbackActivity = activity
                }

                val paused = readBooleanField(record, "paused")
                    ?: readBooleanField(record, "mPaused")
                    ?: false

                val stopped = readBooleanField(record, "stopped")
                    ?: readBooleanField(record, "mStopped")
                    ?: false

                if (!paused && !stopped) {
                    return activity
                }
            }

            fallbackActivity

        } catch (_: Throwable) {
            null
        }
    }

    private fun getActivityFromRecord(record: Any): Activity? {
        return try {

            val names = arrayOf(
                "activity",
                "mActivity"
            )

            for (name in names) {
                try {
                    val field = findField(record.javaClass, name)
                    if (field != null) {
                        field.isAccessible = true

                        val value = field.get(record)

                        if (value is Activity) {
                            return value
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            null

        } catch (_: Throwable) {
            null
        }
    }

    private fun readBooleanField(
        instance: Any,
        name: String
    ): Boolean? {

        return try {

            val field =
                findField(instance.javaClass, name)
                    ?: return null

            field.isAccessible = true

            when (val value = field.get(instance)) {
                is Boolean -> value
                else -> null
            }

        } catch (_: Throwable) {
            null
        }
    }

    private fun findField(
        clazz: Class<*>,
        name: String
    ): Field? {

        var current: Class<*>? = clazz

        while (current != null) {

            try {
                return current.getDeclaredField(name)
            } catch (_: Throwable) {
            }

            current = current.superclass
        }

        return null
    }

    // ------------------------------------------------------------------------
    // DIALOG
    // ------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPopup(activity: Activity) {

        mainHandler.post {

            if (!showing.compareAndSet(false, true)) {
                return@post
            }

            try {

                dismissInternal(resetFlag = false)

                val dialog =
                    Dialog(
                        activity,
                        android.R.style.Theme_Translucent_NoTitleBar
                    )

                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                dialog.setCancelable(false)
                dialog.setCanceledOnTouchOutside(false)

                val webView = WebView(activity)

                currentDialog = WeakReference(dialog)
                currentWebView = WeakReference(webView)

                configureWebView(
                    webView = webView,
                    dialog = dialog,
                    activity = activity
                )

                webView.loadDataWithBaseURL(
                    "file:///android_res/drawable/",
                    HTML,
                    "text/html",
                    "UTF-8",
                    null
                )

                dialog.setContentView(webView)

                dialog.setOnDismissListener {

                    try {
                        cleanupWebView(webView)
                    } catch (_: Throwable) {
                    }

                    currentWebView = null
                    currentDialog = null

                    showing.set(false)
                }

                dialog.show()

                configureWindow(
                    activity = activity,
                    dialog = dialog
                )

            } catch (_: Throwable) {

                showing.set(false)

                try {
                    dismissInternal()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun configureWindow(
        activity: Activity,
        dialog: Dialog
    ) {

        val window =
            dialog.window ?: return

        try {

            val metrics =
                activity.resources.displayMetrics

            val horizontalMargin =
                dp(activity, 18)

            val verticalMargin =
                dp(activity, 32)

            val availableWidth =
                (metrics.widthPixels - horizontalMargin)
                    .coerceAtLeast(dp(activity, 280))

            val availableHeight =
                (metrics.heightPixels - verticalMargin)
                    .coerceAtLeast(dp(activity, 400))

            val targetWidth =
                min(
                    dp(activity, MAX_WIDTH_DP),
                    availableWidth
                )

            val targetHeight =
                min(
                    dp(activity, MAX_HEIGHT_DP),
                    availableHeight
                )

            window.setBackgroundDrawable(
                ColorDrawable(Color.TRANSPARENT)
            )

            window.setLayout(
                targetWidth,
                targetHeight
            )

            window.setGravity(Gravity.CENTER)

            window.addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            window.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )

            window.setDimAmount(0.82f)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.statusBarColor =
                    Color.TRANSPARENT

                window.navigationBarColor =
                    Color.BLACK
            }

        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------------
    // WEBVIEW
    // ------------------------------------------------------------------------

    @SuppressLint(
        "SetJavaScriptEnabled",
        "JavascriptInterface"
    )
    private fun configureWebView(
        webView: WebView,
        dialog: Dialog,
        activity: Activity
    ) {

        webView.setBackgroundColor(
            Color.TRANSPARENT
        )

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.overScrollMode =
            View.OVER_SCROLL_NEVER

        webView.isLongClickable = false

        webView.setOnLongClickListener {
            true
        }

        try {
            webView.setLayerType(
                View.LAYER_TYPE_HARDWARE,
                null
            )
        } catch (_: Throwable) {
        }

        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = false

            databaseEnabled = false

            allowContentAccess = false

            allowFileAccess = true

            blockNetworkLoads = true

            loadsImagesAutomatically = true

            cacheMode =
                WebSettings.LOAD_NO_CACHE

            defaultTextEncodingName =
                "UTF-8"

            javaScriptCanOpenWindowsAutomatically =
                false

            setSupportMultipleWindows(false)

            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.LOLLIPOP
            ) {

                mixedContentMode =
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                safeBrowsingEnabled = true
            }
        }

        webView.webViewClient =
            object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?
                ): Boolean {
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(
                        view,
                        request,
                        error
                    )
                }
            }

        val deviceInfo =
            buildDeviceInfo(activity)

        val bridge =
            object {

                @JavascriptInterface
                fun close() {

                    mainHandler.post {

                        try {

                            if (dialog.isShowing) {
                                dialog.dismiss()
                            }

                        } catch (_: Throwable) {
                        }
                    }
                }

                @JavascriptInterface
                fun getDeviceInfo(): String {
                    return deviceInfo
                }

                @JavascriptInterface
                fun getAutoCloseSeconds(): Int {
                    return AUTO_CLOSE_SECONDS
                }
            }

        webView.addJavascriptInterface(
            bridge,
            "Android"
        )
    }

    private fun cleanupWebView(
        webView: WebView
    ) {

        try {
            webView.removeJavascriptInterface(
                "Android"
            )
        } catch (_: Throwable) {
        }

        try {
            webView.stopLoading()
        } catch (_: Throwable) {
        }

        try {
            webView.webChromeClient = null
        } catch (_: Throwable) {
        }

        try {
            webView.webViewClient =
                WebViewClient()
        } catch (_: Throwable) {
        }

        try {
            webView.clearHistory()
        } catch (_: Throwable) {
        }

        try {
            webView.clearCache(true)
        } catch (_: Throwable) {
        }

        try {
            webView.loadUrl(
                "about:blank"
            )
        } catch (_: Throwable) {
        }

        try {
            webView.removeAllViews()
        } catch (_: Throwable) {
        }

        try {
            webView.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun dismissInternal(
        resetFlag: Boolean = true
    ) {

        try {

            currentDialog
                ?.get()
                ?.let {

                    if (it.isShowing) {
                        it.dismiss()
                    }
                }

        } catch (_: Throwable) {
        }

        currentDialog = null
        currentWebView = null

        if (resetFlag) {
            showing.set(false)
        }
    }

    // ------------------------------------------------------------------------
    // DEVICE INFO
    // ------------------------------------------------------------------------

    private fun buildDeviceInfo(
        activity: Activity
    ): String {

        return try {

            JSONObject().apply {

                put(
                    "manufacturer",
                    safeText(Build.MANUFACTURER)
                )

                put(
                    "model",
                    safeText(Build.MODEL)
                )

                put(
                    "device",
                    buildString {

                        append(
                            safeText(
                                Build.MANUFACTURER
                            )
                        )

                        if (!Build.MODEL.isNullOrBlank()) {

                            append(" ")

                            append(
                                safeText(
                                    Build.MODEL
                                )
                            )
                        }
                    }.trim()
                )

                put(
                    "android",
                    Build.VERSION.RELEASE
                        ?: "Unknown"
                )

                put(
                    "api",
                    Build.VERSION.SDK_INT
                )

                put(
                    "abi",
                    Build.SUPPORTED_ABIS
                        ?.firstOrNull()
                        ?: "Unknown"
                )

                put(
                    "brand",
                    safeText(Build.BRAND)
                )

                put(
                    "product",
                    safeText(Build.PRODUCT)
                )

                put(
                    "density",
                    activity.resources
                        .displayMetrics
                        .density
                )

            }.toString()

        } catch (_: Throwable) {

            """{
                "device":"Unknown",
                "android":"Unknown",
                "api":0,
                "abi":"Unknown"
            }""".trimIndent()
        }
    }

    private fun safeText(
        value: String?
    ): String {

        return value
            ?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?: "Unknown"
    }

    private fun dp(
        activity: Activity,
        value: Int
    ): Int {

        return (
            value *
                activity.resources
                    .displayMetrics
                    .density
            ).toInt()
    }

    // ------------------------------------------------------------------------
    // HTML
    // ------------------------------------------------------------------------

    private const val HTML = """
<!DOCTYPE html>

<html lang="en">

<head>

<meta charset="UTF-8">

<meta
    name="viewport"
    content="
        width=device-width,
        initial-scale=1,
        maximum-scale=1,
        user-scalable=no
    "
>

<meta
    name="color-scheme"
    content="dark"
>

<style>

:root {

    --bg: #040312;

    --surface: #090b1f;

    --surface2: #101432;

    --gold: #00f0ff;

    --gold2: #a855f7;

    --danger: #a855f7;

    --danger2: #3b82f6;

    --text: #f5f7ff;

    --muted: #9aa5c4;

    --border: rgba(0,240,255,.14);

    --goldBorder: rgba(0,240,255,.38);

    --dangerBorder: rgba(168,85,247,.34);
}

* {
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
}

html,
body {

    width: 100%;

    height: 100%;

    margin: 0;

    padding: 0;

    overflow: hidden;
}

body {

    display: flex;

    align-items: center;

    justify-content: center;

    background: transparent;

    color: var(--text);

    font-family:
        -apple-system,
        BlinkMacSystemFont,
        "Segoe UI",
        Roboto,
        Arial,
        sans-serif;

    -webkit-font-smoothing: antialiased;
}

.shell {

    position: relative;

    width: 100%;

    height: 100%;

    display: flex;

    align-items: center;

    justify-content: center;

    padding: 8px;
}

.card {

    position: relative;

    width: 100%;

    max-width: 382px;

    max-height: 100%;

    overflow: hidden;

    border:
        1px solid
        var(--goldBorder);

    border-radius: 30px;

    background:
        radial-gradient(
            circle at 50% -10%,
            rgba(168,85,247,.26),
            transparent 36%
        ),
        linear-gradient(
            150deg,
            rgba(13,15,38,.995),
            rgba(4,3,18,.998) 68%
        );

    box-shadow:
        0 32px 90px rgba(0,0,0,.82),
        0 0 0 1px rgba(255,255,255,.015),
        inset 0 1px rgba(255,255,255,.045);

    animation:
        cardEnter
        .62s
        cubic-bezier(.18,.92,.25,1)
        both;
}

.card::before {

    content: "";

    position: absolute;

    z-index: 0;

    width: 260px;

    height: 260px;

    left: 50%;

    top: -200px;

    transform:
        translateX(-50%);

    border-radius: 50%;

    background:
        rgba(0,240,255,.32);

    filter:
        blur(80px);

    pointer-events: none;
}

.card::after {

    content: "";

    position: absolute;

    z-index: 0;

    width: 180px;

    height: 180px;

    right: -100px;

    bottom: -100px;

    border-radius: 50%;

    background:
        rgba(168,85,247,.22);

    filter:
        blur(54px);

    pointer-events: none;
}

.inner {

    position: relative;

    z-index: 2;

    padding:
        20px
        19px
        18px;
}

/* --------------------------------------------------------- */
/* TOP BAR                                                   */
/* --------------------------------------------------------- */

.top {

    display: flex;

    align-items: center;

    justify-content: space-between;

    gap: 12px;
}

.identity {

    display: flex;

    align-items: center;

    gap: 12px;

    min-width: 0;
}

.logo {

    position: relative;

    width: 54px;

    height: 54px;

    min-width: 54px;

    display: grid;

    place-items: center;

    overflow: hidden;

    border:
        1px solid
        rgba(0,240,255,.48);

    border-radius: 17px;

    background:
        linear-gradient(
            145deg,
            #191d25,
            #090b10
        );

    box-shadow:
        0 10px 24px rgba(0,0,0,.45),
        inset 0 1px rgba(255,255,255,.05);
}

.logo::before {

    content: "";

    position: absolute;

    inset: -30%;

    background:
        conic-gradient(
            transparent,
            rgba(0,240,255,.85),
            transparent 28%
        );

    animation:
        rotateGlow
        5s
        linear
        infinite;
}

.logoInner {

    position: relative;

    z-index: 2;

    width: 46px;

    height: 46px;

    display: grid;

    place-items: center;

    border-radius: 14px;

    background:
        linear-gradient(
            145deg,
            #151922,
            #080a0f
        );
}

.logoMark {

    font-size: 22px;

    line-height: 1;

    font-weight: 950;

    color: var(--gold);

    letter-spacing: -1px;

    text-shadow:
        0 0 22px
        rgba(231,189,99,.34);
}

.copy {
    min-width: 0;
}

.eyebrow {

    color: var(--gold);

    font-size: 8px;

    font-weight: 900;

    letter-spacing: 2.2px;

    text-transform: uppercase;
}

.brand {

    margin-top: 4px;

    overflow: hidden;

    color: #f3f5f8;

    font-size: 17px;

    font-weight: 850;

    letter-spacing: .25px;

    text-overflow: ellipsis;

    white-space: nowrap;
}

.secure {

    display: flex;

    align-items: center;

    gap: 6px;

    padding:
        6px
        9px;

    border:
        1px solid
        rgba(102,227,158,.16);

    border-radius: 999px;

    background:
        rgba(102,227,158,.06);

    color: #91e9b8;

    font-size: 7px;

    font-weight: 850;

    letter-spacing: 1.1px;

    white-space: nowrap;
}

.secureDot {

    width: 5px;

    height: 5px;

    border-radius: 50%;

    background: #66e39e;

    box-shadow:
        0 0 9px
        #66e39e;
}

/* --------------------------------------------------------- */
/* HERO                                                      */
/* --------------------------------------------------------- */

.hero {

    position: relative;

    margin-top: 18px;

    padding:
        20px
        14px
        18px;

    overflow: hidden;

    text-align: center;

    border:
        1px solid
        var(--dangerBorder);

    border-radius: 23px;

    background:
        radial-gradient(
            circle at 50% 0,
            rgba(255,88,113,.11),
            transparent 55%
        ),
        rgba(255,255,255,.018);
}

.hero::after {

    content: "";

    position: absolute;

    width: 140px;

    height: 140px;

    left: 50%;

    top: 10px;

    transform:
        translateX(-50%);

    border-radius: 50%;

    background:
        rgba(255,88,113,.06);

    filter:
        blur(35px);

    pointer-events: none;
}

.lockOuter {

    position: relative;

    z-index: 2;

    width: 76px;

    height: 76px;

    margin:
        0
        auto
        15px;

    display: grid;

    place-items: center;

    border-radius: 50%;

    background:
        radial-gradient(
            circle,
            rgba(255,88,113,.17),
            rgba(255,88,113,.025)
        );

    box-shadow:
        0 0 0 8px rgba(255,88,113,.025),
        0 0 38px rgba(255,88,113,.09);

    animation:
        pulse
        2.3s
        ease-in-out
        infinite;
}

.lockInner {

    width: 56px;

    height: 56px;

    display: grid;

    place-items: center;

    border:
        1px solid
        rgba(255,88,113,.24);

    border-radius: 50%;

    background:
        linear-gradient(
            145deg,
            rgba(255,88,113,.13),
            rgba(255,255,255,.018)
        );
}

.lockInner svg {

    width: 27px;

    height: 27px;

    fill: none;

    stroke: var(--danger);

    stroke-width: 1.75;

    stroke-linecap: round;

    stroke-linejoin: round;

    filter:
        drop-shadow(
            0 0 8px
            rgba(255,88,113,.45)
        );
}

.heroTitle {

    position: relative;

    z-index: 2;

    margin: 0;

    color: #ffffff;

    font-size: 23px;

    font-weight: 900;

    letter-spacing: -.2px;
}

.heroText {

    position: relative;

    z-index: 2;

    max-width: 285px;

    margin:
        8px
        auto
        0;

    color: var(--muted);

    font-size: 11px;

    line-height: 1.62;
}

.status {

    position: relative;

    z-index: 2;

    display: inline-flex;

    align-items: center;

    gap: 7px;

    margin-top: 14px;

    padding:
        7px
        12px;

    border:
        1px solid
        rgba(255,88,113,.24);

    border-radius: 999px;

    background:
        rgba(255,88,113,.075);

    color: #ffc1ca;

    font-size: 8px;

    font-weight: 900;

    letter-spacing: 1.45px;

    text-transform: uppercase;
}

.statusDot {

    width: 6px;

    height: 6px;

    border-radius: 50%;

    background:
        var(--danger);

    box-shadow:
        0 0 10px
        var(--danger);

    animation:
        statusPulse
        1.3s
        ease-in-out
        infinite;
}

/* --------------------------------------------------------- */
/* INFO GRID                                                 */
/* --------------------------------------------------------- */

.sectionTitle {

    margin:
        16px
        1px
        8px;

    color: #697486;

    font-size: 7px;

    font-weight: 900;

    letter-spacing: 2px;

    text-transform: uppercase;
}

.grid {

    display: grid;

    grid-template-columns:
        1fr
        1fr;

    gap: 8px;
}

.info {

    position: relative;

    min-width: 0;

    padding:
        11px
        11px
        10px;

    overflow: hidden;

    border:
        1px solid
        var(--border);

    border-radius: 15px;

    background:
        linear-gradient(
            145deg,
            rgba(255,255,255,.042),
            rgba(255,255,255,.018)
        );
}

.info::before {

    content: "";

    position: absolute;

    width: 40px;

    height: 40px;

    right: -18px;

    top: -18px;

    border-radius: 50%;

    background:
        rgba(231,189,99,.07);
}

.info label {

    position: relative;

    z-index: 1;

    display: block;

    margin-bottom: 5px;

    color: #667184;

    font-size: 7px;

    font-weight: 850;

    letter-spacing: 1.15px;

    text-transform: uppercase;
}

.info strong {

    position: relative;

    z-index: 1;

    display: block;

    overflow: hidden;

    color: #e9edf2;

    font-size: 10px;

    font-weight: 750;

    letter-spacing: .1px;

    text-overflow: ellipsis;

    white-space: nowrap;
}

/* --------------------------------------------------------- */
/* NOTICE                                                    */
/* --------------------------------------------------------- */

.notice {

    display: flex;

    align-items: flex-start;

    gap: 11px;

    margin-top: 11px;

    padding:
        12px;

    border:
        1px solid
        rgba(231,189,99,.12);

    border-radius: 15px;

    background:
        linear-gradient(
            135deg,
            rgba(231,189,99,.085),
            rgba(231,189,99,.025)
        );
}

.noticeIcon {

    width: 30px;

    height: 30px;

    min-width: 30px;

    display: grid;

    place-items: center;

    border-radius: 10px;

    background:
        rgba(231,189,99,.075);
}

.noticeIcon svg {

    width: 17px;

    height: 17px;

    fill: none;

    stroke: var(--gold);

    stroke-width: 1.8;

    stroke-linecap: round;

    stroke-linejoin: round;
}

.noticeText {

    color: #aab3c1;

    font-size: 9px;

    line-height: 1.55;
}

.noticeText b {
    color: #d8dee8;
}

/* --------------------------------------------------------- */
/* BUTTON                                                    */
/* --------------------------------------------------------- */

.actions {
    margin-top: 13px;
}

.closeButton {

    position: relative;

    width: 100%;

    height: 50px;

    overflow: hidden;

    border: 0;

    border-radius: 16px;

    outline: none;

    background:
        linear-gradient(
            105deg,
            #9333ea,
            #3b82f6 48%,
            #00f0ff
        );

    color: #ffffff;
    text-shadow: 0 1px 8px rgba(4,3,18,.65);

    font-size: 10px;

    font-weight: 950;

    letter-spacing: 1.45px;

    text-transform: uppercase;

    box-shadow:
        0 12px 32px
        rgba(168,85,247,.26);
}

.closeButton::before {

    content: "";

    position: absolute;

    width: 60px;

    height: 160%;

    top: -30%;

    left: -100px;

    transform:
        rotate(20deg);

    background:
        linear-gradient(
            90deg,
            transparent,
            rgba(255,255,255,.38),
            transparent
        );

    animation:
        shine
        3.3s
        ease-in-out
        infinite;
}

.closeButton:active {

    transform:
        scale(.985);
}

/* --------------------------------------------------------- */
/* TIMER                                                     */
/* --------------------------------------------------------- */

.timerRow {

    display: flex;

    align-items: center;

    justify-content: center;

    gap: 5px;

    margin-top: 11px;

    color: #667183;

    font-size: 8px;

    letter-spacing: .45px;
}

.timerValue {

    min-width: 16px;

    color: var(--gold);

    font-weight: 900;
}

.progress {

    position: relative;

    width: 100%;

    height: 3px;

    margin-top: 9px;

    overflow: hidden;

    border-radius: 99px;

    background:
        rgba(255,255,255,.065);
}

.progressFill {

    width: 100%;

    height: 100%;

    transform-origin: left;

    border-radius: inherit;

    background:
        linear-gradient(
            90deg,
            #a855f7,
            #3b82f6,
            #00f0ff
        );

    box-shadow:
        0 0 10px
        rgba(231,189,99,.26);
}

.footer {

    margin-top: 11px;

    color: #414a57;

    text-align: center;

    font-size: 7px;

    font-weight: 750;

    letter-spacing: 1.35px;

    text-transform: uppercase;
}

/* --------------------------------------------------------- */
/* ANIMATION                                                 */
/* --------------------------------------------------------- */

@keyframes cardEnter {

    0% {
        opacity: 0;

        transform:
            translateY(24px)
            scale(.94);
    }

    70% {
        opacity: 1;

        transform:
            translateY(-2px)
            scale(1.005);
    }

    100% {
        opacity: 1;

        transform:
            translateY(0)
            scale(1);
    }
}

@keyframes rotateGlow {

    from {
        transform:
            rotate(0deg);
    }

    to {
        transform:
            rotate(360deg);
    }
}

@keyframes pulse {

    0%,
    100% {
        transform:
            scale(1);
    }

    50% {
        transform:
            scale(1.045);

        box-shadow:
            0 0 0 12px rgba(255,88,113,.018),
            0 0 43px rgba(255,88,113,.11);
    }
}

@keyframes statusPulse {

    0%,
    100% {
        opacity: 1;
    }

    50% {
        opacity: .45;
    }
}

@keyframes shine {

    0%,
    60% {
        left: -100px;
    }

    100% {
        left: calc(100% + 80px);
    }
}

/* Small displays */

@media (max-height: 560px) {

    .inner {
        padding: 14px;
    }

    .hero {
        margin-top: 12px;
        padding: 14px 10px;
    }

    .lockOuter {
        width: 58px;
        height: 58px;
        margin-bottom: 9px;
    }

    .lockInner {
        width: 44px;
        height: 44px;
    }

    .heroTitle {
        font-size: 19px;
    }

    .heroText {
        font-size: 9px;
        line-height: 1.45;
    }

    .sectionTitle {
        margin-top: 11px;
    }

    .notice {
        padding: 9px;
    }

    .closeButton {
        height: 44px;
    }
}

</style>

</head>

<body>

<div class="shell">

<main
    class="card"
    role="dialog"
    aria-label="License expired"
>

<div class="inner">

    <header class="top">

        <div class="identity">

            <div class="logo">

                <div class="logoInner" style="padding:0;overflow:hidden;background:#040312;">

                    <img src="data:image/png;base64,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" alt="FeaturesticBox" style="width:100%;height:100%;object-fit:cover;border-radius:14px;display:block;" />

                </div>

            </div>

            <div class="copy">

                <div class="eyebrow">
                    FeaturesticBox / Core
                </div>

                <div class="brand">
                    Runtime Security
                </div>

            </div>

        </div>

        <div class="secure">

            <span class="secureDot"></span>

            SECURE

        </div>

    </header>


    <section class="hero">

        <div class="lockOuter">

            <div class="lockInner">

                <svg
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                >

                    <rect
                        x="5"
                        y="10"
                        width="14"
                        height="11"
                        rx="3"
                    />

                    <path
                        d="
                            M8 10V7
                            a4 4 0 0 1 8 0
                            v3
                        "
                    />

                    <path
                        d="M12 14v3"
                    />

                </svg>

            </div>

        </div>


        <h1 class="heroTitle">
            License expired
        </h1>


        <p class="heroText">

            This protected runtime session
            is no longer authorized.

            Renew your activation key
            before continuing.

        </p>


        <div class="status">

            <span class="statusDot"></span>

            Access revoked

        </div>

    </section>


    <div class="sectionTitle">
        Runtime environment
    </div>


    <section class="grid">

        <div class="info">

            <label>
                Device
            </label>

            <strong id="device">
                Detecting...
            </strong>

        </div>


        <div class="info">

            <label>
                Android
            </label>

            <strong id="android">
                Detecting...
            </strong>

        </div>


        <div class="info">

            <label>
                Runtime API
            </label>

            <strong id="api">
                Detecting...
            </strong>

        </div>


        <div class="info">

            <label>
                Architecture
            </label>

            <strong id="abi">
                Detecting...
            </strong>

        </div>

    </section>


    <section class="notice">

        <div class="noticeIcon">

            <svg viewBox="0 0 24 24">

                <path
                    d="
                        M12 3
                        l7 3
                        v5
                        c0 4.6-2.9 8-7 10
                        -4.1-2
                        -7-5.4
                        -7-10
                        V6
                        l7-3z
                    "
                />

                <path
                    d="M9 12l2 2 4-4"
                />

            </svg>

        </div>


        <div class="noticeText">

            <b>
                FeaturesticBox
            </b>

            supports Android API 24–36.

            Contact your authorized
            provider to renew access.

        </div>

    </section>


    <section class="actions">

        <button
            class="closeButton"
            type="button"
            onclick="closeSecurely()"
        >

            Close securely

        </button>


        <div class="timerRow">

            Closing automatically in

            <span
                id="timer"
                class="timerValue"
            >
                --
            </span>

            seconds

        </div>


        <div class="progress">

            <div
                id="progressFill"
                class="progressFill"
            ></div>

        </div>


        <div class="footer">
            FeaturesticBox protected runtime
        </div>

    </section>

</div>

</main>

</div>


<script>

(function () {

    "use strict";

    var closed = false;

    function safeAndroidCall(
        callback
    ) {

        try {

            if (
                typeof Android !== "undefined"
            ) {

                callback(Android);
            }

        } catch (ignore) {
        }
    }


    function setText(
        id,
        value
    ) {

        try {

            var element =
                document.getElementById(id);

            if (!element) {
                return;
            }

            if (
                value === null ||
                value === undefined ||
                value === ""
            ) {

                value = "Unknown";
            }

            element.textContent =
                String(value);

        } catch (ignore) {
        }
    }


    function loadDeviceInfo() {

        safeAndroidCall(
            function (bridge) {

                try {

                    var raw =
                        bridge.getDeviceInfo();

                    var info =
                        JSON.parse(raw);

                    setText(
                        "device",
                        info.device
                    );

                    setText(
                        "android",
                        "Android " +
                        String(
                            info.android || "Unknown"
                        )
                    );

                    setText(
                        "api",
                        "API " +
                        String(
                            info.api || "Unknown"
                        )
                    );

                    setText(
                        "abi",
                        info.abi
                    );

                } catch (ignore) {
                }
            }
        );
    }


    function closeSecurely() {

        if (closed) {
            return;
        }

        closed = true;

        var card =
            document.querySelector(
                ".card"
            );

        if (card) {

            card.style.transition =
                "opacity .18s ease, transform .18s ease";

            card.style.opacity =
                "0";

            card.style.transform =
                "scale(.96)";
        }

        setTimeout(
            function () {

                safeAndroidCall(
                    function (bridge) {
                        bridge.close();
                    }
                );

            },
            170
        );
    }


    window.closeSecurely =
        closeSecurely;


    var duration =
        12;

    safeAndroidCall(
        function (bridge) {

            try {

                var customDuration =
                    Number(
                        bridge
                            .getAutoCloseSeconds()
                    );

                if (
                    isFinite(customDuration) &&
                    customDuration > 0
                ) {

                    duration =
                        customDuration;
                }

            } catch (ignore) {
            }
        }
    );


    var remaining =
        duration;

    var timer =
        document.getElementById(
            "timer"
        );

    var progress =
        document.getElementById(
            "progressFill"
        );


    if (timer) {
        timer.textContent =
            String(remaining);
    }


    if (progress) {

        progress.style.transition =
            "transform " +
            String(duration) +
            "s linear";

        progress.style.transform =
            "scaleX(1)";

        requestAnimationFrame(
            function () {

                requestAnimationFrame(
                    function () {

                        progress.style.transform =
                            "scaleX(0)";
                    }
                );
            }
        );
    }


    loadDeviceInfo();


    var interval =
        setInterval(
            function () {

                remaining -= 1;

                if (remaining < 0) {
                    remaining = 0;
                }

                if (timer) {
                    timer.textContent =
                        String(remaining);
                }

                if (remaining <= 0) {

                    clearInterval(
                        interval
                    );

                    closeSecurely();
                }

            },
            1000
        );


})();

</script>

</body>

</html>
"""
}
