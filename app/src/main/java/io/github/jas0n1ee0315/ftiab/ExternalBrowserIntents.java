package io.github.jas0n1ee0315.ftiab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the external-browser intent that replaces an in-app browser launch. */
final class ExternalBrowserIntents {

    private ExternalBrowserIntents() {
    }

    /** @return the intent to start instead, or {@code null} to leave {@code original} alone. */
    static Intent replacement(ModuleMain module, Context context, Intent original) {
        if (original == null) return null;
        ComponentName component = original.getComponent();
        if (component == null || !LinkPolicy.isInAppBrowser(component.getPackageName(), component.getClassName())) {
            return null;
        }

        String raw = original.getDataString();
        if (raw == null) raw = urlFromExtras(original.getExtras());
        String url = LinkPolicy.destination(raw);
        if (url == null) {
            module.logI("keeping in-app browser for " + component.getShortClassName() + " url=" + raw);
            return null;
        }

        Intent external = browserIntent(context, url);
        if (external == null) {
            module.logI("no external browser installed, keeping in-app browser");
            return null;
        }
        module.logI("opening in external browser: " + url);
        return external;
    }

    /** Fallback for builds that pass the URL as an extra instead of the intent data. */
    private static String urlFromExtras(Bundle extras) {
        if (extras == null) return null;
        try {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof String && key.toLowerCase().contains("url")) {
                    String s = (String) value;
                    if (s.startsWith("http://") || s.startsWith("https://")) return s;
                }
            }
        } catch (Throwable ignored) {
            // Unparcelling app-private Parcelables can fail; nothing to read then.
        }
        return null;
    }

    static Intent browserIntent(Context context, String url) {
        PackageManager pm = context.getPackageManager();
        // Probe with a generic URL so a verified app link for the real host
        // (or Threads itself) is not mistaken for the browser.
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);

        Map<String, ResolveInfo> browsers = new LinkedHashMap<>();
        List<ResolveInfo> matches = pm.queryIntentActivities(probe,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER);
        for (ResolveInfo info : matches) {
            String pkg = info.activityInfo.packageName;
            if (ModuleMain.THREADS_PACKAGE.equals(pkg)) continue;
            // A real browser's web filter accepts every host and path.
            if (info.filter != null && (info.filter.countDataAuthorities() > 0 || info.filter.countDataPaths() > 0)) {
                continue;
            }
            browsers.putIfAbsent(pkg, info);
        }
        if (browsers.isEmpty()) return null;

        ResolveInfo preferred = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        ResolveInfo chosen = preferred != null ? browsers.get(preferred.activityInfo.packageName) : null;
        if (chosen == null && browsers.size() == 1) chosen = browsers.values().iterator().next();

        Intent result;
        if (chosen != null) {
            result = view(url, chosen);
        } else {
            // No default browser set: let the user pick.
            List<Intent> views = new ArrayList<>();
            for (ResolveInfo info : browsers.values()) views.add(view(url, info));
            result = Intent.createChooser(views.remove(0), null)
                    .putExtra(Intent.EXTRA_INITIAL_INTENTS, views.toArray(new Intent[0]));
        }
        // A normal, separate browser task without Threads' browser extras or task flags.
        return result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Intent view(String url, ResolveInfo browser) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setComponent(new ComponentName(browser.activityInfo.packageName, browser.activityInfo.name));
    }
}
