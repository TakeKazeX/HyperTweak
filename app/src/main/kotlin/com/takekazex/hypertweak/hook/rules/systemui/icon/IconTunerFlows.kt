package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Shared immutable StateFlow instances injected into SystemUI ViewModels to force icon
 * visibility, mirroring Hyper Helper's icon tuner: it replaces the ViewModel's
 * `ReadonlyStateFlow<Boolean>`/`ReadonlyStateFlow<Int>` fields with a shared `false`/`0` flow
 * right after construction, so the pipeline renders the hidden state.
 *
 * **Critical**: the flows must be created with the host (SystemUI) class loader. The module
 * bundles its own kotlinx-coroutines; creating a flow with the module loader and injecting it
 * into SystemUI produces `IncompatibleClassChangeError` when SystemUI collects it (its
 * coroutine classes are a different type identity). [init] must run before any flow is first
 * touched, so every hooker calls it at the top of `onHook()`.
 *
 * `ReadonlyStateFlow` falls back to the raw `MutableStateFlow` instance if the wrapper
 * constructor is unavailable; the pipeline only reads the `StateFlow` interface, and
 * [writeField] bypasses the reflective type check via Unsafe.
 */
object IconTunerFlows {
    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** Bind to the hooked process's class loader; call from every hooker's onHook(). */
    fun init(hostClassLoader: ClassLoader) {
        this.hostClassLoader = hostClassLoader
    }

    private fun cl(): ClassLoader = hostClassLoader
        ?: IconTunerFlows::class.java.classLoader
        ?: ClassLoader.getSystemClassLoader()

    /**
     * Host class names assembled at runtime. R8 rewrites `Class.forName` string constants that
     * match renamed bundled-class names: in release builds the module's bundled
     * kotlinx-coroutines is obfuscated (`kotlinx.coroutines.flow.StateFlowKt` → `z12`), so a
     * literal would look the module-private renamed name up in the HOST (SystemUI) loader and
     * throw `ClassNotFoundException` at the first flow access — observed on-device (release APK)
     * as `[Hook] E: before hook failed ... ClassNotFoundException: z12` on every
     * `MiuiCellularIconVM` getter, silently killing all flow-replaced visibility tweaks
     * (debug builds without R8 worked). Splitting the name into fragments that are not class
     * names keeps R8 from rewriting them; the host keeps the original kotlinx names (verified
     * in the OS4 0.19 SystemUI dex), so the host-loader lookup succeeds.
     */
    private fun hostClassName(pkg: String, simple: String): String =
        StringBuilder(pkg.length + 1 + simple.length)
            .append(pkg)
            .append('.')
            .append(simple)
            .toString()

    val falseFlow: Any by lazy { createReadonlyStateFlow(false) }
    val zeroFlow: Any by lazy { createReadonlyStateFlow(0) }
    val trueFlow: Any by lazy { createReadonlyStateFlow(true) }

    /** Creates a `ReadonlyStateFlow` (host loader) seeded with [value]. */
    fun createReadonlyStateFlow(value: Any): Any {
        val loader = cl()
        val mutable = runCatching {
            val stateFlowKt = Class.forName(
                hostClassName("kotlinx.coroutines.flow", "StateFlowKt"), false, loader
            )
            val mutableStateFlow = stateFlowKt.getMethod("MutableStateFlow", Any::class.java)
            mutableStateFlow.invoke(null, value)
        }.getOrElse { t ->
            DebugLog.e("IconTunerFlows", "failed to create MutableStateFlow", t)
            throw t
        }
        return runCatching {
            val readonly = Class.forName(
                hostClassName("kotlinx.coroutines.flow", "ReadonlyStateFlow"), false, loader
            )
            val ctor = readonly.getConstructor(
                Class.forName(
                    hostClassName("kotlinx.coroutines.flow", "StateFlow"), false, loader
                )
            )
            ctor.newInstance(mutable)
        }.getOrElse {
            DebugLog.d("IconTunerFlows", "ReadonlyStateFlow wrapper unavailable, using MutableStateFlow")
            mutable
        }
    }

    /** Creates a `kotlin.Pair` (host loader), used by SystemUI flows typed as `StateFlow<Pair<...>>`. */
    fun createPair(first: Any, second: Any): Any? = runCatching {
        Class.forName(hostClassName("kotlin", "Pair"), false, cl())
            .getConstructor(Any::class.java, Any::class.java)
            .newInstance(first, second)
    }.getOrNull()

    /** Creates a raw `MutableStateFlow` (host loader) seeded with [value], so callers can push
     *  updates through [setFlowValue]. The pipeline only reads the `StateFlow` interface. */
    fun createMutableStateFlow(value: Any): Any? = runCatching {
        val stateFlowKt = Class.forName(
            hostClassName("kotlinx.coroutines.flow", "StateFlowKt"), false, cl()
        )
        stateFlowKt.getMethod("MutableStateFlow", Any::class.java).invoke(null, value)
    }.getOrNull()

    /** Unwraps the `MutableStateFlow` behind a `ReadonlyStateFlow` (`$$delegate_0`). */
    fun mutableOfReadonly(flow: Any): Any? = runCatching {
        flow.javaClass.getDeclaredField("\$\$delegate_0").apply { isAccessible = true }.get(flow)
    }.getOrNull()

    /** Calls `MutableStateFlow.setValue(value)` reflectively. */
    fun setFlowValue(mutable: Any, value: Any) {
        runCatching {
            mutable.javaClass.getMethod("setValue", Any::class.java).invoke(mutable, value)
        }.onFailure { t ->
            DebugLog.w("IconTunerFlows", "setValue failed (${t.javaClass.simpleName})", t)
        }
    }

    /** Reads the current value of a `StateFlow` via `getValue()`, unwrapping `Pair` firsts. */
    fun readFlowValue(flow: Any): Any? = runCatching {
        flow.javaClass.getMethod("getValue").invoke(flow)
    }.getOrNull()

    /** Boolean from a flow value, tolerating `Boolean` or `Pair(Boolean, *)` shapes. */
    fun readFlowBoolean(value: Any?): Boolean {
        if (value == null) return true
        if (value is Boolean) return value
        return runCatching {
            val first = value.javaClass.getMethod("getFirst").invoke(value)
            first as? Boolean ?: true
        }.getOrDefault(true)
    }

    private val unsafe: sun.misc.Unsafe by lazy {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null) as sun.misc.Unsafe
    }

    /**
     * Writes [field] on [target], falling back to Unsafe when the reflective type check rejects
     * the value (ART refuses to write a `ReadonlyStateFlow`-typed field with a `StateFlowImpl`).
     */
    @Suppress("DEPRECATION") // sun.misc.Unsafe is the only host API on ART; intentional.
    fun writeField(target: Any, field: Field, value: Any) {
        if (!field.isAccessible) runCatching { field.isAccessible = true }
        runCatching { field.set(target, value) }.onFailure {
            DebugLog.d("IconTunerFlows", "field.set rejected (${it.javaClass.simpleName}), using Unsafe")
            unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
        }
    }
}
