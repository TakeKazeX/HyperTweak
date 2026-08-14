package com.takekazex.hypertweak.hook.rules.systemui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.ResourceLookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object GestureBarActionHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "GestureBarActions"
    private const val NAVIGATION_BAR_CLASS =
        "com.android.systemui.navigationbar.views.NavigationBar"
    private const val INPUT_MONITOR_NAME = "HyperTweakGestureBar"

    private val receivers = IdentityHashMap<Any, GestureBarInputReceiver>()

    private var navigationBarViewField: Field? = null
    private var assistManagerLazyField: Field? = null
    private var assistManagerLazyGetMethod: Method? = null
    private var assistManagerStartMethod: Method? = null
    private var actionExecutor: ExecutorService? = null

    override fun onPrepareHotReload() {
        detachAllReceivers()
        actionExecutor?.shutdownNow()
        actionExecutor = null
        navigationBarViewField = null
        assistManagerLazyField = null
        assistManagerLazyGetMethod = null
        assistManagerStartMethod = null
    }

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false)) {
            DebugLog.hookSkipped(SCOPE, "SystemUI gesture input", "disabled")
            return
        }

        val navigationBarClass = classLoader.loadClass(NAVIGATION_BAR_CLASS)
        navigationBarViewField = findField(navigationBarClass, "mView")
        runCatching {
            assistManagerLazyField = findField(navigationBarClass, "mAssistManagerLazy")
            assistManagerLazyGetMethod = assistManagerLazyField?.type
                ?.getMethod("get")
                ?.apply { isAccessible = true }
            assistManagerStartMethod = classLoader
                .loadClass("com.android.systemui.assist.AssistManager")
                .getDeclaredMethod("startAssist", Bundle::class.java)
                .apply { isAccessible = true }
        }.onFailure {
            DebugLog.w(SCOPE, "SystemUI AssistManager bridge is unavailable", it)
        }
        actionExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HyperTweak-GestureActions").apply { isDaemon = true }
        }

        navigationBarClass.getDeclaredMethod("onViewAttached")
            .apply { isAccessible = true }
            .hook("gesture_bar_actions_attach") {
                after { param ->
                    HookFailurePolicy.open(SCOPE, "attach input monitor", Unit) {
                        attachReceiver(param.thisObject)
                    }
                }
            }

        navigationBarClass.getDeclaredMethod("onViewDetached")
            .apply { isAccessible = true }
            .hook("gesture_bar_actions_detach") {
                before { param ->
                    HookFailurePolicy.open(SCOPE, "detach input monitor", Unit) {
                        detachReceiver(param.thisObject)
                    }
                }
            }
    }

    private fun attachReceiver(navigationBar: Any) {
        detachReceiver(navigationBar)
        val navigationView = navigationBarViewField?.get(navigationBar) as? View ?: return
        val context = navigationView.context ?: return
        val displayId = navigationView.display?.displayId ?: 0
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) ?: return
        val monitorMethod = inputManager.javaClass.methods.firstOrNull { method ->
            method.name == "monitorGestureInput" && method.parameterTypes.contentEquals(
                arrayOf(String::class.java, Int::class.javaPrimitiveType)
            )
        } ?: throw NoSuchMethodException("InputManager#monitorGestureInput(String, int)")
        val monitor = monitorMethod.invoke(
            inputManager,
            "$INPUT_MONITOR_NAME-$displayId",
            displayId
        ) as? InputMonitor ?: error("monitorGestureInput returned null")

        val receiver = try {
            GestureBarInputReceiver(
                context = context,
                navigationBar = navigationBar,
                navigationView = navigationView,
                inputMonitor = monitor,
                displayId = displayId
            )
        } catch (t: Throwable) {
            runCatching { monitor.dispose() }
            throw t
        }
        synchronized(receivers) {
            receivers[navigationBar] = receiver
        }
        DebugLog.i(
            SCOPE,
            "input monitor attached display=$displayId " +
                "long=${configuredLongPressAction()} double=${configuredDoubleTapAction()}"
        )
    }

    private fun detachReceiver(navigationBar: Any) {
        val receiver = synchronized(receivers) { receivers.remove(navigationBar) } ?: return
        receiver.detach()
    }

    private fun detachAllReceivers() {
        val snapshot = synchronized(receivers) {
            receivers.values.toList().also { receivers.clear() }
        }
        snapshot.forEach(GestureBarInputReceiver::detach)
    }

    private fun configuredLongPressAction(): GestureBarAction {
        return configuredAction(
            key = Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
            defaultAction = GestureBarAction.DEFAULT_ASSISTANT
        )
    }

    private fun configuredDoubleTapAction(): GestureBarAction {
        return configuredAction(
            key = Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
            defaultAction = GestureBarAction.CIRCLE_TO_SEARCH
        )
    }

    private fun configuredAction(
        key: String,
        defaultAction: GestureBarAction
    ): GestureBarAction {
        if (!Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false)) {
            return GestureBarAction.DISABLED
        }
        return GestureBarAction.fromPersistedId(
            Preferences.getInt(key, defaultAction.persistedId)
        )
    }

    private fun executeAction(
        action: GestureBarAction,
        navigationBar: Any,
        navigationView: View
    ) {
        when (action) {
            GestureBarAction.DISABLED -> Unit
            GestureBarAction.DEFAULT_ASSISTANT -> launchDefaultAssistant(
                navigationBar,
                navigationView
            )
            GestureBarAction.CIRCLE_TO_SEARCH -> {
                val executor = actionExecutor ?: return
                runCatching {
                    executor.execute {
                        val result = ContextualSearchInvoker.start()
                        if (result) {
                            DebugLog.i(SCOPE, "Circle to Search requested")
                        } else {
                            DebugLog.w(SCOPE, "Circle to Search request failed")
                        }
                    }
                }.onFailure {
                    DebugLog.w(SCOPE, "failed to schedule Circle to Search", it)
                }
            }
        }
    }

    private fun launchDefaultAssistant(navigationBar: Any, navigationView: View) {
        val handled = runCatching {
            val lazy = assistManagerLazyField?.get(navigationBar) ?: return@runCatching false
            val assistManager = assistManagerLazyGetMethod?.invoke(lazy)
                ?: return@runCatching false
            val startMethod = assistManagerStartMethod ?: return@runCatching false
            val args = Bundle().apply {
                putBoolean(GESTURE_BAR_ASSIST_REQUEST_MARKER, true)
            }
            startMethod.invoke(assistManager, args)
            true
        }.onFailure {
            DebugLog.w(SCOPE, "SystemUI assistant invocation failed", it)
        }.getOrDefault(false)
        if (handled) {
            DebugLog.i(SCOPE, "default assistant requested without Launcher override")
            return
        }

        runCatching {
            navigationView.context.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            DebugLog.i(SCOPE, "default assistant requested through ACTION_ASSIST")
        }.onFailure {
            DebugLog.w(SCOPE, "fallback assistant invocation failed", it)
        }
    }

    private fun currentHandleView(navigationView: View): View? {
        val id = ResourceLookup.identifier(
            navigationView.resources,
            "home_handle",
            "id",
            "com.android.systemui"
        )
        return id.takeIf { it != 0 }?.let(navigationView::findViewById)
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                return field.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}#$name")
    }

    private class GestureBarInputReceiver(
        private val context: Context,
        private val navigationBar: Any,
        private val navigationView: View,
        private val inputMonitor: InputMonitor,
        private val displayId: Int
    ) : InputEventReceiver(inputMonitor.inputChannel, Looper.getMainLooper()) {
        private val handler = Handler(Looper.getMainLooper())
        private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        private val viewConfiguration = ViewConfiguration.get(context)
        private val detector = GestureBarGestureDetector(
            moveSlop = viewConfiguration.scaledTouchSlop.toFloat(),
            doubleTapSlop = viewConfiguration.scaledDoubleTapSlop.toFloat(),
            doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
        )
        private val longPressTimeoutMs = GestureBarLongPressTiming.recognitionTimeout(
            ViewConfiguration.getLongPressTimeout().toLong()
        )
        private var pilfered = false

        private val longPressRunnable = Runnable {
            HookFailurePolicy.open(SCOPE, "long press callback", Unit) {
                if (configuredLongPressAction() == GestureBarAction.DISABLED) {
                    detector.cancel()
                    return@open
                }
                if (!detector.onLongPressTimeout() || !pilfer("long press")) return@open
                dispatchGesture(GestureBarGesture.LONG_PRESS)
            }
        }

        override fun onInputEvent(event: InputEvent) {
            var handled = false
            try {
                if (event is MotionEvent) {
                    handled = handleMotionEvent(event)
                }
            } catch (t: Throwable) {
                DebugLog.e(SCOPE, "input callback failed", t)
                cancelGesture()
            } finally {
                finishInputEvent(event, handled)
            }
        }

        fun detach() {
            handler.removeCallbacks(longPressRunnable)
            detector.cancel()
            runCatching { dispose() }
                .onFailure { DebugLog.w(SCOPE, "failed to dispose input receiver", it) }
            runCatching { inputMonitor.dispose() }
                .onFailure { DebugLog.w(SCOPE, "failed to dispose input monitor", it) }
            DebugLog.i(SCOPE, "input monitor detached display=$displayId")
        }

        private fun handleMotionEvent(event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(event)
                MotionEvent.ACTION_MOVE -> onMove(event)
                MotionEvent.ACTION_UP -> onUp(event)
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_CANCEL -> {
                    val handled = pilfered
                    cancelGesture()
                    handled
                }
                else -> pilfered
            }
        }

        private fun onDown(event: MotionEvent): Boolean {
            handler.removeCallbacks(longPressRunnable)
            pilfered = false
            if (keyguardManager?.isKeyguardLocked == true ||
                !isInsideGestureHandle(event.x, event.y)
            ) {
                detector.cancel()
                return false
            }

            val doubleTapAction = configuredDoubleTapAction()
            val longPressAction = configuredLongPressAction()
            if (doubleTapAction == GestureBarAction.DISABLED) {
                detector.clearTapHistory()
            }
            val isSecondTap = detector.onDown(event.eventTime, event.x, event.y)
            if (isSecondTap && doubleTapAction != GestureBarAction.DISABLED) {
                pilfer("double tap")
            } else if (longPressAction != GestureBarAction.DISABLED) {
                handler.postDelayed(longPressRunnable, longPressTimeoutMs)
            }
            return pilfered
        }

        private fun onMove(event: MotionEvent): Boolean {
            if (!detector.onMove(event.x, event.y, event.pointerCount)) {
                handler.removeCallbacks(longPressRunnable)
            }
            return pilfered
        }

        private fun onUp(event: MotionEvent): Boolean {
            handler.removeCallbacks(longPressRunnable)
            val isDoubleTap = detector.onUp(event.eventTime, event.x, event.y)
            if (isDoubleTap && pilfered) {
                dispatchGesture(GestureBarGesture.DOUBLE_TAP)
            }
            return pilfered.also { pilfered = false }
        }

        private fun dispatchGesture(gesture: GestureBarGesture) {
            val action = when (gesture) {
                GestureBarGesture.LONG_PRESS -> configuredLongPressAction()
                GestureBarGesture.DOUBLE_TAP -> configuredDoubleTapAction()
            }
            if (action == GestureBarAction.DISABLED) return
            DebugLog.i(SCOPE, "dispatching $gesture action=$action")
            executeAction(action, navigationBar, navigationView)
        }

        private fun cancelGesture() {
            handler.removeCallbacks(longPressRunnable)
            detector.cancel()
            pilfered = false
        }

        private fun pilfer(reason: String): Boolean {
            if (pilfered) return true
            return runCatching {
                inputMonitor.pilferPointers()
                pilfered = true
                DebugLog.i(SCOPE, "claimed $reason on display=$displayId")
                true
            }.onFailure {
                DebugLog.w(SCOPE, "failed to claim $reason", it)
            }.getOrDefault(false)
        }

        private fun isInsideGestureHandle(x: Float, y: Float): Boolean {
            val density = navigationView.resources.displayMetrics.density
            val screenWidth = navigationView.resources.displayMetrics.widthPixels
            val screenHeight = navigationView.resources.displayMetrics.heightPixels
            val bounds = Rect()
            val handle = currentHandleView(navigationView)
            val hasHandleBounds = handle != null &&
                handle.getGlobalVisibleRect(bounds) &&
                !bounds.isEmpty
            return GestureBarHitRegion.contains(
                x = x,
                y = y,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = density,
                handleCenterX = bounds.exactCenterX().takeIf { hasHandleBounds },
                handleWidth = bounds.width().toFloat().takeIf { hasHandleBounds }
            )
        }
    }

    private object ContextualSearchInvoker {
        private const val ENTRY_POINT_NAV_HANDLE = 1

        fun start(): Boolean {
            return runCatching { startContextualSearchService() }
                .onFailure { DebugLog.w(SCOPE, "contextual_search service failed", it) }
                .getOrDefault(false)
        }

        private fun startContextualSearchService(): Boolean {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "contextual_search") as? IBinder ?: return false
            val stubClass = Class.forName(
                "android.app.contextualsearch.IContextualSearchManager\$Stub"
            )
            val service = stubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return false
            val interfaceClass = Class.forName(
                "android.app.contextualsearch.IContextualSearchManager"
            )
            interfaceClass.getMethod(
                "startContextualSearch",
                Int::class.javaPrimitiveType
            ).invoke(service, ENTRY_POINT_NAV_HANDLE)
            return true
        }
    }
}
