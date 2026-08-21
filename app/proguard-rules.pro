# libxposed API 102
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
# The entry class name must survive on its own, not via EzHookTool's stricter
# consumer rule, so drop allowobfuscation while keeping allowoptimization.
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Hooker simple names double as runtime hook IDs and hot-reload state-map keys
# (BaseHooker.hookerName = this::class.java.simpleName). Keep them stable across
# releases so hot-reload state restore and in-place handle replacement still match
# after a module update; -keepnames still allows unused hookers to be shrunk.
-keepnames class * extends com.takekazex.hypertweak.hook.base.BaseHooker

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

# IconTunerFlows resolves kotlinx-coroutines classes in the HOST (SystemUI) class loader
# reflectively by their original names. Release R8 obfuscates the module's bundled
# kotlinx-coroutines and rewrites Class.forName string constants to the renamed names
# (StateFlowKt -> z12), which then throw ClassNotFoundException in the host loader and kill
# every flow-replaced visibility tweak (observed: "before hook failed ... CNFE z12" on all
# MiuiCellularIconVM getters). Keeping the bundled names unrenamed (plus the runtime name
# assembly in IconTunerFlows) keeps the host lookup on the original FQN.
-keep class kotlinx.coroutines.flow.** { *; }
-keep class kotlin.Pair

# Suppress missing class warnings for KavaRef / Java reflect
-dontwarn java.lang.reflect.AnnotatedType
