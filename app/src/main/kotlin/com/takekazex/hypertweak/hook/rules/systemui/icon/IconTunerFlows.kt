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

    val falseFlow: Any by lazy { createReadonlyStateFlow(false) }
    val zeroFlow: Any by lazy { createReadonlyStateFlow(0) }
    val trueFlow: Any by lazy { createReadonlyStateFlow(true) }

    private fun createReadonlyStateFlow(value: Any): Any {
        val loader = cl()
        val mutable = runCatching {
            val stateFlowKt = Class.forName("kotlinx.coroutines.flow.StateFlowKt", false, loader)
            val mutableStateFlow = stateFlowKt.getMethod("MutableStateFlow", Any::class.java)
            mutableStateFlow.invoke(null, value)
        }.getOrElse { t ->
            DebugLog.e("IconTunerFlows", "failed to create MutableStateFlow", t)
            throw t
        }
        return runCatching {
            val readonly = Class.forName("kotlinx.coroutines.flow.ReadonlyStateFlow", false, loader)
            val ctor = readonly.getConstructor(Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader))
            ctor.newInstance(mutable)
        }.getOrElse {
            DebugLog.d("IconTunerFlows", "ReadonlyStateFlow wrapper unavailable, using MutableStateFlow")
            mutable
        }
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
    fun writeField(target: Any, field: Field, value: Any) {
        if (!field.isAccessible) runCatching { field.isAccessible = true }
        runCatching { field.set(target, value) }.onFailure {
            DebugLog.d("IconTunerFlows", "field.set rejected (${it.javaClass.simpleName}), using Unsafe")
            unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
        }
    }
}
