package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
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
    private class Row(val label: Label, @JvmField val isKeyguardLayout: Boolean = true,
        @JvmField val innerCarrierSlotId: Int = 1) : FrameLayout(ContextWrapper(null)) {
        @JvmField val carrierTextView = label
        @JvmField val hdText = Label().apply { content = "HD" }
        @JvmField val plusText = Label().apply { content = "+" }
        var shown = View.VISIBLE
        override fun getVisibility() = shown
        override fun setVisibility(visibility: Int) { shown = visibility }
    }

    @Test fun controlCenterWholeRowsAndHdRestoreWithoutChangingLockscreenOrAirplaneLabels() {
        val owner = HideCarrierLabelHooker
        val changes = mutableListOf<Pair<Any, Any?>>()
        fun state(name: String, value: Any) {
            val slot = owner.javaClass.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(owner)
            val field = slot.javaClass.getDeclaredField("value").apply { isAccessible = true }
            changes += slot to field.get(slot)
            field.set(slot, value)
        }
        @Suppress("UNCHECKED_CAST")
        val masks = owner.javaClass.getDeclaredField("hiddenViews").apply { isAccessible = true }.get(owner) as MutableMap<View, Int>
        val callback = owner.javaClass.getDeclaredMethod("hideRequestedRow", Any::class.java).apply { isAccessible = true }
        try {
            state("keyguardLayoutField", Row::class.java.getDeclaredField("isKeyguardLayout"))
            state("carrierTextViewField", Row::class.java.getDeclaredField("carrierTextView"))
            state("hdTextField", Row::class.java.getDeclaredField("hdText"))
            state("plusTextField", Row::class.java.getDeclaredField("plusText"))
            masks.clear()
            val cc = listOf(Row(Label(), false, 0), Row(Label(), false, 1))
            val lockscreen = Row(Label())
            val hiddenSlots = listOf(emptySet(), setOf(0), setOf(1), setOf(0, 1))
            for (mode in listOf(0, 1, 2, 3, 2, 1, 0, 3)) {
                state("ccCarrierHiding", mode)
                cc.forEachIndexed { slot, row ->
                    callback.invoke(owner, row)
                    val expected = if (slot in hiddenSlots[mode]) View.GONE else View.VISIBLE
                    assertEquals("mode=$mode slot=$slot", expected, row.label.shown)
                    assertEquals(expected, row.shown)
                    assertEquals(expected, row.hdText.shown)
                    assertEquals(expected, row.plusText.shown)
                    // Host text and HD callbacks can re-show each child independently.
                    owner.javaClass.getDeclaredMethod("prepareHdUpdate", Any::class.java).apply { isAccessible = true }.invoke(owner, row)
                    row.hdText.shown = View.VISIBLE
                    row.plusText.shown = View.VISIBLE
                    row.label.shown = View.VISIBLE
                    callback.invoke(owner, row)
                    assertEquals(expected, row.label.shown)
                    assertEquals(expected, row.shown)
                    assertEquals(expected, row.hdText.shown)
                    assertEquals(expected, row.plusText.shown)
                }
                callback.invoke(owner, lockscreen)
                assertEquals(View.VISIBLE, lockscreen.label.shown)
                assertEquals(View.VISIBLE, lockscreen.shown)
                assertEquals(View.VISIBLE, lockscreen.hdText.shown)
                assertEquals(View.VISIBLE, lockscreen.plusText.shown)
            }
            fun config(twoLine: Boolean) = IconConfigDecoder.decode { key -> when (key) {
                IconSettingsKeys.KEY_ICON_CC_CARRIER_HIDING -> 3
                SystemUiSettingsKeys.KEY_CC_HIDE_DATE -> true
                SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE -> twoLine
                else -> null
            } }
            // Opening compact layout suspends only this dropdown and releases native row masks.
            state("ccCarrierHiding", config(true).controlCenter.nativeCarrierHiding)
            cc.forEach { row ->
                callback.invoke(owner, row)
                assertEquals(View.VISIBLE, row.shown)
                assertEquals(View.VISIBLE, row.label.shown)
                assertEquals(View.VISIBLE, row.hdText.shown)
                assertEquals(View.VISIBLE, row.plusText.shown)
            }
            state("ccCarrierHiding", config(false).controlCenter.nativeCarrierHiding)
            cc.forEach { row -> callback.invoke(owner, row); assertEquals(View.GONE, row.shown) }
            // HD can turn off while its row is hidden; release must restore the latest native state.
            owner.javaClass.getDeclaredMethod("prepareHdUpdate", Any::class.java).apply { isAccessible = true }.invoke(owner, cc[0])
            cc[0].hdText.shown = View.GONE
            cc[0].plusText.shown = View.GONE
            callback.invoke(owner, cc[0])
            owner.onPrepareHotReload()
            assertEquals(View.GONE, cc[0].hdText.shown)
            assertEquals(View.GONE, cc[0].plusText.shown)
            assertEquals(View.VISIBLE, cc[1].hdText.shown)
            assertEquals(View.VISIBLE, cc[1].plusText.shown)
            cc.forEach { assertEquals(View.VISIBLE, it.shown) }
            cc.forEach { assertEquals(View.VISIBLE, it.label.shown) }
            state("ccCarrierHiding", 0)
            cc.forEach { callback.invoke(owner, it); assertEquals(View.VISIBLE, it.label.shown) }
            state("ccCarrierHiding", 3)
            cc[1].label.content = "飞行模式"
            callback.invoke(owner, cc[1])
            assertEquals(View.VISIBLE, cc[1].label.shown)
            assertEquals(View.VISIBLE, cc[1].shown)
            assertFalse(masks.containsKey(cc[1].label))
        } finally {
            masks.clear()
            changes.asReversed().forEach { (slot, previous) ->
                slot.javaClass.getDeclaredField("value").apply { isAccessible = true }.set(slot, previous)
            }
        }
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
