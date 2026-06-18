# libxposed API 102
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# BaseHooker rebuilds EzHookTool hookers for API 102 replaceHook during hot reload.
-keepclassmembers class io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory {
    private java.util.List stages;
}
-keep class io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactoryKt {
    public static io.github.libxposed.api.XposedInterface$Hooker buildHooker(java.lang.reflect.Executable, java.util.List);
}

# Keep MainActivity status checker method
-keep class com.takekazex.hypertweak.MainActivity {
    public boolean isModuleActive();
}

# Suppress missing class warnings for KavaRef / Java reflect
-dontwarn java.lang.reflect.AnnotatedType
