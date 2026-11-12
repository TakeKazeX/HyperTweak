package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test

/** Stateful host doubles exercise the real SIM-hiding callback without installing any hooks. */
class CarrierAirplaneVisibilityTest {
    @Suppress("DEPRECATION")
    private class HostResources : Resources(null, null, null) {
        override fun getIdentifier(name: String?, defType: String?, defPackage: String?) = 42
        override fun getString(id: Int) = "飞行模式"
    }
    private class Label : TextView(ContextWrapper(null)) {
        private val hostResources = HostResources()
        var content: CharSequence = "SIM 2"
        var shown = View.VISIBLE
        override fun getResources() = hostResources
        override fun getText() = content
        override fun getVisibility() = shown
        override fun setVisibility(visibility: Int) { shown = visibility }
    }
    private class Row(val label: Label) : FrameLayout(ContextWrapper(null)) {
        @JvmField val isKeyguardLayout = true
        @JvmField val innerCarrierSlotId = 1
        @JvmField val carrierTextView = label
    }

    @Test fun airplaneExemptionDoesNotRestoreOverAnotherOwnersHide() {
        val owner = HideCarrierLabelHooker
        val changes = mutableListOf<Pair<Any, Any?>>()
        fun state(name: String, value: Any) {
            val slot = owner.javaClass.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(owner)
            val field = slot.javaClass.getDeclaredField("value").apply { isAccessible = true }
            changes += slot to field.get(slot)
            field.set(slot, value)
        }
        val hidden = owner.javaClass.getDeclaredField("hiddenViews").apply { isAccessible = true }.get(owner)
        @Suppress("UNCHECKED_CAST") val masks = hidden as MutableMap<View, Int>
        val callback = owner.javaClass.getDeclaredMethod("hideRequestedRow", Any::class.java).apply { isAccessible = true }
        try {
            state("keyguardLayoutField", Row::class.java.getDeclaredField("isKeyguardLayout"))
            state("carrierTextViewField", Row::class.java.getDeclaredField("carrierTextView"))
            state("hideLsTwo", true)
            masks.clear()
            val row = Row(Label())
            callback.invoke(owner, row)
            assertEquals(View.GONE, row.label.shown)
            assertEquals(View.VISIBLE, masks[row.label])
            row.label.content = "飞行模式"
            // Native callback re-shows it, then the airplane-label policy requests GONE.
            row.label.shown = View.GONE
            callback.invoke(owner, row)
            assertEquals(View.GONE, row.label.shown)
            assertFalse(masks.containsKey(row.label))
            row.label.shown = View.VISIBLE // A system/default airplane label must remain visible too.
            callback.invoke(owner, row)
            assertEquals(View.VISIBLE, row.label.shown)
        } finally {
            masks.clear()
            changes.asReversed().forEach { (slot, previous) ->
                slot.javaClass.getDeclaredField("value").apply { isAccessible = true }.set(slot, previous)
            }
        }
    }
}
