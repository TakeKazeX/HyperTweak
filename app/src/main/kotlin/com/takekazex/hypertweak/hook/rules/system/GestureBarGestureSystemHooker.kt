package com.takekazex.hypertweak.hook.rules.system

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.GESTURE_BAR_GESTURE_ACTION
import com.takekazex.hypertweak.hook.rules.systemui.GESTURE_BAR_GESTURE_DISPLAY_EXTRA
import com.takekazex.hypertweak.hook.rules.systemui.GESTURE_BAR_GESTURE_EXTRA
import com.takekazex.hypertweak.hook.rules.systemui.GESTURE_BAR_GESTURE_TOKEN_EXTRA
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarGesture
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarGestureDetector
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarHitRegion
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarLongPressTiming
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarMonitorNames
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarPilferGate
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarPilferTiming
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Delays Launcher ownership while a bottom-handle shortcut is still a candidate. */
object GestureBarGestureSystemHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "GestureBarBridge"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val LAUNCHER_PACKAGE = "com.miui.home"
    private const val INPUT_MANAGER_SERVICE_CLASS =
        "com.android.server.input.InputManagerService"
    private const val PER_USER_UID_RANGE = 100_000
    private const val POINTER_EVENT_DISPATCHER_CLASS =
        "com.android.server.wm.PointerEventDispatcher"

    private val sessionLock = Any()
    private val sessions = mutableMapOf<Int, DisplayGestureSession>()
    private val pendingLauncherPilfers = mutableMapOf<Int, MutableList<DeferredPilfer>>()
    private val launcherClaimLatches = mutableMapOf<Int, LauncherClaimLatch>()

    @Volatile
    private var pilferHandler: Handler? = null

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var displayManager: DisplayManager? = null

    @Volatile
    private var keyguardManager: KeyguardManager? = null

    private var inputManagerGlobal: Any? = null
    private var cancelCurrentTouchMethod: Method? = null
    private var inputEventDisplayIdMethod: Method? = null
    private var nativeInputManagerField: Field? = null
    private var nativePilferPointersMethod: Method? = null
    private var inputMonitorsField: Field? = null
    private var monitorWindowHandleField: Field? = null
    private var inputWindowHandleNameField: Field? = null
    private var inputWindowHandleDisplayIdField: Field? = null
    private var inputWindowHandleOwnerUidField: Field? = null

    @Volatile
    private var launcherAppId = -1

    @Volatile
    private var systemUiAppId = -1

    @Volatile
    private var checkServiceMethod: Method? = null
    private var navigationModeResourceId = 0

    override fun onPrepareHotReload() {
        val (sessionSnapshot, pendingSnapshot) = synchronized(sessionLock) {
            val currentSessions = sessions.values.toList()
            val pending = pendingLauncherPilfers.values.flatten()
            sessions.clear()
            pendingLauncherPilfers.clear()
            launcherClaimLatches.clear()
            currentSessions to pending
        }
        sessionSnapshot.forEach(DisplayGestureSession::cancel)
        replayPilfers(pendingSnapshot, "hook reload")
        pilferHandler = null
        systemContext = null
        displayManager = null
        keyguardManager = null
        inputManagerGlobal = null
        cancelCurrentTouchMethod = null
        inputEventDisplayIdMethod = null
        nativeInputManagerField = null
        nativePilferPointersMethod = null
        inputMonitorsField = null
        monitorWindowHandleField = null
        inputWindowHandleNameField = null
        inputWindowHandleDisplayIdField = null
        inputWindowHandleOwnerUidField = null
        launcherAppId = -1
        systemUiAppId = -1
        checkServiceMethod = null
        navigationModeResourceId = 0
    }

    @SuppressLint("BlockedPrivateApi")
    override fun onHook() {
        pilferHandler = Handler(Looper.getMainLooper())
        inputEventDisplayIdMethod = runCatching {
            InputEvent::class.java.getDeclaredMethod("getDisplayId")
                .apply { isAccessible = true }
        }.onFailure {
            DebugLog.w(SCOPE, "InputEvent display id is unavailable", it)
        }.getOrNull()
        checkServiceMethod = runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("checkService", String::class.java)
                .apply { isAccessible = true }
        }.onFailure {
            DebugLog.w(SCOPE, "system service readiness probe is unavailable", it)
        }.getOrNull()
        captureSystemContext(resolveCurrentSystemContext())
        hookSystemContextCreation()
        hookPointerEventStream()
        hookLauncherPilferGate()
    }

    private fun hookSystemContextCreation() {
        val systemServerClass = classLoader.loadClass("com.android.server.SystemServer")
        val contextField = findField(systemServerClass, "mSystemContext")
        systemServerClass.getDeclaredMethod("createSystemContext")
            .apply { isAccessible = true }
            .hook("gesture_bar_bridge_system_context") {
                after { param ->
                    HookFailurePolicy.open(SCOPE, "capture system context", Unit) {
                        captureSystemContext(contextField.get(param.thisObject) as? Context)
                    }
                }
            }
    }

    private fun hookPointerEventStream() {
        val dispatcherClass = classLoader.loadClass(POINTER_EVENT_DISPATCHER_CLASS)
        dispatcherClass.getDeclaredMethod("onInputEvent", InputEvent::class.java)
            .apply { isAccessible = true }
            .hook("gesture_bar_bridge_pointer_stream") {
                before { param ->
                    val event = param.args.getOrNull(0) as? MotionEvent ?: return@before
                    HookFailurePolicy.open(SCOPE, "observe pointer event", Unit) {
                        handleMotionEvent(event)
                    }
                }
            }
    }

    @SuppressLint("BlockedPrivateApi")
    private fun hookLauncherPilferGate() {
        val inputManagerServiceClass = runCatching {
            classLoader.loadClass(INPUT_MANAGER_SERVICE_CLASS)
        }.onFailure {
            DebugLog.w(SCOPE, "InputManagerService is unavailable", it)
        }.getOrNull() ?: return

        runCatching {
            val nativeField = findField(inputManagerServiceClass, "mNative")
            val nativeMethod = nativeField.type.getDeclaredMethod(
                "pilferPointers",
                IBinder::class.java
            ).apply { isAccessible = true }
            nativeInputManagerField = nativeField
            nativePilferPointersMethod = nativeMethod
        }.onFailure {
            nativeInputManagerField = null
            nativePilferPointersMethod = null
            DebugLog.w(SCOPE, "Launcher pilfer reflection is unavailable", it)
            return
        }

        runCatching {
            inputMonitorsField = findField(inputManagerServiceClass, "mInputMonitors")
            val monitorClass = classLoader.loadClass(
                "com.android.server.input.GestureMonitorSpyWindow"
            )
            monitorWindowHandleField = findField(monitorClass, "mWindowHandle")
            val inputWindowHandleClass = Class.forName("android.view.InputWindowHandle")
            inputWindowHandleNameField = inputWindowHandleClass.getDeclaredField("name")
                .apply { isAccessible = true }
            inputWindowHandleDisplayIdField = inputWindowHandleClass
                .getDeclaredField("displayId")
                .apply { isAccessible = true }
            inputWindowHandleOwnerUidField = inputWindowHandleClass
                .getDeclaredField("ownerUid")
                .apply { isAccessible = true }
        }.onFailure {
            inputMonitorsField = null
            monitorWindowHandleField = null
            inputWindowHandleNameField = null
            inputWindowHandleDisplayIdField = null
            inputWindowHandleOwnerUidField = null
            DebugLog.w(SCOPE, "gesture monitor names are unavailable", it)
        }

        runCatching {
            val hostClass = classLoader.loadClass(
                "$INPUT_MANAGER_SERVICE_CLASS\$InputMonitorHost"
            )
            val hostTokenField = findField(hostClass, "mInputChannelToken")
            val hostOuterField = findField(hostClass, "this\$0")
            hostClass.getDeclaredMethod("pilferPointers")
                .apply { isAccessible = true }
                .hook("gesture_bar_input_monitor_host_pilfer_gate") {
                    before { param ->
                        val shouldGate = HookFailurePolicy.open(
                            SCOPE,
                            "gate InputMonitorHost pilfer",
                            false
                        ) {
                            val host = param.thisObject
                            val inputManagerService = hostOuterField.get(host)
                                ?: return@open false
                            val token = hostTokenField.get(host) as? IBinder
                                ?: return@open false
                            handlePilferRequest(inputManagerService, token)
                        }
                        if (shouldGate) param.result = null
                    }
                }
        }.onFailure {
            DebugLog.w(SCOPE, "InputMonitorHost pilfer gate is unavailable", it)
        }

        // Keep the public Binder path for platform components that pilfer by channel token.
        runCatching {
            inputManagerServiceClass.getDeclaredMethod(
                "pilferPointers",
                IBinder::class.java
            ).apply { isAccessible = true }.hook("gesture_bar_token_pilfer_gate") {
                before { param ->
                    val shouldDefer = HookFailurePolicy.open(
                        SCOPE,
                        "gate token pilfer",
                        false
                    ) {
                        val token = param.args.getOrNull(0) as? IBinder ?: return@open false
                        handlePilferRequest(param.thisObject, token)
                    }
                    if (shouldDefer) param.result = null
                }
            }
        }.onFailure {
            DebugLog.w(SCOPE, "token pilfer compatibility gate is unavailable", it)
        }
    }

    private fun handlePilferRequest(inputManagerService: Any, token: IBinder): Boolean {
        val callerAppId = appIdForUid(Binder.getCallingUid())
        val monitor = monitorInfoFor(inputManagerService, token) ?: return false
        if (callerAppId == resolvePackageAppId(LAUNCHER_PACKAGE) &&
            appIdForUid(monitor.ownerUid) == callerAppId &&
            GestureBarMonitorNames.isLauncherSwipeUp(monitor.name)
        ) {
            val nativeService = nativeInputManagerField?.get(inputManagerService) ?: return false
            val nativeMethod = nativePilferPointersMethod ?: return false
            val deferred = DeferredPilfer(
                nativeService = nativeService,
                token = token,
                nativeMethod = nativeMethod,
                requestedAt = SystemClock.uptimeMillis()
            )
            if (isLauncherClaimActive(monitor.displayId, deferred.requestedAt)) {
                DebugLog.d(SCOPE, "discarded Launcher pilfer after SystemUI claim")
                return true
            }
            if (deferLauncherPilfer(deferred, monitor.displayId)) {
                DebugLog.i(
                    SCOPE,
                    "gated Launcher pilfer monitor=${monitor.name} display=${monitor.displayId}"
                )
                return true
            }
            if (holdLauncherPilfer(deferred, monitor.displayId)) {
                DebugLog.d(
                    SCOPE,
                    "held early Launcher pilfer monitor=${monitor.name} " +
                        "display=${monitor.displayId}"
                )
                return true
            }
            return false
        }

        if (callerAppId == resolvePackageAppId(SYSTEM_UI_PACKAGE) &&
            appIdForUid(monitor.ownerUid) == callerAppId &&
            GestureBarMonitorNames.isHyperTweakGestureBar(monitor.name)
        ) {
            if (consumeLauncherPilferForSystemUiClaim(monitor.displayId)) {
                DebugLog.i(
                    SCOPE,
                    "SystemUI claimed gesture monitor=${monitor.name} display=${monitor.displayId}"
                )
            }
        }
        return false
    }

    private fun monitorInfoFor(inputManagerService: Any, token: IBinder): GestureMonitorInfo? {
        return runCatching {
            val monitors = inputMonitorsField?.get(inputManagerService) as? Map<*, *>
                ?: return@runCatching null
            val monitor = synchronized(monitors) { monitors[token] }
                ?: return@runCatching null
            val windowHandle = monitorWindowHandleField?.get(monitor)
                ?: return@runCatching null
            val name = inputWindowHandleNameField?.get(windowHandle) as? String
                ?: return@runCatching null
            val displayId = inputWindowHandleDisplayIdField?.getInt(windowHandle)
                ?: return@runCatching null
            val ownerUid = inputWindowHandleOwnerUidField?.getInt(windowHandle)
                ?: return@runCatching null
            GestureMonitorInfo(name, displayId, ownerUid)
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve gesture monitor", it)
        }.getOrNull()
    }

    private fun deferLauncherPilfer(request: DeferredPilfer, displayId: Int): Boolean {
        val session = synchronized(sessionLock) { sessions[displayId] } ?: return false
        return session.tryDeferPilfer(request, request.requestedAt)
    }

    private fun holdLauncherPilfer(request: DeferredPilfer, displayId: Int): Boolean {
        val handler = pilferHandler ?: return false
        return synchronized(sessionLock) {
            val pending = pendingLauncherPilfers.getOrPut(displayId) { mutableListOf() }
            if (pending.any { it.token == request.token }) return@synchronized true
            pending += request
            val posted = handler.postDelayed(
                {
                    HookFailurePolicy.open(SCOPE, "early Launcher pilfer timeout", Unit) {
                        expirePendingLauncherPilfer(displayId, request)
                    }
                },
                GestureBarPilferTiming.PRE_CANDIDATE_HOLD_MS
            )
            if (!posted) {
                pending.remove(request)
                if (pending.isEmpty()) pendingLauncherPilfers.remove(displayId)
            }
            posted
        }
    }

    private fun expirePendingLauncherPilfer(displayId: Int, expected: DeferredPilfer) {
        val request = synchronized(sessionLock) {
            val pending = pendingLauncherPilfers[displayId] ?: return
            val index = pending.indexOf(expected)
            if (index < 0) return
            pending.removeAt(index).also {
                if (pending.isEmpty()) pendingLauncherPilfers.remove(displayId)
            }
        }
        if (isLauncherClaimActive(displayId, SystemClock.uptimeMillis())) return
        if (deferLauncherPilfer(request, displayId)) {
            DebugLog.d(SCOPE, "associated delayed Launcher pilfer display=$displayId")
            return
        }
        if (isLauncherClaimActive(displayId, SystemClock.uptimeMillis())) return
        replayPilfers(listOf(request), "no bottom candidate within hold window")
    }

    private fun adoptPendingLauncherPilfers(
        displayId: Int,
        session: DisplayGestureSession
    ) {
        val pending = drainPendingLauncherPilfers(displayId)
        if (pending.isEmpty()) return
        val rejected = pending.filterNot { session.tryDeferPilfer(it, it.requestedAt) }
        val adoptedCount = pending.size - rejected.size
        if (adoptedCount > 0) {
            DebugLog.i(SCOPE, "gated $adoptedCount early Launcher pilfer(s) display=$displayId")
        }
        if (!isLauncherClaimActive(displayId, SystemClock.uptimeMillis())) {
            replayPilfers(rejected, "early request did not match bottom candidate")
        }
    }

    private fun drainPendingLauncherPilfers(displayId: Int): List<DeferredPilfer> =
        synchronized(sessionLock) {
            pendingLauncherPilfers.remove(displayId)?.toList().orEmpty()
        }

    private fun releasePendingLauncherPilfers(displayId: Int, reason: String) {
        replayPilfers(drainPendingLauncherPilfers(displayId), reason)
    }

    private fun consumeLauncherPilferForSystemUiClaim(displayId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val (session, hadPending) = synchronized(sessionLock) {
            launcherClaimLatches[displayId] = LauncherClaimLatch(
                claimedAt = now,
                expiresAt = now + GestureBarPilferTiming.SYSTEM_UI_CLAIM_LATCH_MS
            )
            sessions[displayId] to (pendingLauncherPilfers.remove(displayId)?.isNotEmpty() == true)
        }
        val activeClaimed = session?.consumeForSystemUiClaim() == true
        return activeClaimed || hadPending
    }

    private fun isLauncherClaimActive(displayId: Int, now: Long): Boolean =
        synchronized(sessionLock) {
            val latch = launcherClaimLatches[displayId] ?: return@synchronized false
            if (now <= latch.expiresAt) return@synchronized true
            launcherClaimLatches.remove(displayId)
            false
        }

    private fun clearLauncherClaimForDown(displayId: Int, eventTime: Long) {
        synchronized(sessionLock) {
            val latch = launcherClaimLatches[displayId] ?: return
            if (eventTime > latch.claimedAt || SystemClock.uptimeMillis() > latch.expiresAt) {
                launcherClaimLatches.remove(displayId)
            }
        }
    }

    private fun clearLauncherClaim(displayId: Int) {
        synchronized(sessionLock) { launcherClaimLatches.remove(displayId) }
    }

    @Synchronized
    private fun resolvePackageAppId(packageName: String): Int {
        val cached = when (packageName) {
            LAUNCHER_PACKAGE -> launcherAppId
            SYSTEM_UI_PACKAGE -> systemUiAppId
            else -> -1
        }
        if (cached >= 0) return cached

        val context = systemContext ?: return -1
        val resolved = runCatching {
            appIdForUid(
                context.packageManager.getPackageUid(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            )
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve app id for $packageName", it)
        }.getOrDefault(-1)
        when (packageName) {
            LAUNCHER_PACKAGE -> launcherAppId = resolved
            SYSTEM_UI_PACKAGE -> systemUiAppId = resolved
        }
        return resolved
    }

    private fun appIdForUid(uid: Int): Int = uid % PER_USER_UID_RANGE

    private fun replayPilfers(requests: List<DeferredPilfer>, reason: String) {
        if (requests.isEmpty()) return
        requests.forEach { request ->
            runCatching {
                request.nativeMethod.invoke(request.nativeService, request.token)
            }.onFailure {
                DebugLog.w(SCOPE, "failed to replay Launcher pilfer reason=$reason", it)
            }
        }
        DebugLog.d(SCOPE, "replayed ${requests.size} Launcher pilfer request(s) reason=$reason")
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) !=
            InputDevice.SOURCE_TOUCHSCREEN
        ) {
            return
        }

        val displayId = displayIdFor(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearLauncherClaimForDown(displayId, event.eventTime)
            val longPressEnabled = isGestureConfigured(GestureBarGesture.LONG_PRESS)
            val doubleTapEnabled = isGestureConfigured(GestureBarGesture.DOUBLE_TAP)
            if ((!longPressEnabled && !doubleTapEnabled) || !isGesturalNavigationMode()) {
                synchronized(sessionLock) { sessions[displayId] }?.cancel()
                releasePendingLauncherPilfers(displayId, "gesture shortcuts unavailable")
                return
            }
            val session = sessionForDisplay(displayId) ?: run {
                releasePendingLauncherPilfers(displayId, "display session unavailable")
                return
            }
            val eligible = isDeviceUnlocked() &&
                session.isInsideGestureHandle(event.x, event.y)
            if (!eligible) {
                session.cancel()
                releasePendingLauncherPilfers(displayId, "pointer outside bottom handle")
                return
            }
            session.onDown(
                eventTime = event.eventTime,
                downTime = event.downTime,
                x = event.x,
                y = event.y,
                longPressEnabled = longPressEnabled,
                doubleTapEnabled = doubleTapEnabled
            )
            DebugLog.d(SCOPE, "candidate started display=$displayId downTime=${event.downTime}")
            adoptPendingLauncherPilfers(displayId, session)
            return
        }

        val session = synchronized(sessionLock) { sessions[displayId] } ?: return

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> session.onMove(event.x, event.y, event.pointerCount)
            MotionEvent.ACTION_UP -> {
                session.onUp(event.eventTime, event.x, event.y)
                clearLauncherClaim(displayId)
            }
            MotionEvent.ACTION_POINTER_DOWN -> session.cancel()
            MotionEvent.ACTION_CANCEL -> {
                session.cancel()
                clearLauncherClaim(displayId)
            }
        }
    }

    private fun sessionForDisplay(displayId: Int): DisplayGestureSession? {
        synchronized(sessionLock) {
            sessions[displayId]?.let { return it }
        }

        val context = systemContext ?: return null
        val manager = resolveDisplayManager(context) ?: return null
        val display = runCatching { manager.getDisplay(displayId) }
            .onFailure { DebugLog.w(SCOPE, "failed to resolve display=$displayId", it) }
            .getOrNull() ?: return null
        val displayContext = context.createDisplayContext(display)
        val viewConfiguration = ViewConfiguration.get(displayContext)
        val session = DisplayGestureSession(
            displayId = displayId,
            displayContext = displayContext,
            handler = Handler(Looper.getMainLooper()),
            moveSlop = viewConfiguration.scaledTouchSlop.toFloat(),
            doubleTapSlop = viewConfiguration.scaledDoubleTapSlop.toFloat(),
            doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
            longPressTimeoutMs = GestureBarLongPressTiming.recognitionTimeout(
                ViewConfiguration.getLongPressTimeout().toLong()
            ),
            onGesture = ::dispatchGesture,
            onReplayPilfers = ::replayPilfers
        )
        synchronized(sessionLock) {
            return sessions.getOrPut(displayId) { session }
        }
    }

    private fun dispatchGesture(
        gesture: GestureBarGesture,
        displayId: Int,
        gestureToken: Long
    ): Boolean {
        if (!isGestureConfigured(gesture)) return false
        val context = systemContext ?: return false

        cancelCurrentTouch()

        val intent = Intent(GESTURE_BAR_GESTURE_ACTION)
            .setPackage(SYSTEM_UI_PACKAGE)
            .putExtra(GESTURE_BAR_GESTURE_EXTRA, gesture.persistedId)
            .putExtra(GESTURE_BAR_GESTURE_DISPLAY_EXTRA, displayId)
            .putExtra(GESTURE_BAR_GESTURE_TOKEN_EXTRA, gestureToken)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND)
        return runCatching {
            context.sendBroadcast(intent)
            DebugLog.i(SCOPE, "dispatched $gesture for display=$displayId")
            true
        }.onFailure {
            DebugLog.w(SCOPE, "failed to dispatch $gesture", it)
        }.getOrDefault(false)
    }

    private fun isGestureConfigured(gesture: GestureBarGesture): Boolean {
        if (!Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false)) {
            return false
        }
        val action = when (gesture) {
            GestureBarGesture.LONG_PRESS -> GestureBarAction.fromPersistedId(
                Preferences.getInt(
                    Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                    GestureBarAction.DEFAULT_ASSISTANT.persistedId
                )
            )

            GestureBarGesture.DOUBLE_TAP -> GestureBarAction.fromPersistedId(
                Preferences.getInt(
                    Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                    GestureBarAction.CIRCLE_TO_SEARCH.persistedId
                )
            )
        }
        return action != GestureBarAction.DISABLED
    }

    private fun captureSystemContext(context: Context?) {
        if (context == null) return
        systemContext = context
        // createSystemContext runs before display/trust services are published. Resolving them
        // here poisons ContextImpl's service cache with partially initialized managers.
        navigationModeResourceId = context.resources.getIdentifier(
            "config_navBarInteractionMode",
            "integer",
            "android"
        )
        DebugLog.i(SCOPE, "system context ready")
    }

    private fun resolveDisplayManager(context: Context): DisplayManager? {
        displayManager?.let { return it }
        if (!isSystemServicePublished(Context.DISPLAY_SERVICE)) return null
        return runCatching {
            context.getSystemService(DisplayManager::class.java)
        }.onFailure {
            DebugLog.w(SCOPE, "display service is unavailable", it)
        }.getOrNull()?.also { displayManager = it }
    }

    private fun isDeviceUnlocked(): Boolean {
        val context = systemContext ?: return false
        val manager = keyguardManager ?: run {
            if (!isSystemServicePublished(Context.WINDOW_SERVICE) ||
                !isSystemServicePublished(Context.NOTIFICATION_SERVICE) ||
                !isSystemServicePublished("trust")
            ) {
                return false
            }
            runCatching {
                context.getSystemService(KeyguardManager::class.java)
            }.onFailure {
                DebugLog.w(SCOPE, "keyguard service is unavailable", it)
            }.getOrNull()?.also { keyguardManager = it } ?: return false
        }
        return runCatching { !manager.isKeyguardLocked }
            .onFailure { DebugLog.w(SCOPE, "failed to query keyguard state", it) }
            .getOrDefault(false)
    }

    private fun isSystemServicePublished(name: String): Boolean {
        val method = checkServiceMethod ?: return false
        return try {
            method.invoke(null, name) != null
        } catch (t: Throwable) {
            checkServiceMethod = null
            DebugLog.w(SCOPE, "failed to probe service=$name; disabling system bridge", t)
            false
        }
    }

    private fun resolveCurrentSystemContext(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null) ?: return@runCatching null
            activityThreadClass.getDeclaredMethod("getSystemContext")
                .invoke(activityThread) as? Context
        }.onFailure {
            DebugLog.w(SCOPE, "system context is not ready yet", it)
        }.getOrNull()
    }

    private fun resolveInputManagerGlobal() {
        runCatching {
            val type = Class.forName("android.hardware.input.InputManagerGlobal")
            inputManagerGlobal = type.getDeclaredMethod("getInstance").invoke(null)
            cancelCurrentTouchMethod = type.getDeclaredMethod("cancelCurrentTouch")
                .apply { isAccessible = true }
        }.onFailure {
            inputManagerGlobal = null
            cancelCurrentTouchMethod = null
            DebugLog.w(SCOPE, "cancelCurrentTouch is unavailable", it)
        }
    }

    private fun cancelCurrentTouch() {
        if (inputManagerGlobal == null || cancelCurrentTouchMethod == null) {
            resolveInputManagerGlobal()
        }
        val instance = inputManagerGlobal ?: return
        val method = cancelCurrentTouchMethod ?: return
        runCatching { method.invoke(instance) }
            .onFailure { DebugLog.w(SCOPE, "failed to cancel Launcher-owned touch", it) }
    }

    private fun displayIdFor(event: InputEvent): Int {
        val method = inputEventDisplayIdMethod ?: return 0
        return try {
            (method.invoke(event) as? Int)?.takeIf { it >= 0 } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun isGesturalNavigationMode(): Boolean {
        val context = systemContext ?: return false
        val resourceId = navigationModeResourceId
        if (resourceId == 0) return false
        return runCatching { context.resources.getInteger(resourceId) == 2 }
            .getOrDefault(false)
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

    private class DeferredPilfer(
        val nativeService: Any,
        val token: IBinder,
        val nativeMethod: Method,
        val requestedAt: Long
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || other is DeferredPilfer && token == other.token

        override fun hashCode(): Int = token.hashCode()
    }

    private data class GestureMonitorInfo(
        val name: String,
        val displayId: Int,
        val ownerUid: Int
    )

    private data class LauncherClaimLatch(
        val claimedAt: Long,
        val expiresAt: Long
    )

    private class DisplayGestureSession(
        val displayId: Int,
        private val displayContext: Context,
        private val handler: Handler,
        moveSlop: Float,
        doubleTapSlop: Float,
        doubleTapTimeoutMs: Long,
        private val longPressTimeoutMs: Long,
        private val onGesture: (GestureBarGesture, Int, Long) -> Boolean,
        private val onReplayPilfers: (List<DeferredPilfer>, String) -> Unit
    ) {
        private val stateLock = Any()
        private val detector = GestureBarGestureDetector(
            moveSlop = moveSlop,
            doubleTapSlop = doubleTapSlop,
            doubleTapTimeoutMs = doubleTapTimeoutMs
        )
        private var longPressEnabled = false
        private var doubleTapEnabled = false
        private var gestureToken = 0L
        private var ownershipClaimedAt: Long? = null
        private val pilferGate = GestureBarPilferGate<DeferredPilfer>()

        private val longPressRunnable: Runnable by lazy {
            Runnable {
                HookFailurePolicy.open(SCOPE, "system long press callback", Unit) {
                    var recognizedToken: Long? = null
                    var deferredRequests = emptyList<DeferredPilfer>()
                    synchronized(stateLock) {
                        if (longPressEnabled && detector.onLongPressTimeout()) {
                            handler.removeCallbacks(pilferFailOpenRunnable)
                            longPressEnabled = false
                            doubleTapEnabled = false
                            ownershipClaimedAt = pilferGate.candidateStartedAtOrNull()
                            deferredRequests = pilferGate.releaseCandidate()
                            recognizedToken = gestureToken
                            gestureToken = 0L
                        }
                    }
                    recognizedToken?.let {
                        val dispatched = HookFailurePolicy.open(
                            SCOPE,
                            "dispatch recognized long press",
                            false
                        ) {
                            onGesture(GestureBarGesture.LONG_PRESS, displayId, it)
                        }
                        if (!dispatched) {
                            synchronized(stateLock) { ownershipClaimedAt = null }
                            onReplayPilfers(deferredRequests, "long press dispatch unavailable")
                        }
                    }
                }
            }
        }

        private val pilferFailOpenRunnable: Runnable by lazy {
            Runnable {
                HookFailurePolicy.open(SCOPE, "Launcher pilfer fail-open callback", Unit) {
                    val requests = synchronized(stateLock) {
                        if (!pilferGate.hasDeferredRequests()) return@open
                        handler.removeCallbacks(longPressRunnable)
                        detector.cancel()
                        longPressEnabled = false
                        doubleTapEnabled = false
                        gestureToken = 0L
                        ownershipClaimedAt = null
                        pilferGate.releaseCandidate()
                    }
                    onReplayPilfers(requests, "fail-open timeout")
                }
            }
        }

        fun onDown(
            eventTime: Long,
            downTime: Long,
            x: Float,
            y: Float,
            longPressEnabled: Boolean,
            doubleTapEnabled: Boolean
        ) {
            val staleRequests = synchronized(stateLock) {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(pilferFailOpenRunnable)
                val stale = pilferGate.beginCandidate(eventTime)
                this.longPressEnabled = longPressEnabled
                this.doubleTapEnabled = doubleTapEnabled
                gestureToken = downTime
                ownershipClaimedAt = null
                if (!doubleTapEnabled) detector.clearTapHistory()
                val isSecondTap = detector.onDown(eventTime, x, y)
                if (!isSecondTap && longPressEnabled) {
                    handler.postDelayed(longPressRunnable, longPressTimeoutMs)
                }
                stale
            }
            onReplayPilfers(staleRequests, "new pointer down")
        }

        fun onMove(x: Float, y: Float, pointerCount: Int) {
            val requests = synchronized(stateLock) {
                if (ownershipClaimedAt != null) return
                if (detector.onMove(x, y, pointerCount)) return
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(pilferFailOpenRunnable)
                longPressEnabled = false
                doubleTapEnabled = false
                gestureToken = 0L
                pilferGate.releaseCandidate()
            }
            onReplayPilfers(requests, "gesture moved beyond slop")
        }

        fun onUp(eventTime: Long, x: Float, y: Float) {
            var recognizedToken: Long? = null
            val requests = synchronized(stateLock) {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(pilferFailOpenRunnable)
                longPressEnabled = false
                if (ownershipClaimedAt != null) {
                    ownershipClaimedAt = null
                    detector.cancel()
                    doubleTapEnabled = false
                    gestureToken = 0L
                    pilferGate.consumeCandidate()
                    return@synchronized emptyList()
                }
                val recognized = detector.onUp(eventTime, x, y) && doubleTapEnabled
                if (recognized) {
                    ownershipClaimedAt = pilferGate.candidateStartedAtOrNull()
                }
                val pending = if (recognized) {
                    recognizedToken = gestureToken
                    pilferGate.releaseCandidate()
                } else {
                    pilferGate.releaseCandidate()
                }
                doubleTapEnabled = false
                gestureToken = 0L
                pending
            }
            recognizedToken?.let {
                val dispatched = HookFailurePolicy.open(
                    SCOPE,
                    "dispatch recognized double tap",
                    false
                ) {
                    onGesture(GestureBarGesture.DOUBLE_TAP, displayId, it)
                }
                if (!dispatched) {
                    synchronized(stateLock) { ownershipClaimedAt = null }
                    onReplayPilfers(requests, "double tap dispatch unavailable")
                }
            } ?: run {
                onReplayPilfers(requests, "pointer up")
            }
        }

        fun cancel() {
            val requests = synchronized(stateLock) {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(pilferFailOpenRunnable)
                detector.cancel()
                longPressEnabled = false
                doubleTapEnabled = false
                gestureToken = 0L
                ownershipClaimedAt = null
                pilferGate.releaseCandidate()
            }
            onReplayPilfers(requests, "pointer canceled")
        }

        fun tryDeferPilfer(request: DeferredPilfer, requestTime: Long): Boolean {
            var requestsToReplay = emptyList<DeferredPilfer>()
            val deferred = synchronized(stateLock) {
                ownershipClaimedAt?.let { claimedAt ->
                    val claimDeadline = GestureBarPilferTiming.failOpenDeadline(
                        claimedAt,
                        longPressTimeoutMs
                    )
                    if (requestTime <= claimDeadline) return@synchronized true
                    ownershipClaimedAt = null
                }
                if (!pilferGate.tryDefer(request, requestTime)) return false
                val downEventTime = pilferGate.candidateStartedAtOrNull() ?: return false
                handler.removeCallbacks(pilferFailOpenRunnable)
                val posted = handler.postAtTime(
                    pilferFailOpenRunnable,
                    GestureBarPilferTiming.failOpenDeadline(
                        downEventTime,
                        longPressTimeoutMs
                    )
                )
                if (!posted) {
                    handler.removeCallbacks(longPressRunnable)
                    detector.cancel()
                    longPressEnabled = false
                    doubleTapEnabled = false
                    gestureToken = 0L
                    ownershipClaimedAt = null
                    requestsToReplay = pilferGate.releaseCandidate()
                        .filterNot { it == request }
                }
                posted
            }
            onReplayPilfers(requestsToReplay, "failed to schedule fail-open")
            return deferred
        }

        fun consumeForSystemUiClaim(): Boolean = synchronized(stateLock) {
            if (ownershipClaimedAt != null) return true
            val candidateStartedAt = pilferGate.candidateStartedAtOrNull() ?: return false
            handler.removeCallbacks(longPressRunnable)
            handler.removeCallbacks(pilferFailOpenRunnable)
            detector.cancel()
            longPressEnabled = false
            doubleTapEnabled = false
            gestureToken = 0L
            ownershipClaimedAt = candidateStartedAt
            pilferGate.consumeCandidate()
            true
        }

        fun isInsideGestureHandle(x: Float, y: Float): Boolean {
            val metrics = displayContext.resources.displayMetrics
            return GestureBarHitRegion.contains(
                x = x,
                y = y,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                density = metrics.density,
                handleCenterX = null,
                handleWidth = null
            )
        }
    }
}
