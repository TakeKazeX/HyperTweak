package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.util.ignoreFailure
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

sealed class BaseHooker {
    lateinit var module: XposedModule
    lateinit var classLoader: ClassLoader

    /** Module-level metadata about the current process / package. */
    lateinit var hookParam: ModuleContext

    private val hooks = HookRegistrar(this)
    private val dexTargets = HookerDexTargets(this)
    private val subscriptions = CopyOnWriteArraySet<AutoCloseable>()

    internal fun ownSubscription(subscription: AutoCloseable) { subscriptions += subscription }

    private fun closeSubscriptions() {
        val failures = subscriptions.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
        subscriptions.clear()
        if (failures.isNotEmpty()) throw failures.first().apply { failures.drop(1).forEach(::addSuppressed) }
    }

    private val inputs = GenerationInputs()

    /**
     * True from retirement until this hooker is installed again. A retiring generation's hooks and
     * callbacks keep running until the replacement swaps the handles; any path that lazily creates a
     * host registration, thread or worker outside the `generation*` helpers must check this under
     * the same monitor as its `onPrepareHotReload`, or the late resource outlives the generation.
     */
    internal val generationRetired: Boolean get() = inputs.isRetired

    /** Main-looper handler whose pending work retirement drops. Use instead of a bare main Handler. */
    internal fun generationMainHandler(): Handler =
        Handler(Looper.getMainLooper()).also { handler -> inputs.reusable(GenerationInputs.Reusable { handler.removeCallbacksAndMessages(null) }) }

    /** Single-thread worker shut down on retirement and recreated on next use. */
    internal fun generationWorker(name: String, idleSeconds: Long = 30): GenerationWorker =
        inputs.reusable(GenerationWorker(name, idleSeconds))

    /** Coroutine scope cancelled on retirement; later launches use a fresh job. */
    internal fun generationScope(context: CoroutineContext = HookDispatchers.IO): CoroutineScope =
        inputs.reusable(GenerationScope(context))

    /**
     * Registers now and unregisters on retirement or [AutoCloseable.close], at most once. After
     * retirement and before reinstall nothing is registered: a late callback of a retiring
     * generation cannot leave a host registration behind.
     */
    internal fun generationRegistration(register: () -> Unit, unregister: () -> Unit): GenerationInputs.Registration =
        inputs.registration(register, unregister)

    internal fun generationObserver(
        resolver: ContentResolver, uri: Uri, descendants: Boolean = false, handler: Handler? = null,
        onChange: (selfChange: Boolean, uri: Uri?) -> Unit,
    ): AutoCloseable = inputs.observer(resolver, uri, descendants, handler, onChange)

    /**
     * Registers on the published Application ([HostApplicationContext.whenPublished]): an attach-time
     * registration is keyed on a ContextImpl that retirement cannot unregister. A registration still
     * waiting for publication is skipped once closed, and no broadcast is delivered after closing.
     * [flags] must state exportedness (`ContextCompat.RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED`).
     */
    internal fun generationReceiver(
        context: Context, filter: IntentFilter, flags: Int, permission: String? = null, handler: Handler? = null,
        onReceive: (Context, Intent) -> Unit,
    ): AutoCloseable = inputs.receiver(hookerName, context, filter, flags, permission, handler, onReceive)

    private val childHookers = CopyOnWriteArraySet<BaseHooker>()
    private val generationStates = GenerationStateOwner()
    private val hostRecoveries = CopyOnWriteArraySet<HostSurfaceRecovery>()

    /** Declare transient state once; retirement resets it even when custom cleanup fails. */
    protected fun <T> generationState(initial: () -> T): GenerationState<T> =
        generationStates.state(initial)

    /** Required installation metadata is unavailable again after generation retirement. */
    protected fun <T : Any> generationRequiredState(): kotlin.properties.ReadWriteProperty<Any?, T> =
        generationStates.requiredState()

    /** Idempotent resource/retirement fallback, also run when feature-specific restoration throws. */
    protected fun generationCleanup(action: () -> Unit) = generationStates.cleanup(action)

    /** Cache identity and synchronization monitors survive until all host cleanup has finished. */
    protected fun <T : Any> generationResource(value: T, reset: (T) -> Unit): T =
        generationStates.resource(value, reset = reset)

    /** UI-owned collections retire on their owning looper, with no monitor held during dispatch. */
    protected fun <T : Any> generationMainResource(value: T, reset: (T) -> Unit): T =
        generationStates.resource(value, dispatch = { action -> HostAccess.onMain(action) }, reset = reset)

    /** Recovery belongs to the installed owner, so absent/plugin-only hookers cannot participate. */
    protected fun hostRecovery(
        phase: HostRecoveryPhase,
        icons: Boolean = false,
        recover: (HostScope) -> Unit
    ) {
        hostRecoveries += object : HostSurfaceRecovery {
            override val phase = phase
            override val icons = icons
            override fun recover(scope: HostScope) = recover.invoke(scope)
        }
    }

    internal fun recoverHostSurfaces(scope: HostScope, iconsOnly: Boolean) {
        hostRecoveries.filter { it.phase == scope.phase && (!iconsOnly || it.icons) }.forEach { recovery ->
            // Slot mutation must escape to the native transaction so its rollback can run.
            if (scope.phase == HostRecoveryPhase.SLOT_ORDER) recovery.recover(scope)
            else runCatching { recovery.recover(scope) }.onFailure {
                ignoreFailure { DebugLog.w("HostRecovery", "$hookerName phase=${scope.phase}", it) }
            }
        }
        childHookers.forEach { it.recoverHostSurfaces(scope, iconsOnly) }
    }

    private val trackedInstances = CopyOnWriteArraySet<HookInstances>()

    open val isHooked: Boolean
        get() = hooks.hasHandles || subscriptions.isNotEmpty()

    open val hookerName: String
        get() = this::class.java.simpleName

    /** How the user-facing switch decides whether this hooker is installed at all. */
    open val installPolicy: InstallPolicy = InstallPolicy.ALWAYS

    val isMainProcess: Boolean
        get() = hookParam.isMainProcess

    private var isParentEnabled: Boolean = false
    private var enabled: Boolean = false

    private val isEffectiveEnabled: Boolean
        get() = enabled

    /**
     * Whether this hooker's own user-facing switch currently allows installation.
     *
     * Only consulted when [installPolicy] is [InstallPolicy.WHEN_ENABLED]. Hookers that own a
     * display-only feature return their preference here so a switch left off costs neither a scan
     * nor an installed hook, and prepares targets only when enabled.
     */
    open fun isFeatureEnabled(): Boolean = true

    /** When the switch is off, should this hooker be absent rather than installed-and-inert? */
    private val installsWhenEnabledOnly: Boolean
        get() = installPolicy == InstallPolicy.WHEN_ENABLED

    private fun shouldInstall(): Boolean = !installsWhenEnabledOnly || isFeatureEnabled()

    /**
     * Re-reads [isFeatureEnabled] and installs or removes accordingly.
     *
     * This is the entry point a settings-reload participant calls when the user flips a switch.
     * A cold cache is prepared by the feature through its managed background task.
     */
    @Synchronized
    fun refreshFeatureState() {
        if (installPolicy == InstallPolicy.ALWAYS) return
        val oldState = isEffectiveEnabled
        enabled = isParentEnabled && shouldInstall()
        handleStateChange(oldState)
    }

    @Synchronized
    fun updateParentState(parentEnabled: Boolean) {
        val oldState = isEffectiveEnabled
        isParentEnabled = parentEnabled
        enabled = parentEnabled && shouldInstall()
        handleStateChange(oldState)
    }

    private fun handleStateChange(oldState: Boolean) {
        val newState = isEffectiveEnabled
        if (oldState != newState) {
            if (newState) {
                activateGeneration()
                installIfNeeded()
            } else {
                // A switch-gated hooker must restore the host's own behavior, so it is removed even
                // when it is static: "static" only means "survives a preference hiccup", not
                // "survives the user turning the feature off".
                if (this is DynamicHooker || installsWhenEnabledOnly) {
                    cancelDexPreparations()
                    DebugLog.d("BaseHooker", "unhooking $hookerName")
                    val failures = hooks.unhookAll()
                    // Successful managed unhooks remove themselves; retain failed handles for retirement.
                    runCatching { closeSubscriptions() }.exceptionOrNull()?.let { error ->
                        DebugLog.w("BaseHooker", "subscription cleanup failed for $hookerName", error)
                    }
                    failures.forEach { DebugLog.w("BaseHooker", "unhook failed for $hookerName", it) }
                }
            }
            childHookers.forEach { it.updateParentState(newState) }
        }
    }

    /**
     * Installs this hooker when it is enabled, wanted, and not already installed.
     *
     * Presence is derived from [isEffectiveEnabled], [shouldInstall] and [isHooked] on every call
     * rather than remembered by an independent self-enable flag.
     */
    private fun installIfNeeded() {
        if (!isEffectiveEnabled || isHooked) return
        if (!shouldInstall()) {
            DebugLog.d("BaseHooker", "deferring $hookerName until its switch is enabled")
            return
        }
        DebugLog.d("BaseHooker", "hooking $hookerName")
        val problemsBefore = DebugLog.reportedProblemCount()
        val registrationsBefore = hooks.registrations
        onHook()
        auditInstallation(problemsBefore, registrationsBefore)
    }

    /**
     * An enabled feature that leaves no hook, child, subscription or pending DEX preparation behind
     * is not working. Unless the hooker already reported why, that outcome must never be silent.
     */
    private fun auditInstallation(problemsBefore: Int, registrationsBefore: Int) {
        if (hooks.registrations != registrationsBefore || hooks.hasHandles || subscriptions.isNotEmpty() ||
            childHookers.isNotEmpty() || dexTargets.hasPreparations || !expectsHooks
        ) return
        if (DebugLog.reportedProblemCount() != problemsBefore) return
        DebugLog.hookSkipped(hookerName, hookerName, "enabled but installed nothing and reported no reason")
    }

    /** False for hookers whose onHook legitimately performs one-shot work without hooks. */
    protected open val expectsHooks: Boolean get() = true

    /**
     * Records that this hooker's installation was handed to a later host boundary (for example the
     * camera config provider). The audit then waits for that boundary instead of reporting silence.
     */
    internal fun noteDeferredInstallation() = hooks.noteDeferredInstallation()

    open fun onInit() {}

    open fun onHook() {}

    open fun onPrepareHotReload() {}

    /** Optional process-local state carried to the replacement generation. */
    open fun saveHotReloadState(): Any? = null

    /** Restores state captured by [saveHotReloadState] after hooks are attached. */
    open fun restoreHotReloadState(state: Any?) {}

    /** Recover living hosts even when the previous APK had no state for this feature. */
    open fun recoverHotReloadHosts() {}

    /** Process-local recovery also reaches plugin children, including children without old state. */
    internal fun recoverGenerationHosts() {
        val failures = mutableListOf<Throwable>()
        runCatching { recoverHotReloadHosts() }.onFailure(failures::add)
        childHookers.forEach { child ->
            runCatching { child.recoverGenerationHosts() }.onFailure(failures::add)
        }
        if (failures.isNotEmpty()) throw failures.first().apply {
            failures.drop(1).forEach(::addSuppressed)
        }
    }

    protected val dexCacheDirectory: java.io.File?
        get() = dexTargets.cacheDirectory

    /** Final feature contracts: compute must be pure resolution; encode materializes all lazy data. */
    internal fun <T : Any> resolveDexContract(
        id: String,
        apk: String,
        schema: Int = 1,
        compute: (org.luckypray.dexkit.DexKitBridge) -> T?,
        encode: (T) -> Map<String, String>,
        decode: (com.takekazex.hypertweak.hook.base.dex.DexContractMembers) -> T?,
        cacheOnly: Boolean = false,
    ): T? = dexTargets.resolveContract(id, apk, schema, compute, encode, decode, cacheOnly)

    /**
     * Install-thread entry for a final feature contract. A cached contract installs synchronously,
     * exactly as before. A miss never scans here: install callbacks run on the host main thread at
     * process start (and on a binder thread during hot reload), where a cold whole-APK query cost
     * up to seconds. The scan is queued through [prepareDexTargets] instead and [install] is
     * published on the main thread once it finishes; the host keeps its native behavior until then.
     */
    internal fun <T : Any> prepareDexContract(
        id: String,
        apk: String,
        schema: Int = 1,
        compute: (org.luckypray.dexkit.DexKitBridge) -> T?,
        encode: (T) -> Map<String, String>,
        decode: (com.takekazex.hypertweak.hook.base.dex.DexContractMembers) -> T?,
        install: (T) -> Unit,
    ) = dexTargets.prepareContract(id, apk, schema, compute, encode, decode, install)

    /** Roll back only handles installed by this publication, including synchronous cache replay. */
    internal fun <T> installDexContract(install: () -> T): T = dexTargets.installContract(install)

    /**
     * Like [prepareDexContract] for a resolver made of several cached lookups: it is replayed from
     * the cache on the install thread, and any miss defers the whole [resolve] to the worker, so a
     * warm start installs exactly as before and a cold one never scans on main or binder threads.
     */
    protected fun <T : Any> prepareCachedDexTargets(
        name: String = hookerName,
        resolve: () -> T?,
        install: (T) -> Unit,
    ) = dexTargets.prepareCached(name, resolve, install)

    /** Features provide a query and hook publication; startup, queueing and retirement are shared. */
    protected fun <T : Any> prepareDexTargets(
        name: String = hookerName,
        resolve: () -> T?,
        install: (T) -> Unit,
    ) = dexTargets.prepare(name, resolve, install, low = false)

    /**
     * For a feature whose hooks only matter later (for example inside a game): its cold scan
     * queues behind the process's other preparations instead of delaying them.
     */
    protected fun <T : Any> prepareLowPriorityDexTargets(
        name: String = hookerName,
        resolve: () -> T?,
        install: (T) -> Unit,
    ) = dexTargets.prepare(name, resolve, install, low = true)

    private fun activateGeneration() {
        dexTargets.activate()
        inputs.activate()
    }

    private fun cancelDexPreparations() {
        dexTargets.cancel()
    }

    internal fun instanceTracker(): HookInstances = HookInstances().also(trackedInstances::add)

    /** Observes constructors only. A feature must still validate instances against its resolved contract. */
    internal fun observeInstances(className: String, tracker: HookInstances) {
        runCatching { classLoader.loadClass(className) }.getOrNull()?.declaredConstructors?.forEach { constructor ->
            constructor.hook {
                after { param ->
                    if (!dexTargets.isRetired) HookFailurePolicy.open(hookerName, "capture host instance", Unit) {
                        tracker.add(param.thisObject)
                    }
                }
            }
        }
    }

    internal fun performInit() {
        activateGeneration()
        onInit()
    }

    fun prepareForHotReload() {
        cancelDexPreparations()
        val failures = mutableListOf<Throwable>()
        childHookers.forEach { child ->
            runCatching { child.prepareForHotReload() }
                .onFailure { t ->
                    failures += t
                    ignoreFailure { DebugLog.w("BaseHooker", "failed to prepare child ${child.hookerName} for hot reload", t) }
                }
        }
        // Inputs stop before the feature restores its host.
        runCatching { inputs.releaseAll() }.onFailure(failures::add)
        runCatching { onPrepareHotReload() }
            .onFailure { t ->
                failures += t
                ignoreFailure { DebugLog.w("BaseHooker", "failed to prepare $hookerName for hot reload", t) }
            }
        runCatching { closeSubscriptions() }.onFailure(failures::add)
        runCatching { generationStates.reset() }.onFailure(failures::add)
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
        hooks.clear()
        childHookers.clear()
        enabled = false
        isParentEnabled = false
    }

    /**
     * Retry a hooker that skipped its first [onHook] while the remote preferences bridge was
     * unavailable. Hookers that already own a managed handle are left untouched.
     */
    fun retryHookIfNeeded() {
        if (!dexTargets.isRetired && isEffectiveEnabled && !isHooked) {
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
        hooker.hooks.replacementHandles = hooks.replacementHandles

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
        hooks.replacementHandles = handles
        childHookers.forEach { it.setHotReloadReplacementHandles(handles) }
    }

    fun detach(hooker: BaseHooker) {
        if (childHookers.remove(hooker)) {
            hooker.updateParentState(false)
        }
    }

    // ─── Hook registration helpers ─────────────────────────────────────────────

    /**
     * Hook a [Method] through the module hook DSL ([HookFactory]).
     * Registers the handle for lifecycle management.
     */
    fun Method.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = hooks.hook(this, null, managed, block)

    fun Method.hook(
        hookId: String,
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = hooks.hook(this, hookId, managed, block)

    /** Registers a runtime-supplied hooker while retaining BaseHooker ownership. */
    fun registerRuntimeHook(
        method: java.lang.reflect.Executable,
        hookId: String,
        hooker: XposedInterface.Hooker
    ): XposedInterface.HookHandle {
        return when (method) {
            is Method -> method.hook(hookId) { intercept(hooker) }
            is Constructor<*> -> method.hook(hookId) { intercept(hooker) }
            else -> error("Unsupported hook executable: $method")
        }
    }

    /**
     * Hook a [Constructor] through the module hook DSL ([HookFactory]).
     */
    fun Constructor<*>.hook(
        managed: Boolean = true,
        block: HookFactory.() -> Unit
    ): XposedInterface.HookHandle = hooks.hook(this, null, managed, block)

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
    ): XposedInterface.HookHandle = hooks.hook(this, hookId, managed, block)

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
        return hooks.ownedHandles + childHookers.flatMap { it.collectManagedHookHandles() }
    }

    /** See [HookRegistrar.adopt]. */
    internal fun adoptManagedHookHandle(handle: XposedInterface.HookHandle) = hooks.adopt(handle)

    /**
     * Undo AOT inlining of [executable] so hooks on small or private methods actually fire.
     * The framework's Boolean result is preserved; a thrown failure is logged and reads as false.
     */
    fun deoptimize(executable: Executable): Boolean = hooks.deoptimize(executable)

    /** Re-registering an already owned replacement must not add a second wrapper. */
    internal fun ownManagedHandle(handle: XposedInterface.HookHandle): XposedInterface.HookHandle = hooks.own(handle)

    // ─── Reflection helpers (scoped to this hooker's classLoader) ─────────────

    fun String.toClass(initialize: Boolean = false): Class<Any> {
        @Suppress("UNCHECKED_CAST")
        return loadHostClass(this, classLoader) as Class<Any>
    }

    fun String.toClassOrNull(initialize: Boolean = false): Class<Any>? {
        @Suppress("UNCHECKED_CAST")
        return loadHostClassOrNull(this, classLoader) as? Class<Any>
    }

    fun String.lazyClass(initialize: Boolean = false): Lazy<Class<Any>> = lazy {
        this.toClass(initialize)
    }

    fun String.lazyClassOrNull(initialize: Boolean = false): Lazy<Class<Any>?> = lazy {
        this.toClassOrNull(initialize)
    }

    /** Hook all declared constructors of a class. */
    fun Class<*>.hookAllConstructors(block: HookFactory.() -> Unit) {
        declaredConstructorsAccessible().forEach { ctor ->
            try {
                ctor.hook(block = block)
            } catch (t: Throwable) {
                DebugLog.hookFailed(hookerName, formatExecutable(ctor), t)
            }
        }
    }

    private fun formatExecutable(executable: Executable): String = HookRegistrar.formatExecutable(executable)

    /**
     * Resolve an unobfuscated host class by its name, using the DexKit [queries] only when the name
     * does not load. Every caller's query matches that same class name inside the same package, so a
     * scan can only find a class the direct lookup already finds; running it first cost up to 1.1 s
     * of host main-thread time on a cold cache (SystemUI fingerprint views, Settings headers).
     * Requires [hookParam.appInfo] to be available for cache/APK path resolution.
     */
    fun resolveAppClass(
        className: String,
        queries: Map<String, (org.luckypray.dexkit.DexKitBridge) -> String?>
    ): Class<*>? = dexTargets.resolveAppClass(className.toClassOrNull(), queries)
}

abstract class StaticHooker : BaseHooker()

abstract class DynamicHooker : BaseHooker()

/**
 * Whether a hooker is installed at all, or only while its switch is on.
 *
 * [ALWAYS] keeps the historical behavior: the hook is installed for the process generation and
 * its callback decides what to do. [WHEN_ENABLED] skips installation entirely while the switch is
 * off, which avoids resolving targets for an unused feature.
 */
enum class InstallPolicy {
    ALWAYS,
    WHEN_ENABLED,
}
