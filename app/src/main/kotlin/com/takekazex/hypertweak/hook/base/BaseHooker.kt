package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.DebugLog
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
    companion object {
        private val hookFactoryConstructor by lazy {
            HookFactory::class.java.getDeclaredConstructor(Executable::class.java).apply {
                isAccessible = true
            }
        }
        private val hookFactoryStagesField by lazy {
            HookFactory::class.java.getDeclaredField("stages").apply {
                isAccessible = true
            }
        }
        private val buildHookerMethod by lazy {
            Class.forName("io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactoryKt")
                .getDeclaredMethod("buildHooker", Executable::class.java, List::class.java)
                .apply { isAccessible = true }
        }
    }

    lateinit var module: XposedModule
    lateinit var classLoader: ClassLoader

    /** Module-level metadata about the current process / package. */
    lateinit var hookParam: ModuleContext

    private val hookHandles = CopyOnWriteArraySet<XposedInterface.HookHandle>()
    private val childHookers = CopyOnWriteArraySet<BaseHooker>()

    private var replacementHandles: HotReloadHandleStore? = null

    val isHooked: Boolean
        get() = hookHandles.isNotEmpty()

    open val hookerName: String
        get() = this::class.java.simpleName

    open val hotReloadMode: HotReloadMode = HotReloadMode.RECREATE

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
                DebugLog.d("BaseHooker", "hooking $hookerName")
                if (!isHooked) onHook()
            } else {
                when (this) {
                    is StaticHooker -> { /* Static hookers survive for the lifetime of the process */ }
                    is DynamicHooker -> {
                        DebugLog.d("BaseHooker", "unhooking $hookerName")
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

    open fun onPrepareHotReload() {}

    internal fun performInit() {
        onInit()
    }

    fun prepareForHotReload() {
        val failures = mutableListOf<Throwable>()
        childHookers.forEach { child ->
            runCatching { child.prepareForHotReload() }
                .onFailure { t ->
                    failures += t
                    DebugLog.w("BaseHooker", "failed to prepare child ${child.hookerName} for hot reload", t)
                }
        }
        runCatching { onPrepareHotReload() }
            .onFailure { t ->
                failures += t
                DebugLog.w("BaseHooker", "failed to prepare $hookerName for hot reload", t)
            }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    fun resetAfterHotReloadPrepared() {
        childHookers.forEach { it.resetAfterHotReloadPrepared() }
        hookHandles.clear()
        childHookers.clear()
        isSelfEnabled = true
        isParentEnabled = false
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
        hooker.replacementHandles = replacementHandles

        childHookers.add(hooker)
        hooker.performInit()
        hooker.updateParentState(isEffectiveEnabled)
    }

    fun setHotReloadReplacementHandles(handles: HotReloadHandleStore?) {
        replacementHandles = handles
        childHookers.forEach { it.setHotReloadReplacementHandles(handles) }
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
        val target = formatExecutable(this)
        val hookId = defaultHookId(this)
        return try {
            val replacementMap = replacementHandles
            val oldHandle = replacementMap?.firstForId(hookId)
            val handle = if (oldHandle != null) {
                replaceOldHandle(oldHandle, target, hookId, block).also {
                    replacementMap.markHandled(oldHandle)
                }
            } else this.createHook {
                id(hookId)
                block()
            }
            registerHandle(handle, target, managed)
        } catch (t: Throwable) {
            DebugLog.hookFailed(hookerName, target, t)
            throw t
        }
    }

    /**
     * Hook a [Constructor] using EzHookTool's createHook DSL.
     */
    fun Constructor<*>.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val target = formatExecutable(this)
        val hookId = defaultHookId(this)
        return try {
            val replacementMap = replacementHandles
            val oldHandle = replacementMap?.firstForId(hookId)
            val handle = if (oldHandle != null) {
                replaceOldHandle(oldHandle, target, hookId, block).also {
                    replacementMap.markHandled(oldHandle)
                }
            } else this.createHook {
                id(hookId)
                block()
            }
            registerHandle(handle, target, managed)
        } catch (t: Throwable) {
            DebugLog.hookFailed(hookerName, target, t)
            throw t
        }
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

    fun collectManagedHookHandles(): List<XposedInterface.HookHandle> {
        return hookHandles.toList() + childHookers.flatMap { it.collectManagedHookHandles() }
    }

    private fun registerHandle(
        handle: XposedInterface.HookHandle,
        target: String,
        managed: Boolean
    ): XposedInterface.HookHandle {
        DebugLog.hookRegistered(hookerName, "$target id=${handle.id}")
        if (!managed) return handle
        val managedHandle = wrapHandle(handle)
        hookHandles.add(managedHandle)
        return managedHandle
    }

    private fun replaceOldHandle(
        oldHandle: XposedInterface.HookHandle,
        target: String,
        hookId: String,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val factory = hookFactoryConstructor.newInstance(oldHandle.executable) as HookFactory
        factory.id(hookId)
        block(factory)
        @Suppress("UNCHECKED_CAST")
        val stages = hookFactoryStagesField.get(factory) as List<Any>
        val hooker = buildHookerMethod.invoke(null, oldHandle.executable, stages) as XposedInterface.Hooker
        val replacement = oldHandle.replaceHook(hooker)
        DebugLog.hookRegistered(hookerName, "$target id=$hookId replaced=true")
        return replacement
    }

    private fun wrapHandle(original: XposedInterface.HookHandle): XposedInterface.HookHandle {
        return object : XposedInterface.HookHandle {
            override fun getExecutable(): Executable = original.executable
            override fun getId(): String? = original.getId()
            override fun unhook() {
                original.unhook()
                hookHandles.remove(this)
            }
            override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                val replaced = original.replaceHook(hooker)
                hookHandles.remove(this)
                val managed = wrapHandle(replaced)
                hookHandles.add(managed)
                return managed
            }
        }
    }

    private fun defaultHookId(executable: Executable): String {
        return "$hookerName:${formatExecutable(executable)}"
    }

    // ─── Reflection helpers (scoped to this hooker's classLoader) ─────────────

    fun String.toClass(initialize: Boolean = false): Class<Any> {
        @Suppress("UNCHECKED_CAST")
        return io.github.lingqiqi5211.ezhooktool.core.loadClass(this, classLoader) as Class<Any>
    }

    fun String.toClassOrNull(initialize: Boolean = false): Class<Any>? {
        @Suppress("UNCHECKED_CAST")
        return io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull(this, classLoader) as? Class<Any>
    }

    fun String.lazyClass(initialize: Boolean = false): Lazy<Class<Any>> = lazy {
        this.toClass(initialize)
    }

    fun String.lazyClassOrNull(initialize: Boolean = false): Lazy<Class<Any>?> = lazy {
        this.toClassOrNull(initialize)
    }

    /** Find a method on a [Class] using EzHookTool's DSL query. */
    fun Class<*>.findMethod(query: MethodQuery.() -> Unit): Method =
        io.github.lingqiqi5211.ezhooktool.core.findMethod(this, query)

    fun Class<*>.findMethodOrNull(query: MethodQuery.() -> Unit): Method? =
        io.github.lingqiqi5211.ezhooktool.core.findMethodOrNull(this, query)

    fun Class<*>.findConstructor(query: ConstructorQuery.() -> Unit): Constructor<*> =
        io.github.lingqiqi5211.ezhooktool.core.findConstructor(this, query)

    fun Class<*>.findConstructorOrNull(query: ConstructorQuery.() -> Unit): Constructor<*>? =
        io.github.lingqiqi5211.ezhooktool.core.findConstructorOrNull(this, query)

    fun Class<*>.findField(query: FieldQuery.() -> Unit) =
        io.github.lingqiqi5211.ezhooktool.core.findField(this, query)

    fun Class<*>.findFieldOrNull(query: FieldQuery.() -> Unit) =
        io.github.lingqiqi5211.ezhooktool.core.findFieldOrNull(this, query)

    /** Hook all declared constructors of a class. */
    fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit) {
        findAllConstructors(this).forEach { ctor ->
            try {
                ctor.hook(block = block)
            } catch (t: Throwable) {
                DebugLog.hookFailed(hookerName, formatExecutable(ctor), t)
            }
        }
    }

    private fun formatExecutable(executable: Executable): String {
        val owner = executable.declaringClass.name
        val name = when (executable) {
            is Constructor<*> -> "<init>"
            is Method -> executable.name
            else -> executable.name
        }
        val params = executable.parameterTypes.joinToString(",") { it.simpleName }
        return "$owner#$name($params)"
    }

    /**
     * Resolve a class via DexKit first, falling back to classLoader lookup.
     * Requires [hookParam.appInfo] to be available for cache/APK path resolution.
     */
    fun resolveAppClass(
        className: String,
        queries: Map<String, (org.luckypray.dexkit.DexKitBridge) -> String?>
    ): Class<*>? {
        val appInfo = hookParam.appInfo ?: return className.toClassOrNull()
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return className.toClassOrNull()
        val cacheDir = java.io.File(baseDir, "cache")
        val apkPath = appInfo.sourceDir ?: return className.toClassOrNull()

        val resolved = DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = queries
        )
        return resolved.values.firstOrNull() ?: className.toClassOrNull()
    }
}

abstract class StaticHooker : BaseHooker()

abstract class DynamicHooker : BaseHooker()

enum class HotReloadMode {
    RECREATE,
    RESTART_RECOMMENDED,
    UNSUPPORTED
}
