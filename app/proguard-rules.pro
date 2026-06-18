# libxposed API 102
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Keep MainActivity status checker method
-keep class com.takekazex.hypertweak.MainActivity {
    public boolean isModuleActive();
}

# Suppress missing class warnings for KavaRef / Java reflect
-dontwarn java.lang.reflect.AnnotatedType
