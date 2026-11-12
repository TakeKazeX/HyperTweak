package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Picture
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.presentation.icon.AirplaneLabelGeometry
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Main-thread registry. Producers retire only their own labels; endpoints never retain a View. */
internal object AirplaneGlyphTargets {
    enum class Surface { CONTROL_CENTER, KEYGUARD }
    enum class Owner { HOST_LABEL, COMPACT_CARRIER }
    private data class Registration(val surface: Surface, val owner: Owner)

    /** Stable while its weak-key label lives, including across host text rewrites. */
    class Endpoint internal constructor(view: TextView) : GlyphEndpoint {
        private val reference = WeakReference(view)
        override val view: TextView? get() = reference.get()
        private val span get() = view?.let { AirplaneLabelText.span(it.text) }
        private var alpha = 1f
        val motion = GlyphMotionLease { DebugLog.w("IconTuner", "airplane motion release failed", it) }

        override var value: Float
            get() = alpha
            set(next) { alpha = next; sync() }
        val order: AirplaneLabelGeometry.Order? get() = span?.order
        override fun picture(): Picture? = view?.let { AirplaneLabelText.span(it.text)?.picture(it) }

        internal fun sync() {
            val view = view ?: return
            val span = AirplaneLabelText.span(view.text) ?: return
            if (span.glyphAlpha == alpha) return
            span.glyphAlpha = alpha
            view.invalidate()
        }

        fun reset() { value = 1f }
        internal fun retire() { motion.retire(); reset() }
    }

    private val labels = WeakHashMap<TextView, Registration>()
    private val endpoints = WeakHashMap<TextView, Endpoint>()
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            // A reattached label keeps its producer registration, but no detached motion or alpha.
            (view as? TextView)?.let { endpoints.remove(it)?.retire() }
        }
    }

    @JvmOverloads
    fun register(view: TextView, surface: Surface, owner: Owner = Owner.HOST_LABEL) {
        val previous = labels.put(view, Registration(surface, owner))
        if (previous == null) view.addOnAttachStateChangeListener(attachment)
        else if (previous.owner != owner) endpoints.remove(view)?.retire()
        endpoints[view]?.sync()
    }

    @JvmOverloads
    fun unregister(view: TextView, owner: Owner = Owner.HOST_LABEL) {
        if (labels[view]?.owner != owner) return
        labels.remove(view)
        endpoints.remove(view)?.retire()
        view.removeOnAttachStateChangeListener(attachment)
    }

    fun release(owner: Owner) {
        labels.entries.filter { it.value.owner == owner }.map { it.key }.forEach { unregister(it, owner) }
    }

    fun controlCenter(): Endpoint? = visible(Surface.CONTROL_CENTER)
    fun keyguard(carrier: View, drawn: Boolean = true): Endpoint? = visible(Surface.KEYGUARD, carrier, drawn)

    private fun visible(surface: Surface, within: View? = null, drawn: Boolean = true): Endpoint? {
        labels.entries.forEach { (view, registration) ->
            if (registration.surface != surface) return@forEach
            if (within != null && !view.isDescendantOf(within)) return@forEach
            val span = AirplaneLabelText.span(view.text) ?: return@forEach
            if (!view.isAttachedToWindow || drawn && span.drawnBounds.isEmpty) return@forEach
            if (!signalEndpointVisible(view, view.rootView ?: return@forEach)) return@forEach
            return endpoints.getOrPut(view) { Endpoint(view) }
        }
        return null
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var node: View? = this
        while (node != null) {
            if (node === ancestor) return true
            node = node.parent as? View
        }
        return false
    }

    /** Full teardown/test cleanup; scoped feature retirement must call [release] instead. */
    fun releaseAll() { Owner.entries.forEach(::release) }
}
