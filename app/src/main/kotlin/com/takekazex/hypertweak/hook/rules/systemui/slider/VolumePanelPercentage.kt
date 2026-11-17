package com.takekazex.hypertweak.hook.rules.systemui.slider

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The volume dialog's percentages: the inner per-column labels and the outer capsule beside the
 * collapsed bar. Each host entry captures baselines first and enforces excluded scopes last;
 * all styling goes through [styleText] / [applyBadge].
 */
internal class VolumePanelPercentage(private val session: SliderPercentageHooker) : HookComponent(session) {
    private val badgeStyler = VolumeBadgeStyler(TAG)
    private val controllers = WeakHashMap<Any, Boolean>()
    private class Owner(owner: Any, val column: Boolean) { val ref = WeakReference(owner) }
    private val owners = WeakHashMap<View, Owner>()

    /** Set while the host re-renders natively; every module write is suspended. */
    private var replaying = false
    /** The capsule material is repainted once per show, not on every level update. */
    private var badgeApplied = false

    private val policy get() = session.policy

    fun install() {
        // Native updates and dialog animations share these views; only excluded scopes are forced.
        ViewVisibilityRouter.visibility(session, before = { view, _ -> if (shouldSuppress(view)) View.GONE else null })
        ViewVisibilityRouter.alpha(session, before = { view, _ -> if (shouldSuppress(view)) 0f else null })
        hookColumn()
        hookController()
        hookCapsuleMargin()
        DIALOG_VIEW.toClassOrNull()?.declaredMethods?.singleOrNull {
            it.name == "updateSuperVolumeVisibility" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }?.hook {
            before { param ->
                val view = param.thisObject
                if (replaying || !policy.volumePanel(inControlCenter(view))) return@before
                val expanded = runCatching { HostAccess.publicMethod(view.javaClass, "isExpanded", accessible = false)?.invoke(view) as? Boolean }
                    .getOrNull() ?: false
                // The module's collapsed/outside rule replaces the host's super-volume-only rule.
                param.args[0] = !expanded && policy.outside
            }
        }
    }

    private fun hookColumn() {
        val column = COLUMN.toClassOrNull() ?: return
        column.declaredMethods.singleOrNull { it.name == "setSuperVolumeParma" && it.parameterCount == 0 }?.hook {
            after { param ->
                // The host has just recomputed the label's real margin for this configuration.
                val text = HostAccess.readDeclared(param.thisObject, "superVolume") as? TextView ?: return@after
                val margin = (text.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: return@after
                session.labels[text]?.topMargin = margin
            }
        }
        column.declaredMethods.singleOrNull { it.name == "initColumn" && it.parameterCount == 6 }?.hook {
            after { param ->
                val self = param.thisObject
                val text = HostAccess.readDeclared(self, "superVolume") as? TextView ?: return@after
                track(text, self, column = true)
                if (shouldSuppress(text)) hide(text)
                val inCC = columnInControlCenter(self)
                if (replaying || !policy.volumePanel(inCC)) return@after
                val expanded = param.args[5] as? Boolean ?: false
                text.visibility = if (policy.innerVolume(expanded, inCC)) View.VISIBLE else View.INVISIBLE
                styleText(text, inside = true, inCC = inCC, expanded = expanded)
            }
        }
    }

    private fun hookController() {
        val type = CONTROLLER.toClassOrNull() ?: return
        fun method(name: String, vararg parameters: String) = type.declaredMethods.singleOrNull { method ->
            method.name == name && method.parameterTypes.map { it.name } == parameters.toList()
        }

        method("initPanelView")?.hook {
            before { param -> captureController(param.thisObject) }
            after { param -> suppressExcluded(param.thisObject) }
        }
        method("showVolumePanelH", "int")?.hook {
            before { param ->
                val controller = param.thisObject
                captureController(controller)
                if (!enabled(controller)) return@before
                // Fill the capsule before the dialog becomes visible (first press and cold start).
                (HostAccess.readDeclared(controller, "mActiveStream") as? Int)?.let { stream ->
                    val text = HostAccess.readDeclared(controller, "mSuperVolume") as? TextView
                    streamPercent(controller, stream)?.let { if (text != null) setPercentIfChanged(text, it) }
                }
                badgeApplied = false
                applyBadge(controller)
            }
            after { param -> suppressExcluded(param.thisObject) }
        }
        method("updateVolumeColumnH", COLUMN)?.hook {
            before { param -> captureController(param.thisObject) }
            after { param ->
                param.args[0]?.let { updateColumn(param.thisObject, it) }
                suppressExcluded(param.thisObject)
            }
        }
        method("updateSuperVolumeView", COLUMN)?.hook {
            before { param -> captureController(param.thisObject) }
            after { param ->
                val controller = param.thisObject
                if (enabled(controller)) {
                    val text = param.args[0]?.let { HostAccess.readDeclared(it, "superVolume") } as? TextView
                    val show = policy.innerVolume(expanded(controller), inControlCenter(controller))
                    text?.visibility = if (show) View.VISIBLE else View.INVISIBLE
                }
                suppressExcluded(controller)
            }
        }
        method("updateSuperVolumeText", TextView::class.java.name, "int")?.hook {
            intercept { chain ->
                val controller = chain.thisObject
                captureController(controller)
                val handled = (chain.args[0] as? TextView)?.let { renderText(controller, it) } ?: false
                val result = if (handled) null else chain.proceed()
                suppressExcluded(controller)
                result
            }
        }
        // The host's own capsule setup runs first; the module then paints the selected material.
        method("initSuperVolumeColor")?.hook { after { param -> applyBadge(param.thisObject, forceMaterial = true) } }
        // Material-mode and configuration (including UI_MODE) changes never re-init this capsule.
        method("onMaterialModeChanged")?.hook { after { badgeApplied = false; refresh() } }
        method("onConfigChanged", "int")?.hook { after { badgeApplied = false; refresh() } }
    }

    /**
     * The host anchors the capsule's top edge above the collapsed bar. A capsule enlarged for the
     * custom size keeps its bottom edge there instead, so it grows upward away from the bar.
     */
    private fun hookCapsuleMargin() {
        MOTION.toClassOrNull()?.declaredMethods?.singleOrNull { it.name == "updateSuperVolumeState" && it.parameterCount == 0 }?.hook {
            after { param ->
                if (replaying) return@after
                runCatching {
                    val background = HostAccess.readDeclared(param.thisObject, "mSuperVolume") as? View ?: return@runCatching
                    val owner = owners[background]?.ref?.get() ?: return@runCatching
                    val params = background.layoutParams as? ViewGroup.MarginLayoutParams ?: return@runCatching
                    val native = badgeStyler.nativeHeight(background, loader(owner)) ?: return@runCatching
                    val extra = params.height - native
                    if (extra > 0) {
                        params.topMargin -= extra
                        background.layoutParams = params
                    }
                }.onFailure { DebugLog.w(TAG, "Cannot lift enlarged volume capsule", it) }
            }
        }
    }

    // ─── Rendering ────────────────────────────────────────────────────────────

    private fun updateColumn(controller: Any, column: Any) {
        if (!enabled(controller)) return
        runCatching {
            val stream = columnStream(column) ?: return
            val percent = streamPercent(controller, stream) ?: return
            val expanded = expanded(controller)
            val inCC = inControlCenter(controller)
            (HostAccess.readDeclared(column, "superVolume") as? TextView)?.let { text ->
                setPercentIfChanged(text, percent)
                val show = policy.innerVolume(expanded, inCC)
                text.visibility = if (show) View.VISIBLE else View.INVISIBLE
                // A hidden inner label means the outer capsule is in charge: repaint it below.
                if (show) styleText(text, inside = true, inCC = inCC, expanded = expanded) else badgeApplied = false
            }
            val active = HostAccess.readDeclared(controller, "mActiveStream") as? Int
            if (expanded || inCC || !policy.outside || stream != active) return
            (HostAccess.readDeclared(controller, "mSuperVolume") as? TextView)?.let { text ->
                setPercentIfChanged(text, percent)
                text.visibility = View.VISIBLE
                session.style.popup(text, policy)
            }
            if (!badgeApplied) {
                applyBadge(controller)
                badgeApplied = true
            }
        }.onFailure { DebugLog.w(TAG, "Cannot update volume column percentage", it) }
    }

    /** Replaces the host's super-volume text for a known stream; false lets the host render it. */
    private fun renderText(controller: Any, text: TextView): Boolean {
        if (!enabled(controller)) return false
        return runCatching {
            val outer = text === HostAccess.readDeclared(controller, "mSuperVolume")
            val stream = if (outer) HostAccess.readDeclared(controller, "mActiveStream") as? Int else columnOf(controller, text)?.let(::columnStream)
            val percent = stream?.let { streamPercent(controller, it) } ?: return false
            setPercentIfChanged(text, percent)
            val expanded = expanded(controller)
            val inCC = inControlCenter(controller)
            text.visibility = when {
                outer -> if (policy.outerVolume(expanded, inCC)) View.VISIBLE else View.GONE
                else -> if (policy.innerVolume(expanded, inCC)) View.VISIBLE else View.INVISIBLE
            }
            styleText(text, inside = !outer, inCC = inCC, expanded = expanded)
            true
        }.onFailure { DebugLog.w(TAG, "Cannot render super-volume percentage", it) }.getOrDefault(false)
    }

    /** Bare percentage text; inner labels drop 12dp when the capsule sits inside the collapsed bar. */
    private fun styleText(text: TextView, inside: Boolean, inCC: Boolean, expanded: Boolean) {
        val baseline = session.labels.capture(text, geometry = true)
        badgeStyler.detachTextBackground(text)
        val params = text.layoutParams as? ViewGroup.MarginLayoutParams
        val nativeTop = baseline.topMargin
        if (inside && params != null && nativeTop != null) {
            val offset = if (!inCC && !expanded && !policy.outside) (12f * text.resources.displayMetrics.density).toInt() else 0
            if (params.topMargin != nativeTop + offset) {
                params.topMargin = nativeTop + offset
                text.layoutParams = params
            }
        }
        text.alpha = 1f
        session.style.popup(text, policy)
    }

    /** Outer capsule: native geometry, the selected material, then the bare text on top. */
    private fun applyBadge(controller: Any, forceMaterial: Boolean = false) {
        if (!enabled(controller)) return
        runCatching {
            val background = HostAccess.readDeclared(controller, "mSuperVolumeBg") as? View ?: return
            val text = HostAccess.readDeclared(controller, "mSuperVolume") as? TextView ?: return
            val expanded = expanded(controller)
            val inCC = inControlCenter(controller)
            if (!inCC && policy.outside) {
                badgeStyler.restoreNativeSize(background, loader(controller), capsuleScale())
                // The host's show pre-draw computes margins from display metrics, insets and expansion.
                val motion = HostAccess.readDeclared(controller, "mVolumeView")?.let { HostAccess.readDeclared(it, "mMotion") }
                motion?.let { HostAccess.declaredMethod(it.javaClass, "updateSuperVolumeState", accessible = true)?.invoke(it) }
            }
            if (forceMaterial) badgeStyler.invalidateAppearance(background)
            badgeStyler.detachTextBackground(text)
            badgeStyler.applyCapsule(background, expanded, loader(controller), policy.popupStyle)
            session.style.popup(text, policy)
            val visibility = if (policy.outerVolume(expanded, inCC)) View.VISIBLE else View.GONE
            listOf(background, text).forEach {
                it.visibility = visibility
                if (visibility == View.VISIBLE) it.alpha = 1f
            }
        }.onFailure { DebugLog.w(TAG, "Cannot apply volume capsule", it) }
    }

    /** The capsule grows with the label size; it never shrinks below the native capsule. */
    private fun capsuleScale(): Float = (policy.typography.effective().sizePercent / 100f).coerceAtLeast(1f)

    // ─── Baselines and excluded scopes ────────────────────────────────────────

    fun captureController(controller: Any) {
        if (replaying) return
        controllers[controller] = true
        (HostAccess.readDeclared(controller, "mSuperVolumeBg") as? View)?.let { background ->
            // Repair a size left by an earlier generation before taking a new baseline.
            if (session.labels[background] == null) badgeStyler.restoreNativeSize(background, loader(controller))
            track(background, controller)
        }
        (HostAccess.readDeclared(controller, "mSuperVolume") as? TextView)?.let { text ->
            // Same for the font, once; later writes belong to the style pass only.
            if (session.labels[text] == null) text.typeface = PercentLabelStyle.POPUP_TYPEFACE
            track(text, controller)
        }
        columns(controller).forEach { column -> (HostAccess.readDeclared(column, "superVolume") as? View)?.let { track(it, controller) } }
    }

    private fun track(view: View, owner: Any, column: Boolean = false) {
        if (replaying) return
        session.labels.capture(view, geometry = true)
        if (owners[view]?.ref?.get() !== owner) owners[view] = Owner(owner, column)
    }

    private fun shouldSuppress(view: View): Boolean {
        if (replaying) return false
        val record = owners[view] ?: return false
        val owner = record.ref.get() ?: return false
        return policy.suppressVolumePanel(if (record.column) columnInControlCenter(owner) else inControlCenter(owner))
    }

    private fun suppressExcluded(controller: Any) {
        if (replaying) return
        captureController(controller)
        owners.keys.toList().filter(::shouldSuppress).forEach(::hide)
    }

    private fun hide(view: View) {
        view.visibility = View.GONE
        view.alpha = 0f
    }

    private fun restoreViews() {
        owners.keys.toList().forEach { view ->
            badgeStyler.clearMaterial(view)
            session.labels.restore(view)
        }
    }

    /** Re-run the native renderer with overrides suspended, then apply the committed policy. */
    fun refresh() {
        replaying = true
        try { restoreViews() } finally { replaying = false }
        controllers.keys.toList().forEach { controller ->
            runCatching {
                replaying = true
                try { nativeRender(controller) } finally { replaying = false }
                captureController(controller)
                columns(controller).forEach { invokeColumnUpdate(controller, it) }
                (HostAccess.readDeclared(controller, "mSuperVolume") as? TextView)?.let { renderText(controller, it) }
                applyBadge(controller)
                suppressExcluded(controller)
            }.onFailure { DebugLog.w(TAG, "Cannot refresh living volume panel", it) }
        }
    }

    fun retire() {
        replaying = true
        restoreViews()
        controllers.keys.toList().forEach { runCatching { nativeRender(it) } }
        controllers.clear()
        owners.clear()
        badgeStyler.clear()
    }

    private fun nativeRender(controller: Any) {
        HostAccess.declaredMethod(controller.javaClass, "initSuperVolumeColor", accessible = true)?.invoke(controller)
        columns(controller).forEach { invokeColumnUpdate(controller, it) }
    }

    private fun invokeColumnUpdate(controller: Any, column: Any) {
        HostAccess.declaredMethod(controller.javaClass, "updateVolumeColumnH", column.javaClass, accessible = true)?.invoke(controller, column)
    }

    // ─── Host reads ───────────────────────────────────────────────────────────

    private fun enabled(controller: Any) = !replaying && policy.volumePanel(inControlCenter(controller))
    private fun inControlCenter(owner: Any) = HostAccess.readDeclared(owner, "isControlCenterPanel") as? Boolean ?: false
    private fun expanded(controller: Any) = HostAccess.readDeclared(controller, "mExpanded") as? Boolean ?: false
    private fun loader(controller: Any) = requireNotNull(controller.javaClass.classLoader)
    private fun columns(controller: Any) = (HostAccess.readDeclared(controller, "mColumns") as? List<*>)?.filterNotNull().orEmpty()

    private fun columnInControlCenter(column: Any) =
        runCatching { HostAccess.publicMethod(column.javaClass, "isInCCMainPage", accessible = false)?.invoke(column) as? Boolean }.getOrNull() ?: false

    private fun columnStream(column: Any): Int? =
        runCatching { HostAccess.publicMethod(column.javaClass, "getStream", accessible = false)?.invoke(column) as? Int }.getOrNull()

    private fun columnOf(controller: Any, text: TextView): Any? =
        columns(controller).firstOrNull { HostAccess.readDeclared(it, "superVolume") === text }

    /** `mState.states.get(stream)` level over the stream's own min/max. */
    private fun streamPercent(controller: Any, stream: Int): Int? = runCatching {
        val state = HostAccess.readDeclared(controller, "mState") ?: return null
        val states = HostAccess.readDeclared(state, "states") ?: return null
        val streamState = HostAccess.publicMethod(states.javaClass, "get", Int::class.javaPrimitiveType!!, accessible = false)
            ?.invoke(states, stream) ?: return null
        volumePercent(
            HostAccess.readDeclared(streamState, "level") as? Int ?: return null,
            HostAccess.readDeclared(streamState, "levelMin") as? Int ?: 0,
            HostAccess.readDeclared(streamState, "levelMax") as? Int ?: return null
        )
    }.getOrNull()

    companion object {
        private const val TAG = "VolumeSlider"
        const val CONTROLLER = "com.android.systemui.miui.volume.VolumePanelViewController"
        const val COLUMN = "com.android.systemui.miui.volume.VolumeColumn"
        const val DIALOG_VIEW = "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        const val MOTION = "com.android.systemui.miui.volume.MiuiVolumeDialogMotion"
    }
}
