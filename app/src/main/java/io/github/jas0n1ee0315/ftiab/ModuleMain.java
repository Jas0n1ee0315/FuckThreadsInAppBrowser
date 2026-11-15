package io.github.jas0n1ee0315.ftiab;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 entry point (listed in META-INF/xposed/java_init.list).
 *
 * <p>Threads opens links in two ways, both redirected to the default browser:
 * <ul>
 *   <li>the "immersive" in-app browser, a Compose destination inside the main
 *       Activity ({@link ImmersiveBrowserHook});</li>
 *   <li>the classic BrowserLite Activity ({@link ExternalBrowserHook}).</li>
 * </ul>
 */
public final class ModuleMain extends XposedModule {

    static final String TAG = "FuckThreadsIAB";
    static final String THREADS_PACKAGE = "com.instagram.barcelona";

    private volatile ClassLoader classLoader;
    private volatile ApplicationInfo appInfo;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!THREADS_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        install(param.getClassLoader(), param.getApplicationInfo());
    }

    private synchronized void install(ClassLoader loader, ApplicationInfo info) {
        if (classLoader != null || loader == null || info == null) return;
        classLoader = loader;
        appInfo = info;
        ContextTracker.install(this);
        ExternalBrowserHook.install(this);
        ImmersiveBrowserHook.install(this, loader, info);
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // Both objects belong to the framework/Threads, not to this module's classloader.
        param.setSavedInstanceState(new Object[]{classLoader, appInfo});
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) handle.unhook();
        Object saved = param.getSavedInstanceState();
        if (saved instanceof Object[] && ((Object[]) saved).length == 2) {
            Object[] state = (Object[]) saved;
            if (state[0] instanceof ClassLoader && state[1] instanceof ApplicationInfo) {
                install((ClassLoader) state[0], (ApplicationInfo) state[1]);
            }
        }
        logI("hot reloaded");
    }

    void logI(String msg) {
        log(Log.INFO, TAG, msg);
    }

    void logW(String msg, Throwable t) {
        log(Log.WARN, TAG, msg, t);
    }
}
