package com.takekazex.hypertweak.hook.rules.systemui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.provider.Settings
import android.util.SparseArray
import android.util.SparseIntArray
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executor

object ImmediateMonetRefreshHooker : StaticHooker() {
    private const val SCOPE = "ImmediateMonetRefresh"
    private const val CONTROLLER_CLASS = "com.android.systemui.theme.ThemeOverlayController"
    private const val LISTENER_CLASS = "com.android.systemui.theme.ThemeOverlayController\$2"
    private const val MIUI_HELPER_CLASS = "com.miui.keyguard.utils.ThemeOverlayControllerHelper"
    private const val MIUI_WALLPAPER_CALLBACK_CLASS =
        "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager\$4"
    private const val CONTROLLER_DELEGATE_CLASS =
        "com.miui.systemui.theme.ThemeOverlayControllerDelegate"
    private const val INTERFACES_MANAGER_CLASS =
        "com.miui.systemui.interfacesmanager.InterfacesImplManager"
    private const val USER_TRACKER_IMPL_CLASS = "com.android.systemui.settings.UserTrackerImpl"
    private const val FASHION_GALLERY_PACKAGE = "com.miui.android.fashiongallery"

    private data class DeferredStore(
        val colorsField: Field,
        val flagsField: Field,
        val source: String
    )

    private var controllerClass: Class<*>? = null
    @Volatile
    private var controllerReference: WeakReference<Any>? = null
    private var listenerControllerField: Field? = null
    private var handleWallpaperColorsMethod: Method? = null
    private var currentColorsField: Field? = null
    private var contextField: Field? = null
    private var wallpaperManagerField: Field? = null
    private var mainExecutorField: Field? = null
    private var userTrackerField: Field? = null
    private var userIdMethod: Method? = null
    private var controllerStore: DeferredStore? = null
    private var miuiStore: DeferredStore? = null

    private var delegateClass: Class<*>? = null
    private var getImplementationMethod: Method? = null
    private var delegateLazyField: Field? = null
    private var lazyGetMethod: Method? = null

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
        handleWallpaperColorsMethod = controllerType.declaredMethods.firstOrNull {
            it.name == "handleWallpaperColors" && it.parameterTypes.contentEquals(
                arrayOf(
                    WallpaperColors::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            )
        }?.apply { isAccessible = true }
        currentColorsField = declaredField(controllerType, "mCurrentColors")
        contextField = declaredField(controllerType, "mContext")
        wallpaperManagerField = declaredField(controllerType, "mWallpaperManager")
        mainExecutorField = declaredField(controllerType, "mMainExecutor")
        userTrackerField = declaredField(controllerType, "mUserTracker")
        userIdMethod = USER_TRACKER_IMPL_CLASS.toClassOrNull()?.declaredMethods?.firstOrNull {
            it.name == "getUserId" && it.parameterCount == 0
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
        val listenerType = LISTENER_CLASS.toClassOrNull()
            ?: controllerType.declaredFields.firstOrNull {
                it.name == "mOnColorsChangedListener"
            }?.type
            ?: controllerType.declaredClasses.firstOrNull { nested ->
                nested.declaredMethods.any(::isWallpaperColorsCallback)
            }
            ?: return false
        val callback = listenerType.declaredMethods.firstOrNull(::isWallpaperColorsCallback)
            ?.apply { isAccessible = true }
            ?: return false

        listenerControllerField = listenerType.declaredFields.firstOrNull {
            controllerType.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }

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
        val callbackType = MIUI_WALLPAPER_CALLBACK_CLASS.toClassOrNull() ?: return false
        val callback = callbackType.declaredMethods.firstOrNull {
            it.name == "onWallpaperChanged" && it.parameterTypes.contentEquals(
                arrayOf(
                    WallpaperColors::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType
                )
            )
        }?.apply { isAccessible = true } ?: return false

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

    private fun isWallpaperColorsCallback(method: Method): Boolean {
        return method.name == "onColorsChanged" && method.parameterTypes.contentEquals(
            arrayOf(
                WallpaperColors::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        )
    }

    private fun isEnabled(): Boolean = Preferences.getBoolean(
        Preferences.KEY_IMMEDIATE_MONET_REFRESH,
        Preferences.DEFAULT_IMMEDIATE_MONET_REFRESH
    )

    private fun rememberController(controller: Any) {
        if (controllerClass?.isInstance(controller) == true) {
            controllerReference = WeakReference(controller)
        }
    }

    private fun resolveController(): Any? {
        controllerReference?.get()?.let { return it }
        val type = delegateClass ?: return null
        val delegate = getImplementationMethod?.invoke(null, type) ?: return null
        val lazy = delegateLazyField?.get(delegate) ?: return null
        val controller = lazyGetMethod?.invoke(lazy) ?: return null
        if (controllerClass?.isInstance(controller) != true) return null
        rememberController(controller)
        return controller
    }

    private fun resolveControllerFallbackMembers() {
        val managerType = INTERFACES_MANAGER_CLASS.toClassOrNull() ?: return
        val delegateType = CONTROLLER_DELEGATE_CLASS.toClassOrNull() ?: return
        delegateClass = delegateType
        getImplementationMethod = managerType.declaredMethods.firstOrNull {
            it.name == "getImpl" && it.parameterTypes.contentEquals(arrayOf(Class::class.java))
        }?.apply { isAccessible = true }
        delegateLazyField = delegateType.declaredFields.firstOrNull {
            it.name == "themeOverlayController"
        }?.apply { isAccessible = true }
        lazyGetMethod = delegateLazyField?.type?.methods?.firstOrNull {
            it.name == "get" && it.parameterCount == 0
        }?.apply { isAccessible = true }
    }

    private fun currentUserId(controller: Any): Int? {
        val tracker = userTrackerField?.get(controller) ?: return null
        val method = userIdMethod ?: tracker.javaClass.methods.firstOrNull {
            it.name == "getUserId" && it.parameterCount == 0
        }?.apply {
            isAccessible = true
            userIdMethod = this
        } ?: return null
        return method.invoke(tracker) as? Int
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
        type.getDeclaredField(name).apply { isAccessible = true }
    }.getOrNull()

    private fun clearResolvedMembers() {
        controllerClass = null
        controllerReference = null
        listenerControllerField = null
        handleWallpaperColorsMethod = null
        currentColorsField = null
        contextField = null
        wallpaperManagerField = null
        mainExecutorField = null
        userTrackerField = null
        userIdMethod = null
        controllerStore = null
        miuiStore = null
        delegateClass = null
        getImplementationMethod = null
        delegateLazyField = null
        lazyGetMethod = null
    }

    private const val WALLPAPER_FLAGS = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
}
