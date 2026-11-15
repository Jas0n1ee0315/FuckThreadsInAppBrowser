# Module entry class (needs a public no-arg constructor; listed in java_init.list).
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(); }
-adaptresourcefilecontents META-INF/xposed/java_init.list

# DexKit is called from native code.
-keep class org.luckypray.dexkit.** { *; }
