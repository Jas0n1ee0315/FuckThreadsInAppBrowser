package io.github.jas0n1ee0315.ftiab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Current Threads builds open links in an "immersive" in-app browser: a Compose
 * navigation destination inside the main Activity rather than a new Activity.
 *
 * <p>Two route architectures exist across Threads releases, both handled here:
 * <ul>
 *   <li>Typed route objects (Threads 458+): a route class implementing a shared
 *       navigation route interface builds {@code immersive_browser/{encodedUrl}?postId=...}
 *       in a zero-arg {@code String} method, and a navigation dispatcher invokes that
 *       method on the interface to navigate. We hook the dispatcher(s) and intercept
 *       instances of the immersive browser route class.</li>
 *   <li>Direct route helper (older builds): a static {@code void} helper builds
 *       {@code immersive_browser/{encodedUrl}?postId=...} and navigates directly.
 *       We hook that helper and inspect its first {@code String} parameter.</li>
 * </ul>
 *
 * <p>The DexKit lookup result is cached per Threads APK, so scanning only runs
 * after Threads updates.
 */
final class ImmersiveBrowserHook {

    private static final String ROUTE_PREFIX = "immersive_browser/";
    private static final String CACHE_FILE = "ftiab_route_method.txt";
    private static final String CACHE_VERSION = "v2";

    private ImmersiveBrowserHook() {
    }

    private static final class Target {
        /** Direct mode (older builds): static void helper taking the raw URL. */
        final Method directMethod;
        /** Typed-route mode (458+): immersive browser route class & its route-string builder. */
        final Class<?> routeClass;
        final Method routeMethod;
        final Class<?> dispatchType;
        final List<Method> navMethods;

        Target(Method directMethod) {
            this.directMethod = directMethod;
            this.routeClass = null;
            this.routeMethod = null;
            this.dispatchType = null;
            this.navMethods = null;
        }

        Target(Class<?> routeClass, Method routeMethod, Class<?> dispatchType, List<Method> navMethods) {
            this.directMethod = null;
            this.routeClass = routeClass;
            this.routeMethod = routeMethod;
            this.dispatchType = dispatchType;
            this.navMethods = navMethods;
        }

        boolean isDirect() {
            return directMethod != null;
        }
    }

    static void install(ModuleMain module, ClassLoader loader, ApplicationInfo appInfo) {
        Target target;
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
        if (target.isDirect()) {
            hookDirect(module, target.directMethod);
        } else {
            hookTypedRoute(module, target);
        }
    }

    private static void hookDirect(ModuleMain module, Method target) {
        final int urlIndex = firstStringParam(target);
        if (urlIndex < 0) {
            module.logI("route helper has no String parameter: " + target);
            return;
        }
        module.hook(target).intercept(chain -> {
            try {
                Object raw = chain.getArg(urlIndex);
                if (openInExternalBrowser(module, raw instanceof String ? (String) raw : null)) {
                    return null; // void: skip the in-app navigation
                }
                return chain.proceed();
            } catch (Throwable t) {
                module.logW("redirect failed, keeping immersive browser", t);
                return chain.proceed();
            }
        });
        module.logI("immersive browser route hooked: " + target);
    }

    private static void hookTypedRoute(ModuleMain module, Target target) {
        target.routeMethod.setAccessible(true);
        int hooked = 0;
        for (Method nav : target.navMethods) {
            final int routeIndex = findParamOfType(nav, target.dispatchType);
            if (routeIndex < 0) continue;
            try {
                module.hook(nav).intercept(chain -> {
                    try {
                        Object routeObj = chain.getArg(routeIndex);
                        if (!target.routeClass.isInstance(routeObj)) {
                            return chain.proceed();
                        }
                        Object built = target.routeMethod.invoke(routeObj);
                        String rawUrl = extractUrlFromRoute(built instanceof String ? (String) built : null);
                        if (openInExternalBrowser(module, rawUrl)) {
                            return null; // void: skip the in-app navigation
                        }
                        return chain.proceed();
                    } catch (Throwable t) {
                        module.logW("redirect failed, keeping immersive browser", t);
                        return chain.proceed();
                    }
                });
                hooked++;
                module.logI("immersive browser dispatcher hooked: " + nav + " (route=" + target.routeClass.getName() + ")");
            } catch (Throwable t) {
                module.logW("hook failed: " + nav, t);
            }
        }
        if (hooked == 0) {
            module.logI("immersive browser dispatchers could not be hooked; only Activity launches are redirected");
        }
    }

    private static boolean openInExternalBrowser(ModuleMain module, String rawUrl) {
        String url = LinkPolicy.destination(rawUrl);
        if (url == null) {
            module.logI("keeping immersive browser for " + rawUrl);
            return false;
        }
        Context context = ContextTracker.current();
        if (context == null) {
            module.logI("no context yet, keeping immersive browser");
            return false;
        }
        Intent external = ExternalBrowserIntents.browserIntent(context, url);
        if (external == null) {
            module.logI("no external browser installed, keeping immersive browser");
            return false;
        }
        context.startActivity(external);
        module.logI("opened in external browser: " + url);
        return true;
    }

    private static String extractUrlFromRoute(String route) {
        if (route == null || !route.startsWith(ROUTE_PREFIX)) return null;
        int start = ROUTE_PREFIX.length();
        int end = route.indexOf('?', start);
        String encoded = end >= 0 ? route.substring(start, end) : route.substring(start);
        if (encoded.isEmpty()) return null;
        return Uri.decode(encoded);
    }

    private static int firstStringParam(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) if (types[i] == String.class) return i;
        return -1;
    }

    private static int findParamOfType(Method method, Class<?> type) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type || types[i].isAssignableFrom(type)) return i;
        }
        return -1;
    }

    private static Class<?> findDeclaringInterface(Class<?> cls, Method impl) {
        for (Class<?> iface : cls.getInterfaces()) {
            try {
                Method m = iface.getMethod(impl.getName(), impl.getParameterTypes());
                if (m.getReturnType() == impl.getReturnType()) return iface;
            } catch (NoSuchMethodException ignored) {
                // Keep checking other interfaces.
            }
        }
        return null;
    }

    private static Target resolve(ModuleMain module, ClassLoader loader, ApplicationInfo appInfo) throws Throwable {
        File apk = new File(appInfo.sourceDir);
        String key = apk.getAbsolutePath() + "|" + apk.length() + "|" + apk.lastModified();
        File cache = new File(appInfo.dataDir, "cache/" + CACHE_FILE);

        Target cached = loadCache(loader, cache, key);
        if (cached != null) return cached;

        long start = System.currentTimeMillis();
        System.loadLibrary("dexkit");
        Target found = null;
        try (DexKitBridge bridge = DexKitBridge.create(loader, true)) {
            MethodDataList results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(ROUTE_PREFIX, "?postId=")));
            Method directCandidate = null;
            Method routeBuilder = null;
            for (MethodData data : results) {
                // The route template "immersive_browser/{encodedUrl}?postId=..." lives in a
                // constructor that also matches the substring search; only plain methods count.
                if (!data.isMethod()) continue;
                Method m = data.getMethodInstance(loader);
                if (m.getReturnType() == void.class
                        && Modifier.isStatic(m.getModifiers())
                        && firstStringParam(m) >= 0) {
                    directCandidate = m;
                    break;
                }
                if (routeBuilder == null
                        && m.getReturnType() == String.class
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0) {
                    routeBuilder = m;
                }
            }
            if (directCandidate != null) {
                found = new Target(directCandidate);
            } else if (routeBuilder != null) {
                Class<?> routeClass = routeBuilder.getDeclaringClass();
                Class<?> dispatchType = findDeclaringInterface(routeClass, routeBuilder);
                if (dispatchType == null) dispatchType = routeClass;

                MethodDataList navResults = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .addInvoke(MethodMatcher.create()
                                        .declaredClass(dispatchType)
                                        .name(routeBuilder.getName())
                                        .returnType("java.lang.String")
                                        .paramCount(0))));
                List<Method> navMethods = new ArrayList<>();
                for (MethodData data : navResults) {
                    if (!data.isMethod()) continue;
                    Method nav = data.getMethodInstance(loader);
                    if (findParamOfType(nav, dispatchType) >= 0) {
                        navMethods.add(nav);
                    }
                }
                if (!navMethods.isEmpty()) {
                    found = new Target(routeClass, routeBuilder, dispatchType, navMethods);
                }
            }
        }
        module.logI("DexKit lookup took " + (System.currentTimeMillis() - start) + " ms -> "
                + (found == null ? "null" : (found.isDirect() ? found.directMethod : found.navMethods)));
        if (found != null) {
            saveCache(cache, key, found);
        }
        return found;
    }

    private static Target loadCache(ClassLoader loader, File file, String key) {
        String[] lines = readCache(file);
        if (lines == null || lines.length < 3) return null;
        if (!lines[0].equals(CACHE_VERSION + "|" + key)) return null;
        if ("direct".equals(lines[1])) {
            Method m = parseMethodLine(loader, lines[2]);
            return m != null ? new Target(m) : null;
        }
        if ("route".equals(lines[1]) && lines.length >= 5) {
            Method routeMethod = parseMethodLine(loader, lines[2]);
            if (routeMethod == null) return null;
            Class<?> dispatchType = findClass(loader, lines[3]);
            if (dispatchType == null) return null;
            List<Method> navs = new ArrayList<>();
            for (int i = 4; i < lines.length; i++) {
                if (lines[i].isEmpty()) continue;
                Method nav = parseMethodLine(loader, lines[i]);
                if (nav == null) return null;
                navs.add(nav);
            }
            if (navs.isEmpty()) return null;
            return new Target(routeMethod.getDeclaringClass(), routeMethod, dispatchType, navs);
        }
        return null;
    }

    private static void saveCache(File file, String key, Target target) {
        StringBuilder sb = new StringBuilder();
        sb.append(CACHE_VERSION).append('|').append(key).append('\n');
        if (target.isDirect()) {
            sb.append("direct\n");
            sb.append(target.directMethod.getDeclaringClass().getName()).append('|').append(target.directMethod).append('\n');
        } else {
            sb.append("route\n");
            sb.append(target.routeClass.getName()).append('|').append(target.routeMethod).append('\n');
            sb.append(target.dispatchType.getName()).append('\n');
            for (Method nav : target.navMethods) {
                sb.append(nav.getDeclaringClass().getName()).append('|').append(nav).append('\n');
            }
        }
        writeCache(file, sb.toString());
    }

    private static Method parseMethodLine(ClassLoader loader, String line) {
        int sep = line.indexOf('|');
        if (sep <= 0 || sep + 1 >= line.length()) return null;
        return findDeclared(loader, line.substring(0, sep), line.substring(sep + 1));
    }

    private static Class<?> findClass(ClassLoader loader, String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
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
