# Framework entry, JNI exports and persisted hook identities.
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.takekazex.hypertweak.hook.NativeRules { native <methods>; }
-keepnames class * extends com.takekazex.hypertweak.hook.base.BaseHooker

# API-102 hot-reload reflection into EzHookTool.
-keepclassmembers class io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory {
    private java.util.List stages;
}
-keep,allowoptimization class io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactoryKt {
    public static io.github.libxposed.api.XposedInterface$Hooker buildHooker(java.lang.reflect.Executable, java.util.List);
}
-keepclassmembers,allowoptimization class com.takekazex.hypertweak.MainActivity {
    public boolean isModuleActive();
}

# Guard only host FQNs overlapping bundled types; unused members may be removed.
# IconTunerFlows also assembles names at runtime to avoid host lookup rewriting.
-keep,allowshrinking,allowoptimization class kotlinx.coroutines.flow.StateFlowKt { *; }
-keep,allowshrinking,allowoptimization class kotlinx.coroutines.flow.ReadonlyStateFlow { *; }
-keep,allowshrinking,allowoptimization class kotlinx.coroutines.flow.StateFlow { *; }
-keep,allowshrinking,allowoptimization class kotlin.Pair

# Replacement for the filtered hook-xposed-102:1.3.1 consumer file. All other
# library helpers use direct calls. Preserve its API-isolation/metadata contract.
-keep class io.github.lingqiqi5211.ezhooktool.xposed.internal.XposedApiCompat$Api102 { *; }
-keep class kotlin.Metadata
-dontwarn java.lang.reflect.AnnotatedType

# Production app_process entry; invoked after the existing user confirmations.
-keep,allowoptimization class com.takekazex.hypertweak.root.NotificationBannerBatch {
    public static void main(java.lang.String[]);
}
