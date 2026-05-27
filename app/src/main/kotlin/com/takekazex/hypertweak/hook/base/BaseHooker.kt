package com.takekazex.hypertweak.hook.base

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet

sealed class BaseHooker {
    lateinit var module: XposedModule
    lateinit var classLoader: ClassLoader

    /** Module-level metadata about the current process / package. */
    lateinit var hookParam: ModuleContext

    private val hookHandles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
    private val childHookers = CopyOnWriteArraySet<BaseHooker>()

    val isHooked: Boolean
        get() = hookHandles.isNotEmpty()

    open val hookerName: String
        get() = this::class.java.simpleName

    val isMainProcess: Boolean
        get() = hookParam.isMainProcess

    private var isSelfEnabled: Boolean = true
    private var isParentEnabled: Boolean = false

    private val isEffectiveEnabled: Boolean
        get() = isSelfEnabled && isParentEnabled

    @Synchronized
    fun updateSelfState(enabled: Boolean) {
        val oldState = isEffectiveEnabled
        isSelfEnabled = enabled
        handleStateChange(oldState)
    }

    @Synchronized
    fun updateParentState(parentEnabled: Boolean) {
        val oldState = isEffectiveEnabled
        isParentEnabled = parentEnabled
        handleStateChange(oldState)
    }

    private fun handleStateChange(oldState: Boolean) {
        val newState = isEffectiveEnabled
        if (oldState != newState) {
            if (newState) {
                Log.d("HyperTweak", "Hooking $hookerName")
                if (!isHooked) onHook()
            } else {
                when (this) {
                    is StaticHooker -> { /* Static hookers survive for the lifetime of the process */ }
                    is DynamicHooker -> {
                        Log.d("HyperTweak", "Unhooking $hookerName")
                        hookHandles.forEach { it.unhook() }
                        hookHandles.clear()
                    }
                }
            }
            childHookers.forEach { it.updateParentState(newState) }
        }
    }

    open fun onInit() {}

    open fun onHook() {}

    internal fun performInit() {
        onInit()
    }

    fun attach(
        hooker: BaseHooker,
        customClassLoader: ClassLoader? = null,
        param: ModuleContext? = null
    ) {
        if (childHookers.contains(hooker)) return

        hooker.module = this.module
        hooker.classLoader = customClassLoader ?: this.classLoader
        hooker.hookParam = param ?: this.hookParam

        childHookers.add(hooker)
        hooker.performInit()
        hooker.updateParentState(isEffectiveEnabled)
    }

    fun detach(hooker: BaseHooker) {
        if (childHookers.remove(hooker)) {
            hooker.updateParentState(false)
        }
    }

    // ─── Hook registration helpers ─────────────────────────────────────────────

    /**
     * Hook a [Method] using EzHookTool's createHook DSL.
     * Registers the handle for lifecycle management.
     */
    fun Method.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val handle = this.createHook(block = block)
        if (!managed) return handle
        val managed = wrapHandle(handle)
        hookHandles.add(managed)
        return managed
    }

    /**
     * Hook a [Constructor] using EzHookTool's createHook DSL.
     */
    fun Constructor<*>.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val handle = this.createHook(block = block)
        if (!managed) return handle
        val managed = wrapHandle(handle)
        hookHandles.add(managed)
        return managed
    }

    /**
     * Hook any [Executable] (dispatches to Method or Constructor variant).
     */
    fun Executable.hookExecutable(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = when (this) {
        is Method -> this.hook(managed, block)
        is Constructor<*> -> this.hook(managed, block)
        else -> throw IllegalArgumentException("Unsupported executable type: $this")
    }

    /** Batch-hook a list of Executables. */
    fun Iterable<Executable?>.hookAll(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ) = mapNotNull { it?.hookExecutable(managed, block) }

    private fun wrapHandle(original: XposedInterface.HookHandle): XposedInterface.HookHandle {
        return object : XposedInterface.HookHandle {
            override fun getExecutable(): Executable = original.executable
            override fun unhook() {
                original.unhook()
                hookHandles.remove(this)
            }
        }
    }

    // ─── Reflection helpers (scoped to this hooker's classLoader) ─────────────

    fun String.toClass(initialize: Boolean = false): Class<Any> {
        @Suppress("UNCHECKED_CAST")
        return io.github.lingqiqi5211.ezhooktool.core.findClass(this, classLoader) as Class<Any>
    }

    fun String.toClassOrNull(initialize: Boolean = false): Class<Any>? {
        @Suppress("UNCHECKED_CAST")
        return io.github.lingqiqi5211.ezhooktool.core.findClassOrNull(this, classLoader) as? Class<Any>
    }

    fun String.lazyClass(initialize: Boolean = false): Lazy<Class<Any>> = lazy {
        this.toClass(initialize)
    }

    fun String.lazyClassOrNull(initialize: Boolean = false): Lazy<Class<Any>?> = lazy {
        this.toClassOrNull(initialize)
    }

    /** Find a method on a [Class] using EzHookTool's DSL query. */
    fun Class<*>.findMethod(findSuper: Boolean? = null, query: MethodQuery.() -> Unit): Method =
        io.github.lingqiqi5211.ezhooktool.core.findMethod(this, findSuper, query)

    fun Class<*>.findMethodOrNull(findSuper: Boolean? = null, query: MethodQuery.() -> Unit): Method? =
        io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull(this, findSuper, query)

    fun Class<*>.findConstructor(query: ConstructorQuery.() -> Unit): Constructor<*> =
        io.github.lingqiqi5211.ezhooktool.core.findConstructor(this, query)

    fun Class<*>.findConstructorOrNull(query: ConstructorQuery.() -> Unit): Constructor<*>? =
        io.github.lingqiqi5211.ezhooktool.core.findConstructorOrNull(this, query)

    fun Class<*>.findField(findSuper: Boolean? = null, query: FieldQuery.() -> Unit) =
        io.github.lingqiqi5211.ezhooktool.core.findField(this, findSuper, query)

    fun Class<*>.findFieldOrNull(findSuper: Boolean? = null, query: FieldQuery.() -> Unit) =
        io.github.lingqiqi5211.ezhooktool.core.findFieldOrNull(this, findSuper, query)

    /** Hook all declared constructors of a class. */
    fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit) {
        findAllConstructors(this).forEach { ctor ->
            try { ctor.hook(block = block) } catch (t: Throwable) { /* ignore */ }
        }
    }
}

abstract class StaticHooker : BaseHooker()

abstract class DynamicHooker : BaseHooker()
