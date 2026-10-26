@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.hook.base.HostAccess
import java.util.WeakHashMap
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import android.view.inspector.WindowInspector
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconHostPorts

/**
 * Hides the keyguard status bar while the lock screen is lit.
 *
 * The hooks stay installed for the process generation and every call re-reads [enabled], so both
 * directions of the switch apply immediately.
 *
 * Two values are tracked per hidden view and never mixed: [LockscreenStatusBarIntent] is what the host last
 * *asked* for (captured from the intercepted arguments, and updated by every later host call), and
 * the module's own suppression is written directly. Disabling restores the native intent, so a
 * host that changed visibility or alpha while the row was hidden keeps that change.
 */
object HideLockscreenStatusBarHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingHideLockscreenStatusBar by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) {
            recoverStateController(it.component); recoverExistingViews(it.views)
        }
    }

    private var enabled by generationState { false }
    private var keyguardStatusBarClass: Class<*>? by generationState { null }

    /** The host's own request per hidden view; see [LockscreenStatusBarIntent]. */
    private val intents = generationMainResource(WeakHashMap<View, LockscreenStatusBarIntent>()) { it.clear() }
    private var applying by generationState { false }

    /** Living keyguard status bars, remembered from construction even before they are attached. */
    private val knownViews: MutableSet<View> =
        java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private var retiring = false
    init { generationCleanup { retiring = true } }
    private var stateController: Any? by generationState { null }
    private val attachListeners = generationMainResource(WeakHashMap<View, View.OnAttachStateChangeListener>()) { it.clear() }

    /** Null until the host controller is recovered; consumers may use their platform fallback. */
    internal fun keyguardShowing(): Boolean? = stateController?.let {
        runCatching {
            val showing = HostAccess.read(it, "mShowing") as? Boolean ?: return@runCatching null
            val occluded = HostAccess.read(it, "mOccluded") as? Boolean ?: return@runCatching null
            showing && !occluded
        }.getOrNull()
    }
    internal fun statusBarSuppressed(): Boolean = enabled

    internal fun recoverStateController(component: Any?) {
        if (component == null || retiring) return
        runCatching { HostAccess.provider(component, "keyguardStateControllerImplProvider") }
            .onSuccess { if (it != null) { stateController = it; notifyPresentation() } }
            .onFailure { DebugLog.w("HideLockscreenStatusBar", "keyguard controller recovery failed", it) }
    }

    private fun notifyPresentation() {
        if (!retiring) IconHostPorts.left.visibilityChanged()
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain { listOf(knownViews.toList(), stateController) }
    override fun restoreHotReloadState(state: Any?) {
        HostAccess.onMain {
            val saved = state as? List<*> ?: emptyList<Any>()
            // Accept older generations which saved just a flat list of framework Views.
            val views = (saved.firstOrNull() as? List<*>)?.filterIsInstance<View>()
                ?: saved.filterIsInstance<View>()
            stateController = saved.getOrNull(1)?.takeUnless { it is View }
            recoverExistingViews(views)
        }
    }

    /** A replacement may or may not have saved views; classify both and re-hide when enabled. */
    internal fun recoverExistingViews(views: List<View>) {
        val target = keyguardStatusBarClass
        (views + existingKeyguardStatusBars()).filter { target?.isInstance(it) == true }
            .forEach(::remember)
        if (enabled) knownViews.filter { it.isAttachedToWindow }.forEach(::hide)
        notifyPresentation()
    }

    /**
     * Accepted-snapshot refresh. Enabling hides the current host; disabling restores the native
     * intent first, so the interceptors cannot keep the view hidden by replaying the module value.
     */
    fun refreshSettings() {
        val next = settingHideLockscreenStatusBar
        val changed = next != enabled
        enabled = next
        HostAccess.onMain {
            existingKeyguardStatusBars().forEach(::remember)
            if (enabled) {
                knownViews.filter { it.isAttachedToWindow }.forEach(::hide)
            } else if (changed) {
                restoreNative(knownViews.toList())
            }
            notifyPresentation()
        }
        DebugLog.i(
            "HideLockscreenStatusBar",
            "settings applied enabled=" + enabled + " views=" + knownViews.size
        )
    }

    override fun onPrepareHotReload() {
        retiring = true
        HostAccess.onMain {
            attachListeners.forEach { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
            attachListeners.clear()
            restoreNative(knownViews.toList())
            knownViews.clear()
            intents.clear()
            stateController = null
            keyguardStatusBarClass = null
            enabled = false
        }
    }

    override fun onHook() {
        retiring = false
        enabled = settingHideLockscreenStatusBar
        // The host emits actual showing/occlusion changes here, including insecure lock screens.
        val stateClass = "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl".toClassOrNull()
        stateClass?.declaredMethods?.singleOrNull {
            it.name == "notifyKeyguardState" && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    runCatching {
                        if (!retiring) HostAccess.onMain {
                            stateController = param.thisObject
                            notifyPresentation()
                        }
                    }.onFailure { DebugLog.w("HideLockscreenStatusBar", "keyguard state callback failed", it) }
                }
            }
        } ?: DebugLog.hookSkipped("HideLockscreenStatusBar", "notifyKeyguardState(ZZ)", "method not found")
        val controllerClass = "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController".toClassOrNull()
        controllerClass?.findMethodOrNull { name("onViewAttached"); noParams() }?.hook {
            after { param ->
                runCatching {
                    HostAccess.onMain {
                        stateController = HostAccess.read(param.thisObject, "mKeyguardStateController") ?: stateController
                        (HostAccess.read(param.thisObject, "mView") as? View)?.let(::remember)
                        notifyPresentation()
                    }
                }.onFailure { DebugLog.w("HideLockscreenStatusBar", "keyguard attach recovery failed", it) }
            }
        }

        val targetClass = runCatching {
            classLoader.loadClass("com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView")
        }.getOrElse {
            DebugLog.hookSkipped("HideLockscreenStatusBar", "MiuiKeyguardStatusBarView", "class not found")
            return
        }
        keyguardStatusBarClass = targetClass

        // Construction happens before attachment, so the view is remembered while detached and
        // only hidden once it is on screen. This also covers a status bar rebuilt later.
        targetClass.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.hook {
                    after { param -> (param.thisObject as? View)?.let(::remember) }
                }
            }.onFailure { DebugLog.w("HideLockscreenStatusBar", "constructor hook unavailable", it) }
        }
        targetClass.findMethodOrNull { name("onFinishInflate"); paramCount(0) }?.hook {
            after { param -> (param.thisObject as? View)?.let(::remember) }
        } ?: DebugLog.hookSkipped(
            "HideLockscreenStatusBar",
            "MiuiKeyguardStatusBarView#onFinishInflate",
            "method not found"
        )

        // The intercepted argument is the host's request. It updates the native intent for that
        // dimension only; the module's own hidden value is never written back into the baseline.
        ViewVisibilityRouter.visibility(this, targetClass, before = { view, requested ->
            if (!shouldHide(view)) null else {
                val previous = intents.getOrPut(view) { LockscreenStatusBarIntent(view.visibility, view.alpha) }
                intents[view] = previous.withVisibility(requested)
                View.INVISIBLE
            }
        })
        ViewVisibilityRouter.alpha(this, targetClass, before = { view, requested ->
            if (!shouldHide(view)) null else {
                val previous = intents.getOrPut(view) { LockscreenStatusBarIntent(view.visibility, view.alpha) }
                intents[view] = previous.withAlpha(requested)
                0f
            }
        })

        if (enabled) HostAccess.onMain { knownViews.filter { it.isAttachedToWindow }.forEach(::hide) }
    }

    private fun remember(view: View) {
        if (retiring) return
        knownViews += view
        if (!attachListeners.containsKey(view)) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (retiring) return
                    if (enabled) hide(v)
                    notifyPresentation()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    if (!retiring) notifyPresentation()
                }
            }
            attachListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
        if (enabled) HostAccess.onMain { hide(view) }
    }

    /** Writes the host's own last request back to every hidden view and forgets it. */
    private fun restoreNative(views: List<View>) {
        val target = keyguardStatusBarClass
        views.forEach { view ->
            if (target?.isInstance(view) != true) return@forEach
            val intent = intents.remove(view) ?: return@forEach
            applying = true
            try {
                runCatching {
                    if (view.visibility != intent.visibility) view.visibility = intent.visibility
                    if (view.alpha != intent.alpha) view.alpha = intent.alpha
                }.onFailure {
                    DebugLog.w("HideLockscreenStatusBar", "failed to restore keyguard status bar", it)
                }
            } finally { applying = false }
        }
    }

    /**
     * Find already-inflated hosts in every real window, including upgrade without saved state.
     * A Context is never treated as a View.
     */
    private fun existingKeyguardStatusBars(): List<View> {
        val target = keyguardStatusBarClass ?: return knownViews.toList()
        val roots: List<View> = systemUiViewRoots()
        val found = ArrayList<View>(2)
        roots.distinct().forEach { root ->
            runCatching {
                if (target.isInstance(root)) found += root
                walk(root as? ViewGroup ?: return@runCatching, target, found)
            }
        }
        return (found + knownViews).filter { target.isInstance(it) }
    }

    private fun walk(group: ViewGroup, target: Class<*>, out: MutableList<View>) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (target.isInstance(child)) out += child
            if (child is ViewGroup) walk(child, target, out)
        }
    }

    /** Snapshot every window as Views; mViews contains Views, not ViewRootImpl objects. */
    private fun systemUiViewRoots(): List<View> = runCatching {
        WindowInspector.getGlobalWindowViews().toList()
    }.onFailure { DebugLog.w("HideLockscreenStatusBar", "window discovery failed", it) }
        .getOrDefault(emptyList())

    /** Kept as package-readiness entry point; host Views are discovered independently. */
    fun onApplicationContextReady(context: Context) {
        if (!retiring && keyguardStatusBarClass != null) HostAccess.onMain { recoverExistingViews(emptyList()) }
    }

    private fun shouldHide(instance: Any?): Boolean =
        !retiring && enabled && !applying && instance != null && keyguardStatusBarClass?.isInstance(instance) == true

    private fun hide(view: View) {
        if (!enabled || !view.isAttachedToWindow) return
        if (!intents.containsKey(view)) intents[view] = LockscreenStatusBarIntent(view.visibility, view.alpha)
        applying = true
        try {
            runCatching {
                view.visibility = View.INVISIBLE
                view.alpha = 0f
            }.onFailure { DebugLog.w("HideLockscreenStatusBar", "failed to hide keyguard status bar", it) }
        } finally { applying = false }
    }
}