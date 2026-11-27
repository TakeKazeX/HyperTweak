package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.ignoreFailure
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Replaces the expanded flashlight focus card (「手电筒已开启」 + 关闭) with [FlashlightIslandPanel].
 *
 * The plugin's `FocusNotificationController` builds a `DynamicIslandData` per focus notification
 * and hands it to `DynamicIslandWindowViewController.addDynamicIslandView/updateDynamicIslandView`.
 * Before either runs, the flashlight entry (sbn from `miui.systemui.plugin`, channel
 * `flashlight_channel`, focus business `miui_flashlight`) gets the panel (the plugin's own
 * flashlight page, cropped) as `view` and a second copy as `fakeView`, which the host animates
 * during expand and collapse. The big and small
 * islands keep their native template. Disable, plugin unload and hot reload put the native views
 * back through the same update entry.
 */
class IslandFlashlightHooker : DynamicHooker() {
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL, false)
    }

    @Volatile private var retiring = false
    @Volatile private var enabled = false
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, FlashlightIslandSession>()
    @Volatile private var controller: WeakReference<Any>? = null
    @Volatile private var assets: FlashlightNativeAssets? = null
    @Volatile private var assetsFailed = false
    @Volatile private var haptics: FlashlightHaptics? = null
    /** Main thread only: the safeguards controller that parked the island for the page. */
    @Volatile private var parkedBy: WeakReference<Any>? = null
    private var pageExitReceiver: Pair<Context, BroadcastReceiver>? = null
    private lateinit var restoreParked: (Any, String) -> Any?
    private lateinit var islandBounds: (Any) -> Bundle?
    /** Main thread only: a restore in progress makes the island grow from the cutout. */
    private var growFromCutoutUntil = 0L
    /** Main thread only: when the page's exit signal last restored the island. */
    private var restoredEarlyAt = 0L
    private lateinit var access: Access

    private class Access(
        val update: Method,
        val islandData: Field,
        val getKey: Method,
        val getView: Method,
        val getFakeView: Method,
        val setView: Method,
        val setFakeView: Method,
        val getExtras: Method,
    )

    override fun onHook() {
        val controllerClass = CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER, "not found on host")
            return
        }
        val dataClass = classLoader.loadClass(DATA)
        val add = HostAccess.requireDeclaredMethod(controllerClass, "addDynamicIslandView", dataClass,
            Boolean::class.javaPrimitiveType, accessible = false)
        val update = HostAccess.requireDeclaredMethod(controllerClass, "updateDynamicIslandView", dataClass,
            Boolean::class.javaPrimitiveType, accessible = true)
        val remove = HostAccess.requireDeclaredMethod(controllerClass, "removeDynamicIslandView", String::class.java,
            Boolean::class.javaPrimitiveType, accessible = false)
        listOf(add, update, remove).forEach { check(it.returnType == Void.TYPE) }
        val islandData = HostAccess.requireDeclaredField(controllerClass, "islandData", accessible = true)
            .apply { check(Map::class.java.isAssignableFrom(type)) }
        fun getter(name: String, type: Class<*>) = HostAccess.requirePublicMethod(dataClass, name, accessible = false)
            .apply { check(returnType == type) }
        fun setter(name: String) = HostAccess.requirePublicMethod(dataClass, name, View::class.java, accessible = false)
        access = Access(update, islandData, getter("getKey", String::class.java), getter("getView", View::class.java),
            getter("getFakeView", View::class.java), setter("setView"), setter("setFakeView"),
            getter("getExtras", Bundle::class.java))
        enabled = settingEnabled

        listOf(add, update).forEach { method ->
            method.hook {
                before { param ->
                    val owner = param.thisObjectOrNull ?: return@before
                    controller = WeakReference(owner)
                    if (retiring || !enabled) return@before
                    val data = param.args[0] ?: return@before
                    HookFailurePolicy.open(TAG, "swap flashlight island view", Unit) { swap(data) }
                }
            }
        }
        remove.hook {
            after { param ->
                val key = param.args[0] as? String ?: return@after
                sessions.remove(key)?.let { session ->
                    HookFailurePolicy.open(TAG, "drop flashlight session", Unit) { onMain { session.dispose() } }
                }
            }
        }
        // The host stretches expanded content to `expandedViewMaxWidth` before measuring it, so
        // `updateExpandedSize` always receives the 364dp maximum. For the flashlight island the
        // panel's own size is passed instead; the host clamps it and centres the island itself.
        val content = classLoader.loadClass(CONTENT_VIEW)
        val sizeMethod = HostAccess.requireDeclaredMethod(content, "updateExpandedSize", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, dataClass, accessible = false).apply { check(returnType == Void.TYPE) }
        sizeMethod.hook {
            before { param ->
                if (retiring || !enabled) return@before
                val data = param.args[2] ?: return@before
                HookFailurePolicy.open(TAG, "size flashlight island", Unit) {
                    val panel = access.getView.invoke(data) as? FlashlightIslandPanel ?: return@open
                    if (sessions.values.none { it.panel === panel }) return@open
                    param.args[0] = panel.desiredWidth()
                    param.args[1] = panel.desiredHeight()
                }
            }
        }
        // A gesture that starts on the panel belongs to the panel alone, so the window's press
        // sink, drag-to-collapse and pull-to-full-screen handle never see it.
        val window = classLoader.loadClass(WINDOW_VIEW)
        val dispatch = HostAccess.requireDeclaredMethod(window, "dispatchTouchEvent", MotionEvent::class.java,
            accessible = false).apply { check(returnType == Boolean::class.javaPrimitiveType) }
        dispatch.hook {
            before { param ->
                val host = param.thisObject as? View ?: return@before
                val event = param.args[0] as? MotionEvent ?: return@before
                if (HookFailurePolicy.open(TAG, "route flashlight panel touch", false) { routeTouch(host, event) }) {
                    param.result = true
                }
            }
        }
        // Every island update re-adds the expanded view, and the detach destroys the renderer's
        // surface. Its frame is captured first, so the new surface opens behind the same picture.
        val contentView = classLoader.loadClass(CONTENT_VIEW_IMPL)
        val readd = contentView.declaredMethods.single { m ->
            m.name == "updateExpandedView" && m.parameterTypes.size == 3 && m.parameterTypes[0] == dataClass &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType && m.returnType == Any::class.java
        }
        readd.hook {
            before { param ->
                if (retiring || !enabled || Looper.myLooper() != main.looper) return@before
                val data = param.args[0] ?: return@before
                HookFailurePolicy.open(TAG, "capture flashlight frame", Unit) {
                    val view = access.getView.invoke(data) as? FlashlightIslandPanel ?: return@open
                    sessions.values.firstOrNull { it.panel === view }?.captureFrame()
                }
            }
        }
        // Pulling the island down opens the page as a mini window (`EnterMiniWindow`). When it
        // grows to full screen and is closed with back, the window state machine reports
        // `ExitApp`, which only restores islands parked by `EnterApp`, so the flashlight island
        // stays parked and invisible. The framework's own mini-window exit restores it.
        val safeguards = classLoader.loadClass(SAFEGUARDS)
        val exitApp = HostAccess.requireDeclaredMethod(safeguards, "exitApp", String::class.java, accessible = false)
            .apply { check(returnType == Void.TYPE) }
        val exitMiniWindow = HostAccess.requireDeclaredMethod(safeguards, "exitMiniWindow", String::class.java,
            accessible = true).apply { check(returnType == Void.TYPE) }
        val safeguardsWindow = safeguards.declaredFields.single { it.type == window }.apply { isAccessible = true }
        val mainMiniWindow = HostAccess.requireDeclaredMethod(window, "getMainMiniWindow", String::class.java,
            accessible = true)
        restoreParked = restore@{ owner, reason ->
            val windowView = safeguardsWindow.get(owner)
            val parked = windowView?.let { mainMiniWindow.invoke(it, FlashlightNativeAssets.PLUGIN_PACKAGE) }
            val data = parked?.let { runCatching { it.javaClass.getMethod("getCurrentIslandData").invoke(it) }.getOrNull() }
            if (data == null || !isFlashlight(data)) return@restore null
            DebugLog.i(TAG, "restoring the flashlight island from its mini-window state reason=$reason")
            growFromCutoutUntil = SystemClock.uptimeMillis() + GROW_WINDOW_MS
            try {
                exitMiniWindow.invoke(owner, FlashlightNativeAssets.PLUGIN_PACKAGE)
            } finally {
                // The transition resolves synchronously in the dispatch; a later one is native.
                main.post { growFromCutoutUntil = 0L }
            }
            parked
        }
        // `ExitMiniWindow` collapses the island with `MiniWindowToBigIsland`, a morph from the
        // expanded geometry: for this island the 200×320dp panel, which reads as a box shrinking
        // over the page. The flashlight island instead grows from the cutout with the native
        // `HiddenToBigIsland` (what a sub mini window uses), while the page flies into it.
        val resolver = classLoader.loadClass(RESOLVE_FROM_MINI_WINDOW)
        val bigIslandState = classLoader.loadClass(STATE_BIG_ISLAND)
        val growTransition = classLoader.loadClass(TRANSITION_HIDDEN_TO_BIG).getConstructor()
        val resolveMini = resolver.declaredMethods.single { m ->
            m.name == "resolveFromMiniWindow" && Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
                m.parameterTypes.size == 3 && List::class.java.isAssignableFrom(m.parameterTypes[2])
        }
        val tempHidden = resolveMini.parameterTypes[1].getMethod("isTempHidden")
            .apply { check(returnType == Boolean::class.javaPrimitiveType) }
        resolveMini.hook {
            before { param ->
                if (retiring || !enabled || SystemClock.uptimeMillis() > growFromCutoutUntil) return@before
                if (!bigIslandState.isInstance(param.args[0])) return@before
                HookFailurePolicy.open(TAG, "grow flashlight island from the cutout", Unit) {
                    if (tempHidden.invoke(param.args[1]) == true) return@open
                    @Suppress("UNCHECKED_CAST")
                    val result = param.args[2] as? MutableList<Any> ?: return@open
                    growFromCutoutUntil = 0L
                    result.add(growTransition.newInstance())
                    param.result = null
                }
            }
        }
        fun intGetter(name: String) = HostAccess.requirePublicMethod(content, name, accessible = false)
            .apply { check(returnType == Int::class.javaPrimitiveType) }
        val bigIslandX = intGetter("getBigIslandX")
        val bigIslandWidth = intGetter("getBigIslandViewWidth")
        val islandHeight = intGetter("getIslandViewHeight")
        islandBounds = { parked ->
            val view = parked as? View
            val width = bigIslandWidth.invoke(parked) as Int
            if (view == null || width <= 0) null else {
                val location = IntArray(2).also { view.getLocationOnScreen(it) }
                Bundle().apply {
                    putInt(FlashlightPageExitHooker.EXTRA_ISLAND_LEFT, location[0] + bigIslandX.invoke(parked) as Int)
                    putInt(FlashlightPageExitHooker.EXTRA_ISLAND_WIDTH, width)
                    putInt(FlashlightPageExitHooker.EXTRA_ISLAND_HEIGHT, islandHeight.invoke(parked) as Int)
                }
            }
        }
        // The page opens through the window view directly, never through `enterMiniWindow`, so the
        // controller is taken from the island's own timeout bookkeeping, which runs on every
        // flashlight island update (`delayDeleted("0|miui.systemui.plugin|…", seconds)`).
        val delayDeleted = HostAccess.requireDeclaredMethod(safeguards, "delayDeleted", String::class.java,
            Long::class.javaPrimitiveType, accessible = false).apply { check(returnType == Void.TYPE) }
        delayDeleted.hook {
            after { param ->
                if (retiring || !enabled) return@after
                val key = param.args[0] as? String ?: return@after
                if (!key.contains("|${FlashlightNativeAssets.PLUGIN_PACKAGE}|")) return@after
                HookFailurePolicy.open(TAG, "track flashlight island controller", Unit) {
                    track(param.thisObjectOrNull ?: return@open, safeguardsWindow)
                }
            }
        }
        // Once the page's exit signal has restored the island, the native app-close handling
        // would run its close-end state on an island that is already back, so it is skipped.
        // Without the signal, the framework's own mini-window exit restores it here.
        exitApp.hook {
            before { param ->
                if (retiring || !enabled || param.args[0] != FlashlightNativeAssets.PLUGIN_PACKAGE) return@before
                val owner = param.thisObjectOrNull ?: return@before
                HookFailurePolicy.open(TAG, "restore flashlight island after its page", Unit) {
                    track(owner, safeguardsWindow)
                    if (SystemClock.uptimeMillis() - restoredEarlyAt < EARLY_RESTORE_WINDOW_MS) {
                        restoredEarlyAt = 0L
                        DebugLog.d(TAG, "flashlight island already restored by the page exit; native app close skipped")
                        param.result = null
                    }
                }
            }
            after { param ->
                if (retiring || !enabled || param.args[0] != FlashlightNativeAssets.PLUGIN_PACKAGE) return@after
                HookFailurePolicy.open(TAG, "restore flashlight island after its page", Unit) {
                    val _ = restoreParked(param.thisObjectOrNull ?: return@open, "page closed")
                }
            }
        }
        DebugLog.hookRegistered(TAG, "flashlight island brightness panel")
    }

    /**
     * The page's process announces its exit as it starts flying into the island (see
     * [FlashlightPageExitHooker]), so the island is back before the page lands instead of after
     * its window is gone. Only senders holding `STATUS_BAR_SERVICE` (the plugin runs as system)
     * reach it.
     */
    private fun track(owner: Any, window: Field) {
        if (parkedBy?.get() !== owner) parkedBy = WeakReference(owner)
        if (pageExitReceiver == null) (window.get(owner) as? View)?.context?.let(::listenForPageExit)
    }

    private fun listenForPageExit(context: Context) {
        if (pageExitReceiver != null || retiring) return
        val app = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (retiring || !enabled) return
                HookFailurePolicy.open(TAG, "restore flashlight island for the page exit", Unit) {
                    val parked = restoreParked(parkedBy?.get() ?: return@open, "page exit started") ?: return@open
                    restoredEarlyAt = SystemClock.uptimeMillis()
                    // The page aims its flight at the island it lands in.
                    if (isOrderedBroadcast) islandBounds(parked)?.let { setResultExtras(it) }
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter(FlashlightPageExitHooker.ACTION_PAGE_EXIT),
            FlashlightPageExitHooker.SENDER_PERMISSION, main, Context.RECEIVER_EXPORTED)
        pageExitReceiver = app to receiver
    }

    private fun swap(data: Any) {
        val key = access.getKey.invoke(data) as? String ?: return
        if (!isFlashlight(data)) return
        val current = access.getView.invoke(data) as? View ?: return
        val existing = sessions[key]
        if (existing != null && (current === existing.panel)) {
            access.setFakeView.invoke(data, existing.fake)
            promote(data, existing)
            return
        }
        // A panel left by another module generation is never mistaken for the native view.
        if (current.tag == FlashlightIslandPanel.TAG_MARKER) return
        val session = existing ?: createSession(current.context)?.also { sessions[key] = it } ?: return
        session.nativeView = current
        session.nativeFake = access.getFakeView.invoke(data) as? View
        access.setView.invoke(data, session.panel)
        access.setFakeView.invoke(data, session.fake)
        promote(data, session)
        if (existing == null) DebugLog.i(TAG, "flashlight island panel installed key=$key")
    }

    /**
     * `miui.focus.isPromoted` is the only flag that lets `updateExpandedSize` take the panel's
     * height past `expanded_island_height_dp` (168dp). It also switches the expanded background
     * to the frosted material, which the panel's black page covers. The controller rebuilds the
     * extras on every update, so it is set on every swap and put back on restore.
     */
    private fun promote(data: Any, session: FlashlightIslandSession) {
        val extras = access.getExtras.invoke(data) as? Bundle ?: return
        if (!session.promoted) {
            session.nativePromoted = extras.getBoolean(EXTRA_PROMOTED, false)
            session.promoted = true
        }
        extras.putBoolean(EXTRA_PROMOTED, true)
    }

    /** Main thread only: the window dispatches touches there. */
    private var touchTarget: FlashlightIslandPanel? = null

    private fun routeTouch(window: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchTarget = if (retiring || !enabled) null
            else sessions.values.firstOrNull { it.panel.hitFromWindow(window, event) }?.panel
        }
        val target = touchTarget ?: return false
        val ends = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (ends) touchTarget = null
        // The window never saw this gesture's down, so the rest of it stays here even if the
        // panel goes away mid-gesture.
        if (target.isAttachedToWindow) target.dispatchFromWindow(window, event)
        return true
    }

    private fun isFlashlight(data: Any): Boolean {
        val extras = access.getExtras.invoke(data) as? Bundle ?: return false
        val sbn = extras.getParcelable(EXTRA_SBN, StatusBarNotification::class.java) ?: return false
        if (sbn.packageName != FlashlightNativeAssets.PLUGIN_PACKAGE) return false
        val notification = sbn.notification ?: return false
        return notification.channelId == CHANNEL &&
            FlashlightLevels.isFlashlightFocus(notification.extras?.getString(EXTRA_FOCUS_PARAM))
    }

    private fun createSession(viewContext: Context): FlashlightIslandSession? {
        if (assetsFailed) return null
        return runCatching {
            val loaded = assets ?: FlashlightNativeAssets.load(viewContext, classLoader).also { assets = it }
            val feedback = haptics ?: FlashlightHaptics(viewContext).also { haptics = it }
            FlashlightIslandSession(viewContext, loaded, FlashlightTorch.resolve(viewContext), feedback).apply { seed(on = true) }
        }.onFailure {
            assetsFailed = true
            DebugLog.e(TAG, "flashlight panel unavailable; keeping the native card", it)
        }.getOrNull()
    }

    /** Puts every living flashlight island back on its native views, then drops the sessions. */
    private fun restoreAll(reason: String) {
        val owner = controller?.get()
        val map = owner?.let { runCatching { access.islandData.get(it) as? Map<*, *> }.getOrNull() }
        sessions.forEach { (key, session) ->
            val data = map?.get(key)
            if (data != null && access.getView.invoke(data) === session.panel && session.nativeView != null) {
                access.setView.invoke(data, session.nativeView)
                access.setFakeView.invoke(data, session.nativeFake)
                (access.getExtras.invoke(data) as? Bundle)?.putBoolean(EXTRA_PROMOTED, session.nativePromoted)
                runCatching { access.update.invoke(owner, data, false) }
                    .onFailure { DebugLog.e(TAG, "restore native flashlight island failed key=$key", it) }
            }
            session.closeIfSuppressed()
            session.dispose()
        }
        if (sessions.isNotEmpty()) DebugLog.i(TAG, "flashlight island restored to native views reason=$reason count=${sessions.size}")
        sessions.clear()
        FlashlightIslandHold.clear()
    }

    /** Swaps the living flashlight island, if any, after a live enable or a hot reload. */
    private fun applyToLiving() {
        val owner = controller?.get() ?: return
        val map = runCatching { access.islandData.get(owner) as? Map<*, *> }.getOrNull() ?: return
        map.values.filterNotNull().filter { isFlashlight(it) }.forEach { data ->
            swap(data)
            if (sessions.values.any { access.getView.invoke(data) === it.panel }) access.update.invoke(owner, data, false)
        }
    }

    fun snapshotController(): List<Any> = listOfNotNull(controller?.get())

    fun restoreController(owners: List<Any>) {
        if (retiring || !::access.isInitialized) return
        owners.firstOrNull { access.islandData.declaringClass.isInstance(it) }?.let { controller = WeakReference(it) }
        refreshSettings()
    }

    fun refreshSettings() {
        if (retiring || !::access.isInitialized) return
        val next = settingEnabled
        main.post {
            if (retiring) return@post
            val changed = next != enabled
            enabled = next
            HookFailurePolicy.open(TAG, "apply flashlight island setting", Unit) {
                if (!next && changed) restoreAll("disabled") else if (next) applyToLiving()
            }
            if (changed) DebugLog.i(TAG, "settings applied enabled=$next")
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post(action)
    }

    override fun onPrepareHotReload() {
        retiring = true
        if (!::access.isInitialized) return
        val cleanup = Runnable {
            pageExitReceiver?.let { (context, receiver) -> ignoreFailure { context.unregisterReceiver(receiver) } }
            pageExitReceiver = null
            HookFailurePolicy.open(TAG, "restore native flashlight island", Unit) { restoreAll("retired") }
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "flashlight island restoration timed out" }
        }
    }

    private companion object {
        const val TAG = "IslandFlashlight"
        /** The page window closes about 0.5s after its exit signal. */
        const val EARLY_RESTORE_WINDOW_MS = 3000L
        const val GROW_WINDOW_MS = 500L
        const val RESOLVE_FROM_MINI_WINDOW = "miui.systemui.dynamicisland.anim.domain.interactor.ResolveFromMiniWindowKt"
        const val STATE_BIG_ISLAND = "miui.systemui.dynamicisland.event.DynamicIslandState\$BigIsland"
        const val TRANSITION_HIDDEN_TO_BIG = "miui.systemui.dynamicisland.anim.model.AnimTransitionType\$HiddenToBigIsland"
        const val CONTROLLER = "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController"
        const val DATA = "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData"
        const val EXTRA_SBN = "miui.sbn"
        const val EXTRA_FOCUS_PARAM = "miui.focus.param"
        const val CHANNEL = "flashlight_channel"
        const val EXTRA_PROMOTED = "miui.focus.isPromoted"
        const val SAFEGUARDS = "miui.systemui.dynamicisland.window.DynamicIslandSafeguardsController"
        const val WINDOW_VIEW = "miui.systemui.dynamicisland.window.DynamicIslandWindowView"
        const val CONTENT_VIEW_IMPL = "miui.systemui.dynamicisland.window.content.DynamicIslandContentView"
        const val CONTENT_VIEW = "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    }
}
