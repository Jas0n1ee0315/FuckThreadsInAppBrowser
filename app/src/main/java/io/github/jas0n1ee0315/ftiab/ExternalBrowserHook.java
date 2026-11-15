package io.github.jas0n1ee0315.ftiab;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Hooks every {@code Instrumentation.execStartActivit*} overload (startActivity,
 * startActivityForResult, startActivities, from Activity / Fragment /
 * application contexts) and swaps an intent aimed at the Threads in-app
 * browser for a plain VIEW intent to the device's default browser.
 */
final class ExternalBrowserHook {

    private ExternalBrowserHook() {
    }

    static void install(ModuleMain module) {
        int count = 0;
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("execStartActivit")) continue;
            if (Modifier.isAbstract(method.getModifiers())) continue;
            if (!takesIntent(method)) continue;
            try {
                module.hook(method).intercept(chain -> intercept(module, chain));
                count++;
            } catch (Throwable t) {
                module.logW("hook failed: " + method, t);
            }
        }
        module.logI("armed on " + count + " Instrumentation launch overload(s)");
    }

    private static boolean takesIntent(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (type == Intent.class || type == Intent[].class) return true;
        }
        return false;
    }

    private static Object intercept(ModuleMain module, XposedInterface.Chain chain) throws Throwable {
        Object[] args;
        boolean changed = false;
        try {
            List<Object> list = chain.getArgs();
            args = list.toArray();
            Context context = null;
            for (Object arg : args) {
                if (arg instanceof Context) {
                    context = (Context) arg;
                    break;
                }
            }
            if (context == null) return chain.proceed();

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Intent) {
                    Intent replacement = ExternalBrowserIntents.replacement(module, context, (Intent) arg);
                    if (replacement != null) {
                        args[i] = replacement;
                        changed = true;
                    }
                } else if (arg instanceof Intent[]) {
                    Intent[] intents = ((Intent[]) arg).clone();
                    for (int j = 0; j < intents.length; j++) {
                        Intent replacement = ExternalBrowserIntents.replacement(module, context, intents[j]);
                        if (replacement != null) {
                            intents[j] = replacement;
                            changed = true;
                        }
                    }
                    args[i] = intents;
                }
            }
        } catch (Throwable t) {
            // Never break the app's own navigation because of this module.
            module.logW("could not redirect link, falling back to in-app browser", t);
            return chain.proceed();
        }
        return changed ? chain.proceed(args) : chain.proceed();
    }
}
