package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AirplaneLabelMode
import com.takekazex.hypertweak.settings.contract.AirplaneLabelModes
import com.takekazex.hypertweak.settings.contract.AirplaneLabelPolicy
import com.takekazex.hypertweak.settings.contract.AirplaneLabelSurface
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftHandover
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftPanelMotion
import kotlin.math.abs
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Field
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * The host's carrier "airplane mode" label on the control-center, notification and lockscreen
 * headers: native, hidden, or drawn as the airplane glyph through [AirplaneLabelText].
 *
 * `ControlCenterCarrierText$mCarrierTextCallback$1.onCarrierTextChanged` is the only writer of the
 * row text. In airplane mode without VoWiFi the controller fires `(0, "", 0)` and then the label at
 * slot 1. The row is identified by comparing its text with the host string, resolved by name. The
 * surface comes from the parent `MiuiCarrierTextLayout` id and its keyguard flag. An unknown layout,
 * a missing string or a missing field leaves the native label.
 *
 * While the control-center label draws the glyph, the native airplane slot in the same header's
 * status icons yields (the other headers carry no status icons). Compact control-center rows
 * draw their own airplane text, which `CarrierRenderer` styles from the same option.
 *
 * Every drawn glyph is an [AirplaneGlyphTargets] endpoint. While the panel opens, the airplane in
 * the header's fake status row travels into the control-center glyph, unless the left container
 * owns that slot and moves its own clone there. A lockscreen visibility change asks the left
 * container to re-evaluate its carrier swap, so a hidden label never delays the icons.
 */
internal object AirplaneLabelHooker : IconRenderer() {
    override val hostCallbackOrder = 60 // SIM hiding/exemption resolves before the final label policy.
    private const val TAG = "AirplaneLabel"
    private const val LAYOUT_CLASS = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
    private const val CC_TEXT_CLASS = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
    private const val CC_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.ControlCenterCarrierText\$mCarrierTextCallback\$1"
    private const val STATUS_ICON_CONTAINER_CLASS = "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    private const val HOST_PACKAGE = "com.android.systemui"

    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val modes by hookSetting(AirplaneLabelModes()) { Preferences.iconConfig().misc.airplaneLabelModes }
    private val mirror by hookSetting(false) { Preferences.iconConfig().misc.mirrorAirplane }
    private var carrierTextField: Field? by generationState { null }
    private var keyguardField: Field? by generationState { null }
    private var leftRowField: Field? by generationState { null }
    private var rightRowField: Field? by generationState { null }
    private var callbackOwnerField: Field? by generationState { null }
    private var installed by generationState { false }

    /** Text views whose visibility this generation changed, with the visibility to restore. */
    private val hidden = generationMainResource(WeakHashMap<TextView, Int>()) { it.clear() }
    /** Text views carrying a module glyph. */
    private val decorated = generationMainResource(WeakHashMap<TextView, Boolean>()) { it.clear() }
    /** Status containers whose native airplane slot yields to the control-center label. */
    private val masked = generationMainResource(WeakHashMap<Any, Boolean>()) { it.clear() }
    /** The fake-row airplane travelling into the control-center glyph while the panel opens. */
    private var motion: LeftPanelMotion? by generationState { null }
    private var motionSource: View? by generationState { null }
    private var sourceSaved = 1f
    private var sourceApplied = 1f

    init {
        hostRecovery(HostRecoveryPhase.ICONS, icons = true) { scope ->
            if (!installed) return@hostRecovery
            scope.views.filter { it.javaClass.name == CC_TEXT_CLASS }.forEach { applyRow(it, hostWrote = false) }
        }
    }

    override fun onHook() {
        if (!modes.customized) {
            DebugLog.hookSkippedDebug(TAG, "carrier airplane label", "all surfaces native")
            return
        }
        val textClass = CC_TEXT_CLASS.toClassOrNull()
        val layoutClass = LAYOUT_CLASS.toClassOrNull()
        val callbackClass = CC_CALLBACK_CLASS.toClassOrNull()
        if (textClass == null || layoutClass == null || callbackClass == null) {
            DebugLog.hookSkipped(TAG, "carrier airplane label", "carrier classes not found")
            return
        }
        carrierTextField = HostAccess.field(textClass, "carrierTextView")
        keyguardField = HostAccess.field(textClass, "isKeyguardLayout")
        leftRowField = HostAccess.field(layoutClass, "leftCarrierTextView")
        rightRowField = HostAccess.field(layoutClass, "rightCarrierTextView")
        callbackOwnerField = HostAccess.field(callbackClass, "this\$0")
        if (listOf(carrierTextField, keyguardField, leftRowField, rightRowField, callbackOwnerField).any { it == null }) {
            DebugLog.hookSkipped(TAG, "carrier airplane label", "carrier fields not found")
            return
        }
        val method = callbackClass.findMethodOrNull { name("onCarrierTextChanged"); paramCount(3) } ?: run {
            DebugLog.hookSkipped(TAG, "$CC_CALLBACK_CLASS#onCarrierTextChanged", "method not found")
            return
        }
        method.observe {
            after { param ->
                if (param.throwable != null) return@after
                val owner = runCatching { callbackOwnerField?.get(param.thisObject) }.getOrNull() as? View
                    ?: return@after
                applyRow(owner, hostWrote = true)
            }
        }
        installed = true
        if (modes.controlCenter.drawsGlyph) PanelHub.subscribe(this) { updateMotion() }
        DebugLog.hookRegistered(TAG, "ControlCenterCarrierText callback#onCarrierTextChanged")
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            installed = false
            releaseMotion()
            AirplaneGlyphTargets.release(AirplaneGlyphTargets.Owner.HOST_LABEL)
            decorated.keys.toList().forEach { view ->
                runCatching { AirplaneLabelText.nativeText(view.text)?.let { if (AirplaneLabelText.hasGlyph(view.text)) view.text = it } }
            }
            decorated.clear()
            hidden.forEach { (view, visibility) -> if (view.visibility == View.GONE) view.visibility = visibility }
            hidden.clear()
            masked.keys.toList().forEach { runCatching { SlotMaskApplier.setAirplaneLabelMask(it, false) } }
            masked.clear()
            // A disabled label releases Duo/native ownership even if the replacement installs no hooks.
            IconHostPorts.left.visibilityChanged()
        }
    }

    /**
     * [hostWrote] is true right after the host callback, which has just set the text and made the
     * view visible; a stale hide record is then dropped instead of being restored.
     */
    private fun applyRow(row: View, hostWrote: Boolean) {
        runCatching {
            val text = carrierTextField?.get(row) as? TextView ?: return
            val layout = row.parent as? ViewGroup
            val surface = layout?.takeIf { it.javaClass.name == LAYOUT_CLASS }?.let { surfaceOf(it, row) }
            // Compact control-center rows replace the native label with their own views.
            val compact = surface == AirplaneLabelSurface.CONTROL_CENTER && IconHostPorts.carrier.owns(layout)
            val mode = if (surface == null || compact) AirplaneLabelMode.SYSTEM else modes[surface]
            val airplane = AirplaneLabelText.isAirplaneLabel(text)
            val native = AirplaneLabelText.nativeText(text.text).orEmpty()

            if (airplane && mode == AirplaneLabelMode.HIDE) {
                if (!hidden.containsKey(text)) hidden[text] = if (hostWrote) View.VISIBLE else text.visibility
                text.visibility = View.GONE
            } else hidden.remove(text)?.let { saved ->
                if (!hostWrote && text.visibility == View.GONE) text.visibility = saved
            }

            val glyphMode = if (airplane && mode.drawsGlyph) mode else AirplaneLabelMode.SYSTEM
            val drawn = AirplaneLabelText.apply(text, native, glyphMode, mirror) && glyphMode.drawsGlyph
            if (drawn) decorated[text] = true else decorated.remove(text)
            val endpoint = when (surface) {
                AirplaneLabelSurface.CONTROL_CENTER -> AirplaneGlyphTargets.Surface.CONTROL_CENTER
                AirplaneLabelSurface.KEYGUARD -> AirplaneGlyphTargets.Surface.KEYGUARD
                else -> null
            }
            if (drawn && endpoint != null) AirplaneGlyphTargets.register(text, endpoint)
                else AirplaneGlyphTargets.unregister(text)
            if (layout != null && surface == AirplaneLabelSurface.CONTROL_CENTER) updateTakeover(layout)
            // The left container's carrier swap and airplane location read this label. Posted: the
            // host delivers text while the window attaches, and a reconcile there edits the tree.
            if (surface == AirplaneLabelSurface.KEYGUARD) {
                row.post {
                    if (!installed) return@post
                    IconHostPorts.left.visibilityChanged()
                }
            }
        }.onFailure { DebugLog.w(TAG, "carrier airplane label update failed", it) }
    }

    private fun surfaceOf(layout: ViewGroup, row: View): AirplaneLabelSurface? {
        val name = layout.id.takeIf { it != View.NO_ID }?.let {
            runCatching { layout.resources.getResourceEntryName(it) }.getOrNull()
        }
        val keyguard = runCatching { keyguardField?.getBoolean(row) }.getOrNull() ?: return null
        return AirplaneLabelPolicy.surfaceFor(name, keyguard)
    }

    /** One header owns one mask: either of its two rows may carry the label. */
    private fun updateTakeover(layout: ViewGroup) {
        val active = listOf(leftRowField, rightRowField).any { field ->
            val row = runCatching { field?.get(layout) }.getOrNull() as? View ?: return@any false
            val text = runCatching { carrierTextField?.get(row) }.getOrNull() as? TextView ?: return@any false
            decorated.containsKey(text) && text.visibility == View.VISIBLE && row.visibility == View.VISIBLE
        }
        val container = statusContainer(layout) ?: return
        if (!active && !masked.containsKey(container)) return
        if (SlotMaskApplier.setAirplaneLabelMask(container, active)) {
            if (active) masked[container] = true else masked.remove(container)
        }
    }

    /**
     * Main thread. The left container owns the hand-over of a left-placed airplane; a shade switch
     * keeps the host's own rows. The fake-row icon stays the only copy until the overlay draws.
     */
    private fun updateMotion() {
        runCatching {
            val state = PanelHub.state
            val progress = state.progress
            val glyph = AirplaneGlyphTargets.controlCenter()
            val glyphView = glyph?.view
            val leftOwned = IconSlotPolicy.SLOT_AIRPLANE in IconHostPorts.left.homeSlots()
            if (!installed || glyph == null || glyphView == null || leftOwned || state.shadeSwitching ||
                !(progress > 0f && progress < 1f)) {
                releaseMotion()
                return
            }
            // The fake row is searched once per source; the drag reuses it while it stays usable.
            val source = motionSource?.takeIf {
                it.isAttachedToWindow && signalEndpointVisible(it, it.rootView) &&
                    HostAccess.read(it, "mSlot") == IconSlotPolicy.SLOT_AIRPLANE
            } ?: fakeRowAirplane(glyphView)
            val root = glyphView.rootView as? ViewGroup
            val picture = glyph.picture()
            if (source?.parent?.let { HandoverCoordinator.shade.ownsAirplane(it) } == true) {
                releaseMotion()
                return
            }
            if (source == null || root == null || picture == null) {
                releaseMotion()
                return
            }
            if (motionSource !== source) {
                releaseSource()
                motionSource = source
                sourceSaved = source.alpha
                sourceApplied = source.alpha
            } else if (abs(source.alpha - sourceApplied) > .001f) sourceSaved = source.alpha
            val current = motion ?: LeftPanelMotion { if (installed) updateMotion() }.also { motion = it }
            val ready = current.update(root, source, glyphView, progress, targetPicture = picture, targetAlpha = glyph)
            sourceApplied = sourceSaved * LeftHandover.sourceAlpha(progress, ready)
            if (source.alpha != sourceApplied) HandoverCoordinator.alpha(source, sourceApplied)
        }.onFailure {
            releaseMotion()
            DebugLog.w(TAG, "airplane glyph hand-over failed", it)
        }
    }

    private fun releaseMotion() {
        motion?.clear()
        motion = null
        releaseSource()
    }

    /** Restores the fake-row icon only while its alpha is still the one this motion wrote. */
    private fun releaseSource() {
        val source = motionSource ?: return
        motionSource = null
        if (abs(source.alpha - sourceApplied) < .001f) HandoverCoordinator.alpha(source, sourceSaved)
    }

    /** The airplane icon of the fake status row in the glyph's own header. */
    private fun fakeRowAirplane(label: View): View? {
        val id = ResourceLookup.identifier(label.resources, "normal_fake_control_center_status_bar", "id", HOST_PACKAGE)
        if (id == 0) return null
        var node = label.parent as? ViewGroup
        var depth = 0
        while (node != null && depth++ < 6) {
            val fake = node.findViewById<View>(id) as? ViewGroup
            if (fake != null) return findSlot(fake, IconSlotPolicy.SLOT_AIRPLANE)
                ?.takeIf { it.isAttachedToWindow && signalEndpointVisible(it, it.rootView) }
            node = node.parent as? ViewGroup
        }
        return null
    }

    private fun findSlot(root: ViewGroup, slot: String): View? {
        val queue = ArrayDeque<View>().apply { add(root) }
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 256) {
            val view = queue.removeFirst()
            if ((HostAccess.read(view, "mSlot") as? String) == slot) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
        }
        return null
    }

    private fun statusContainer(layout: ViewGroup): Any? {
        val header = layout.parent as? ViewGroup ?: return null
        val id = ResourceLookup.identifier(header.resources, "normal_control_center_status_bar", "id", HOST_PACKAGE)
        val root = (if (id == 0) null else header.findViewById<View>(id)) as? ViewGroup ?: return null
        val queue = ArrayDeque<View>().apply { add(root) }
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 256) {
            val view = queue.removeFirst()
            if (view.javaClass.name == STATUS_ICON_CONTAINER_CLASS) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
        }
        return null
    }
}
