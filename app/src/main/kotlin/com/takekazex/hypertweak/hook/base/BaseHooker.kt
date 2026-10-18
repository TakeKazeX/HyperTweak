package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lingqiqi5211.ezhooktool.core.findAllConstructors
import io.github.lingqiqi5211.ezhooktool.core.query.ConstructorQuery
import io.github.lingqiqi5211.ezhooktool.core.query.FieldQuery
import io.github.lingqiqi5211.ezhooktool.core.query.MethodQuery
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.DexPreparationOwner
import android.os.Handler
import android.os.Looper

sealed class BaseHooker {
    companion object {
        private val preparationMain by lazy { Handler(Looper.getMainLooper()) }
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
    private val dexPreparations = DexPreparationOwner()
    private val trackedInstances = CopyOnWriteArraySet<HookInstances>()

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
                dexPreparations.activate()
                DebugLog.d("BaseHooker", "hooking $hookerName")
                if (!isHooked) onHook()
            } else {
                when (this) {
                    is StaticHooker -> { /* Static hookers survive for the lifetime of the process */ }
                    is DynamicHooker -> {
                        cancelDexPreparations()
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

    /** Optional process-local state carried to the replacement generation. */
    open fun saveHotReloadState(): Any? = null

    /** Restores state captured by [saveHotReloadState] after hooks are attached. */
    open fun restoreHotReloadState(state: Any?) {}

    protected val dexCacheDirectory: java.io.File?
        get() = DexRuntime.cacheDirectory(hookParam.appInfo)

    /** Features provide a query and hook publication; startup, queueing and retirement are shared. */
    protected fun <T : Any> prepareDexTargets(
        name: String = hookerName,
        resolve: () -> T?,
        install: (T) -> Unit,
    ) {
        dexPreparations.prepare(name,
            create = { failure -> DexRuntime.preparation(
                afterStartup = { action -> check(preparationMain.post { action() }) },
                publish = { action -> check(preparationMain.post { action() }) },
                failure = failure,
            ) },
            failure = { DebugLog.hookFailed(hookerName, "prepare targets: $name", it) },
            resolve = resolve,
        ) { target ->
            val before = collectManagedHookHandles().toSet()
            val started = android.os.SystemClock.uptimeMillis()
            try {
                install(target)
                DebugLog.i("DexRuntime", "published hooker=$hookerName name=$name " +
                    "installMs=${android.os.SystemClock.uptimeMillis() - started}")
            } catch (error: Throwable) {
                // Retain earlier independent hooks; roll back only this failed publication.
                collectManagedHookHandles().filterNot { it in before }.forEach { handle ->
                    runCatching { handle.unhook() }.onFailure(error::addSuppressed)
                }
                throw error
            }
        }
    }

    private fun cancelDexPreparations() {
        dexPreparations.cancel()
    }

    internal fun instanceTracker(): HookInstances = HookInstances().also(trackedInstances::add)

    /** Observes constructors only. A feature must still validate instances against its resolved contract. */
    internal fun observeInstances(className: String, tracker: HookInstances) {
        runCatching { classLoader.loadClass(className) }.getOrNull()?.declaredConstructors?.forEach { constructor ->
            constructor.hook {
                after { param ->
                    if (!dexPreparations.isRetired) HookFailurePolicy.open(hookerName, "capture host instance", Unit) {
                        tracker.add(param.thisObject)
                    }
                }
            }
        }
    }

    internal fun performInit() {
        dexPreparations.activate()
        onInit()
    }

    fun prepareForHotReload() {
        cancelDexPreparations()
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
        trackedInstances.forEach(HookInstances::clear)
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }

    fun resetAfterHotReloadPrepared() {
        cancelDexPreparations()
        childHookers.forEach { it.resetAfterHotReloadPrepared() }
        hookHandles.clear()
        childHookers.clear()
        isSelfEnabled = true
        isParentEnabled = false
    }

    /**
     * Retry a hooker that skipped its first [onHook] while the remote preferences bridge was
     * unavailable. Hookers that already own a managed handle are left untouched.
     */
    fun retryHookIfNeeded() {
        if (!dexPreparations.isRetired && isEffectiveEnabled && !isHooked) {
            runCatching {
                DebugLog.d("BaseHooker", "retrying $hookerName after preferences became available")
                onHook()
            }.onFailure { t ->
                DebugLog.w("BaseHooker", "preference retry failed for $hookerName", t)
            }
        }
        childHookers.forEach { it.retryHookIfNeeded() }
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

    /** Settings reload uses the same capture/prepare/restore contract as API-102 replacement.
     * Fresh children avoid reusing retired callbacks, handlers and per-host failure state.
     */
    internal fun replaceChildren(replacements: List<Pair<BaseHooker, BaseHooker>>, recover: () -> Unit = {}) {
        check(replacements.all { it.first in childHookers }) { "replacement must belong to this parent" }
        ManagedHookReload.run(
            replacements,
            reset = { old -> detach(old); old.resetAfterHotReloadPrepared() },
            install = { old, next -> attach(next, old.classLoader, old.hookParam) },
            recover = recover
        )
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
    ): XposedInterface.HookHandle = hookMethod(this, defaultHookId(this), managed, block)

    fun Method.hook(
        hookId: String,
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = hookMethod(this, hookId, managed, block)

    private fun hookMethod(
        method: Method,
        hookId: String,
        managed: Boolean,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val target = formatExecutable(method)
        return try {
            val replacementMap = replacementHandles
            val oldHandle = replacementMap?.firstForTarget(hookId, method)
            val handle = if (oldHandle != null) {
                replaceOldHandle(oldHandle, target, hookId, block).also {
                    replacementMap.markHandled(oldHandle)
                }
            } else method.createHook {
                id(hookId)
                block()
            }
            registerHandle(handle, target, managed)
        } catch (t: Throwable) {
            DebugLog.hookFailed(hookerName, target, t)
            throw t
        }
    }

    /** Registers a runtime-supplied hooker while retaining BaseHooker ownership. */
    fun registerRuntimeHook(
        method: Method,
        hookId: String,
        hooker: XposedInterface.Hooker
    ): XposedInterface.HookHandle {
        return method.hook(hookId) {
            intercept(hooker)
        }
    }

    /**
     * Hook a [Constructor] using EzHookTool's createHook DSL.
     */
    fun Constructor<*>.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle =
        hookConstructor(this, defaultHookId(this), managed, block)

    /**
     * Hook a [Constructor] under an explicit hook id.
     *
     * The default constructor id embeds the declaring class and parameter types, so it changes
     * whenever an obfuscated host build renames them and a hot reload can no longer match the
     * previous handle. Targets resolved from obfuscation-independent markers need an id that
     * survives the host build.
     */
    fun Constructor<*>.hook(
        hookId: String,
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = hookConstructor(this, hookId, managed, block)

    private fun hookConstructor(
        constructor: Constructor<*>,
        hookId: String,
        managed: Boolean,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle {
        val target = formatExecutable(constructor)
        return try {
            val replacementMap = replacementHandles
            val oldHandle = replacementMap?.firstForTarget(hookId, constructor)
            val handle = if (oldHandle != null) {
                replaceOldHandle(oldHandle, target, hookId, block).also {
                    replacementMap.markHandled(oldHandle)
                }
            } else constructor.createHook {
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

    /**
     * Adopts a handle that was replaced before the new hooker generation was attached.
     * Hot-reload coordinators use this to keep carried hooks visible to lifecycle cleanup and
     * diagnostics even when their normal install path intentionally skips an already-replaced id.
     */
    internal fun adoptManagedHookHandle(handle: XposedInterface.HookHandle) {
        ownManagedHandle(handle)
        DebugLog.hookRegistered(hookerName, "carried id=${handle.id}")
    }

    /**
     * Undo AOT inlining of [executable] so hooks on small or private methods actually fire.
     * EzHookTool preserves the framework's Boolean result and exposes exceptions for logging.
     */
    fun deoptimize(executable: Executable): Boolean {
        return runCatching {
            EzXposed.deoptimizeOrThrow(executable)
        }.onFailure { t ->
            DebugLog.w(hookerName, "failed to deoptimize ${formatExecutable(executable)}", t)
        }.getOrDefault(false)
    }

    private fun registerHandle(
        handle: XposedInterface.HookHandle,
        target: String,
        managed: Boolean
    ): XposedInterface.HookHandle {
        DebugLog.hookRegistered(hookerName, "$target id=${handle.id}")
        if (!managed) return handle
        return ownManagedHandle(handle)
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

    /** Re-registering an already owned replacement must not add a second wrapper. */
    internal fun ownManagedHandle(handle: XposedInterface.HookHandle): XposedInterface.HookHandle {
        if (handle is ManagedHookHandle && handle.owner === this) return handle
        return wrapHandle(handle).also(hookHandles::add)
    }

    private fun wrapHandle(original: XposedInterface.HookHandle): XposedInterface.HookHandle =
        ManagedHookHandle(original)

    private inner class ManagedHookHandle(private val original: XposedInterface.HookHandle) : XposedInterface.HookHandle {
        val owner: BaseHooker = this@BaseHooker
        override fun getExecutable(): Executable = original.executable
        override fun getId(): String? = original.getId()
        override fun unhook() {
            original.unhook()
            hookHandles.remove(this)
        }
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
            val replaced = original.replaceHook(hooker)
            hookHandles.remove(this)
            return ownManagedHandle(replaced)
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

        val resolved = DexRuntime.resolveClasses(
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
