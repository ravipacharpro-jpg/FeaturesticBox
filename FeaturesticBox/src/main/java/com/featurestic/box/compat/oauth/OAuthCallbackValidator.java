package com.featurestic.box.compat.oauth;

import android.net.Uri;

import java.util.Locale;

/**
 * Validates browser-controlled OAuth callback URIs before they cross back into
 * a virtual application.
 */
public final class OAuthCallbackValidator {
    private OAuthCallbackValidator() {
    }

    public static boolean matches(Uri authUri, Uri expectedRedirect, Uri callback) {
        if (authUri == null || expectedRedirect == null || callback == null) {
            return false;
        }
        if (!lower(expectedRedirect.getScheme()).equals(lower(callback.getScheme()))) {
            return false;
        }
        String expectedAuth = lower(expectedRedirect.getEncodedAuthority());
        String callbackAuth = lower(callback.getEncodedAuthority());
        if (!expectedAuth.isEmpty() && !expectedAuth.equals(callbackAuth)) {
            return false;
        }
        String expectedPath = normalizePath(expectedRedirect.getEncodedPath());
        String callbackPath = normalizePath(callback.getEncodedPath());
        if (!expectedPath.isEmpty() && !expectedPath.equals(callbackPath)) {
            return false;
        }

        try {
            String expectedState = authUri.getQueryParameter("state");
            if (expectedState != null && !expectedState.isEmpty()) {
                String callbackState = callback.getQueryParameter("state");
                if (callbackState == null || callbackState.isEmpty()) {
                    callbackState = getFragmentParameter(callback, "state");
                }
                if (callbackState != null && !callbackState.isEmpty()
                        && !normalizeState(expectedState).equals(normalizeState(callbackState))) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    private static String normalizeState(String value) {
        if (value == null) return "";
        return value.trim().replace("+", " ").replace("%20", " ");
    }

    private static String getFragmentParameter(Uri uri, String name) {
        if (uri == null || name == null || name.isEmpty()) {
            return null;
        }
        String fragment;
        try {
            fragment = uri.getEncodedFragment();
        } catch (Throwable ignored) {
            return null;
        }
        if (fragment == null || fragment.isEmpty()) {
            return null;
        }
        String[] pairs = fragment.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String encodedKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String key;
            try {
                key = Uri.decode(encodedKey);
            } catch (Throwable ignored) {
                continue;
            }
            if (!name.equals(key)) continue;
            if (separator < 0 || separator + 1 >= pair.length()) {
                return "";
            }
            try {
                return Uri.decode(pair.substring(separator + 1).replace("+", "%20"));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
