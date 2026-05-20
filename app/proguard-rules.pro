# Keep the Xposed entry class
-keep class com.takekazex.hypertweak.hook.HookEntry { *; }
-keep class * extends io.github.libxposed.api.XposedModule { *; }

# Keep MainActivity status checker method
-keep class com.takekazex.hypertweak.MainActivity {
    public boolean isModuleActive();
}

# Suppress missing class warnings for KavaRef / Java reflect
-dontwarn java.lang.reflect.AnnotatedType
