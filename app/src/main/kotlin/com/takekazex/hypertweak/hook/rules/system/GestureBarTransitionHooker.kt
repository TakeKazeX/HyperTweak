package com.takekazex.hypertweak.hook.rules.system

import android.content.res.Resources
import android.os.IBinder
import android.view.SurfaceControl
import android.window.TransitionInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Keeps the gesture bar attached to the app while the recents transition is playing
 * (`config_attachNavBarToAppDuringTransition`, 小白条跟手上滑).
 *
 * HyperOS ships two overlays that map the same framework bools into `framework-res`:
 * `com.android.systemui.gesture.line.overlay` (the stock gesture line; this build sets
 * `config_attachNavBarToAppDuringTransition = false`) and
 * `com.android.internal.systemui.navbar.gestural` (AOSP gestural; `true`). The
 * gesture-line overlay is the one that is enabled here, so the platform keeps the navigation bar as
 * a separate layer and it does not travel with the swipe-up animation.
 *
 * `DisplayPolicy#onConfigurationChanged` caches the resource into
 * `mShouldAttachNavBarToAppDuringTransition` and `Transition#handleLegacyRecentsStartBehavior`
 * (confirmed live in logcat as "handleLegacyRecentsStartBehavior recentsTask=…") asks
 * `DisplayPolicy#shouldAttachNavBarToAppDuringTransition()` once per recents transition.
 * Hooking that getter, rather than the cached field, is what makes the switch live: the value is
 * re-read on every transition, so no configuration change, SystemUI restart or reboot is needed.
 * The resource cache is left untouched so disabling restores the ROM value immediately.
 * The compiled transition caller is deoptimized as well as the getter to undo inlining.
 *
 * The native resource default, no-navigation-bar and tiny-taskbar guards are preserved.
 * Reading the native resource also repairs stale caches from older module versions.
 * Restoring the navigation bar afterwards stays with the platform
 * (`Transition#legacyRestoreNavigationBarFromApp`), so no detach handshake is reimplemented.
 *
 * The switch reaches this process through the shared runtime-settings snapshot
 * (`RuntimeSettingsGroups.GESTURE_BAR`), so toggling it in the app applies to the next
 * swipe-up without restarting anything. API-102 replacement also installs this hook into an
 * existing system_server; replacing only SystemUI cannot update that process.
 */
object GestureBarTransitionHooker : StaticHooker() {
    override val hookerName = "GestureBarTransition"
    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "GestureBarTransition"
    private const val DISPLAY_POLICY = "com.android.server.wm.DisplayPolicy"
    private const val SHOULD_ATTACH = "shouldAttachNavBarToAppDuringTransition"

    override fun onHook() {
        hookDisplayPolicyGetter()
        hookNativeDetach()
    }

    // display -> [native transition, display, navigation token, app task, detach deferred]
    private val sessions = java.util.concurrent.ConcurrentHashMap<Int, Array<Any>>()
    private val detachRequest = ThreadLocal<Boolean>()
    private var transitionContract: TransitionContract? = null
    private val recovery by lazy { SystemServerStartupRetry(TAG, action = ::releaseDisabledSessions) }

    private fun settings() = GestureBarTransitionSettings(attachEnabled(),
        Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR_RECENTS, false))

    override fun saveHotReloadState(): Any = sessions.toMap()
    override fun restoreHotReloadState(state: Any?) {
        (state as? Map<*, *>)?.forEach { (id, record) ->
            if (id is Int && record is Array<*> && record.size == 5 && record.all { it != null }) {
                sessions[id] = record.filterNotNull().toTypedArray()
            }
        }
    }
    override fun recoverHotReloadHosts() = refreshSettings()
    override fun onPrepareHotReload() {
        recovery.cancel()
        sessions.clear()
        detachRequest.remove()
        transitionContract = null
    }
    fun refreshSettings() = recovery.schedule()

    private fun releaseDisabledSessions(): Boolean {
        if (settings().hideDisplayCopy) return true
        val c = transitionContract ?: return true
        sessions.entries.toList().forEach { (id, record) ->
            runCatching {
                val wm = c.wm.get(record[1]) ?: return@runCatching
                val lock = c.lock.get(wm) ?: return@runCatching
                synchronized(lock) {
                    // Not in the detach-request scope: the original restore must run in full.
                    c.restore.invoke(record[0])
                    sessions.remove(id, record)
                }
            }.onFailure { DebugLog.w(TAG, "failed to release deferred navigation attachment", it) }
        }
        return true
    }

    private fun hookNativeDetach() {
        val c = runCatching { TransitionContract(classLoader) }.onFailure {
            DebugLog.hookFailed(TAG, "Transition navigation attachment contract", it)
        }.getOrNull() ?: return
        transitionContract = c
        c.callers.forEach(::deoptimize)
        c.detach.hook {
            before { detachRequest.set(true) }
            after { detachRequest.remove() }
        }
        c.start.hook {
            after { param ->
                if (!settings().hideDisplayCopy) return@after
                runCatching {
                    val dc = param.args[0] ?: return@runCatching
                    val info = param.args[1] as? TransitionInfo ?: return@runCatching
                    val id = c.displayId.getInt(dc)
                    val controller = c.controller.get(param.thisObject) ?: return@runCatching
                    val policy = c.policy.invoke(dc) ?: return@runCatching
                    if (c.shouldAttach.invoke(policy) != true || c.rotation.invoke(dc) != null) return@runCatching
                    val nav = c.navigation.invoke(policy) ?: return@runCatching
                    val token = c.token.get(nav) ?: return@runCatching
                    val candidates = info.changes.filter { change ->
                        change.taskInfo?.let { c.taskDisplay.getInt(it) == id && c.activityType.invoke(it) == 1 } == true
                    }
                    val opening = candidates.firstOrNull { it.mode == 1 || it.mode == 3 }
                    val returning = sessions[id]?.get(4) == true && opening != null
                    val closing = candidates.firstOrNull { it.mode == 2 || it.mode == 4 }
                    val attached = c.attached.getBoolean(controller) && c.recentsDisplay.getInt(param.thisObject) == id
                    val change = if (returning) opening else if (attached) closing else null
                    val binder = change?.let { c.container.invoke(it) }?.let { c.asBinder.invoke(it) } as? IBinder
                        ?: return@runCatching
                    val task = c.fromBinder.invoke(null, binder) ?: return@runCatching
                    if (c.multiWindow.invoke(task) == true) return@runCatching
                    if (returning) {
                        // Same token and native parent/layer operations as handleLegacyRecentsStartBehavior.
                        val surface = c.surface.invoke(token) as? SurfaceControl ?: return@runCatching
                        val parent = c.surface.invoke(task) as? SurfaceControl ?: return@runCatching
                        if (!surface.isValid || !parent.isValid) return@runCatching
                        val t = c.pending.invoke(token) as? SurfaceControl.Transaction ?: return@runCatching
                        c.cancel.invoke(token)
                        t.reparent(surface, parent)
                        c.show.invoke(t, surface)
                        val ime = c.ime.invoke(dc)
                        val imeSurface = ime?.takeIf { c.visible.invoke(it) == true }
                            ?.let { c.surface.invoke(it) as? SurfaceControl }?.takeIf { it.isValid }
                        if (imeSurface != null) c.relative.invoke(t, surface, imeSurface, 1)
                        else t.setLayer(surface, Int.MAX_VALUE)
                        c.attached.setBoolean(controller, true)
                        c.recentsDisplay.setInt(param.thisObject, id)
                        c.luma.invoke(param.thisObject, dc, false)
                        DebugLog.i(TAG, "returning navigation token attached to opening task display=$id")
                    }
                    sessions[id] = arrayOf(param.thisObject, dc, token, task, false)
                }.onFailure { DebugLog.w(TAG, "failed to follow native task transition", it) }
            }
        }
        c.restore.hook {
            before { param ->
                if (!settings().hideDisplayCopy) return@before
                runCatching {
                    val controller = c.controller.get(param.thisObject) ?: return@runCatching
                    if (!c.attached.getBoolean(controller)) return@runCatching
                    val id = c.recentsDisplay.getInt(param.thisObject)
                    val record = sessions[id] ?: return@runCatching
                    when (gestureBarRestoreAction(detachRequest.get() == true, record[0] === param.thisObject,
                        record[4] == true, c.controller.get(record[0]) === controller)) {
                        GestureBarRestoreAction.NATIVE -> return@runCatching
                        GestureBarRestoreAction.IGNORE_RETIRED -> {
                            // A delayed old owner must not steal the newly opening app's token.
                            param.result = null
                            return@runCatching
                        }
                        GestureBarRestoreAction.DEFER -> Unit
                    }
                    // Keep the native sampling notification at its original point. SystemUI uses
                    // this paired false/true notification to fade the real handle only now.
                    c.luma.invoke(param.thisObject, record[1], true)
                    record[4] = true
                    param.result = null
                    DebugLog.i(TAG, "native detach deferred; app handle fade requested display=$id")
                }.onFailure { DebugLog.w(TAG, "failed to defer navigation detach", it) }
            }
            after { param ->
                if (detachRequest.get() == true) return@after
                runCatching {
                    val targets = c.targets.get(param.thisObject) as? List<*>
                    val home = targets?.firstNotNullOfOrNull { target ->
                        target?.let { c.targetContainer.get(it) }?.let { c.asTask.invoke(it) }
                            ?.takeIf { c.isHome.invoke(it) == true }
                    }
                    val toHome = home != null && c.visibleRequested.invoke(home) == true
                    sessions.entries.removeIf { (_, record) ->
                        record[0] === param.thisObject && (record[4] != true || !toHome)
                    }
                }.onFailure { DebugLog.w(TAG, "failed to retire navigation attachment", it) }
            }
        }
        DebugLog.i(TAG, "native Transition detach hooks installed")
    }

    private class TransitionContract(private val cl: ClassLoader) {
        private fun type(name: String) = cl.loadClass(name)
        private fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }
        private fun Class<*>.method(name: String, vararg args: Class<*>) = getDeclaredMethod(name, *args).apply { isAccessible = true }
        private val tr = type("com.android.server.wm.Transition")
        private val dc = type("com.android.server.wm.DisplayContent")
        private val wc = type("com.android.server.wm.WindowContainer")
        private val tc = type("com.android.server.wm.TransitionController")
        private val policyClass = type(DISPLAY_POLICY)
        val start = tr.method("handleLegacyRecentsStartBehavior", dc, TransitionInfo::class.java)
        val restore = tr.method("legacyRestoreNavigationBarFromApp")
        val detach = tc.method("legacyDetachNavigationBarFromApp", IBinder::class.java)
        val callers = listOf(start, restore, detach,
            tr.method("onTransactionReady", Int::class.javaPrimitiveType!!, SurfaceControl.Transaction::class.java),
            tr.method("startLegacyRecentsStartBehavior", dc, TransitionInfo::class.java),
            tr.method("finishTransition", type("com.android.server.wm.ActionChain")),
            type("com.android.server.wm.ActivityTaskManagerService").method("detachNavigationBarFromApp", IBinder::class.java))
        val controller = tr.field("mController")
        val targets = tr.field("mTargets")
        val targetContainer = type("com.android.server.wm.Transition\$ChangeInfo").field("mContainer")
        val asTask = wc.method("asTask")
        val isHome = type("com.android.server.wm.ConfigurationContainer").method("isActivityTypeHomeOrRecents")
        val visibleRequested = wc.method("isVisibleRequested")
        val attached = tc.field("mNavigationBarAttachedToApp")
        val recentsDisplay = tr.field("mRecentsDisplayId")
        val displayId = dc.field("mDisplayId")
        val policy = dc.method("getDisplayPolicy")
        val shouldAttach = policyClass.method(SHOULD_ATTACH)
        val rotation = dc.method("getAsyncRotationController")
        val navigation = policyClass.method("getNavigationBar")
        val token = type("com.android.server.wm.WindowState").field("mToken")
        val taskDisplay = type("android.app.TaskInfo").field("displayId")
        val activityType = type("android.app.TaskInfo").method("getActivityType")
        val container = TransitionInfo.Change::class.java.method("getContainer")
        val asBinder = type("android.window.WindowContainerToken").method("asBinder")
        val fromBinder = wc.method("fromBinder", IBinder::class.java)
        val multiWindow = type("com.android.server.wm.ConfigurationContainer").method("inMultiWindowMode")
        val surface = wc.method("getSurfaceControl")
        val pending = wc.method("getPendingTransaction")
        val cancel = wc.method("cancelAnimation")
        val visible = wc.method("isVisible")
        val ime = dc.method("getImeContainer")
        val show = SurfaceControl.Transaction::class.java.method("show", SurfaceControl::class.java)
        val relative = SurfaceControl.Transaction::class.java.method("setRelativeLayer", SurfaceControl::class.java,
            SurfaceControl::class.java, Int::class.javaPrimitiveType!!)
        val luma = tr.method("sendLumaSamplingEnabledToStatusBarInternal", dc, Boolean::class.javaPrimitiveType!!)
        val wm = wc.field("mWmService")
        val lock = type("com.android.server.wm.WindowManagerService").field("mGlobalLock")
    }

    private fun hookDisplayPolicyGetter() {
        val policyClass = DISPLAY_POLICY.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DISPLAY_POLICY, "class not found")
            return
        }
        val method = runCatching {
            policyClass.getDeclaredMethod(SHOULD_ATTACH).apply { isAccessible = true }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$DISPLAY_POLICY#$SHOULD_ATTACH()", it)
        }.getOrNull() ?: return
        // Deoptimizing a tiny getter does not undo inlining in its already compiled caller.
        // The native transition owns both attachment and restoration; only change its live gate.
        runCatching {
            val transition = "com.android.server.wm.Transition".toClassOrNull()
                ?: error("Missing native transition owner")
            val caller = transition.getDeclaredMethod(
                "handleLegacyRecentsStartBehavior",
                classLoader.loadClass("com.android.server.wm.DisplayContent"),
                classLoader.loadClass("android.window.TransitionInfo")
            )
            check(caller.returnType == Void.TYPE && method.returnType == Boolean::class.javaPrimitiveType)
            deoptimize(caller)
            deoptimize(method)
        }.onFailure { DebugLog.hookFailed(TAG, "native recents attach gate", it) }.getOrNull() ?: return

        val attachResourceId = Resources.getSystem().getIdentifier(
            "config_attachNavBarToAppDuringTransition", "bool", "android"
        )
        if (attachResourceId == 0) {
            DebugLog.hookSkipped(TAG, SHOULD_ATTACH, "native resource missing")
            return
        }
        val contract = runCatching {
            Triple(
                policyClass.getDeclaredField("mNavigationBar").apply { isAccessible = true },
                policyClass.getDeclaredMethod("getCurrentUserResources").apply { isAccessible = true },
                classLoader.loadClass("com.android.wm.shell.Flags").getDeclaredMethod("enableTinyTaskbar")
            )
        }.onFailure { DebugLog.hookFailed(TAG, "native attach safety contract", it) }.getOrNull() ?: return
        method.hook {
            after { param ->
                runCatching {
                    val resources = contract.second.invoke(param.thisObject) as? Resources ?: return@runCatching
                    // Re-read the native source rather than the cached field: an older module's
                    // resource hook may have left that field forced true across APK replacement.
                    // Preserve the native no-navbar and tiny-taskbar guards on both toggle paths.
                    param.result = settings().shouldAttach(
                        nativeDefault = resources.getBoolean(attachResourceId),
                        navigationAvailable = contract.first.get(param.thisObject) != null,
                        tinyTaskbar = contract.third.invoke(null) == true)
                }.onFailure { DebugLog.w(TAG, "failed to report nav-bar attach state", it) }
            }
        }
        DebugLog.d(TAG, "hooked $DISPLAY_POLICY#$SHOULD_ATTACH for the gesture bar transition")
    }

    /** Read per transition; the platform's cached field is never made sticky. */
    private fun attachEnabled(): Boolean =
        Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION, false)
}
