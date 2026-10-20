@file:Suppress("InternalInsetResource")

package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.View
import android.view.animation.PathInterpolator
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hides the gesture bar (小白条) and keeps it hidden for exactly as long as recent tasks owns the
 * bottom of the screen.
 *
 * Three independent switches share this one draw path, which is why they live in one hooker:
 * - [Preferences.KEY_HIDE_GESTURE_BAR] hides it everywhere (the original feature);
 * - [Preferences.KEY_HIDE_GESTURE_BAR_RECENTS] hides it only in recent tasks;
 * - [Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION] keeps it attached to the application during
 *   the swipe-up transition, which is what makes hiding it there look native.
 *
 * State and decisions belong to [GestureBarState]. This class only feeds it host events, performs
 * the returned action on the real views, and owns the deadline timer. The previous implementation
 * had two overlapping state classes, a 700 ms reveal timer, a 1.2 s polling watchdog with a
 * four-round patience counter, and a separate alpha map; those are all gone.
 *
 * Suppression ends on evidence, in this order:
 * 1. the launcher/Shell Overview exit plus the finished return transition (normal path);
 * 2. the finished return transition alone, when the Overview source never reported;
 * 3. the display deadline, which fails open so a lost event cannot leave the bar hidden.
 */
object HideBottomBarHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private val navigationHandleClassNames = listOf(
        "com.android.systemui.navigationbar.gestural.NavigationHandle",
        "com.android.systemui.navigationbar.gestural.QuickswitchOrientedNavHandle"
    )

    private const val DECORATION_VIEW =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"

    /** Exact NavBarFadeAnimationController fade-out duration and interpolator. */
    private const val FADE_OUT_MS = 133L

    private val hooksApplied = AtomicBoolean(false)
    @Volatile private var hideGestureBarEnabled = false
    @Volatile private var raiseLayoutEnabled = false
    @Volatile private var hideInRecentsEnabled = false
    @Volatile private var attachTransitionEnabled = false
    @Volatile private var followGenerationActive = false

    private val state = GestureBarState()
    private val handleViews = instanceTracker()
    private val navigationBars = instanceTracker()

    /**
     * Host alpha captured before the first suppression, so a reveal restores what the platform
     * wanted (an immersive app is not forced to alpha = 1) instead of assuming full opacity.
     */
    private val hostAlphas = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Float>())

    /** Guards our own alpha writes from the setAlpha hook that records the host's value. */
    private val ownAlphaWrite = ThreadLocal<Boolean>()

    private val followHandler by lazy { Handler(Looper.getMainLooper()) }
    private val deadlines = HashMap<Int, PendingDeadline>()

    /**
     * Displays whose handle is mid-fade. The draw gate must keep painting them for the duration:
     * suppressing [android.view.View.onDraw] at the same instant the alpha animation starts leaves
     * nothing to fade, so the bar vanishes instead of fading out.
     */
    private val fadingDisplays = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Views whose alpha is being written by our own animation. Without this the setAlpha hook would
     * record the animation's intermediate values as the host's alpha and destroy the value a later
     * reveal has to restore.
     */
    private val selfDrivenAlpha =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private var hostContext = WeakReference<Context>(null)

    @Volatile private var navBarHeightAndroidResId = 0
    @Volatile private var navBarHeightSystemUiResId = 0

    /** Whether any feature of this hooker is on, so the draw hooks can skip work when idle. */
    private val anyEnabled: Boolean
        get() = hideGestureBarEnabled || (hideInRecentsEnabled && attachTransitionEnabled)

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun saveHotReloadState(): Any = arrayOf<Any>(
        ArrayList(handleViews.snapshot()),
        state.save(),
        ArrayList(navigationBars.snapshot()),
        synchronized(hostAlphas) {
            hostAlphas.map { (view, alpha) -> arrayOf<Any>(view, alpha) }.toTypedArray()
        }
    )

    override fun restoreHotReloadState(saved: Any?) {
        val values = saved as? Array<*> ?: return
        (values.getOrNull(0) as? List<*>)?.filterIsInstance<View>()?.forEach(handleViews::add)
        state.restore(values.getOrNull(1), SystemClock.uptimeMillis())
        (values.getOrNull(2) as? List<*>)?.filterNotNull()?.forEach(navigationBars::add)
        (values.getOrNull(3) as? Array<*>)?.forEach { row ->
            val pair = row as? Array<*> ?: return@forEach
            val view = pair.getOrNull(0) as? View ?: return@forEach
            val alpha = pair.getOrNull(1) as? Float ?: return@forEach
            hostAlphas[view] = alpha
        }
        refreshSettings()
    }

    /** Shared SystemUI recovery also backfills upgrades from versions with no saved hosts. */
    internal fun recoverExistingViews(views: List<View>, component: Any?) {
        views.filter(::isHandleOrDecoration).forEach(handleViews::add)
        recoverNavigationBars(component)
        refreshNavigationLayout()
        // The native Overview map survives module replacement, including an already-open Recents.
        if (component != null) runCatching {
            val controller = shellController(component) ?: return@runCatching
            val query = StatusIconHostAccess.method(
                controller, "isOverviewVisible", Int::class.javaPrimitiveType!!
            ) ?: return@runCatching
            handleViews.snapshot().filterIsInstance<View>()
                .mapNotNull { it.display?.displayId }.distinct().forEach { displayId ->
                    if (query.invoke(controller, displayId) == true) {
                        state.onOverview(displayId, true, SystemClock.uptimeMillis())
                    }
                }
        }.onFailure { DebugLog.w(TAG, "failed to recover native overview state", it) }
        applyCurrentState()
        // The replacement's draw gate is a different callback instance; the frame recorded by the
        // retired generation was drawn under the old decision. Repaint so a switch that changed
        // while the module was being replaced is visible without waiting for a host invalidation.
        repaintGestureBarViews()
        DebugLog.i(TAG, "hot reload recovered hosts=${handleViews.snapshot().size} " +
            "suppressed=${state.suppressedDisplays()}")
    }

    override fun onPrepareHotReload() {
        followGenerationActive = false
        cancelAllDeadlines()
        // Only our handle alpha animation is owned here; the WM surface remains native.
        synchronized(hostAlphas) { hostAlphas.keys.toList() }.forEach { view ->
            StatusIconHostAccess.onMain { view.animate().cancel() }
        }
        AospBackSystemUiHooker.setOverviewVisibilityListener(null)
        hooksApplied.set(false)
        hideGestureBarEnabled = false
        raiseLayoutEnabled = false
        hideInRecentsEnabled = false
        attachTransitionEnabled = false
        state.clear()
        hostAlphas.clear()
        fadingDisplays.clear()
        selfDrivenAlpha.clear()
        ownAlphaWrite.remove()
        navBarHeightAndroidResId = 0
        navBarHeightSystemUiResId = 0
        handleViews.clear()
        navigationBars.clear()
        hostContext.clear()
    }

    override fun onHook() {
        followGenerationActive = true
        loadSettings()
        // Every callback below tests the live fields, so the hooks install regardless of the
        // current values; a switch toggled later applies without a SystemUI restart.
        try {
            runCatching {
                navBarHeightAndroidResId = Resources.getSystem()
                    .getIdentifier("navigation_bar_height", "dimen", "android")
            }
            Resources::class.java.getMethod("getDimensionPixelSize", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (!hideGestureBarEnabled || raiseLayoutEnabled) return@before
                    val resources = param.thisObject as? Resources ?: return@before
                    val id = param.args[0] as? Int ?: return@before
                    if (id != navBarHeightAndroidResId && id != navBarHeightSystemUiResId) return@before
                    try {
                        // Rare match: keep the original name/type/package verification so an id
                        // that collides numerically in another package is not zeroed.
                        val name = resources.getResourceEntryName(id)
                        val type = resources.getResourceTypeName(id)
                        val pkg = resources.getResourcePackageName(id)
                        if (name == "navigation_bar_height" && type == "dimen" &&
                            (pkg == "android" || pkg == "com.android.systemui")
                        ) {
                            param.result = 0
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            DebugLog.e(TAG, "failed to hook Resources.getDimensionPixelSize", t)
        }
        // Dynamic SystemUI classes are hooked from HookEntry.onPackageReady.
    }

    fun onPackageReady(context: Context?, readyClassLoader: ClassLoader) {
        // The context must be adopted even when the hooks are already installed: hot reload
        // re-enters here with a fresh generation, and losing the context silently disables every
        // platform query (the overview fallback then never recovers the bar).
        adoptContext(context)
        if (hooksApplied.getAndSet(true)) return
        loadSettings()
        applyDynamicHooks(readyClassLoader)
        // HYOS MiuiHome publishes its completed Dart Overview state through the authenticated
        // bridge rather than necessarily calling Shell's overview pair. Registration replays the
        // current state immediately, including upgrades from APKs that saved no state payload.
        AospBackSystemUiHooker.setOverviewVisibilityListener { visible ->
            // The bridge carries no display id; the primary display is the one it describes.
            if (visible == null) return@setOverviewVisibilityListener
            if (onOverview(PRIMARY_DISPLAY, visible)) {
                DebugLog.d(TAG, "launcher overview=$visible")
            }
        }
    }

    /** Adopts the SystemUI application context, keeping the last usable one across hot reload. */
    private fun adoptContext(context: Context?) {
        val usable = context?.applicationContext ?: context ?: return
        hostContext = WeakReference(usable)
        Preferences.initLocalCache(usable)
        resolveNavigationBarHeightResIds(usable)
    }

    /** Applies a committed live-settings change from the shared dispatcher. */
    fun refreshSettings() {
        // Hot reload restores state without going through onPackageReady; recover the context here
        // too, otherwise the platform query stays unavailable for the whole generation.
        if (hostContext.get() == null) {
            adoptContext(runCatching { io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContextOrNull }
                .getOrNull())
        }
        val previousHide = hideGestureBarEnabled
        val previousRaise = raiseLayoutEnabled
        val previousRecents = hideInRecentsEnabled
        val previousAttach = attachTransitionEnabled
        loadSettings()
        if (previousHide == hideGestureBarEnabled && previousRaise == raiseLayoutEnabled &&
            previousRecents == hideInRecentsEnabled && previousAttach == attachTransitionEnabled
        ) return
        DebugLog.i(TAG, "live settings applied hide=$hideGestureBarEnabled raise=$raiseLayoutEnabled " +
            "recents=$hideInRecentsEnabled attach=$attachTransitionEnabled")
        if (previousHide != hideGestureBarEnabled || previousRaise != raiseLayoutEnabled) {
            refreshNavigationLayout()
        }
        if (!anyEnabled) {
            // Turning the feature off must hand every display back immediately, including a
            // suppression carried over from the previous generation.
            cancelAllDeadlines()
            val suppressed = state.suppressedDisplays()
            state.clear()
            restoreHandles(suppressed)
        } else {
            applyCurrentState()
        }
        // The draw gate only skips the *next* frame and nothing asks for one, so a committed
        // change repainted nothing: the pill already on screen stayed there after the switch was
        // turned on, and stayed missing after it was turned off, until some unrelated host
        // invalidation happened to arrive. That is the reported "only works after a restart".
        // Repaint after the alpha work above so the new gate and the restored alpha share a frame.
        repaintGestureBarViews()
    }

    private fun loadSettings() {
        hideGestureBarEnabled = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
        raiseLayoutEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
        hideInRecentsEnabled = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR_RECENTS, false)
        attachTransitionEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION, false)
    }

    // ── Evidence → state ─────────────────────────────────────────────────────────

    /** Overview visibility for one display. Returns whether the value changed the session. */
    private fun onOverview(display: Int, visible: Boolean): Boolean {
        val action = runCatching {
            state.onOverview(display, visible, SystemClock.uptimeMillis())
        }.onFailure { DebugLog.w(TAG, "failed to apply overview visibility", it) }.getOrNull()
            ?: return false
        applyAction(display, action)
        applyCurrentState()
        return true
    }

    private fun onAttachment(display: Int, attached: Boolean) {
        runCatching {
            val action = state.onAttachment(display, attached, SystemClock.uptimeMillis())
            // The bar is still drawn while leaving; only the transition end fades it out.
            applyAction(display, action)
        }.onFailure { DebugLog.w(TAG, "failed to apply navigation attachment", it) }
        DebugLog.d(TAG, "attachment attached=$attached display=$display " +
            "suppressed=${state.isSuppressed(display)}")
    }

    private fun onTransitionFinished(display: Int) {
        if (!followGenerationActive) return
        runCatching {
            // The same callback covers both directions; the platform decides which one this was.
            val overview = queryOverviewVisible(display)
            val action = state.onTransitionFinished(display, overview)
            DebugLog.d(TAG, "transition finished display=$display overview=$overview " +
                "suppressed=${state.isSuppressed(display)} action=$action")
            applyAction(display, action)
        }.onFailure { DebugLog.w(TAG, "failed to restore the gesture bar", it) }
    }

    private fun onDeadline(display: Int) {
        if (!followGenerationActive) return
        runCatching {
            // Ask the platform rather than assuming: null means the native map is unreachable.
            val overview = queryOverviewVisible(display)
            val action = state.onDeadline(display, SystemClock.uptimeMillis(), overview)
            DebugLog.d(TAG, "deadline display=$display overview=$overview " +
                "suppressed=${state.isSuppressed(display)} action=$action")
            if (action == GestureBarState.Action.NONE) {
                // Still genuinely in Recents (or waiting on an answer): keep the guard alive.
                scheduleDeadline(display)
                return@runCatching
            }
            applyAction(display, action)
        }.onFailure { DebugLog.w(TAG, "failed to release the gesture bar deadline", it) }
    }

    /** Null when the native visibility map cannot be reached; callers must not guess. */
    /**
     * The platform's own answer for "is Recents on screen", or null when it cannot be asked.
     *
     * Every failure path is logged at DEBUG: a silently null answer is indistinguishable from a
     * genuinely unavailable platform and made a cancelled swipe look like a stuck session.
     */
    private fun queryOverviewVisible(display: Int): Boolean? = runCatching {
        val context = hostContext.get() ?: run {
            DebugLog.d(TAG, "overview query unavailable: no host context"); return@runCatching null
        }
        val app = context.applicationContext ?: context
        val component = StatusIconHostAccess.read(app, "mSysUIComponent")
            ?: StatusIconHostAccess.read(app, "mInitializer")?.let {
                StatusIconHostAccess.invoke(it, "getSysUIComponent")
            } ?: run {
            DebugLog.d(TAG, "overview query unavailable: no SysUIComponent"); return@runCatching null
        }
        val controller = shellController(component) ?: run {
            DebugLog.d(TAG, "overview query unavailable: no ShellController"); return@runCatching null
        }
        val query = StatusIconHostAccess.method(
            controller, "isOverviewVisible", Int::class.javaPrimitiveType!!
        ) ?: run {
            DebugLog.d(TAG, "overview query unavailable: no isOverviewVisible"); return@runCatching null
        }
        query.invoke(controller, display) as? Boolean
    }.getOrElse {
        DebugLog.w(TAG, "failed to query native overview visibility", it)
        null
    }

    private fun applyAction(display: Int, action: GestureBarState.Action) {
        when (action) {
            GestureBarState.Action.FADE_OUT -> {
                scheduleDeadline(display)
                animateHandles(display, fadeOut = true)
                DebugLog.d(TAG, "gesture bar fading out display=$display")
            }
            GestureBarState.Action.HIDE_NOW -> {
                // The platform restored the token at display level and fades its own copy in
                // there. Anything drawn here is that flash at the bottom of Recents, so stop
                // painting immediately instead of fading.
                scheduleDeadline(display)
                fadeImmediately(display)
                DebugLog.d(TAG, "gesture bar suppressed display=$display")
            }
            GestureBarState.Action.SHOW -> {
                cancelDeadline(display)
                animateHandles(display, fadeOut = false)
                DebugLog.i(TAG, "gesture bar restored display=$display")
            }
            GestureBarState.Action.NONE -> scheduleDeadline(display)
        }
    }

    /**
     * Forces the next frame for every gesture-bar view the draw gate can affect.
     *
     * The gate lives in `onDraw`, and skipping a draw never schedules another one: the handle's
     * RenderNode keeps whatever it recorded last. A switch change therefore repainted nothing and
     * the bar stayed exactly as it was until some unrelated host invalidation happened to arrive -
     * which is the "the toggle only works after a SystemUI restart" report. `invalidate()` re-runs
     * the gate immediately, so hiding and revealing both apply in the next frame.
     */
    private fun repaintGestureBarViews() {
        StatusIconHostAccess.onMain {
            if (!followGenerationActive) return@onMain
            // The install-time log shows handles=0 because no handle has drawn yet; a toggle in
            // that state would otherwise repaint nothing at all. Discover the live hosts, but only
            // when the tracker has no attached one - the window walk is not worth repeating once
            // the draw hook has populated it.
            var views = liveGestureBarViews()
            if (views.isEmpty()) {
                discoverHandleViews()
                views = liveGestureBarViews()
            }
            views.forEach { view ->
                runCatching { view.invalidate() }
                    .onFailure { DebugLog.w(TAG, "failed to repaint the gesture bar", it) }
            }
        }
    }

    /** The tracked handles and decoration views that are currently attached to a window. */
    private fun liveGestureBarViews(): List<View> = handleViews.snapshot().filterIsInstance<View>()
        .filter(::isHandleOrDecoration)
        .filter { it.isAttachedToWindow }

    /** Adopts the handles that exist right now, for a toggle that arrives before any draw. */
    private fun discoverHandleViews() {
        runCatching {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { root ->
                collectHandleViews(root, 0)
            }
        }.onFailure { DebugLog.w(TAG, "failed to discover gesture bar hosts", it) }
    }

    private fun collectHandleViews(view: View, depth: Int) {
        // Window trees are shallow; the bound keeps a pathological hierarchy from stalling main.
        if (depth > MAX_VIEW_DEPTH) return
        if (isHandleOrDecoration(view)) handleViews.add(view)
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                collectHandleViews(view.getChildAt(index) ?: continue, depth + 1)
            }
        }
    }

    /** Stops drawing the handles of a display at once, without an animation of our own. */
    private fun fadeImmediately(display: Int) {
        StatusIconHostAccess.onMain {
            if (!followGenerationActive) return@onMain
            fadingDisplays.remove(display)
            handleViews.snapshot().filterIsInstance<View>()
                .filter { isHandle(it) && (it.display?.displayId ?: PRIMARY_DISPLAY) == display }
                .forEach { view ->
                    view.animate().cancel()
                    selfDrivenAlpha.remove(view)
                    hostAlphas.putIfAbsent(view, view.alpha)
                    setHandleAlpha(view, 0f)
                }
        }
    }

    /** Re-applies the stored suppression to live views after recovery or a settings change. */
    private fun applyCurrentState() {
        StatusIconHostAccess.onMain {
            if (!followGenerationActive) return@onMain
            handleViews.snapshot().filterIsInstance<View>().filter(::isHandle).forEach { view ->
                val displayId = view.display?.displayId ?: PRIMARY_DISPLAY
                if (state.isSuppressed(displayId)) {
                    // Suppressed: remember the host alpha once, then keep the handle dark. A
                    // repaint here must never brighten a bar that belongs to Recents.
                    hostAlphas.putIfAbsent(view, view.alpha)
                    setHandleAlpha(view, 0f)
                } else if (displayId !in state.suppressedDisplays()) {
                    // Only restore when this display is genuinely not suppressed. Without the
                    // guard, a stale host alpha from a previous session is written back while
                    // Recents is still on screen, which is the bar reappearing at the bottom.
                    hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
                }
            }
            state.suppressedDisplays().forEach(::scheduleDeadline)
        }
    }

    // ── Deadline timer ───────────────────────────────────────────────────────────

    /**
     * Keeps one callback per display in step with the current phase's deadline. A phase change can
     * move the deadline in either direction (leaving -> hidden extends it, hidden -> returning
     * shortens it), so an already-queued callback is replaced rather than kept.
     */
    private fun scheduleDeadline(display: Int) {
        val at = state.deadlineFor(display)
        if (at == 0L) { cancelDeadline(display); return }
        val existing = synchronized(deadlines) { deadlines[display] }
        if (existing != null && existing.at == at && followHandler.hasCallbacks(existing.task)) return
        cancelDeadline(display)
        val runnable = Runnable {
            synchronized(deadlines) { deadlines.remove(display) }
            onDeadline(display)
        }
        synchronized(deadlines) { deadlines[display] = PendingDeadline(at, runnable) }
        followHandler.postDelayed(runnable, (at - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelDeadline(display: Int) {
        synchronized(deadlines) { deadlines.remove(display) }?.let { followHandler.removeCallbacks(it.task) }
    }

    private fun cancelAllDeadlines() {
        val pending = synchronized(deadlines) {
            deadlines.values.toList().also { deadlines.clear() }
        }
        pending.forEach { followHandler.removeCallbacks(it.task) }
    }

    private class PendingDeadline(val at: Long, val task: Runnable)

    // ── View effects ─────────────────────────────────────────────────────────────

    private fun setHandleAlpha(view: View, alpha: Float) {
        ownAlphaWrite.set(true)
        try { view.alpha = alpha } finally { ownAlphaWrite.remove() }
    }

    private fun isHandle(view: View): Boolean =
        generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .any { it.name in navigationHandleClassNames }

    private fun isHandleOrDecoration(view: View): Boolean =
        isHandle(view) || generateSequence(view.javaClass as Class<*>?) { it.superclass }
            .any { it.name == DECORATION_VIEW }

    /**
     * Applies the stored suppression to the handles of one display. A reveal restores the alpha
     * the host last asked for, so an immersive application is not forced to full opacity.
     */
    private fun animateHandles(display: Int, fadeOut: Boolean) {
        StatusIconHostAccess.onMain {
            if (!followGenerationActive) return@onMain
            handleViews.snapshot().filterIsInstance<View>()
                .filter { isHandle(it) && (it.display?.displayId ?: PRIMARY_DISPLAY) == display }
                .forEach { view ->
                    view.animate().cancel()
                    if (fadeOut) {
                        hostAlphas.putIfAbsent(view, view.alpha)
                        // The native token is still under the application, so this fades the real
                        // handle instead of creating a second one. The display stays drawable
                        // until the animation ends, otherwise there is no pill left to fade.
                        fadingDisplays.add(display)
                        selfDrivenAlpha.add(view)
                        // The native token is still under the application, so this fades the real
                        // handle instead of creating a second one.
                        view.animate().alpha(0f).setDuration(FADE_OUT_MS)
                            .setInterpolator(PathInterpolator(0.2f, 0f, 1f, 1f))
                            .withEndAction {
                                selfDrivenAlpha.remove(view)
                                // Only stop painting once every handle of this display is done.
                                val stillFading = handleViews.snapshot().filterIsInstance<View>()
                                    .any { isHandle(it) && it in selfDrivenAlpha }
                                if (!stillFading) fadingDisplays.remove(display)
                            }
                            .start()
                    } else {
                        selfDrivenAlpha.remove(view)
                        hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
                    }
                }
            if (!fadeOut) fadingDisplays.remove(display)
        }
    }

    private fun restoreHandles(displays: Set<Int>) {
        StatusIconHostAccess.onMain {
            handleViews.snapshot().filterIsInstance<View>().filter(::isHandle).forEach { view ->
                val displayId = view.display?.displayId ?: PRIMARY_DISPLAY
                if (displayId in displays) hostAlphas.remove(view)?.let { setHandleAlpha(view, it) }
            }
        }
    }

    // ── Navigation bar layout ────────────────────────────────────────────────────

    private fun shellController(component: Any): Any? {
        val shell = StatusIconHostAccess.read(component, "setShell") ?: return null
        return StatusIconHostAccess.read(shell, "this\$0")
    }

    // androidx.core's KTX extensions are not resolved from a hook's class loader.
    @Suppress("UseKtx")
    private fun recoverNavigationBars(component: Any?) {
        if (component == null) return
        runCatching {
            val controller = StatusIconHostAccess.provider(component, "navigationBarControllerImplProvider")
                ?: error("Missing native navigation bar controller")
            val bars = StatusIconHostAccess.read(controller, "mNavigationBars") as? SparseArray<*>
                ?: error("Missing native navigation bar map")
            var index = 0
            while (index < bars.size()) {
                bars.valueAt(index)?.let(navigationBars::add)
                index++
            }
        }.onFailure { DebugLog.w(TAG, "failed to recover navigation layouts", it) }
    }

    /** Rebuild native insets for all rotations; invalidation alone only changes the drawn pill. */
    private fun refreshNavigationLayout() {
        StatusIconHostAccess.onMain {
            val app = hostContext.get()?.let { it.applicationContext ?: it }
            if (app != null) {
                val component = StatusIconHostAccess.read(app, "mSysUIComponent")
                    ?: StatusIconHostAccess.read(app, "mInitializer")?.let {
                        StatusIconHostAccess.invoke(it, "getSysUIComponent")
                    }
                recoverNavigationBars(component)
            }
            navigationBars.snapshot().forEach { bar ->
                runCatching {
                    val view = StatusIconHostAccess.read(bar, "mView") as? View ?: return@runCatching
                    if (!view.isAttachedToWindow) return@runCatching
                    val update = StatusIconHostAccess.method(bar, "updateViewLayout", Int::class.javaPrimitiveType!!)
                        ?: error("Missing native navigation layout update")
                    update.invoke(bar, view.display?.rotation ?: return@runCatching)
                    view.requestLayout()
                    view.requestApplyInsets()
                }.onFailure { DebugLog.w(TAG, "failed to refresh native navigation insets", it) }
            }
        }
    }

    private fun resolveNavigationBarHeightResIds(context: Context) {
        runCatching {
            navBarHeightSystemUiResId = context.resources.getIdentifier(
                "navigation_bar_height", "dimen", "com.android.systemui"
            )
        }
    }

    // ── Hook installation ────────────────────────────────────────────────────────

    private fun applyDynamicHooks(cl: ClassLoader) {
        // Capture newly created controllers; recovery adopts controllers from older APKs.
        runCatching {
            cl.loadClass("com.android.systemui.navigationbar.views.NavigationBar")
                .getDeclaredMethod("updateViewLayout", Int::class.javaPrimitiveType).hook {
                    before { param -> navigationBars.add(param.thisObject) }
                }
        }.onFailure { DebugLog.hookFailed(TAG, "NavigationBar#updateViewLayout(int)", it) }

        hookOverviewVisibility(cl)
        hookHandleAlpha(cl)
        hookAttachmentSampling(cl)
        hookTransitionFinished(cl)
        navigationHandleClassNames.forEach { hookOnDraw(cl, it) }
        hookDecorationView(cl)
        hookAuthContainer(cl)
        // One line per installation: the resolved targets and the live switches. Without it a
        // silently skipped hook only shows up as "the feature does nothing".
        DebugLog.i(TAG, "dynamic hooks installed " +
            "handles=${handleViews.snapshot().size} hide=$hideGestureBarEnabled " +
            "recents=$hideInRecentsEnabled attach=$attachTransitionEnabled")
    }

    /**
     * Records the host's own alpha requests so a reveal restores them, and keeps the handle dark
     * while a suppression is active.
     */
    private fun hookHandleAlpha(cl: ClassLoader) {
        runCatching {
            val handleClass = cl.loadClass(navigationHandleClassNames.first())
            val setAlpha = handleClass.getDeclaredMethod("setAlpha", Float::class.javaPrimitiveType)
            // Undo AOT inlining in the animator that also writes the alpha.
            deoptimize(setAlpha)
            deoptimize(handleClass.getDeclaredMethod("onAttachedToWindow"))
            deoptimize(cl.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandlePressAnimator\$2")
                .getDeclaredMethod("onUpdate", Any::class.java, java.util.Collection::class.java))
            setAlpha.hook {
                before { param ->
                    if (!followGenerationActive || ownAlphaWrite.get() == true) return@before
                    val view = param.thisObject as? View ?: return@before
                    val alpha = param.args[0] as? Float ?: return@before
                    // Our own animation's intermediate values are not the host's alpha.
                    if (view in selfDrivenAlpha) return@before
                    if (hostAlphas.containsKey(view)) {
                        // Remember what the host wanted, but never let it brighten a hidden bar.
                        hostAlphas[view] = alpha
                        val displayId = view.display?.displayId ?: PRIMARY_DISPLAY
                        if (state.isSuppressed(displayId)) param.args[0] = minOf(view.alpha, alpha)
                    }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, "NavigationHandle native alpha preservation", it) }
    }

    /** Observe the paired native attachment notifications; never alter surface ownership. */
    private fun hookAttachmentSampling(cl: ClassLoader) {
        runCatching {
            val method = cl.loadClass("com.android.systemui.navigationbar.NavigationBarControllerImpl\$1")
                .getDeclaredMethod("setNavigationBarLumaSamplingEnabled",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            // Deoptimize is best-effort; a failure there must never stop the hook from installing.
            deoptimize(method)
            runCatching {
                deoptimize(cl.loadClass("com.android.systemui.statusbar.CommandQueue\$H")
                    .getDeclaredMethod("handleMessage", android.os.Message::class.java))
            }.onFailure { DebugLog.w(TAG, "failed to deoptimize CommandQueue handler", it) }
            method.hook {
                after { param ->
                    val display = param.args[0] as? Int ?: return@after
                    val enabled = param.args[1] as? Boolean ?: return@after
                    // enabled=false: the gesture started and the token moved to the leaving
                    // application. enabled=true: that transition ended and the token is back at
                    // display level, which is when the bar may fade out.
                    runCatching { onAttachment(display, !enabled) }
                        .onFailure { DebugLog.w(TAG, "failed to apply attachment sampling", it) }
                }
            }
        }.onFailure { DebugLog.hookFailed(TAG, "native navigation attachment notification", it) }
    }

    /**
     * The platform restores the token at display level before the transition ends; this callback
     * arrives after the returning application is in place, so it is the precise reveal moment.
     */
    private fun hookTransitionFinished(cl: ClassLoader) {
        runCatching {
            cl.loadClass("com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks")
                .getDeclaredMethod("appTransitionFinished", Int::class.javaPrimitiveType).hook {
                    after { param ->
                        val display = param.args[0] as? Int ?: return@after
                        runCatching { onTransitionFinished(display) }
                            .onFailure { DebugLog.w(TAG, "failed to restore the gesture bar", it) }
                    }
                }
        }.onFailure { DebugLog.hookFailed(TAG, "appTransitionFinished(int)", it) }
    }

    /** Observe the paired native Overview notifications. */
    private fun hookOverviewVisibility(cl: ClassLoader) {
        val target = "com.android.wm.shell.sysui.ShellController"
        try {
            val controller = cl.loadClass(target)
            val methods = listOf("onOverviewShown" to true, "onOverviewHidden" to false).map { (name, visible) ->
                controller.getDeclaredMethod(name, Int::class.javaPrimitiveType).also {
                    check(it.returnType == Void.TYPE)
                } to visible
            }
            // Resolve both halves before installing: a missing exit must never leave the bar hidden.
            methods.forEach { (method, visible) ->
                deoptimize(method)
                method.hook {
                    after { param ->
                        val displayId = param.args.firstOrNull() as? Int ?: return@after
                        runCatching { onOverview(displayId, visible) }
                            .onFailure { DebugLog.w(TAG, "failed to apply overview visibility", it) }
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, "$target#onOverviewShown/Hidden(int)", t)
            return
        }
        // A launcher crash or rebind must not retain its last visible-Overview report.
        try {
            cl.loadClass("com.android.systemui.LauncherProxyService")
                .getDeclaredMethod("disconnectFromLauncherService", String::class.java).hook {
                    after {
                        cancelAllDeadlines()
                        state.clear()
                        AospBackSystemUiHooker.clearLauncherOverviewVisibility()
                        restoreHandles(state.suppressedDisplays())
                        applyCurrentState()
                    }
                }
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, "LauncherProxyService#disconnectFromLauncherService(String)", t)
        }
    }

    private fun hookOnDraw(cl: ClassLoader, className: String) {
        val target = "${className.substringAfterLast('.')}#onDraw(Canvas)"
        val targetClass = try {
            cl.loadClass(className)
        } catch (_: ClassNotFoundException) {
            DebugLog.hookSkipped(TAG, target, "class not found")
            return
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, target, t)
            return
        }
        try {
            val methods = targetClass.declaredMethods.filter {
                it.name == "onDraw" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Canvas::class.java
            }
            if (methods.isEmpty()) {
                DebugLog.hookSkipped(TAG, target, "method not found")
                return
            }
            methods.forEach { method ->
                method.hook {
                    before { param ->
                        val view = param.thisObject as? View ?: return@before
                        handleViews.add(view)
                        // Hide-everywhere wins immediately. Otherwise the bar keeps travelling with
                        // the app and is only suppressed once the leaving transition finished - and
                        // even then it must keep drawing while its own fade-out animation runs.
                        val display = view.display?.displayId ?: PRIMARY_DISPLAY
                        if (hideGestureBarEnabled ||
                            (state.isSuppressed(display) && display !in fadingDisplays)
                        ) {
                            param.result = null
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, target, t)
        }
    }

    private fun hookDecorationView(cl: ClassLoader) {
        try {
            val decoration = cl.loadClass(DECORATION_VIEW)
            val onDrawMethods = decoration.declaredMethods.filter {
                it.name == "onDraw" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Canvas::class.java
            }
            if (onDrawMethods.isEmpty()) {
                DebugLog.hookSkipped(TAG, "MiuiDecorationBottomView#onDraw(Canvas)", "method not found")
            }
            onDrawMethods.forEach { method ->
                method.hook {
                    before { param ->
                        (param.thisObject as? View)?.let(handleViews::add)
                        if (hideGestureBarEnabled) param.result = null
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, "MiuiDecorationBottomView#onDraw(Canvas)", t)
        }
    }

    private fun hookAuthContainer(cl: ClassLoader) {
        try {
            val methods = cl.loadClass("com.android.systemui.biometrics.AuthContainerView")
                .declaredMethods.filter { it.name == "getmBottomHeight" && it.parameterTypes.isEmpty() }
            if (methods.isEmpty()) {
                DebugLog.hookSkipped(TAG, "AuthContainerView#getmBottomHeight()", "method not found")
            }
            methods.forEach { method ->
                method.hook {
                    after { param ->
                        if (hideGestureBarEnabled && !raiseLayoutEnabled) param.result = 0
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, "AuthContainerView#getmBottomHeight()", t)
        }
    }

    private const val TAG = "HideBottomBar"
    private const val PRIMARY_DISPLAY = 0

    /** Depth bound for the one-off handle discovery; SystemUI window trees are far shallower. */
    private const val MAX_VIEW_DEPTH = 40
}
