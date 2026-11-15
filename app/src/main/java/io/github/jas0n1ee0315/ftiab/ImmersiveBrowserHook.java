package io.github.jas0n1ee0315.ftiab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

/**
 * Current Threads builds open links in an "immersive" in-app browser: a Compose
 * navigation destination inside the main Activity rather than a new Activity.
 * A link tap calls a small static helper that builds the route
 * {@code immersive_browser/{encodedUrl}?postId=...} and navigates to it.
 *
 * <p>That helper is found by its route string with DexKit (its obfuscated name
 * changes every release) and replaced: outbound links are sent to the default
 * browser and the navigation is skipped. The lookup result is cached per
 * Threads APK, so DexKit only runs after Threads updates.
 */
final class ImmersiveBrowserHook {

    private static final String ROUTE_PREFIX = "immersive_browser/";
    private static final String CACHE_FILE = "ftiab_route_method.txt";

    private ImmersiveBrowserHook() {
    }

    static void install(ModuleMain module, ClassLoader loader, ApplicationInfo appInfo) {
        Method target;
        try {
            target = resolve(module, loader, appInfo);
        } catch (Throwable t) {
            module.logW("immersive browser lookup failed", t);
            return;
        }
        if (target == null) {
            module.logI("immersive browser route helper not found; only Activity launches are redirected");
            return;
        }
        final int urlIndex = firstStringParam(target);
        if (urlIndex < 0) {
            module.logI("route helper has no String parameter: " + target);
            return;
        }
        module.hook(target).intercept(chain -> {
            try {
                Object raw = chain.getArg(urlIndex);
                String url = LinkPolicy.destination(raw instanceof String ? (String) raw : null);
                if (url == null) {
                    module.logI("keeping immersive browser for " + raw);
                    return chain.proceed();
                }
                Context context = ContextTracker.current();
                if (context == null) {
                    module.logI("no context yet, keeping immersive browser");
                    return chain.proceed();
                }
                Intent external = ExternalBrowserIntents.browserIntent(context, url);
                if (external == null) {
                    module.logI("no external browser installed, keeping immersive browser");
                    return chain.proceed();
                }
                context.startActivity(external);
                module.logI("opened in external browser: " + url);
                return null; // void: skip the in-app navigation
            } catch (Throwable t) {
                module.logW("redirect failed, keeping immersive browser", t);
                return chain.proceed();
            }
        });
        module.logI("immersive browser route hooked: " + target);
    }

    private static int firstStringParam(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) if (types[i] == String.class) return i;
        return -1;
    }

    private static Method resolve(ModuleMain module, ClassLoader loader, ApplicationInfo appInfo) throws Throwable {
        File apk = new File(appInfo.sourceDir);
        String key = apk.getAbsolutePath() + "|" + apk.length() + "|" + apk.lastModified();
        File cache = new File(appInfo.dataDir, "cache/" + CACHE_FILE);

        String[] cached = readCache(cache);
        if (cached != null && cached.length == 3 && cached[0].equals(key)) {
            Method m = findDeclared(loader, cached[1], cached[2]);
            if (m != null) return m;
        }

        long start = System.currentTimeMillis();
        System.loadLibrary("dexkit");
        Method found = null;
        try (DexKitBridge bridge = DexKitBridge.create(loader, true)) {
            MethodDataList results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(ROUTE_PREFIX, "?postId=")
                            .returnType("void")));
            for (MethodData data : results) {
                // The route template "immersive_browser/{encodedUrl}?postId=..." lives in a
                // constructor that also matches the substring search; only plain methods count.
                if (!data.isMethod()) continue;
                Method m = data.getMethodInstance(loader);
                if (Modifier.isStatic(m.getModifiers()) && firstStringParam(m) >= 0) {
                    found = m;
                    break;
                }
            }
        }
        module.logI("DexKit lookup took " + (System.currentTimeMillis() - start) + " ms -> " + found);
        if (found != null) {
            writeCache(cache, key + "\n" + found.getDeclaringClass().getName() + "\n" + found);
        }
        return found;
    }

    private static Method findDeclared(ClassLoader loader, String className, String signature) {
        try {
            for (Method m : Class.forName(className, false, loader).getDeclaredMethods()) {
                if (m.toString().equals(signature)) return m;
            }
        } catch (Throwable ignored) {
            // Stale cache: fall through to a fresh lookup.
        }
        return null;
    }

    private static String[] readCache(File file) {
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 8192)];
            int n = in.read(bytes);
            return new String(bytes, 0, Math.max(n, 0), StandardCharsets.UTF_8).split("\n");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(File file, String content) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // Cache is only an optimisation.
        }
    }
}
