package com.takekazex.hypertweak.hook.rules.systemui.slider

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Control-center brightness/volume tiles and the expanded brightness panel. The label is the
 * holder's `topText`; every write goes through [render], which captures the host baseline first.
 */
internal class ControlCenterPercentage(private val session: SliderPercentageHooker) : HookComponent(session) {
    private class Entry(val kind: LabelKind, label: TextView, slider: SeekBar?) {
        val label = WeakReference(label)
        val slider = WeakReference(slider)
    }

    private val entries = WeakHashMap<Any, Entry>()
    private val labelEntries = WeakHashMap<TextView, Entry>()
    private class Bounds(val from: IntArray, val to: IntArray)
    private val animatorBounds = WeakHashMap<Any, Bounds>()

    private var holderClass: Class<*>? = null

    fun install() {
        holderClass = CC_HOLDER.toClassOrNull()
        CC_BRIGHTNESS.toClassOrNull()?.let { hookController(it, LabelKind.BRIGHTNESS) }
        CC_VOLUME.toClassOrNull()?.let { volume ->
            hookController(volume, LabelKind.VOLUME)
            volume.declaredMethods.singleOrNull { it.name == "syncSystemVolume" && it.parameterCount == 0 }?.hook {
                after { param ->
                    val controller = param.thisObject
                    val entry = entry(controller, LabelKind.VOLUME) ?: return@after
                    val label = entry.label.get() ?: return@after
                    // Read the synced value now; the label write waits for the current frame.
                    val percent = percentOf(controller, entry)
                    label.post { render(controller, force = false, percent = percent) }
                }
            }
            volume.declaredMethods.singleOrNull {
                it.name == "updateSliderValue" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
            }?.hook {
                after { param ->
                    // `true` is the system-volume echo, which syncSystemVolume already renders.
                    if (param.args[1] as Boolean) return@after
                    render(param.thisObject, force = false, percent = sliderVolumePercent(param.thisObject, param.args[0] as Int))
                }
            }
        }
        holderClass?.declaredMethods?.singleOrNull { it.name == "updateBlendBlur" && it.parameterCount == 1 }?.hook {
            // The host clears blend effects when its blur changes (including the brightness mirror).
            after { param ->
                val label = topText(param.thisObject) ?: return@after
                val entry = labelEntries[label] ?: return@after
                if (session.policy.controlCenter(entry.kind.brightness)) {
                    session.style.controlCenter(label, entry.kind, session.policy, force = true, covered = covered(label, entry))
                }
            }
        }
        hookExpandedBrightness()
        hookBrightnessTransition()
    }

    private fun hookController(type: Class<*>, kind: LabelKind) {
        type.declaredMethods.singleOrNull { it.name == "onBindViewHolder" && it.parameterCount == 0 }?.hook {
            after { param -> bind(param.thisObject, kind)?.let { render(param.thisObject, force = true) } }
        }
        type.declaredMethods.singleOrNull { it.name == "onUnbindViewHolder" && it.parameterCount == 0 }?.hook {
            before { param -> release(param.thisObject) }
        }
        // A hot reload while the panel is closed finds no living owner (the seek-bar listener is
        // only set while listening), and the host keeps the label GONE. Opening the panel renders.
        type.declaredMethods.singleOrNull {
            it.name == "setListening" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }?.hook {
            after { param -> if (param.args[0] == true) render(param.thisObject, force = true) }
        }
        type.declaredMethods.filter { it.name == "updateIconProgress" }.forEach { method ->
            method.hook { after { param -> render(param.thisObject, force = false) } }
        }
    }

    // ─── Expanded brightness panel ────────────────────────────────────────────

    private fun hookExpandedBrightness() {
        val delegate = EXPANDED_DELEGATE.toClassOrNull() ?: return
        delegate.declaredMethods.filter { it.name == "prepareShow" || it.name == "updateIconProgress" }.forEach { method ->
            method.hook {
                before { param -> trackExpanded(param.thisObject) }
                after { param ->
                    val controller = param.thisObject
                    if (method.name == "prepareShow") {
                        render(controller, force = true)
                        // Let the host publish the current value through updateIconProgress below.
                        if (session.policy.controlCenter(true)) HostAccess.declaredMethod(controller.javaClass, "updateIconProgress",
                            Boolean::class.javaPrimitiveType!!, accessible = true)?.invoke(controller, true)
                    } else render(controller, force = false)
                }
            }
        }
    }

    fun trackExpanded(delegate: Any) {
        // Runs before every expanded-slider frame; the delegate's binding never changes.
        if (entries[delegate]?.label?.get() != null) return
        runCatching {
            val binding = HostAccess.readDeclared(delegate, "binding") ?: return
            val toggle = HostAccess.requirePublicField(binding.javaClass, "toggleSlider", accessible = true).get(binding) ?: return
            val label = HostAccess.requirePublicField(toggle.javaClass, "topText", accessible = true).get(toggle) as? TextView ?: return
            val slider = HostAccess.requirePublicField(toggle.javaClass, "slider", accessible = true).get(toggle) as? SeekBar
            track(delegate, Entry(LabelKind.EXPANDED_BRIGHTNESS, label, slider))
        }.onFailure { DebugLog.w(TAG, "Cannot track expanded brightness", it) }
    }

    /** The panel animator moves the label between the tile and the panel; follow its frames. */
    private fun hookBrightnessTransition() {
        val animator = BRIGHTNESS_ANIMATOR.toClassOrNull() ?: return
        fun targetLabel(animator: Any): TextView? {
            val to = HostAccess.declaredMethod(animator.javaClass, "getToView", accessible = true)?.invoke(animator) ?: return null
            val binding = HostAccess.publicMethod(to.javaClass, "getSliderBinding", accessible = false)?.invoke(to) ?: return null
            return HostAccess.requirePublicField(binding.javaClass, "topText", accessible = false).get(binding) as? TextView
        }
        animator.declaredMethods.singleOrNull { it.name == "calculateViewValues" && it.parameterCount == 0 }?.hook {
            after { param ->
                if (!session.policy.controlCenter(true)) return@after
                runCatching {
                    val self = param.thisObject
                    val from = HostAccess.readDeclared(self, "fromView") ?: return@runCatching
                    val fromLabel = HostAccess.publicMethod(from.javaClass, "getTopText", accessible = false)?.invoke(from) as? View ?: return@runCatching
                    val toLabel = targetLabel(self) ?: return@runCatching
                    animatorBounds[self] = Bounds(fromLabel.bounds(), toLabel.bounds())
                }.onFailure { DebugLog.w(TAG, "Cannot capture brightness transition bounds", it) }
            }
        }
        animator.declaredMethods.singleOrNull { it.name == "frameCallback" && it.parameterCount == 0 }?.hook {
            after { param ->
                if (!session.policy.controlCenter(true)) return@after
                runCatching {
                    val self = param.thisObject
                    val bounds = animatorBounds[self] ?: return@runCatching
                    val fraction = HostAccess.readDeclared(self, "size") as? Float ?: return@runCatching
                    val label = targetLabel(self) ?: return@runCatching
                    val at = IntArray(4) { (bounds.from[it] + (bounds.to[it] - bounds.from[it]) * fraction).toInt() }
                    label.setLeftTopRightBottom(at[0], at[1], at[0] + at[2], at[1] + at[3])
                }.onFailure { DebugLog.w(TAG, "Cannot follow brightness transition", it) }
            }
        }
    }

    private fun View.bounds() = intArrayOf(left, top, width, height)

    // ─── Render ───────────────────────────────────────────────────────────────

    /** Resolves the bound holder's views; null while the controller is unbound. */
    private fun bind(controller: Any, kind: LabelKind): Entry? {
        val holder = holder(controller) ?: return null
        val label = topText(holder) ?: return null
        val slider = runCatching { HostAccess.publicMethod(holder.javaClass, "getSlider", accessible = false)?.invoke(holder) as? SeekBar }.getOrNull()
        val previous = entries[controller]?.label?.get()
        // A recycled holder: the previous label returns to the host before the new one is used.
        if (previous != null && previous !== label) session.labels.restore(previous)
        return Entry(kind, label, slider).also { track(controller, it) }
    }

    private fun track(controller: Any, entry: Entry) {
        entries[controller] = entry
        entry.label.get()?.let { labelEntries[it] = entry }
    }

    private fun entry(controller: Any, kind: LabelKind): Entry? =
        entries[controller]?.takeIf { it.label.get() != null } ?: bind(controller, kind)

    private fun render(controller: Any, force: Boolean, percent: Int? = null) {
        val entry = entries[controller]?.takeIf { it.label.get() != null }
            ?: kindOf(controller)?.let { bind(controller, it) } ?: return
        val label = entry.label.get() ?: return
        if (!session.policy.controlCenter(entry.kind.brightness)) {
            session.labels.restore(label)
            return
        }
        runCatching {
            session.labels.capture(label, geometry = false)
            if (label.visibility != View.VISIBLE) label.visibility = View.VISIBLE
            (percent ?: percentOf(controller, entry))?.let { setPercentIfChanged(label, it) }
            session.style.controlCenter(label, entry.kind, session.policy, force, covered(label, entry))
        }.onFailure { DebugLog.w(TAG, "Cannot render ${entry.kind} percentage", it) }
    }

    /**
     * Whether the slider fill (drawn from the tile's bottom) reaches the label's centre. The label
     * and the slider share the tile frame; before layout a 15% top inset approximates the label.
     */
    private fun covered(label: TextView, entry: Entry): Boolean {
        val slider = entry.slider.get() ?: return false
        val range = (slider.max - slider.min).takeIf { it > 0 } ?: return false
        val fraction = ((slider.progress - slider.min).toFloat() / range).coerceIn(0f, 1f)
        val tile = (label.parent as? View)?.height ?: 0
        if (tile <= 0 || label.height <= 0) return fraction >= 0.85f
        return tile * (1f - fraction) <= label.top + label.height / 2f
    }

    private fun kindOf(controller: Any): LabelKind? = when (controller.javaClass.name) {
        CC_BRIGHTNESS -> LabelKind.BRIGHTNESS
        CC_VOLUME -> LabelKind.VOLUME
        else -> null
    }

    private fun percentOf(controller: Any, entry: Entry): Int? = when (entry.kind) {
        LabelKind.VOLUME -> runCatching {
            val volume = HostAccess.readDeclared(controller, "systemVolume") as? Int ?: return null
            if (volume == Int.MIN_VALUE) return null
            // systemVolume is a stream index; the slider range is in 1/1000 steps of it.
            volumePercent(volume * 1000, HostAccess.readDeclared(controller, "sliderMinValue") as? Int ?: return null,
                HostAccess.readDeclared(controller, "sliderMaxValue") as? Int ?: return null)
        }.getOrNull()
        LabelKind.BRIGHTNESS -> entry.slider.get()?.let(::brightnessPercent)
        LabelKind.EXPANDED_BRIGHTNESS -> entry.slider.get()?.let { volumePercent(it.progress, it.min, it.max) }
    }

    private fun sliderVolumePercent(controller: Any, value: Int): Int? = runCatching {
        val level = HostAccess.declaredMethod(controller.javaClass, "valueToVolume", Int::class.javaPrimitiveType!!, accessible = true)
            ?.invoke(controller, value) as? Int ?: return null
        volumePercent(level, HostAccess.readDeclared(controller, "streamMinVolume") as? Int ?: return null,
            HostAccess.readDeclared(controller, "streamMaxVolume") as? Int ?: return null)
    }.getOrNull()

    /** The animated target while dragging, falling back to progress; null keeps the last text. */
    private fun brightnessPercent(slider: SeekBar): Int? {
        val getter = HostAccess.cached(slider.javaClass, "slider-brightness:value-getter", java.lang.reflect.Method::class.java) {
            HostAccess.publicMethod(slider.javaClass, "getTargetValue", accessible = false)
                ?: HostAccess.publicMethod(slider.javaClass, "getValue", accessible = false)
        }
        val value = getter?.let { runCatching { it.invoke(slider) as? Int }.getOrNull() } ?: slider.progress
        return volumePercent(value, slider.min, slider.max)
    }

    /** `getSliderHolder()` returns ToggleSliderViewHolder on both controllers; brightness's is private. */
    private fun holder(controller: Any): Any? {
        val method = HostAccess.declaredMethod(controller.javaClass, "getSliderHolder", accessible = true)
            ?.takeIf { holderClass != null && it.returnType == holderClass } ?: return null
        return runCatching { method.invoke(controller) }.getOrNull()
    }

    private fun topText(holder: Any): TextView? =
        runCatching { HostAccess.publicMethod(holder.javaClass, "getTopText", accessible = false)?.invoke(holder) as? TextView }.getOrNull()

    private fun release(controller: Any) {
        entries.remove(controller)?.label?.get()?.let { label ->
            labelEntries.remove(label)
            session.labels.restore(label)
        }
    }

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /** Recovered from a living window: the slider's change listener owns the controller. */
    fun recover(controller: Any) {
        when (controller.javaClass.name) {
            EXPANDED_DELEGATE -> trackExpanded(controller)
            else -> kindOf(controller)?.let { bind(controller, it) }
        }
    }

    /** A committed policy change reaches the labels on screen now, not at their next bind. */
    fun refresh() {
        entries.entries.toList().forEach { (controller, entry) ->
            val label = entry.label.get() ?: return@forEach
            if (!label.isAttachedToWindow) return@forEach
            session.labels.restore(label)
            if (session.policy.controlCenter(entry.kind.brightness)) render(controller, force = true)
        }
    }

    fun retire() {
        entries.values.mapNotNull { it.label.get() }.forEach(session.labels::restore)
        entries.clear()
        labelEntries.clear()
        animatorBounds.clear()
    }

    companion object {
        private const val TAG = "SliderPercentage"
        const val CC_BRIGHTNESS = "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"
        const val CC_VOLUME = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"
        const val CC_HOLDER = "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder"
        const val EXPANDED_DELEGATE = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"
        const val BRIGHTNESS_ANIMATOR = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator"
    }
}
