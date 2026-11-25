package com.takekazex.hypertweak.hook.base

import android.view.View
import com.takekazex.hypertweak.util.logging.DebugLog

/** One pair of framework hooks per SystemUI generation; features own only subscriptions. */
internal object ViewVisibilityRouter : StaticHooker() {
    private val visibility = ViewPropertyRoutes<View, Int> { owner, error ->
        DebugLog.w("ViewVisibilityRouter", "$owner visibility callback failed", error)
    }
    private val alpha = ViewPropertyRoutes<View, Float> { owner, error ->
        DebugLog.w("ViewVisibilityRouter", "$owner alpha callback failed", error)
    }

    private val visibilityInstallation = generationResource(HookInstallation {
        DebugLog.hookFailed(hookerName, "View#setVisibility(int)", it)
    }) { it.reset() }
    private val alphaInstallation = generationResource(HookInstallation {
        DebugLog.hookFailed(hookerName, "View#setAlpha(float)", it)
    }) { it.reset() }

    // A partial installation must remain eligible for the existing preference-readiness retry.
    override val isHooked: Boolean
        get() = visibilityInstallation.isInstalled && alphaInstallation.isInstalled

    private fun reportUnavailable(owner: BaseHooker, installation: HookInstallation, target: String) {
        if (!installation.isInstalled) DebugLog.hookFailed(owner.hookerName, target,
            installation.lastFailure ?: IllegalStateException("View router channel is not ready"))
    }

    fun visibility(owner: BaseHooker, type: Class<*>? = null, before: ((View, Int) -> Int?)? = null, after: ((View) -> Unit)? = null) {
        reportUnavailable(owner, visibilityInstallation, "View#setVisibility(int) subscription pending")
        owner.ownSubscription(visibility.register(owner.hookerName, before, after, type))
    }

    fun alpha(owner: BaseHooker, type: Class<*>? = null, before: ((View, Float) -> Float?)? = null, after: ((View) -> Unit)? = null) {
        reportUnavailable(owner, alphaInstallation, "View#setAlpha(float) subscription pending")
        owner.ownSubscription(alpha.register(owner.hookerName, before, after, type))
    }

    override fun onHook() {
        visibilityInstallation.install {
            val visibilityMethod = HostAccess.requireDeclaredMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType, accessible = false)
            visibilityMethod.hook("HyperTweak.ViewVisibilityRouter.visibility") {
                before { param ->
                    val view = param.thisObject as? View ?: return@before
                    val requested = param.args[0] as? Int ?: return@before
                    // Boxing a fresh argument on every View call is wasted work when no rule changes it.
                    val routed = visibility.before(view, requested)
                    if (routed != requested) param.args[0] = routed
                }
                after { param -> (param.thisObject as? View)?.let(visibility::after) }
            }
        }
        alphaInstallation.install {
            val alphaMethod = HostAccess.requireDeclaredMethod(View::class.java, "setAlpha", Float::class.javaPrimitiveType, accessible = false)
            alphaMethod.hook("HyperTweak.ViewVisibilityRouter.alpha") {
                before { param ->
                    val view = param.thisObject as? View ?: return@before
                    val requested = param.args[0] as? Float ?: return@before
                    val routed = alpha.before(view, requested)
                    if (routed != requested) param.args[0] = routed
                }
                after { param -> (param.thisObject as? View)?.let(alpha::after) }
            }
        }
    }

    override fun onPrepareHotReload() { visibility.clear(); alpha.clear() }
}
