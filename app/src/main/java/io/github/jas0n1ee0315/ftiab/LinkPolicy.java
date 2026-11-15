package io.github.jas0n1ee0315.ftiab;

import android.net.Uri;

import java.util.Locale;

/** Decides which in-app browser launches are outbound links and where they point. */
final class LinkPolicy {

    private LinkPolicy() {
    }

    /** Link shims (l.threads.com/?u=...) that wrap the real destination. */
    private static final String[] SHIM_HOSTS = {
            "l.threads.com", "l.threads.net", "l.instagram.com", "l.facebook.com", "lm.facebook.com",
    };

    /**
     * Meta-owned sites stay in the in-app browser: help center, account center,
     * policy pages and similar flows rely on the logged-in app session.
     */
    private static final String[] INTERNAL_DOMAINS = {
            "threads.com", "threads.net", "instagram.com", "facebook.com", "fb.com", "meta.com",
            "metastatus.com", "messenger.com",
    };

    static boolean isInAppBrowser(String packageName, String className) {
        if (!ModuleMain.THREADS_PACKAGE.equals(packageName) || className == null) return false;
        // com.instagram.barcelona.inappbrowser.fragments.ThreadsBrowserLiteActivity,
        // com.instagram.inappbrowser.fragments.BrowserLiteInMainProcessIGActivity,
        // com.facebook.browser.lite.BrowserLite*Activity ...
        return className.contains("BrowserLite") && className.endsWith("Activity")
                && !className.contains("Playground");
    }

    /** @return the external http(s) URL to open, or {@code null} to keep the in-app browser. */
    static String destination(String url) {
        Uri target = webUri(url);
        if (target == null) return null;
        for (int i = 0; i < 4 && isShim(target.getHost()); i++) {
            Uri inner;
            try {
                inner = webUri(target.getQueryParameter("u"));
            } catch (UnsupportedOperationException e) {
                inner = null;
            }
            if (inner == null) return null;
            target = inner;
        }
        if (isInternal(target.getHost())) return null;
        return target.toString();
    }

    private static Uri webUri(String url) {
        if (url == null) return null;
        Uri uri = Uri.parse(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        return uri;
    }

    private static boolean isShim(String host) {
        String h = normalize(host);
        for (String shim : SHIM_HOSTS) if (h.equals(shim)) return true;
        return false;
    }

    private static boolean isInternal(String host) {
        String h = normalize(host);
        for (String domain : INTERNAL_DOMAINS) {
            if (h.equals(domain) || h.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static String normalize(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }
}
