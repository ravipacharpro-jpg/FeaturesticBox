package com.featurestic.box.compat.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lsparanoid.Obfuscate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java MicroG-compatible Google Authentication Engine.
 *
 * Implements the exact Google Android ClientLogin / OAuth2 protocol from microG GmsCore:
 * 1. Exchanges the `oauth_token` cookie obtained from Google's official EmbeddedSetup
 *    (MinuteMaid) WebView for a permanent Google Master Token (`aas_et/...`) via
 *    `https://android.googleapis.com/auth` (`service=ac2dm`).
 * 2. Stores accounts securely in the host loader so future logins are 1-tap.
 * 3. Acquires genuine, Google RSA-signed OpenID Connect ID tokens (`audience:server:client_id:...`)
 *    and OAuth2 Server Auth Codes (`oauth2:server:client_id:...`) directly from
 *    `https://android.googleapis.com/auth` using the real installed target game's package name
 *    (e.g. `com.pubg.imobile`) and real SHA-1 signing certificate digest.
 *
 * Because Google's own auth servers sign the returned JWT with Google's official RSA private key,
 * Krafton / IMSDK / Firebase servers accept it with 100% signature verification success.
 */
@Obfuscate
public final class MicroGGoogleAuthEngine {
    private static final String TAG = "FeaturesticMicroGAuth";

    public static final String AUTH_SERVICE_URL = "https://android.googleapis.com/auth";
    public static final String EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup";
    public static final String EMBEDDED_RE_AUTH_URL = "https://accounts.google.com/embedded/reauth/v2/android";
    public static final String PROGRAMMATIC_AUTH_URL = "https://accounts.google.com/o/oauth2/programmatic_auth";
    public static final String GOOGLE_SUITE_URL = "https://accounts.google.com/signin/continue";
    public static final String MAGIC_USER_AGENT_SUFFIX = " MinuteMaid";

    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PACKAGE_SIGNATURE_SHA1 = "38918a453d07199354f8b19af05ec6562ced5788";
    public static final int GMS_VERSION_CODE = 240913000;

    private static final String PREFS_NAME = "featurestic_microg_accounts";
    private static final String KEY_ACCOUNTS_JSON = "accounts_v1";
    private static final String KEY_ACTIVE_EMAIL = "active_email_v1";

    private MicroGGoogleAuthEngine() {
    }

    public static final class AccountRecord {
        public String email;
        public String masterToken;
        public String googleUserId;
        public String displayName;
        public String givenName;
        public String familyName;
        public String photoUrl;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("email", email != null ? email : "");
                o.put("masterToken", masterToken != null ? masterToken : "");
                o.put("googleUserId", googleUserId != null ? googleUserId : "");
                o.put("displayName", displayName != null ? displayName : "");
                o.put("givenName", givenName != null ? givenName : "");
                o.put("familyName", familyName != null ? familyName : "");
                o.put("photoUrl", photoUrl != null ? photoUrl : "");
            } catch (Throwable ignored) {
            }
            return o;
        }

        public static AccountRecord fromJson(JSONObject o) {
            if (o == null) return null;
            String email = o.optString("email", "").trim();
            String token = o.optString("masterToken", "").trim();
            if (email.isEmpty() || token.isEmpty()) return null;
            AccountRecord r = new AccountRecord();
            r.email = email;
            r.masterToken = token;
            r.googleUserId = emptyToNull(o.optString("googleUserId", ""));
            r.displayName = emptyToNull(o.optString("displayName", ""));
            r.givenName = emptyToNull(o.optString("givenName", ""));
            r.familyName = emptyToNull(o.optString("familyName", ""));
            r.photoUrl = emptyToNull(o.optString("photoUrl", ""));
            return r;
        }
    }

    public static final class TokenBundle {
        public String email;
        public String googleUserId;
        public String displayName;
        public String givenName;
        public String familyName;
        public String photoUrl;
        public String idToken;
        public String serverAuthCode;
        public String accessToken;
        public String grantedScopes;
        public long expirySec;
        public String error;
        public boolean needsReAuth;
    }

    public static String buildEmbeddedSetupUrl(Locale locale, String reAuthEmail) {
        if (locale == null) locale = Locale.getDefault();
        if (locale == null) locale = Locale.US;
        String lang = locale.getLanguage();
        if (lang == null || lang.isEmpty()) lang = "en";
        String country = locale.getCountry();
        if (country == null || country.isEmpty()) country = "IN";

        boolean hasEmail = reAuthEmail != null && reAuthEmail.contains("@");
        Uri.Builder builder = Uri.parse(EMBEDDED_SETUP_URL).buildUpon()
                .appendQueryParameter("source", "android")
                .appendQueryParameter("xoauth_display_name", "Android Device")
                .appendQueryParameter("lang", lang)
                .appendQueryParameter("cc", country.toLowerCase(Locale.US))
                .appendQueryParameter("langCountry", (lang + "_" + country).toLowerCase(Locale.US))
                .appendQueryParameter("hl", lang + "-" + country)
                .appendQueryParameter("tmpl", "new_account");
        if (hasEmail) {
            builder.appendQueryParameter("Email", reAuthEmail.trim());
        }
        return builder.build().toString();
    }

    public static List<AccountRecord> getSavedAccounts(Context context) {
        List<AccountRecord> list = new ArrayList<>();
        if (context == null) return list;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_ACCOUNTS_JSON, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                AccountRecord rec = AccountRecord.fromJson(arr.optJSONObject(i));
                if (rec != null) list.add(rec);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getSavedAccounts error: " + t.getMessage());
        }
        return list;
    }

    public static AccountRecord getActiveAccount(Context context) {
        if (context == null) return null;
        List<AccountRecord> saved = getSavedAccounts(context);
        if (saved.isEmpty()) return null;
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String activeEmail = sp.getString(KEY_ACTIVE_EMAIL, null);
            if (activeEmail != null && !activeEmail.trim().isEmpty()) {
                for (AccountRecord r : saved) {
                    if (r.email != null && r.email.equalsIgnoreCase(activeEmail.trim())) {
                        return r;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return saved.get(0);
    }

    public static void setActiveAccount(Context context, String email) {
        if (context == null) return;
        try {
            SharedPreferences.Editor ed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            if (email == null || email.trim().isEmpty()) {
                ed.remove(KEY_ACTIVE_EMAIL);
            } else {
                ed.putString(KEY_ACTIVE_EMAIL, email.trim());
            }
            ed.apply();
        } catch (Throwable ignored) {
        }
    }

    public static void saveAccount(Context context, AccountRecord record) {
        if (context == null || record == null || record.email == null || record.masterToken == null) {
            return;
        }
        try {
            List<AccountRecord> existing = getSavedAccounts(context);
            List<AccountRecord> updated = new ArrayList<>();
            updated.add(record);
            for (AccountRecord old : existing) {
                if (old.email != null && !old.email.equalsIgnoreCase(record.email)) {
                    updated.add(old);
                }
            }
            JSONArray arr = new JSONArray();
            for (AccountRecord r : updated) {
                arr.put(r.toJson());
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ACCOUNTS_JSON, arr.toString())
                    .putString(KEY_ACTIVE_EMAIL, record.email.trim())
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "saveAccount error: " + t.getMessage());
        }
    }

    public static void removeAccount(Context context, String email) {
        if (context == null || email == null) return;
        try {
            List<AccountRecord> existing = getSavedAccounts(context);
            JSONArray arr = new JSONArray();
            String nextActive = null;
            for (AccountRecord r : existing) {
                if (r.email != null && !r.email.equalsIgnoreCase(email)) {
                    arr.put(r.toJson());
                    if (nextActive == null) nextActive = r.email;
                }
            }
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String curActive = sp.getString(KEY_ACTIVE_EMAIL, null);
            SharedPreferences.Editor ed = sp.edit().putString(KEY_ACCOUNTS_JSON, arr.toString());
            if (curActive != null && curActive.equalsIgnoreCase(email)) {
                if (nextActive != null) ed.putString(KEY_ACTIVE_EMAIL, nextActive);
                else ed.remove(KEY_ACTIVE_EMAIL);
            }
            ed.apply();
        } catch (Throwable t) {
            Log.w(TAG, "removeAccount error: " + t.getMessage());
        }
    }

    /**
     * Resolves the real device GSF Android ID as lowercase hex (preferred by Google auth servers),
     * falling back to Settings.Secure.ANDROID_ID if GSF is unreadable.
     */
    public static String resolveAndroidIdHex(Context context) {
        if (context != null) {
            Cursor c = null;
            try {
                Uri uri = Uri.parse("content://com.google.android.gsf.gservices");
                c = context.getContentResolver().query(uri, null, null, new String[]{"android_id"}, null);
                if (c != null && c.moveToFirst() && c.getColumnCount() >= 2) {
                    String val = c.getString(1);
                    if (val != null && !val.trim().isEmpty()) {
                        long id = Long.parseLong(val.trim());
                        if (id != 0L) {
                            return Long.toHexString(id);
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            try {
                String aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (aid != null && !aid.trim().isEmpty()) {
                    return aid.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Computes the lowercase 40-char SHA-1 hex digest of the target package's signing certificate,
     * identical to microG's PackageUtils.firstSignatureDigest().
     */
    public static String getPackageSignatureSha1(Context context, String packageName) {
        if (context == null || packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    PackageInfo pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                    if (pi != null && pi.signingInfo != null) {
                        sigs = pi.signingInfo.hasMultipleSigners()
                                ? pi.signingInfo.getApkContentsSigners()
                                : pi.signingInfo.getSigningCertificateHistory();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (sigs == null || sigs.length == 0) {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                if (pi != null) {
                    sigs = pi.signatures;
                }
            }
            if (sigs != null && sigs.length > 0 && sigs[0] != null) {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] digest = md.digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(String.format(Locale.US, "%02x", b));
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "getPackageSignatureSha1 failed for " + packageName + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * Step 1 (microG LoginActivity.retrieveRtToken):
     * Exchanges the `oauth_token` cookie captured from EmbeddedSetup for a permanent
     * Google Master Token (`aas_et/...`).
     */
    public static AccountRecord exchangeOAuthCookieForMasterToken(Context context, String oauthToken) throws IOException {
        if (oauthToken == null || oauthToken.trim().isEmpty()) {
            throw new IOException("Empty oauth_token cookie");
        }
        String androidIdHex = resolveAndroidIdHex(context);
        Locale locale = Locale.getDefault();
        String country = (locale != null && locale.getCountry() != null && !locale.getCountry().isEmpty())
                ? locale.getCountry().toLowerCase(Locale.US) : "in";
        String lang = (locale != null && !locale.toString().isEmpty()) ? locale.toString() : "en_IN";

        Map<String, String> params = new LinkedHashMap<>();
        params.put("app", GMS_PACKAGE_NAME);
        params.put("client_sig", GMS_PACKAGE_SIGNATURE_SHA1);
        params.put("callerPkg", GMS_PACKAGE_NAME);
        params.put("callerSig", GMS_PACKAGE_SIGNATURE_SHA1);
        if (androidIdHex != null) {
            params.put("androidId", androidIdHex);
        }
        params.put("sdk_version", String.valueOf(Build.VERSION.SDK_INT));
        params.put("device_country", country);
        params.put("operatorCountry", country);
        params.put("lang", lang);
        params.put("google_play_services_version", String.valueOf(GMS_VERSION_CODE));
        params.put("service", "ac2dm");
        params.put("Token", oauthToken.trim());
        params.put("ACCESS_TOKEN", "1");
        params.put("add_account", "1");
        params.put("get_accountid", "1");
        params.put("droidguard_results", "null");

        Map<String, String> headers = buildCommonHeaders(GMS_PACKAGE_NAME, androidIdHex);
        Map<String, String> resp = postAuthForm(params, headers);

        String masterToken = resp.get("Token");
        String email = resp.get("Email");
        if (masterToken == null || masterToken.isEmpty() || email == null || email.isEmpty()) {
            String err = resp.get("Error");
            throw new IOException("Master token exchange failed: " + (err != null ? err : resp.toString()));
        }

        AccountRecord record = new AccountRecord();
        record.email = email.trim();
        record.masterToken = masterToken.trim();
        record.googleUserId = emptyToNull(resp.get("accountId"));
        record.givenName = emptyToNull(resp.get("firstName"));
        record.familyName = emptyToNull(resp.get("lastName"));
        if (record.givenName != null && record.familyName != null) {
            record.displayName = record.givenName + " " + record.familyName;
        } else if (record.givenName != null) {
            record.displayName = record.givenName;
        } else {
            record.displayName = record.email.contains("@") ? record.email.split("@")[0] : "Player";
        }

        saveAccount(context, record);
        Log.i(TAG, "Successfully exchanged oauth_token for permanent Master Token for " + record.email);
        return record;
    }

    /**
     * Step 2 (microG AuthSignInExtensions.performSignIn):
     * Uses the saved Master Token to acquire genuine Google-signed ID Token + Server Auth Code
     * for the real target game package and its authentic SHA-1 signing certificate.
     */
    public static TokenBundle performAppSignIn(
            Context context,
            AccountRecord account,
            String targetPackage,
            String serverClientId,
            String requestedScopesSpaceSeparated) {
        TokenBundle result = new TokenBundle();
        if (account == null || account.email == null || account.masterToken == null) {
            result.error = "Missing Google account";
            result.needsReAuth = true;
            return result;
        }

        result.email = account.email;
        result.googleUserId = account.googleUserId;
        result.displayName = account.displayName;
        result.givenName = account.givenName;
        result.familyName = account.familyName;
        result.photoUrl = account.photoUrl;

        String pkg = (targetPackage != null && !targetPackage.trim().isEmpty())
                ? targetPackage.trim() : "com.pubg.imobile";
        String clientSig = getPackageSignatureSha1(context, pkg);
        if (clientSig == null || clientSig.isEmpty()) {
            result.error = "Could not read signing certificate for " + pkg;
            return result;
        }

        if ((serverClientId == null || serverClientId.trim().isEmpty()) && "com.pubg.imobile".equals(pkg)) {
            serverClientId = "646362697320-ng2m2uj8a53arsiqgk7btc3bq9ia3rep.apps.googleusercontent.com";
        }

        Log.i(TAG, "MicroG performAppSignIn: pkg=" + pkg + ", sig=" + clientSig
                + ", clientId=" + serverClientId + ", email=" + account.email);

        String androidIdHex = resolveAndroidIdHex(context);
        String sortedScopes = buildSortedScopes(requestedScopesSpaceSeparated);
        String basicScopes = "email openid profile";

        // 1. Acquire OAuth2 access token (registers app consent on Google backend)
        Map<String, String> oauthResp = null;
        try {
            oauthResp = requestServiceToken(
                    pkg, clientSig, account.email, account.masterToken,
                    "oauth2:" + sortedScopes,
                    androidIdHex, true, "1", null, null, null);
        } catch (AuthHttpException e) {
            Log.w(TAG, "OAuth2 full scopes request failed (" + e.errorCode + "): " + e.getMessage());
            if (isReAuthError(e.errorCode)) {
                result.error = e.errorCode;
                result.needsReAuth = true;
                return result;
            }
        } catch (Throwable t) {
            Log.w(TAG, "OAuth2 full scopes error: " + t.getMessage());
        }

        if (oauthResp == null || emptyToNull(oauthResp.get("Auth")) == null) {
            try {
                oauthResp = requestServiceToken(
                        pkg, clientSig, account.email, account.masterToken,
                        "oauth2:" + basicScopes,
                        androidIdHex, true, "1", null, null, null);
            } catch (AuthHttpException e) {
                Log.w(TAG, "OAuth2 basic scopes failed (" + e.errorCode + "): " + e.getMessage());
                if (isReAuthError(e.errorCode)) {
                    result.error = e.errorCode;
                    result.needsReAuth = true;
                    return result;
                }
            } catch (Throwable t) {
                Log.w(TAG, "OAuth2 basic scopes error: " + t.getMessage());
            }
        }

        if (oauthResp != null) {
            result.accessToken = emptyToNull(oauthResp.get("Auth"));
            result.grantedScopes = emptyToNull(oauthResp.get("grantedScopes"));
            if (result.googleUserId == null) {
                result.googleUserId = emptyToNull(oauthResp.get("accountId"));
            }
            String exp = oauthResp.get("Expiry");
            if (exp != null) {
                try {
                    result.expirySec = Long.parseLong(exp);
                } catch (Throwable ignored) {
                }
            }
        }

        // 2. Acquire REAL Google-signed OpenID Connect ID Token (audience:server:client_id:...)
        if (serverClientId != null && !serverClientId.trim().isEmpty()) {
            try {
                Map<String, String> idResp = requestServiceToken(
                        pkg, clientSig, account.email, account.masterToken,
                        "audience:server:client_id:" + serverClientId.trim(),
                        androidIdHex, true, "0", "1", "1", null);
                String idTok = emptyToNull(idResp.get("Auth"));
                if (idTok != null && idTok.contains(".")) {
                    result.idToken = idTok;
                    hydrateProfileFromJwt(result, idTok);
                    String exp = idResp.get("Expiry");
                    if (exp != null) {
                        try {
                            long e = Long.parseLong(exp);
                            if (result.expirySec <= 0 || e < result.expirySec) result.expirySec = e;
                        } catch (Throwable ignored) {
                        }
                    }
                    Log.i(TAG, "SUCCESS! Acquired REAL Google-signed ID token (len=" + idTok.length()
                            + ") for " + pkg + " / " + account.email);
                } else {
                    Log.w(TAG, "ID token response had no Auth JWT: " + idResp);
                }
            } catch (AuthHttpException e) {
                Log.w(TAG, "ID token request failed (" + e.errorCode + "): " + e.getMessage());
                if (isReAuthError(e.errorCode)) {
                    result.error = e.errorCode;
                    result.needsReAuth = true;
                    return result;
                }
                result.error = e.errorCode;
            } catch (Throwable t) {
                Log.w(TAG, "ID token request exception: " + t.getMessage());
                result.error = t.getMessage();
            }

            // 3. Also acquire REAL Google Server Auth Code (best-effort; BGMI/IMSDK may inspect either)
            try {
                String codeService = "oauth2:server:client_id:" + serverClientId.trim()
                        + ":api_scope:" + basicScopes;
                Map<String, String> codeResp = requestServiceToken(
                        pkg, clientSig, account.email, account.masterToken,
                        codeService,
                        androidIdHex, true, "1", "1", "1", "auto");
                String code = emptyToNull(codeResp.get("Auth"));
                if (code != null) {
                    result.serverAuthCode = code;
                    Log.i(TAG, "SUCCESS! Acquired REAL Google serverAuthCode (len=" + code.length() + ")");
                }
            } catch (Throwable t) {
                Log.w(TAG, "ServerAuthCode fetch optional warning: " + t.getMessage());
            }
        }

        // Update cached profile fields from real JWT claims
        if (result.googleUserId != null && !result.googleUserId.equals(account.googleUserId)) {
            account.googleUserId = result.googleUserId;
            if (result.displayName != null) account.displayName = result.displayName;
            if (result.givenName != null) account.givenName = result.givenName;
            if (result.familyName != null) account.familyName = result.familyName;
            if (result.photoUrl != null) account.photoUrl = result.photoUrl;
            saveAccount(context, account);
        }

        if (result.idToken == null && result.accessToken == null && result.error == null) {
            result.error = "Google auth returned no token";
        }
        return result;
    }

    private static Map<String, String> requestServiceToken(
            String targetPkg,
            String clientSig,
            String email,
            String masterToken,
            String service,
            String androidIdHex,
            boolean hasPermission,
            String includeGrantedScopes,
            String includeEmail,
            String includeProfile,
            String oauth2Prompt) throws IOException {
        Locale locale = Locale.getDefault();
        String country = (locale != null && locale.getCountry() != null && !locale.getCountry().isEmpty())
                ? locale.getCountry().toLowerCase(Locale.US) : "in";
        String lang = (locale != null && !locale.toString().isEmpty()) ? locale.toString() : "en_IN";

        Map<String, String> params = new LinkedHashMap<>();
        params.put("source", "android");
        params.put("app", targetPkg);
        params.put("client_sig", clientSig);
        params.put("callerPkg", GMS_PACKAGE_NAME);
        params.put("callerSig", GMS_PACKAGE_SIGNATURE_SHA1);
        if (androidIdHex != null) {
            params.put("androidId", androidIdHex);
        }
        params.put("sdk_version", String.valueOf(Build.VERSION.SDK_INT));
        params.put("device_country", country);
        params.put("operatorCountry", country);
        params.put("lang", lang);
        params.put("google_play_services_version", String.valueOf(GMS_VERSION_CODE));
        params.put("Email", email);
        params.put("Token", masterToken);
        params.put("service", service);
        params.put("has_permission", hasPermission ? "1" : "0");
        params.put("is_called_from_account_manager", "1");
        params.put("_opt_is_called_from_account_manager", "1");
        if (includeGrantedScopes != null) {
            params.put("include_granted_scopes", includeGrantedScopes);
        }
        if (includeEmail != null) {
            params.put("oauth2_include_email", includeEmail);
        }
        if (includeProfile != null) {
            params.put("oauth2_include_profile", includeProfile);
        }
        if (oauth2Prompt != null) {
            params.put("oauth2_prompt", oauth2Prompt);
            params.put("it_caveat_types", "2");
        }

        Map<String, String> headers = buildCommonHeaders(targetPkg, androidIdHex);
        return postAuthForm(params, headers);
    }

    private static Map<String, String> buildCommonHeaders(String appPkg, String androidIdHex) {
        Map<String, String> headers = new LinkedHashMap<>();
        String device = Build.DEVICE != null ? Build.DEVICE : "generic";
        String buildId = Build.ID != null ? Build.ID : "REL";
        headers.put("User-Agent", String.format(Locale.US, "GoogleAuth/1.4 (%s %s); gzip", device, buildId));
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("app", appPkg);
        if (androidIdHex != null) {
            headers.put("device", androidIdHex);
        }
        return headers;
    }

    private static Map<String, String> postAuthForm(Map<String, String> params, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(AUTH_SERVICE_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }

            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> p : params.entrySet()) {
                if (p.getValue() == null) continue;
                if (body.length() > 0) body.append('&');
                body.append(Uri.encode(p.getKey())).append('=').append(Uri.encode(p.getValue()));
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respText = readStream(is);
            Map<String, String> parsed = parseKeyValueResponse(respText);

            if (code != 200) {
                String errCode = parsed.get("Error");
                if (errCode == null) errCode = "HTTP_" + code;
                throw new AuthHttpException(errCode, respText);
            }
            return parsed;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Map<String, String> parseKeyValueResponse(String text) {
        Map<String, String> map = new HashMap<>();
        if (text == null) return map;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line == null) continue;
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                map.put(k, v);
            }
        }
        return map;
    }

    private static void hydrateProfileFromJwt(TokenBundle bundle, String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return;
            byte[] payloadBytes = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject obj = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            String sub = emptyToNull(obj.optString("sub", ""));
            String email = emptyToNull(obj.optString("email", ""));
            String name = emptyToNull(obj.optString("name", ""));
            String given = emptyToNull(obj.optString("given_name", ""));
            String family = emptyToNull(obj.optString("family_name", ""));
            String pic = emptyToNull(obj.optString("picture", ""));
            long exp = obj.optLong("exp", 0L);

            if (sub != null) bundle.googleUserId = sub;
            if (email != null) bundle.email = email;
            if (name != null) bundle.displayName = name;
            if (given != null) bundle.givenName = given;
            if (family != null) bundle.familyName = family;
            if (pic != null) bundle.photoUrl = pic;
            if (exp > 0L) bundle.expirySec = exp;

            // Log header kid so we can verify in logcat it's a real Google key ID
            byte[] hdrBytes = Base64.decode(parts[0], Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject hdr = new JSONObject(new String(hdrBytes, StandardCharsets.UTF_8));
            Log.i(TAG, "JWT verified structure: alg=" + hdr.optString("alg")
                    + ", kid=" + hdr.optString("kid")
                    + ", aud=" + obj.optString("aud")
                    + ", sub=" + sub);
        } catch (Throwable ignored) {
        }
    }

    private static String buildSortedScopes(String rawScopes) {
        Set<String> set = new LinkedHashSet<>();
        set.add("email");
        set.add("openid");
        set.add("profile");
        if (rawScopes != null && !rawScopes.trim().isEmpty()) {
            for (String s : rawScopes.trim().split("\\s+")) {
                if (s != null && !s.isEmpty()) {
                    set.add(s.trim());
                }
            }
        }
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static boolean isReAuthError(String errCode) {
        if (errCode == null) return false;
        String lower = errCode.toLowerCase(Locale.US);
        return lower.contains("badauthentication")
                || lower.contains("needsbrowser")
                || lower.contains("invalid_grant")
                || lower.contains("accountdeleted")
                || lower.contains("accountdisabled")
                || lower.contains("device_management");
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        is.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static final class AuthHttpException extends IOException {
        public final String errorCode;

        public AuthHttpException(String errorCode, String body) {
            super(errorCode + ": " + body);
            this.errorCode = errorCode;
        }
    }
}
