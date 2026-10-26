@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.hook.base.HostAccess
import java.util.WeakHashMap
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference

/**
 * Hides the keyguard status bar while the lock screen is lit.
 *
 * The hooks stay installed for the process generation and every call re-reads [enabled], so both
 * directions of the switch apply immediately.
 *
 * Two values are tracked per hidden view and never mixed: [NativeIntent] is what the host last
 * *asked* for (captured from the intercepted arguments, and updated by every later host call), and
 * the module's own suppression is written directly. Disabling restores the native intent, so a
 * host that changed visibility or alpha while the row was hidden keeps that change.
 */
object HideLockscreenStatusBarHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    @Volatile
    private var enabled = false
    private var keyguardStatusBarClass: Class<*>? = null

    /** The host's own request per hidden view; see [LockscreenStatusBarIntent]. */
    private val intents = WeakHashMap<View, LockscreenStatusBarIntent>()
    private var applying = false

    /** Living keyguard status bars, remembered from construction even before they are attached. */
    private val knownViews: MutableSet<View> =
        java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * SystemUI's window view root, used only to discover a status bar that was inflated before
     * this generation ran. The application context is never cast to a View.
     */
    @Volatile
    private var windowRoot: View? = null

    override fun saveHotReloadState(): Any = HostAccess.onMain { knownViews.toList() }
    override fun restoreHotReloadState(state: Any?) {
        HostAccess.onMain { recoverExistingViews((state as? List<*>)?.filterIsInstance<View>().orEmpty()) }
    }

    /** A replacement may or may not have saved views; classify both and re-hide when enabled. */
    internal fun recoverExistingViews(views: List<View>) {
        val target = keyguardStatusBarClass
        (views + existingKeyguardStatusBars()).filter { target?.isInstance(it) == true }
            .forEach { knownViews += it }
        if (enabled) knownViews.filter { it.isAttachedToWindow }.forEach(::hide)
    }

    /**
     * Accepted-snapshot refresh. Enabling hides the current host; disabling restores the native
     * intent first, so the interceptors cannot keep the view hidden by replaying the module value.
     */
    fun refreshSettings() {
        val next = Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
        val changed = next != enabled
        enabled = next
        HostAccess.onMain {
            existingKeyguardStatusBars().forEach { knownViews += it }
            if (enabled) {
                knownViews.filter { it.isAttachedToWindow }.forEach(::hide)
            } else if (changed) {
                restoreNative(knownViews.toList())
            }
        }
        DebugLog.i(
            "HideLockscreenStatusBar",
            "settings applied enabled=" + enabled + " views=" + knownViews.size
        )
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain { restoreNative(knownViews.toList()) }
        knownViews.clear()
        intents.clear()
        windowRoot = null
        keyguardStatusBarClass = null
        enabled = false
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)

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
        runCatching {
            View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType).hook {
                before { param ->
                    if (!shouldHide(param.thisObject)) return@before
                    val view = param.thisObject as? View ?: return@before
                    val requested = param.args[0] as? Int ?: return@before
                    val previous = intents.getOrPut(view) { LockscreenStatusBarIntent(view.visibility, view.alpha) }
                    intents[view] = previous.withVisibility(requested)
                    param.args[0] = View.INVISIBLE
                }
            }
        }.onFailure {
            DebugLog.hookFailed("HideLockscreenStatusBar", "View#setVisibility(Int)", it)
        }

        runCatching {
            View::class.java.getMethod("setAlpha", Float::class.javaPrimitiveType).hook {
                before { param ->
                    if (!shouldHide(param.thisObject)) return@before
                    val view = param.thisObject as? View ?: return@before
                    val requested = param.args[0] as? Float ?: return@before
                    val previous = intents.getOrPut(view) { LockscreenStatusBarIntent(view.visibility, view.alpha) }
                    intents[view] = previous.withAlpha(requested)
                    param.args[0] = 0f
                }
            }
        }.onFailure {
            DebugLog.hookFailed("HideLockscreenStatusBar", "View#setAlpha(Float)", it)
        }

        if (enabled) HostAccess.onMain { knownViews.filter { it.isAttachedToWindow }.forEach(::hide) }
    }

    private fun remember(view: View) {
        knownViews += view
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
     * Finds an inflated keyguard status bar without the window tree: SystemUI exposes its window
     * view root through the application, and only that real View can be searched. A Context is
     * never treated as a View.
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

    /**
     * The real View roots of this process. `WindowManagerGlobal.mViews` holds one
     * `ViewRootImpl` per window, so its child is the decor view - the only object a view search
     * may start from. Cached after the first successful read.
     */
    private fun systemUiViewRoots(): List<View> {
        windowRoot?.takeIf { it.isAttachedToWindow }?.let { return listOf(it) }
        val roots = runCatching {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null)
            val views = globalClass.getDeclaredField("mViews")
                .apply { isAccessible = true }.get(global) as? Collection<*>
            views.orEmpty().mapNotNull { rootImpl ->
                runCatching { rootImpl?.javaClass?.getMethod("getView")?.invoke(rootImpl) as? View }
                    .getOrNull()
                    ?: runCatching {
                        rootImpl?.javaClass?.getDeclaredField("mView")
                            ?.apply { isAccessible = true }?.get(rootImpl) as? View
                    }.getOrNull()
            }
        }.getOrDefault(emptyList())
        roots.firstOrNull()?.let { windowRoot = it }
        return roots
    }

    /**
     * Application context, retained from package-ready. It lives as long as the injected
     * SystemUI process; it is only a readiness signal, never searched as a View.
     */
    @Volatile
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var hostContext: Context? = null

    fun onApplicationContextReady(context: Context) {
        hostContext = runCatching { context.applicationContext }.getOrNull() ?: context
    }

    private fun shouldHide(instance: Any?): Boolean =
        enabled && !applying && instance != null && keyguardStatusBarClass?.isInstance(instance) == true

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