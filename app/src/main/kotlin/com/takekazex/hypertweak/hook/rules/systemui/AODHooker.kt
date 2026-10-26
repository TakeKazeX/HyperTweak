package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.cachedPublicMethod
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import java.lang.reflect.Executable
import org.luckypray.dexkit.query.enums.StringMatchType
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerFlows
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import io.github.libxposed.api.XposedInterface

/** Unlocks capability; the native full_screen_aod_on and wallpaper policy still own activation. */
object AODHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingAodFullscreen by hookSetting(false) { Preferences.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false) }

    private const val FEATURE = "support_aod_fullscreen"
    private const val CONFIG = "com.miui.utils.configs.MiuiConfigs"
    private const val MANAGER = "com.android.keyguard.fullaod.MiuiFullAodManager"
    private val main = Handler(Looper.getMainLooper())
    private var enabled by generationState { false }
    private var nativeSupport by generationState { false }
    private val capabilities = linkedSetOf<Field>()
    private val managers = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }
    private val activities = generationMainResource(WeakHashMap<Activity, Boolean>()) { it.clear() }
    private var observerContext: Context? by generationState { null }
    private var observer: ContentObserver? by generationState { null }
    private var primaryManager: Any? by generationState { null }
    private var refreshingPolicy by generationState { false }
    private var generation = 0
    private var cacheChangedAtAttach by generationState { false }
    private val dozeCallbacks = generationMainResource(mutableListOf<Array<Any>>()) { it.clear() }
    private var dozeCallbackTypes: Map<String, Class<*>> by generationState { emptyMap() }
    private data class PreparedTargets(val callers: List<Executable>, val callbacks: Map<String, Class<*>>)

    fun isFullscreenSupported(): Boolean = nativeSupport || enabled

    override fun onHook() {
        com.takekazex.hypertweak.hook.rules.aod.prepareAodIconSettingsSettings()
        generation++
        enabled = settingAodFullscreen
        val parser = "miui.util.FeatureParser".toClassOrNull() ?: return
        val get = HostAccess.requireDeclaredMethod(parser, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType, accessible = false)
        // API-102 can attach this generation before the old generation's hook is removed.
        // Read the original implementation, otherwise the old forced true becomes our baseline.
        nativeSupport = module.getInvoker(get).setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(null, FEATURE, false) as? Boolean == true
        deoptimize(get)
        get.hook { after { param ->
            if (enabled && param.args[0] == FEATURE) param.result = true
        } }
        listOf(CONFIG, "com.miui.aod.Utils").forEach { name ->
            val type = name.toClassOrNull() ?: return@forEach
            runCatching {
                val field = HostAccess.requireDeclaredField(type, "SUPPORT_FULL_AOD", accessible = true)
                require(field.type == Boolean::class.javaPrimitiveType)
                capabilities += field
                cacheChangedAtAttach = cacheChangedAtAttach || field.getBoolean(null) != isFullscreenSupported()
                StaticFieldWriter.setBoolean(field, isFullscreenSupported())
                type.declaredMethods.filter { it.name in setOf("isSupportFsAod", "isFullAodSupport", "isCurrentScreenSupportFsAod") }
                    .forEach(::deoptimize)
            }.onFailure { DebugLog.w("AOD", "capability cache unavailable $name", it) }
        }
        // These verified readers may have inlined the static final capability before replacement.
        listOf(
            "com.miui.keyguard.dagger.MiuiKeyguardModule_Companion_ProvidesMiuiFullAodManagerFactory" to "providesMiuiFullAodManager",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl" to "start",
            "com.android.keyguard.panel.KeyguardPanelViewController" to "initDeductedImageScale"
        ).forEach { (name, method) -> name.toClassOrNull()?.declaredMethods?.filter { it.name == method }?.forEach(::deoptimize) }
        MANAGER.toClassOrNull()?.let { type ->
            type.declaredConstructors.forEach { constructor ->
                deoptimize(constructor)
                constructor.hook { after { param ->
                    val owner = param.thisObject
                    val token = generation
                    // Do not re-enter Dagger or notify doze consumers before construction returns.
                    main.post { if (token == generation) {
                        runCatching { rememberManager(owner) }
                            .onFailure { DebugLog.w("AOD", "native manager registration failed", it) }
                    } }
                } }
            }
            type.declaredMethods.forEach(::deoptimize)
            // Keep the native manager dormant when the unlock is off. Its wallpaper callback
            // must not persist "unsupported" over the user's full-AOD settings merely because
            // we prepared a manager instead of the original empty implementation.
            HostAccess.requireDeclaredMethod(type, "updateSettings", accessible = false).hook { before { param ->
                if (!isFullscreenSupported() || refreshingPolicy) param.result = null
            } }
            type.declaredMethods.single { it.name == "addFullAodStateListener" && it.parameterCount == 1 }
                .hook { before { param ->
                    @Suppress("UNCHECKED_CAST")
                    val listeners = HostAccess.read(param.thisObject, "mStateListeners") as? MutableList<Any>
                        ?: return@before
                    val listener = param.args[0] ?: return@before
                    // A disabled capability must not discard subscriptions needed by first enable.
                    if (!listeners.contains(listener)) listeners.add(listener)
                    param.result = null
                } }
            installManagerBridge(type)
        }
        if (hookParam.packageName == "com.miui.aod") {
            HostAccess.requireDeclaredMethod(Activity::class.java, "onResume", accessible = false).hook { after { param ->
                (param.thisObject as? Activity)?.takeIf { it.packageName == "com.miui.aod" }?.let { activities[it] = true }
            } }
        }
    }

    /** A SystemUI-hosted AOD plugin has its own Utils class, invisible to the root loader. */
    fun applyAodPluginCapability(loader: ClassLoader, retiring: Boolean = false) {
        val type = runCatching { loader.loadClass("com.miui.aod.Utils") }.getOrNull() ?: return
        runCatching {
            val field = HostAccess.requireDeclaredField(type, "SUPPORT_FULL_AOD", accessible = true)
            require(field.type == Boolean::class.javaPrimitiveType)
            StaticFieldWriter.setBoolean(field, if (retiring) nativeSupport else isFullscreenSupported())
            type.declaredMethods.filter { it.name == "isSupportFsAod" || it.name == "isFullAodSupport" }
                .forEach(::deoptimize)
        }.onFailure { DebugLog.w("AOD", "plugin capability cache update failed", it) }
    }

    /** A capability update must update native caches and the already constructed manager too. */
    fun refreshSettings() = HostAccess.onMain {
        val next = settingAodFullscreen
        val changed = next != enabled
        enabled = next
        capabilities.forEach { StaticFieldWriter.setBoolean(it, isFullscreenSupported()) }
        recoverManager()
        if (isFullscreenSupported()) prepareDozeCallbacks()
        managers.keys.toList().forEach { applyManager(it, recomputePolicy = changed && isFullscreenSupported()) }
        recoverActivities()
        if (changed || cacheChangedAtAttach) {
            activities.keys.toList().filter { !it.isFinishing && !it.isDestroyed }.forEach(Activity::recreate)
            cacheChangedAtAttach = false
        }
        DebugLog.i("AOD", "capability applied module=$enabled native=$nativeSupport managers=${managers.size}")
    }

    /** Select the real native implementation once, even for a default-off process startup. */
    private fun installManagerBridge(managerType: Class<*>) {
        val factoryName = "com.miui.keyguard.dagger.MiuiKeyguardModule_Companion_ProvidesMiuiFullAodManagerFactory"
        val api = "com.miui.interfaces.keyguard.IMiuiFullAodManager".toClassOrNull() ?: return
        val factory = factoryName.toClassOrNull()?.declaredMethods?.singleOrNull {
            it.name == "providesMiuiFullAodManager" && it.parameterCount == 1 && it.returnType == api
        } ?: return
        val get = factory.parameterTypes[0].cachedPublicMethod("get", accessible = false)
        val delegates = api.declaredMethods.filter {
            !java.lang.reflect.Modifier.isAbstract(it.modifiers) && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.map { it to HostAccess.requirePublicMethod(managerType, it.name, *it.parameterTypes, accessible = false) }
        require(delegates.isNotEmpty())
        factory.hook { before { param ->
            runCatching {
                val owner = get.invoke(param.args[0])
                if (managerType.isInstance(owner)) param.result = owner
            }.onFailure { DebugLog.w("AOD", "native manager selection failed", it) }
        } }
        // Already constructed controllers may still hold the old empty implementation. Redirect
        // its eight native interface defaults; real managers keep their original method bodies.
        delegates.forEach { (method, target) ->
            deoptimize(method)
            if (method.parameterCount == 0 && method.name in setOf(
                    "getDefaultWallpaperBlack", "isFullAodNotificationEnable", "isWallpaperScaleEnable")) {
                val originalDefault = module.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)
                val cache = AtomicReference<Any?>()
                target.hook { before { param ->
                    if (isFullscreenSupported()) return@before
                    // Preparing the real manager must keep the original empty implementation's
                    // query results while dormant. Cache its pure, zero-argument native defaults.
                    runCatching {
                        cache.get() ?: originalDefault.invokeSpecial(param.thisObject).also {
                            cache.compareAndSet(null, it)
                        }
                    }.onSuccess { param.result = it }
                        .onFailure { DebugLog.w("AOD", "dormant native query failed ${method.name}", it) }
                } }
            }
            method.hook { before { param ->
                val receiver = param.thisObject.javaClass
                if (!receiver.name.startsWith("com.miui.keyguard.dagger.") ||
                    receiver.declaredMethods.isNotEmpty() || receiver.declaredFields.isNotEmpty() ||
                    api !in receiver.interfaces) return@before
                if (!isFullscreenSupported() && method.name != "addFullAodStateListener" &&
                    method.name != "removeFullAodStateListener") return@before
                val owner = primaryManager ?: return@before
                runCatching { target.invoke(owner, *param.args) }
                    .onSuccess { param.result = it }
                    .onFailure { DebugLog.w("AOD", "old manager forwarding failed ${method.name}", it) }
            } }
        }
        hookParam.appInfo?.sourceDir?.let { path ->
            prepareDexTargets("AOD native interface callers", resolve = {
                resolveDexContract("systemui.aod_callers", path, compute = { bridge ->
                    val callers = bridge.getClassData(api)?.methods.orEmpty().flatMap { it.callers }
                        .distinctBy { it.descriptor }.mapNotNull { data ->
                            runCatching<Executable> {
                                if (data.isConstructor) data.getConstructorInstance(classLoader)
                                else data.getMethodInstance(classLoader)
                            }.onFailure { DebugLog.w("AOD", "caller unavailable ${data.descriptor}", it) }.getOrNull()
                        }
                    val callbacks = listOf("onOrientationChanged", "onLockTaskModeChanged").associateWith { callback ->
                        val candidates = bridge.findMethod { matcher {
                            declaredClass { className("com.android.keyguard.injector.DozeServiceHostInjector\$", StringMatchType.StartsWith) }
                            name(callback)
                            paramTypes("int")
                            returnType("void")
                        } }.mapNotNull { data ->
                            val type = data.getMethodInstance(classLoader).declaringClass
                            val fields = type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                            type.takeIf { fields.size == 1 && fields.single().type.name == "com.android.keyguard.injector.DozeServiceHostInjector" }
                        }.distinct()
                        require(candidates.size == 1) { "ambiguous native doze callback $callback count=${candidates.size}" }
                        candidates.single()
                    }
                    PreparedTargets(callers, callbacks)
                }, encode = { target -> DexContractMembers.encode("callers" to target.callers) + target.callbacks.mapValues {
                    com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec.typeDescriptor(it.value)
                } }, decode = { members ->
                    val callbacks = listOf("onOrientationChanged", "onLockTaskModeChanged").associateWith { callback ->
                        members.type(callback).also { type ->
                            require(HostAccess.requireDeclaredMethod(type, callback, Int::class.javaPrimitiveType, accessible = false).returnType == Void.TYPE)
                            val fields = type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                            require(fields.size == 1 && fields.single().type.name == "com.android.keyguard.injector.DozeServiceHostInjector")
                        }
                    }
                    PreparedTargets(members.executables("callers"), callbacks)
                })
            }, install = { prepared ->
                prepared.callers.forEach(::deoptimize)
                dozeCallbackTypes = prepared.callbacks
                if (isFullscreenSupported()) prepareDozeCallbacks()
            })
        }
    }

    private fun rememberManager(owner: Any) {
        managers[owner] = true
        primaryManager = owner
        val context = HostAccess.read(owner, "mContext") as? Context ?: return
        if (observer == null) {
            // A manager constructed on an unsupported ROM has no native full-AOD observers.
            // Observe the same settings without writing them or replacing its native policy.
            val next = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    if (observer != null) managers.keys.toList().forEach { applyManager(it) }
                }
            }
            val register = HostAccess.requirePublicMethod(context.contentResolver.javaClass, "registerContentObserver",
                android.net.Uri::class.java, Boolean::class.javaPrimitiveType, ContentObserver::class.java,
                Int::class.javaPrimitiveType, accessible = false)
            observerContext = context
            observer = next
            try {
                listOf("full_screen_aod_on", "full_screen_aod_support").forEach {
                    register.invoke(context.contentResolver, Settings.Secure.getUriFor(it), false, next, -1)
                }
            } catch (error: Throwable) {
                context.contentResolver.unregisterContentObserver(next)
                observer = null
                observerContext = null
                throw error
            }
        }
        applyManager(owner)
    }

    private fun applyManager(owner: Any, recomputePolicy: Boolean = false) {
        runCatching {
            val context = HostAccess.read(owner, "mContext") as? Context ?: return
            val deviceSupport = HostAccess.requireDeclaredField(owner.javaClass, "isDeviceSupport", accessible = true)
            val previousSupport = deviceSupport.getBoolean(owner)
            deviceSupport.setBoolean(owner, isFullscreenSupported())
            if (recomputePolicy) {
                // Re-evaluate the current native wallpaper/clock policy as one transaction;
                // either callback alone can temporarily persist an unsupported result.
                refreshingPolicy = true
                try {
                    HostAccess.invoke(owner, "onWallpaperChanged")
                    HostAccess.invoke(owner, "onLockScreenInfoChanged")
                } finally { refreshingPolicy = false }
                HostAccess.invoke(owner, "updateSettings")
            }
            val user = Class.forName("android.app.ActivityManager").cachedPublicMethod("getCurrentUser", accessible = false).invoke(null) as Int
            val read = Settings.Secure::class.java.cachedPublicMethod("getIntForUser", android.content.ContentResolver::class.java,
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, accessible = false)
            listOf("mAodFullScreenEnable" to "full_screen_aod_on", "isFullAodSupport" to "full_screen_aod_support")
                .forEach { (field, key) -> HostAccess.requireDeclaredField(owner.javaClass, field, accessible = true)
                    .setBoolean(owner, read.invoke(null, context.contentResolver, key, 1, user) == 1) }
            if (previousSupport || isFullscreenSupported()) {
                HostAccess.invoke(owner, "updateFullAodEnable")
            }
        }.onFailure { DebugLog.w("AOD", "native manager refresh failed", it) }
    }

    /** Replay the two native registrations skipped by a capability-off SystemUI startup. */
    private fun prepareDozeCallbacks() {
        if (hookParam.packageName != "com.android.systemui" || dozeCallbackTypes.isEmpty()) return
        runCatching {
            val registry = "com.miui.systemui.interfacesmanager.InterfacesImplManager".toClassOrNull() ?: return
            val api = "com.miui.sysuiinterfaces.IDozeServiceHost".toClassOrNull() ?: return
            val host = HostAccess.requirePublicMethod(registry, "getImpl", Class::class.java, accessible = false).invoke(null, api) ?: return
            val context = HostAccess.read(host, "mContext") as? Context ?: return
            HostAccess.requireDeclaredField(host.javaClass, "mOrientation", accessible = true)
                .setInt(host, context.resources.configuration.orientation)
            val config = HostAccess.read(host, "configurationController") ?: return
            val tasks = HostAccess.read(host, "mTaskStackChangeListeners") ?: return
            val taskImpl = HostAccess.read(tasks, "mImpl") ?: return
            fun register(controller: Any, listeners: Any?, callbackMethod: String, add: String, remove: String) {
                val list = listeners as? Iterable<*> ?: error("native callback collection unavailable $callbackMethod")
                val type = dozeCallbackTypes.getValue(callbackMethod)
                if (list.any(type::isInstance)) return
                val fields = type.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                require(fields.size == 1 && fields.single().type.isInstance(host))
                // The current DEX elides these constructors and directly calls Object.<init>.
                val callback = runCatching { type.getDeclaredConstructor().newInstance() }
                    .getOrElse { IconTunerFlows.allocateInstance(type) }
                fields.single().apply { isAccessible = true }.set(callback, host)
                val method = NativeCallbackRegistration.select(controller.javaClass.methods, add, type)
                    ?: error("native typed callback registration missing or ambiguous: $add")
                val release = NativeCallbackRegistration.select(controller.javaClass.methods, remove, type)
                    ?: error("native typed callback release missing or ambiguous: $remove")
                method.invoke(controller, callback)
                dozeCallbacks += arrayOf(controller, callback, release)
            }
            register(config, HostAccess.read(config, "listeners"), "onOrientationChanged", "addCallback", "removeCallback")
            register(tasks, HostAccess.read(taskImpl, "mTaskStackListeners"), "onLockTaskModeChanged",
                "registerTaskStackListener", "unregisterTaskStackListener")
        }.onFailure { DebugLog.w("AOD", "native doze callback recovery failed", it) }
    }

    private fun recoverActivities() {
        if (hookParam.packageName != "com.miui.aod") return
        runCatching {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requireDeclaredMethod(owner, "currentActivityThread", accessible = false).invoke(null) ?: return
            val records = HostAccess.read(thread, "mActivities") as? Map<*, *> ?: return
            records.values.filterNotNull().forEach { record ->
                (HostAccess.read(record, "activity") as? Activity)
                    ?.takeIf { it.packageName == "com.miui.aod" }?.let { activities[it] = true }
            }
        }.onFailure { DebugLog.w("AOD", "existing settings activity recovery failed", it) }
    }

    private fun recoverManager() {
        if (hookParam.packageName != "com.android.systemui") return
        runCatching {
            val app = hookParam.appContext ?: EzXposed.appContextOrNull ?: return
            val component = HostAccess.read(app, "mSysUIComponent")
                ?: HostAccess.read(app, "mInitializer")?.let { HostAccess.invoke(it, "getSysUIComponent") }
                ?: return
            val owner = HostAccess.provider(component, "miuiFullAodManagerProvider") ?: return
            if (owner.javaClass.name == MANAGER && owner !in managers) rememberManager(owner)
        }.onFailure { DebugLog.w("AOD", "native manager recovery unavailable", it) }
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain {
        // Boot/host objects only. Retirement removes successful releases from this shared list;
        // the replacement inherits exactly the failures, without retaining old module classes.
        arrayOf(managers.keys.toList(), dozeCallbacks)
    }
    override fun restoreHotReloadState(state: Any?) = HostAccess.onMain {
        val saved = state as? Array<*>
        (saved?.getOrNull(1) as? List<*>)?.forEach { item ->
            val record = item as? Array<*> ?: return@forEach
            val controller = record.getOrNull(0) ?: return@forEach
            val callback = record.getOrNull(1) ?: return@forEach
            val release = record.getOrNull(2) as? java.lang.reflect.Method ?: return@forEach
            dozeCallbacks += arrayOf(controller, callback, release)
        }
        releaseDozeCallbacks()
        ((saved?.getOrNull(0) ?: state) as? List<*>)?.filterNotNull()
            ?.filter { it.javaClass.name == MANAGER }?.forEach(::rememberManager)
        Unit
    }
    private fun releaseDozeCallbacks() = NativeCallbackRegistration.release(dozeCallbacks) {
        DebugLog.w("AOD", "native callback release failed; retaining lease for retry", it)
    }
    override fun recoverHotReloadHosts() { refreshSettings() }

    override fun onPrepareHotReload() = HostAccess.onMain {
        generation++
        main.removeCallbacksAndMessages(null)
        releaseDozeCallbacks()
        dozeCallbackTypes = emptyMap()
        observer?.let { observerContext?.contentResolver?.unregisterContentObserver(it) }
        capabilities.forEach { StaticFieldWriter.setBoolean(it, nativeSupport) }
        managers.keys.toList().forEach { applyManager(it) }
        managers.clear()
        capabilities.clear()
        activities.clear()
        cacheChangedAtAttach = false
    }
}
