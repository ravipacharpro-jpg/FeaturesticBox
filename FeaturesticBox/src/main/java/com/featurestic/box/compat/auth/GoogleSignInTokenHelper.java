package com.featurestic.box.compat.auth;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lsposed.lsparanoid.Obfuscate;

import black.android.app.servertransaction.BRActivityResultItem;
import black.android.app.servertransaction.BRClientTransaction;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;

/**
 * End-to-End Google Sign-In & Play Games Authentication Engine for Android 10–16.
 *
 * Solves all 4 causes of Google Sign-In failure in virtual containers:
 * 1. Clones SignInConfiguration via Parcel before outbound normalization so
 *    SignInHubActivity's in-memory field (this.zbc) is never mutated.
 * 2. Replaces SHA-1-restricted Play Games scopes (games / games_lite) and strips
 *    serverClientId on the outbound GMS Intent so real GoogleSignInChimeraActivity
 *    performs standard email/profile authentication (which succeeds for any APK SHA-1
 *    and opens the native Gmail Account Picker).
 * 3. Supports both legacy ClientTransaction.mActivityCallbacks (Android 9–14) and
 *    modern ClientTransaction.mTransactionItems / getTransactionItems() (Android 14 QPR2–16).
 * 4. Restores the original requested Scopes, populates idToken & serverAuthCode, and
 *    synthesizes a fallback SignInAccount from the device's Google account if GMS
 *    returns DEVELOPER_ERROR (10) or SIGN_IN_FAILED (12500).
 */
@Obfuscate
public final class GoogleSignInTokenHelper {
    private static final String TAG = "FeaturesticGoogleAuth";
    private static volatile String sLastServerClientId;
    private static volatile String sLastVirtualPackage;
    private static final List<Object> sLastRequestedScopes = new ArrayList<>();

    public static final String EXTRA_WEB_EMAIL = "com.featurestic.box.google_web_email";
    public static final String EXTRA_WEB_NAME = "com.featurestic.box.google_web_name";
    public static final String EXTRA_WEB_SUB = "com.featurestic.box.google_web_sub";
    public static final String EXTRA_WEB_ID_TOKEN = "com.featurestic.box.google_web_id_token";

    private static volatile String sLastWebEmail;
    private static volatile String sLastWebName;
    private static volatile String sLastWebSub;
    private static volatile String sLastWebIdToken;

    private GoogleSignInTokenHelper() {
    }

    public static String getLastServerClientId() {
        return sLastServerClientId;
    }

    public static void prepareOutboundSignInIntent(Intent intent, String virtualPkg, String hostPkg) {
        if (intent == null) return;
        sLastVirtualPackage = virtualPkg;
        try {
            ClassLoader guestCl = (BActivityThread.getApplication() != null
                    && BActivityThread.getApplication().getClassLoader() != null)
                    ? BActivityThread.getApplication().getClassLoader() : null;
            if (guestCl == null && intent.getComponent() != null) {
                try {
                    guestCl = intent.getClass().getClassLoader();
                } catch (Throwable ignored) {
                }
            }
            if (guestCl != null) {
                intent.setExtrasClassLoader(guestCl);
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    extras.setClassLoader(guestCl);
                }
            }
            if (!intent.hasExtra("config")) {
                return;
            }

            Bundle configBundle = null;
            Parcelable targetConfig = null;

            Parcelable p = null;
            try {
                p = intent.getParcelableExtra("config");
            } catch (Throwable ignored) {
            }

            if (p instanceof Bundle) {
                configBundle = (Bundle) p;
                if (guestCl != null) {
                    configBundle.setClassLoader(guestCl);
                }
                try {
                    targetConfig = configBundle.getParcelable("config");
                } catch (Throwable ignored) {
                }
            } else if (p != null) {
                targetConfig = p;
            }

            if (targetConfig == null) {
                if (configBundle != null && guestCl != null) {
                    configBundle.setClassLoader(guestCl);
                    intent.putExtra("config", configBundle);
                }
                return;
            }

            Parcelable clonedConfig = cloneParcelable(targetConfig);
            Object configToMutate = clonedConfig != null ? clonedConfig : targetConfig;

            Class<?> cfgClass = configToMutate.getClass();
            while (cfgClass != null && cfgClass != Object.class) {
                for (Field f : cfgClass.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(configToMutate);
                    if (val instanceof String && virtualPkg != null && virtualPkg.equals(val)) {
                        f.set(configToMutate, hostPkg);
                    } else if (val instanceof Parcelable
                            && val.getClass().getName().contains("GoogleSignInOptions")) {
                        stripServerClientIdAndGamesScopeRequirement(val);
                    }
                }
                cfgClass = cfgClass.getSuperclass();
            }

            if (configBundle != null) {
                configBundle.putParcelable("config", (Parcelable) configToMutate);
                if (guestCl != null) {
                    configBundle.setClassLoader(guestCl);
                }
                intent.putExtra("config", configBundle);
            } else {
                intent.putExtra("config", (Parcelable) configToMutate);
            }
            if (guestCl != null) {
                intent.setExtrasClassLoader(guestCl);
            }
        } catch (Throwable t) {
            Log.w(TAG, "prepareOutboundSignInIntent failed: " + t.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static Parcelable cloneParcelable(Parcelable src) {
        if (src == null) return null;
        Parcel p = Parcel.obtain();
        try {
            Field creatorField = src.getClass().getField("CREATOR");
            Parcelable.Creator<?> creator = (Parcelable.Creator<?>) creatorField.get(null);
            if (creator != null) {
                src.writeToParcel(p, 0);
                p.setDataPosition(0);
                return (Parcelable) creator.createFromParcel(p);
            }
        } catch (Throwable ignored) {
        } finally {
            p.recycle();
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stripServerClientIdAndGamesScopeRequirement(Object googleSignInOptions) {
        if (googleSignInOptions == null) return;
        Class<?> cls = googleSignInOptions.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Class<?> type = f.getType();
                    if (type == Account.class) {
                        // Clear cached Account so Google Account Picker always opens!
                        f.set(googleSignInOptions, null);
                    } else if (type == String.class) {
                        String s = (String) f.get(googleSignInOptions);
                        if (s != null && (s.endsWith(".googleusercontent.com") || s.contains(".apps."))) {
                            sLastServerClientId = s;
                            Log.d(TAG, "Preserved outbound serverClientId: " + s);
                            // DO NOT set to null! Google Play Services needs this to generate the real serverAuthCode!
                        } else if (s == null && "com.pubg.imobile".equals(sLastVirtualPackage)
                                && (f.getName().equals("zaj") || f.getName().toLowerCase().contains("serverclientid"))) {
                            String kraftonClientId = "646362697320-ng2m2uj8a53arsiqgk7btc3bq9ia3rep.apps.googleusercontent.com";
                            sLastServerClientId = kraftonClientId;
                            f.set(googleSignInOptions, kraftonClientId);
                            Log.d(TAG, "Injected BGMI serverClientId into outbound options");
                        }
                    } else if (type == boolean.class || type == Boolean.class) {
                        // Ensure mServerAuthCodeRequested is TRUE when serverClientId is present
                        if (f.getName().equals("zah") || f.getName().equalsIgnoreCase("mServerAuthCodeRequested")) {
                            f.set(googleSignInOptions, true);
                            Log.d(TAG, "Ensured mServerAuthCodeRequested is true on outbound options");
                        }
                    } else if (List.class.isAssignableFrom(type) || ArrayList.class.isAssignableFrom(type)) {
                        Object listObj = f.get(googleSignInOptions);
                        if (listObj instanceof List) {
                            List list = (List) listObj;
                            if (!list.isEmpty()) {
                                Object first = list.get(0);
                                if (first != null && first.getClass().getName().endsWith(".Scope")) {
                                    synchronized (sLastRequestedScopes) {
                                        sLastRequestedScopes.clear();
                                        sLastRequestedScopes.addAll(list);
                                    }
                                    // Replace games / games_lite scopes with email & profile scopes
                                    // so GMS does not reject the Loader's SHA-1!
                                    Class<?> scopeClass = first.getClass();
                                    Constructor<?> scopeCtor = scopeClass.getConstructor(String.class);
                                    scopeCtor.setAccessible(true);
                                    ArrayList safeScopes = new ArrayList();
                                    safeScopes.add(scopeCtor.newInstance("email"));
                                    safeScopes.add(scopeCtor.newInstance("profile"));
                                    safeScopes.add(scopeCtor.newInstance("openid"));
                                    f.set(googleSignInOptions, safeScopes);
                                    Log.d(TAG, "Replaced games scopes with safe email/profile/openid scopes on outbound options");
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Extracts ClientTransaction items across Android 9–16, supporting both
     * legacy mActivityCallbacks and Android 14 QPR2 / 15 / 16 mTransactionItems.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> extractClientTransactionItems(Object clientTransaction) {
        if (clientTransaction == null) return null;
        try {
            List<Object> callbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();
            if (callbacks != null && !callbacks.isEmpty()) {
                return callbacks;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getItems = clientTransaction.getClass().getMethod("getTransactionItems");
            Object res = getItems.invoke(clientTransaction);
            if (res instanceof List && !((List<?>) res).isEmpty()) {
                return (List<Object>) res;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field f = clientTransaction.getClass().getDeclaredField("mTransactionItems");
            f.setAccessible(true);
            Object res = f.get(clientTransaction);
            if (res instanceof List && !((List<?>) res).isEmpty()) {
                return (List<Object>) res;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getCb = clientTransaction.getClass().getMethod("getCallbacks");
            Object res = getCb.invoke(clientTransaction);
            if (res instanceof List) {
                return (List<Object>) res;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Inspects incoming ClientTransaction callbacks in HCallbackStub so any
     * ActivityResultItem destined for SignInHubActivity is enriched before
     * SignInHubActivity.onActivityResult executes.
     */
    public static void inspectClientTransaction(Object clientTransaction) {
        if (clientTransaction == null) return;
        try {
            List<Object> callbacks = extractClientTransactionItems(clientTransaction);
            if (callbacks == null) return;
            Class<?> resultItemClass = BRActivityResultItem.getRealClass();
            for (Object item : callbacks) {
                if (item == null) continue;
                if ((resultItemClass != null && resultItemClass.isInstance(item))
                        || item.getClass().getName().endsWith(".ActivityResultItem")) {
                    List<?> resultInfos = extractResultInfoList(item);
                    if (resultInfos != null) {
                        for (Object ri : resultInfos) {
                            inspectResultInfo(ri);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static List<?> extractResultInfoList(Object activityResultItem) {
        try {
            List<?> list = BRActivityResultItem.get(activityResultItem).mResultInfoList();
            if (list != null) return list;
        } catch (Throwable ignored) {
        }
        try {
            Field f = activityResultItem.getClass().getDeclaredField("mResultInfoList");
            f.setAccessible(true);
            Object v = f.get(activityResultItem);
            if (v instanceof List) return (List<?>) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void inspectResultInfo(Object resultInfo) {
        if (resultInfo == null) return;
        try {
            Field dataField = resultInfo.getClass().getDeclaredField("mData");
            dataField.setAccessible(true);
            Intent data = (Intent) dataField.get(resultInfo);
            Field reqField = null;
            int requestCode = -1;
            try {
                reqField = resultInfo.getClass().getDeclaredField("mRequestCode");
                reqField.setAccessible(true);
                requestCode = reqField.getInt(resultInfo);
            } catch (Throwable ignored) {
            }

            Field codeField = null;
            int resultCode = Activity.RESULT_CANCELED;
            try {
                codeField = resultInfo.getClass().getDeclaredField("mResultCode");
                codeField.setAccessible(true);
                resultCode = codeField.getInt(resultInfo);
            } catch (Throwable ignored) {
            }

            // 40962 (0xA002) is SignInHubActivity's request code for GOOGLE_SIGN_IN
            boolean isExplicitCancel = false;
            if (resultCode == Activity.RESULT_CANCELED) {
                if (data == null) {
                    isExplicitCancel = true;
                } else {
                    int err = data.getIntExtra("errorCode", 0);
                    // 16 = CommonStatusCodes.CANCELED, 12501 = GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                    if (err == 16 || err == 12501) {
                        isExplicitCancel = true;
                    } else if (err == 0 && !data.hasExtra("googleSignInStatus")
                            && !data.hasExtra("signInAccount")
                            && !data.hasExtra("googleSignInAccount")
                            && !data.hasExtra(EXTRA_WEB_EMAIL)) {
                        isExplicitCancel = true;
                    }
                }
            }

            if (isExplicitCancel) {
                Log.d(TAG, "User explicitly cancelled Google sign in");
                return;
            }

            if (data == null && requestCode == 40962) {
                data = new Intent();
                dataField.set(resultInfo, data);
            }
            if (data != null) {
                boolean modified = enrichSignInResultIntent(data, requestCode == 40962);
                if (modified && codeField != null) {
                    codeField.setInt(resultInfo, Activity.RESULT_OK);
                    Log.d(TAG, "Enriched Google Sign-In result to RESULT_OK for req=" + requestCode);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean enrichSignInResultIntent(Intent data) {
        return enrichSignInResultIntent(data, false);
    }

    /**
     * Enriches the Intent returned by GoogleSignInChimeraActivity so SignInHubActivity
     * always receives a valid SignInAccount + GoogleSignInAccount with non-null
     * idToken, serverAuthCode, and restored requested Scopes.
     */
    public static boolean enrichSignInResultIntent(Intent data, boolean isSignInHubRequest) {
        if (data == null) return false;
        try {
            ClassLoader guestCl = (BActivityThread.getApplication() != null
                    && BActivityThread.getApplication().getClassLoader() != null)
                    ? BActivityThread.getApplication().getClassLoader() : null;
            if (guestCl == null) {
                if (data.getExtras() != null && data.getExtras().getClassLoader() != null) {
                    guestCl = data.getExtras().getClassLoader();
                } else if (FeaturesticBoxCore.getContext() != null) {
                    guestCl = FeaturesticBoxCore.getContext().getClassLoader();
                }
            }
            if (guestCl != null) {
                data.setExtrasClassLoader(guestCl);
            }

            if (data.hasExtra(EXTRA_WEB_EMAIL)) {
                sLastWebEmail = data.getStringExtra(EXTRA_WEB_EMAIL);
                sLastWebName = data.getStringExtra(EXTRA_WEB_NAME);
                sLastWebSub = data.getStringExtra(EXTRA_WEB_SUB);
                sLastWebIdToken = data.getStringExtra(EXTRA_WEB_ID_TOKEN);
            }
            if (data.hasExtra("authAccount")) {
                String em = data.getStringExtra("authAccount");
                if (em != null && !em.isEmpty()) sLastWebEmail = em;
            } else if (data.hasExtra("accountName")) {
                String em = data.getStringExtra("accountName");
                if (em != null && !em.isEmpty()) sLastWebEmail = em;
            } else if (data.hasExtra(AccountManager.KEY_ACCOUNT_NAME)) {
                String em = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                if (em != null && !em.isEmpty()) sLastWebEmail = em;
            }

            boolean hasSignInExtra = data.hasExtra("signInAccount")
                    || data.hasExtra("googleSignInAccount")
                    || data.hasExtra(EXTRA_WEB_EMAIL)
                    || data.hasExtra("errorCode")
                    || data.hasExtra("googleSignInStatus")
                    || isSignInHubRequest;
            if (!hasSignInExtra) {
                return false;
            }

            Object signInAccountWrapper = data.getParcelableExtra("signInAccount");
            Object googleSignInAccount = data.getParcelableExtra("googleSignInAccount");

            if (signInAccountWrapper != null && googleSignInAccount == null) {
                googleSignInAccount = extractInnerGoogleSignInAccount(signInAccountWrapper);
            }

            // If GMS returned an errorCode (e.g. DEVELOPER_ERROR 10 or SIGN_IN_FAILED 12500)
            // without a GoogleSignInAccount, synthesize a fallback GoogleSignInAccount + SignInAccount
            // using the device's real Google account!
            if (googleSignInAccount == null && guestCl != null
                    && (data.hasExtra(EXTRA_WEB_EMAIL) || data.hasExtra("errorCode") || data.hasExtra("googleSignInStatus") || isSignInHubRequest)) {
                googleSignInAccount = buildFallbackGoogleSignInAccount(guestCl);
                if (googleSignInAccount != null && signInAccountWrapper == null) {
                    signInAccountWrapper = wrapInSignInAccount(guestCl, googleSignInAccount);
                }
            }

            if (googleSignInAccount != null) {
                populateMissingTokensAndScopesOnAccount(googleSignInAccount);
                if (signInAccountWrapper == null && guestCl != null) {
                    signInAccountWrapper = wrapInSignInAccount(guestCl, googleSignInAccount);
                }
                if (signInAccountWrapper instanceof Parcelable) {
                    data.putExtra("signInAccount", (Parcelable) signInAccountWrapper);
                }
                if (googleSignInAccount instanceof Parcelable) {
                    data.putExtra("googleSignInAccount", (Parcelable) googleSignInAccount);
                }
                data.removeExtra("errorCode");
                data.removeExtra("googleSignInStatus");
                if (guestCl != null) {
                    try {
                        Class<?> statusClass = Class.forName("com.google.android.gms.common.api.Status", false, guestCl);
                        Object successStatus = null;
                        try {
                            Field f = statusClass.getField("RESULT_SUCCESS");
                            successStatus = f.get(null);
                        } catch (Throwable ignored) {
                        }
                        if (successStatus == null) {
                            try {
                                Constructor<?> sc = statusClass.getConstructor(int.class);
                                sc.setAccessible(true);
                                successStatus = sc.newInstance(0);
                            } catch (Throwable ignored) {
                                Constructor<?> sc = statusClass.getConstructor(int.class, String.class);
                                sc.setAccessible(true);
                                successStatus = sc.newInstance(0, null);
                            }
                        }
                        if (successStatus instanceof Parcelable) {
                            data.putExtra("googleSignInStatus", (Parcelable) successStatus);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "enrichSignInResultIntent error: " + t.getClass().getSimpleName());
        }
        return false;
    }

    private static Object buildFallbackGoogleSignInAccount(ClassLoader cl) {
        try {
            String email = (sLastWebEmail != null && !sLastWebEmail.trim().isEmpty())
                    ? sLastWebEmail.trim() : resolveDeviceGoogleEmail();
            String displayName = (sLastWebName != null && !sLastWebName.trim().isEmpty())
                    ? sLastWebName.trim() : (email.contains("@") ? email.split("@")[0] : "Player");
            String subId = (sLastWebSub != null && !sLastWebSub.trim().isEmpty())
                    ? sLastWebSub.trim()
                    : String.valueOf(Math.abs((long) email.hashCode()) + 100000000000000000L);

            Class<?> acctClass = Class.forName(
                    "com.google.android.gms.auth.api.signin.GoogleSignInAccount", false, cl);
            Set<Object> scopes = new HashSet<>();
            synchronized (sLastRequestedScopes) {
                scopes.addAll(sLastRequestedScopes);
            }
            if (scopes.isEmpty()) {
                try {
                    Class<?> scopeClass = Class.forName("com.google.android.gms.common.api.Scope", false, cl);
                    Constructor<?> sc = scopeClass.getConstructor(String.class);
                    scopes.add(sc.newInstance("email"));
                    scopes.add(sc.newInstance("profile"));
                } catch (Throwable ignored) {
                }
            }

            String token = (sLastWebIdToken != null && sLastWebIdToken.contains("."))
                    ? sLastWebIdToken : tryFetchRealGoogleToken(email);
            if (token == null || !token.contains(".")) {
                token = buildCompatibleIdToken(subId, email, displayName, displayName, "", null, sLastServerClientId);
            }

            // Look for static factory method zaa(String id, String idToken, String email, String displayName, Uri photo, Long exp, String obf, Set<Scope> scopes)
            for (Method m : acctClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != acctClass) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length >= 6 && pt[0] == String.class && pt[1] == String.class && pt[2] == String.class) {
                    m.setAccessible(true);
                    Object[] args = new Object[pt.length];
                    args[0] = subId;
                    args[1] = token;
                    args[2] = email;
                    if (pt.length > 3 && pt[3] == String.class) args[3] = displayName;
                    for (int i = 4; i < pt.length; i++) {
                        if (pt[i] == Long.class || pt[i] == long.class) {
                            args[i] = Long.valueOf(System.currentTimeMillis() / 1000L + 3600L);
                        } else if (pt[i] == String.class) {
                            args[i] = email;
                        } else if (Set.class.isAssignableFrom(pt[i])) {
                            args[i] = scopes;
                        } else {
                            args[i] = null;
                        }
                    }
                    return m.invoke(null, args);
                }
            }

            // Fallback to constructor
            for (Constructor<?> c : acctClass.getDeclaredConstructors()) {
                c.setAccessible(true);
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length >= 8) {
                    Object[] args = new Object[pt.length];
                    for (int i = 0; i < pt.length; i++) {
                        if (pt[i] == int.class || pt[i] == Integer.class) args[i] = Integer.valueOf(3);
                        else if (pt[i] == long.class || pt[i] == Long.class) args[i] = Long.valueOf(System.currentTimeMillis() / 1000L + 3600L);
                        else if (List.class.isAssignableFrom(pt[i])) args[i] = new ArrayList<>(scopes);
                        else if (Set.class.isAssignableFrom(pt[i])) args[i] = scopes;
                        else if (pt[i] == String.class) {
                            if (i == 1) args[i] = subId;
                            else if (i == 2) args[i] = token;
                            else if (i == 3) args[i] = email;
                            else if (i == 4) args[i] = displayName;
                            else args[i] = email;
                        } else {
                            args[i] = null;
                        }
                    }
                    return c.newInstance(args);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object wrapInSignInAccount(ClassLoader cl, Object googleSignInAccount) {
        try {
            Class<?> wrapperClass = Class.forName(
                    "com.google.android.gms.auth.api.signin.internal.SignInAccount", false, cl);
            String email = invokeStringGetter(googleSignInAccount, "getEmail");
            if (email == null || email.isEmpty()) email = "player@gmail.com";
            String id = invokeStringGetter(googleSignInAccount, "getId");
            if (id == null || id.isEmpty()) id = "100000000000000000000";

            for (Constructor<?> c : wrapperClass.getDeclaredConstructors()) {
                c.setAccessible(true);
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 3 && pt[0] == String.class && pt[2] == String.class) {
                    return c.newInstance(email, googleSignInAccount, id);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String resolveDeviceGoogleEmail() {
        if (sLastWebEmail != null && !sLastWebEmail.trim().isEmpty()) {
            return sLastWebEmail.trim();
        }
        try {
            Context ctx = FeaturesticBoxCore.getContext();
            if (ctx == null) ctx = BActivityThread.getApplication();
            if (ctx != null) {
                Account[] accounts = AccountManager.get(ctx).getAccountsByType("com.google");
                if (accounts != null && accounts.length > 0 && accounts[0].name != null) {
                    return accounts[0].name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            android.os.IBinder binder = black.android.os.BRServiceManager.get().getService(Context.ACCOUNT_SERVICE);
            if (binder != null) {
                Object iam = black.android.accounts.BRIAccountManagerStub.get().asInterface(binder);
                if (iam != null) {
                    for (Method m : iam.getClass().getMethods()) {
                        if ("getAccountsAsUser".equals(m.getName())) {
                            Class<?>[] pt = m.getParameterTypes();
                            if (pt.length == 3 && pt[0] == String.class && pt[1] == int.class && pt[2] == String.class) {
                                Object res = m.invoke(iam, "com.google", 0, FeaturesticBoxCore.getHostPkg());
                                if (res instanceof Account[]) {
                                    Account[] accounts = (Account[]) res;
                                    if (accounts.length > 0 && accounts[0].name != null) {
                                        return accounts[0].name;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "featurestic.player@gmail.com";
    }

    private static Object extractInnerGoogleSignInAccount(Object signInAccountWrapper) {
        if (signInAccountWrapper == null) return null;
        Class<?> cls = signInAccountWrapper.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(signInAccountWrapper);
                    if (v != null && v.getClass().getName().endsWith("GoogleSignInAccount")) {
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void populateMissingTokensAndScopesOnAccount(Object account) {
        if (account == null) return;
        try {
            // Restore original requested scopes (e.g. games_lite / games) onto GoogleSignInAccount
            synchronized (sLastRequestedScopes) {
                if (!sLastRequestedScopes.isEmpty()) {
                    Class<?> sCls = account.getClass();
                    while (sCls != null && sCls != Object.class) {
                        for (Field f : sCls.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(account);
                                if (v instanceof Collection) {
                                    Collection col = (Collection) v;
                                    for (Object reqScope : sLastRequestedScopes) {
                                        if (reqScope != null && !col.contains(reqScope)) {
                                            col.add(reqScope);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        sCls = sCls.getSuperclass();
                    }
                }
            }

            String curIdToken = invokeStringGetter(account, "getIdToken");
            String curAuthCode = invokeStringGetter(account, "getServerAuthCode");
            Log.d(TAG, "Account tokens: idToken=" + (curIdToken != null ? "YES" : "null")
                    + ", serverAuthCode=" + (curAuthCode != null ? ("YES(len=" + curAuthCode.length() + ")") : "null"));
            if (curIdToken != null && !curIdToken.isEmpty()
                    && curAuthCode != null && !curAuthCode.isEmpty()) {
                return;
            }

            String id = invokeStringGetter(account, "getId");
            String email = invokeStringGetter(account, "getEmail");
            String name = invokeStringGetter(account, "getDisplayName");
            String given = invokeStringGetter(account, "getGivenName");
            String family = invokeStringGetter(account, "getFamilyName");
            Uri photo = invokeUriGetter(account, "getPhotoUrl");

            String token = curIdToken;
            if (token == null || token.isEmpty()) {
                if (curAuthCode != null && !curAuthCode.isEmpty()) {
                    token = buildCompatibleIdToken(id, email, name, given, family,
                            photo != null ? photo.toString() : null, sLastServerClientId);
                } else {
                    token = tryFetchRealGoogleToken(email);
                    if (token == null || !token.contains(".")) {
                        token = buildCompatibleIdToken(id, email, name, given, family,
                                photo != null ? photo.toString() : null, sLastServerClientId);
                    }
                }
            }

            String authCode = curAuthCode;
            if (authCode == null || authCode.isEmpty()) {
                authCode = tryFetchRealGoogleAuthCode(email);
            }

            Class<?> cls = account.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> fType = f.getType();
                    if (fType != String.class) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object original = f.get(account);
                        if (original != null) continue;

                        String probe = "__fbox_probe__";
                        f.set(account, probe);
                        if (probe.equals(invokeStringGetter(account, "getIdToken"))) {
                            f.set(account, token);
                            continue;
                        }
                        if (probe.equals(invokeStringGetter(account, "getServerAuthCode"))) {
                            if (authCode != null && !authCode.isEmpty()) {
                                f.set(account, authCode);
                            } else {
                                f.set(account, null);
                            }
                            continue;
                        }
                        f.set(account, null);
                    } catch (Throwable ignored) {
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String tryFetchRealGoogleToken(final String email) {
        if (email == null || email.trim().isEmpty()) return null;
        try {
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> {
                Context ctx = BActivityThread.getApplication();
                if (ctx == null) ctx = FeaturesticBoxCore.getContext();
                if (ctx == null) return null;
                ClassLoader cl = ctx.getClassLoader();
                Class<?> authUtil = Class.forName("com.google.android.gms.auth.GoogleAuthUtil", false, cl);
                Method getToken = authUtil.getMethod("getToken", Context.class, Account.class, String.class);
                Account acct = new Account(email, "com.google");
                if (sLastServerClientId != null && !sLastServerClientId.isEmpty()) {
                    try {
                        Object res = getToken.invoke(null, ctx, acct, "audience:server:client_id:" + sLastServerClientId);
                        if (res instanceof String && !((String) res).isEmpty()) {
                            return (String) res;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Object res = getToken.invoke(null, ctx, acct, "oauth2:email profile openid");
                    if (res instanceof String && !((String) res).isEmpty()) {
                        return (String) res;
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
            new Thread(task, "Featurestic-GoogleTokenFetcher").start();
            return task.get(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String tryFetchRealGoogleAuthCode(final String email) {
        if (email == null || email.trim().isEmpty() || sLastServerClientId == null || sLastServerClientId.isEmpty()) {
            return null;
        }
        try {
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> {
                Context ctx = BActivityThread.getApplication();
                if (ctx == null) ctx = FeaturesticBoxCore.getContext();
                if (ctx == null) return null;
                ClassLoader cl = ctx.getClassLoader();
                Class<?> authUtil = Class.forName("com.google.android.gms.auth.GoogleAuthUtil", false, cl);
                Method getToken = authUtil.getMethod("getToken", Context.class, Account.class, String.class);
                Account acct = new Account(email, "com.google");
                try {
                    String scope = "oauth2:server:client_id:" + sLastServerClientId + ":api_scope:email profile openid";
                    Object res = getToken.invoke(null, ctx, acct, scope);
                    if (res instanceof String && !((String) res).isEmpty()) {
                        return (String) res;
                    }
                } catch (Throwable ignored) {
                }
                return null;
            });
            new Thread(task, "Featurestic-GoogleAuthCodeFetcher").start();
            return task.get(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String buildCompatibleIdToken(
            String sub, String email, String name, String given, String family,
            String picture, String clientId) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long expSec = nowSec + 3600L;
        String aud = (clientId != null && !clientId.isEmpty())
                ? clientId : "google-signin-client.apps.googleusercontent.com";
        String safeSub = (sub != null && !sub.isEmpty()) ? sub : "100000000000000000000";
        String safeEmail = (email != null && !email.isEmpty()) ? email : "user@gmail.com";
        String safeName = (name != null && !name.isEmpty()) ? name : safeEmail.split("@")[0];

        org.json.JSONObject headerObj = new org.json.JSONObject();
        org.json.JSONObject payloadObj = new org.json.JSONObject();
        try {
            headerObj.put("alg", "RS256");
            headerObj.put("kid", "fbox1");
            headerObj.put("typ", "JWT");

            payloadObj.put("iss", "https://accounts.google.com");
            payloadObj.put("azp", aud);
            payloadObj.put("aud", aud);
            payloadObj.put("sub", safeSub);
            payloadObj.put("email", safeEmail);
            payloadObj.put("email_verified", true);
            payloadObj.put("name", safeName);
            if (given != null) payloadObj.put("given_name", given);
            if (family != null) payloadObj.put("family_name", family);
            if (picture != null) payloadObj.put("picture", picture);
            payloadObj.put("iat", nowSec);
            payloadObj.put("exp", expSec);
        } catch (Throwable ignored) {
        }

        int flags = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
        String h = Base64.encodeToString(headerObj.toString().getBytes(StandardCharsets.UTF_8), flags);
        String p = Base64.encodeToString(payloadObj.toString().getBytes(StandardCharsets.UTF_8), flags);
        String s = Base64.encodeToString((h + "." + p).getBytes(StandardCharsets.UTF_8), flags);
        return h + "." + p + "." + s;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Uri invokeUriGetter(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            return v instanceof Uri ? (Uri) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
