package io.github.jas0n1ee0315.ftiab;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Remembers the resumed Activity (and the Application) to start the browser from. */
final class ContextTracker {

    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static volatile Application application;

    private ContextTracker() {
    }

    static void install(ModuleMain module) {
        try {
            Method onResume = Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class);
            module.hook(onResume).intercept(chain -> {
                Object activity = chain.getArg(0);
                if (activity instanceof Activity) {
                    resumed = new WeakReference<>((Activity) activity);
                    if (application == null) application = ((Activity) activity).getApplication();
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.logW("activity tracker failed", t);
        }
    }

    static Context current() {
        Activity activity = resumed.get();
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) return activity;
        if (application != null) return application;
        try {
            // Hot reload into a running process: no resume has been seen yet.
            Object app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (app instanceof Application) application = (Application) app;
        } catch (Throwable ignored) {
            // Not reachable; the caller falls back to the in-app browser.
        }
        return application;
    }
}
