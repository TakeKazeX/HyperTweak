package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.provider.Settings
import android.util.SparseArray
import android.util.SparseIntArray
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executor

object ImmediateMonetRefreshHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingImmediateMonetRefresh by hookSetting(SystemUiSettingsKeys.DEFAULT_IMMEDIATE_MONET_REFRESH) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH, SystemUiSettingsKeys.DEFAULT_IMMEDIATE_MONET_REFRESH)
    }

    private const val SCOPE = "ImmediateMonetRefresh"
    private const val CONTROLLER_CLASS = "com.android.systemui.theme.ThemeOverlayController"
    private const val MIUI_HELPER_CLASS = "com.miui.keyguard.utils.ThemeOverlayControllerHelper"
    private const val MIUI_WALLPAPER_MANAGER_CLASS =
        "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager"
    private const val CONTROLLER_DELEGATE_CLASS =
        "com.miui.systemui.theme.ThemeOverlayControllerDelegate"
    private const val INTERFACES_MANAGER_CLASS =
        "com.miui.systemui.interfacesmanager.InterfacesImplManager"
    private const val FASHION_GALLERY_PACKAGE = "com.miui.android.fashiongallery"

    private data class DeferredStore(
        val colorsField: Field,
        val flagsField: Field,
        val source: String
    )

    private var controllerClass: Class<*>? by generationState { null }
    private var controllerReference: WeakReference<Any>? by generationState { null }
    private var listenerControllerField: Field? by generationState { null }
    private var handleWallpaperColorsMethod: Method? by generationState { null }
    private val currentColorsField: Field?
        get() = controllerClass?.let { declaredField(it, "mCurrentColors") }
    private val contextField: Field?
        get() = controllerClass?.let { declaredField(it, "mContext") }
    private val wallpaperManagerField: Field?
        get() = controllerClass?.let { declaredField(it, "mWallpaperManager") }
    private val mainExecutorField: Field?
        get() = controllerClass?.let { declaredField(it, "mMainExecutor") }
    private val userTrackerField: Field?
        get() = controllerClass?.let { declaredField(it, "mUserTracker") }
    private var controllerStore: DeferredStore? by generationState { null }
    private var miuiStore: DeferredStore? by generationState { null }

    private var delegateClass: Class<*>? by generationState { null }
    private var themeOverlayCallback: Method? by generationState { null }
    private var miuiWallpaperCallback: Method? by generationState { null }

    override fun saveHotReloadState(): Any? = controllerReference?.get()

    override fun restoreHotReloadState(state: Any?) {
        val type = controllerClass
        if (state != null && type?.isInstance(state) == true) {
            controllerReference = WeakReference(state)
        }
    }

    override fun onPrepareHotReload() {
        clearResolvedMembers()
    }

    override fun onHook() {
        val controllerType = CONTROLLER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(SCOPE, CONTROLLER_CLASS, "class not found")
            return
        }
        controllerClass = controllerType
        val callbacks = hookParam.appInfo?.sourceDir?.let(::resolveGeneratedCallbacks)
        themeOverlayCallback = callbacks?.themeOverlay
        miuiWallpaperCallback = callbacks?.miuiWallpaper
        handleWallpaperColorsMethod = controllerType.declaredMethods.firstOrNull {
            it.name == "handleWallpaperColors" && it.parameterTypes.contentEquals(
                arrayOf(
                    WallpaperColors::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            )
        }?.apply { isAccessible = true }
        controllerStore = deferredStore(controllerType, source = "system")
        miuiStore = MIUI_HELPER_CLASS.toClassOrNull()?.let {
            deferredStore(it, source = "miui")
        }
        resolveControllerFallbackMembers()

        if (handleWallpaperColorsMethod == null || currentColorsField == null) {
            DebugLog.hookSkipped(SCOPE, CONTROLLER_CLASS, "required members not found")
            clearResolvedMembers()
            return
        }

        controllerType.declaredMethods.firstOrNull {
            it.name == "start" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.hook {
            after { param -> rememberController(param.thisObject) }
        }

        var colorHookCount = 0
        if (hookThemeOverlayListener(controllerType)) colorHookCount++
        if (hookMiuiWallpaperCallback()) colorHookCount++
        if (colorHookCount == 0) {
            DebugLog.hookSkipped(SCOPE, CONTROLLER_CLASS, "no wallpaper color callback found")
            clearResolvedMembers()
        }
    }

    private fun hookThemeOverlayListener(controllerType: Class<*>): Boolean {
        val callback = themeOverlayCallback ?: return false
        listenerControllerField = uniqueFieldByType(callback.declaringClass, controllerType)

        callback.hook {
            after { param ->
                if (!isEnabled()) return@after
                HookFailurePolicy.open(SCOPE, "syncThemeWallpaperColors", Unit) {
                    val colors = param.args.getOrNull(0) as? WallpaperColors ?: return@open
                    val which = param.args.getOrNull(1) as? Int ?: return@open
                    val userId = param.args.getOrNull(2) as? Int ?: return@open
                    val controller = listenerControllerField?.get(param.thisObject)
                        ?.also(::rememberController)
                        ?: resolveController()
                        ?: error("ThemeOverlayController is unavailable")

                    if (isFashionGallerySource(controller, which)) return@open

                    val handleMethod = handleWallpaperColorsMethod
                        ?: error("handleWallpaperColors is unavailable")
                    val flushed = controllerStore?.let { store ->
                        flushIfCurrent(store, controller, controller, handleMethod, colors, which, userId)
                    } == true || miuiStore?.let { store ->
                        flushIfCurrent(store, null, controller, handleMethod, colors, which, userId)
                    } == true

                    val forwarded = forwardIfChanged(
                        controller = controller,
                        colors = colors,
                        which = which,
                        userId = userId,
                        source = "theme"
                    )
                    if (flushed && !forwarded) {
                        DebugLog.i(SCOPE, "applied deferred wallpaper colors user=$userId which=$which")
                    }
                }
            }
        }
        return true
    }

    private fun hookMiuiWallpaperCallback(): Boolean {
        val callback = miuiWallpaperCallback ?: return false

        callback.hook {
            after { param ->
                if (!isEnabled()) return@after
                HookFailurePolicy.open(SCOPE, "queueMiuiWallpaperColors", Unit) {
                    val colors = param.args.getOrNull(0) as? WallpaperColors ?: return@open
                    val wallpaperType = param.args.getOrNull(1) as? String ?: "unknown"
                    val which = (param.args.getOrNull(2) as? Int)?.and(WALLPAPER_FLAGS) ?: 0
                    if (which == 0) return@open

                    val controller = resolveController()
                        ?: error("ThemeOverlayController is unavailable")
                    val executor = mainExecutorField?.get(controller) as? Executor
                        ?: error("ThemeOverlayController main executor is unavailable")
                    executor.execute {
                        if (!isEnabled()) return@execute
                        HookFailurePolicy.open(SCOPE, "syncMiuiWallpaperColors", Unit) {
                            val currentController = resolveController() ?: controller
                            val userId = currentUserId(currentController)
                                ?: error("current SystemUI user is unavailable")
                            forwardIfChanged(
                                controller = currentController,
                                colors = colors,
                                which = which,
                                userId = userId,
                                source = "miui:$wallpaperType"
                            )
                        }
                    }
                }
            }
        }
        return true
    }

    private data class GeneratedCallbacks(
        val themeOverlay: Method?,
        val miuiWallpaper: Method?
    )

    private fun resolveGeneratedCallbacks(apkPath: String): GeneratedCallbacks? {
        val methods = DexRuntime.resolveMethodMap(
            "systemui.monet_callbacks", apkPath, dexCacheDirectory, classLoader, hookParam.appInfo,
            validate = { key, method ->
                val owner = when (key) { "theme" -> CONTROLLER_CLASS; "wallpaper" -> MIUI_WALLPAPER_MANAGER_CLASS; else -> null }
                val name = if (key == "theme") "onColorsChanged" else "onWallpaperChanged"
                val types = if (key == "theme") arrayOf(WallpaperColors::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    else arrayOf(WallpaperColors::class.java, String::class.java, Int::class.javaPrimitiveType)
                owner != null && method.declaringClass.name.contains(owner) && method.name == name &&
                    method.returnType == Void.TYPE && method.parameterTypes.contentEquals(types) &&
                    !java.lang.reflect.Modifier.isStatic(method.modifiers)
            },
        ) { bridge ->
            val targets = GeneratedCallbacks(
                themeOverlay = resolveCallback(
                    bridge,
                    CONTROLLER_CLASS,
                    "onColorsChanged",
                    arrayOf(
                        WallpaperColors::class.java,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!
                    )
                ),
                miuiWallpaper = resolveCallback(
                    bridge,
                    MIUI_WALLPAPER_MANAGER_CLASS,
                    "onWallpaperChanged",
                    arrayOf(
                        WallpaperColors::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType!!
                    )
                )
            )
            mapOf("theme" to targets.themeOverlay, "wallpaper" to targets.miuiWallpaper)
                .filterValues { it != null }.mapValues { requireNotNull(it.value) }
        } ?: return null
        return GeneratedCallbacks(methods["theme"], methods["wallpaper"])
    }

    private fun resolveCallback(
        bridge: DexKitBridge,
        ownerMarker: String,
        methodName: String,
        parameterTypes: Array<Class<*>>
    ): Method? = bridge.findMethod {
        matcher {
            declaredClass { className(ownerMarker, StringMatchType.Contains) }
            name(methodName)
            paramTypes(*parameterTypes)
            returnType(Void.TYPE)
        }
    }.toList().filter { data ->
        data.className.contains(ownerMarker) && data.methodName == methodName &&
            data.paramTypeNames == parameterTypes.map { it.name } &&
            data.returnTypeName == Void.TYPE.name
    }.mapNotNull { data ->
        runCatching { data.getMethodInstance(classLoader) }
            .onFailure { DebugLog.w(SCOPE, "failed to inspect ${data.className}#${data.methodName}", it) }
            .getOrNull()
    }.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.singleOrNull()

    private fun uniqueFieldByType(callbackClass: Class<*>, ownerClass: Class<*>): Field? =
        HostAccess.cached(callbackClass, "monet:controller:${ownerClass.name}", Field::class.java) {
            generateSequence(callbackClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .filter { ownerClass.isAssignableFrom(it.type) }.singleOrNull()?.apply { isAccessible = true }
        }

    private fun isEnabled(): Boolean = settingImmediateMonetRefresh

    private fun rememberController(controller: Any) {
        if (controllerClass?.isInstance(controller) == true) {
            controllerReference = WeakReference(controller)
        }
    }

    private fun resolveController(): Any? {
        controllerReference?.get()?.let { return it }
        val type = delegateClass ?: return null
        val manager = INTERFACES_MANAGER_CLASS.toClassOrNull() ?: return null
        val delegate = HostAccess.declaredMethod(manager, "getImpl", Class::class.java, accessible = true)
            ?.invoke(null, type) ?: return null
        val lazy = declaredField(type, "themeOverlayController")?.get(delegate) ?: return null
        val controller = HostAccess.publicMethod(lazy.javaClass, "get", accessible = true)?.invoke(lazy) ?: return null
        if (controllerClass?.isInstance(controller) != true) return null
        rememberController(controller)
        return controller
    }

    private fun resolveControllerFallbackMembers() {
        delegateClass = CONTROLLER_DELEGATE_CLASS.toClassOrNull()
    }

    private fun currentUserId(controller: Any): Int? {
        val tracker = userTrackerField?.get(controller) ?: return null
        return HostAccess.publicMethod(tracker.javaClass, "getUserId", accessible = true)?.invoke(tracker) as? Int
    }

    private fun isFashionGallerySource(controller: Any, which: Int): Boolean {
        val wallpaperManager = wallpaperManagerField?.get(controller) as? WallpaperManager
        if (wallpaperManager?.wallpaperInfo?.packageName == FASHION_GALLERY_PACKAGE) return true
        val context = contextField?.get(controller) as? Context ?: return false
        val settingsWhich = if (which == WALLPAPER_FLAGS) WallpaperManager.FLAG_SYSTEM else which
        val source = Settings.Secure.getString(
            context.contentResolver,
            "wallpaper_changed_$settingsWhich"
        )
        return source == FASHION_GALLERY_PACKAGE
    }

    @Suppress("UNCHECKED_CAST")
    private fun currentColors(controller: Any, userId: Int): WallpaperColors? {
        val colors = currentColorsField?.get(controller) as? SparseArray<Any?> ?: return null
        return colors.get(userId) as? WallpaperColors
    }

    private fun forwardIfChanged(
        controller: Any,
        colors: WallpaperColors,
        which: Int,
        userId: Int,
        source: String
    ): Boolean {
        if (currentColors(controller, userId) == colors) return false
        val handleMethod = handleWallpaperColorsMethod ?: return false
        handleMethod.invoke(controller, colors, which, userId)
        DebugLog.i(SCOPE, "forwarded wallpaper colors source=$source user=$userId which=$which")
        return true
    }

    private fun deferredStore(type: Class<*>, source: String): DeferredStore? {
        val colors = declaredField(type, "mDeferredWallpaperColors") ?: return null
        val flags = declaredField(type, "mDeferredWallpaperColorsFlags") ?: return null
        return DeferredStore(colors, flags, source)
    }

    @Suppress("UNCHECKED_CAST")
    private fun flushIfCurrent(
        store: DeferredStore,
        storeOwner: Any?,
        controller: Any,
        handleMethod: Method,
        callbackColors: WallpaperColors,
        callbackWhich: Int,
        userId: Int
    ): Boolean {
        val pendingColors = store.colorsField.get(storeOwner) as? SparseArray<Any?> ?: return false
        val pendingFlags = store.flagsField.get(storeOwner) as? SparseIntArray ?: return false
        val colors = pendingColors.get(userId) as? WallpaperColors ?: return false
        val which = pendingFlags.get(userId)
        if (colors != callbackColors || which != callbackWhich) return false

        pendingColors.put(userId, null)
        pendingFlags.put(userId, 0)
        try {
            handleMethod.invoke(controller, colors, which, userId)
        } catch (throwable: Throwable) {
            pendingColors.put(userId, colors)
            pendingFlags.put(userId, which)
            throw throwable
        }
        DebugLog.d(SCOPE, "flushed ${store.source} deferred colors user=$userId which=$which")
        return true
    }

    private fun declaredField(type: Class<*>, name: String): Field? = runCatching {
        HostAccess.requireDeclaredField(type, name, accessible = true)
    }.getOrNull()

    private fun clearResolvedMembers() {
        controllerClass = null
        controllerReference = null
        themeOverlayCallback = null
        miuiWallpaperCallback = null
        listenerControllerField = null
        handleWallpaperColorsMethod = null
        controllerStore = null
        miuiStore = null
        delegateClass = null
    }

    private const val WALLPAPER_FLAGS = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
}
