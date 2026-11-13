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

# These names describe interfaces loaded from SystemUI's ClassLoader. Their
# module-local copies must retain their ABI names too: R8 otherwise rewrites
# external reflection strings (for example StateFlow -> an obfuscated name).
# Keep only the shared reflection boundary; unused classes/members may shrink.
-keepnames class androidx.compose.runtime.CompositionContext { *; }
-keepnames class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keepnames class androidx.compose.ui.platform.ComposeView { *; }
-keepnames class androidx.compose.ui.platform.WindowRecomposer_androidKt { *; }
-keepnames class androidx.lifecycle.Lifecycle$State { *; }
-keepnames class androidx.lifecycle.LifecycleOwner { *; }
-keepnames class androidx.lifecycle.LifecycleOwnerKt { *; }
-keepnames class androidx.lifecycle.RepeatOnLifecycleKt { *; }
-keepnames class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keepnames class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }
-keepnames class kotlin.LazyKt__LazyJVMKt { *; }
-keepnames class kotlin.Unit { *; }
-keepnames class kotlin.coroutines.Continuation { *; }
-keepnames class kotlin.coroutines.CoroutineContext { *; }
-keepnames class kotlin.coroutines.CoroutineContext$Key { *; }
-keepnames class kotlin.coroutines.intrinsics.CoroutineSingletons { *; }
-keepnames class kotlin.jvm.functions.Function0 { *; }
-keepnames class kotlin.jvm.functions.Function1 { *; }
-keepnames class kotlin.jvm.functions.Function2 { *; }
-keepnames class kotlin.jvm.functions.Function3 { *; }
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keepnames class kotlinx.coroutines.BuildersKt { *; }
-keepnames class kotlinx.coroutines.CoroutineDispatcher { *; }
-keepnames class kotlinx.coroutines.CoroutineScope { *; }
-keepnames class kotlinx.coroutines.CoroutineStart { *; }
-keepnames class kotlinx.coroutines.DisposableHandle { *; }
-keepnames class kotlinx.coroutines.Job { *; }
-keepnames class kotlinx.coroutines.Job$Key { *; }
-keepnames class kotlinx.coroutines.SupervisorKt { *; }
-keepnames class kotlinx.coroutines.flow.Flow { *; }
-keepnames class kotlinx.coroutines.flow.StateFlow { *; }
