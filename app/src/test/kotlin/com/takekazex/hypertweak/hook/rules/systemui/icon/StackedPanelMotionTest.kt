package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Picture
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.SignalRowMotion
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import org.junit.Assert.*
import org.junit.Test

/** Real cleanup methods with stateful view doubles; no window or device UI is involved. */
class StackedPanelMotionTest {
    private class AlphaView(var opacity: Float = 0f) : View(null) {
        override fun getAlpha() = opacity
        override fun setAlpha(value: Float) { opacity = value }
    }
    private class Root : ViewGroup(null) {
        var removals = 0
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        override fun isAttachedToWindow() = true
        override fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { removals++ }
    }
    private class Callback(@JvmField var `this$0`: Any?)
    private class Owner(@JvmField var headerController: Any? = null)
    class LazyHeader(private val value: Any?) { fun get(): Any? = value }
    private class Header(@JvmField var controlCenterStatusBar: Any? = null)

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(owner: Any, name: String, value: Any?) { field(owner, name).set(owner, value) }
    @Suppress("UNCHECKED_CAST") private fun <K, V> map(owner: Any, name: String) = field(owner, name).get(owner) as MutableMap<K, V>
    private fun seed(motion: StackedPanelMotion, copy: AlphaView, target: AlphaView) {
        map<View, Float>(motion, "masked")[copy] = .7f
        val row = SignalRowMotion()
        set(row, "target", target); set(row, "savedAlpha", .8f); set(row, "appliedAlpha", 0f)
        map<Int, SignalRowMotion>(motion, "motions")[11] = row
        map<Int, View>(motion, "targets")[11] = target
    }
    private fun assertReleased(motion: StackedPanelMotion, copy: AlphaView, target: AlphaView) {
        assertEquals(.7f, copy.alpha); assertEquals(.8f, target.alpha)
        assertTrue(map<Any, Any>(motion, "motions").isEmpty())
        assertTrue(map<Any, Any>(motion, "targets").isEmpty())
        assertTrue(map<Any, Any>(motion, "masked").isEmpty())
    }
    @Test fun submittedFirstFrameSurvivesUntilDrawButRejectedFrameReleasesEverything() {
        val motion = StackedPanelMotion(Callback(null)); val copy = AlphaView(); val target = AlphaView()
        seed(motion, copy, target)
        val submitted = map<Int, SignalRowMotion>(motion, "motions")[11]
        val finish = motion.javaClass.getDeclaredMethod("completeFrame", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        finish.invoke(motion, true, false)
        assertSame(submitted, map<Int, SignalRowMotion>(motion, "motions")[11])
        assertSame(target, map<Int, View>(motion, "targets")[11])
        assertEquals(.7f, copy.alpha) // Native copy remains available while draw is pending.
        finish.invoke(motion, false, false)
        assertReleased(motion, copy, target)
    }

    @Test fun missingOwnerHeaderExpandedOrRootRestoresExistingLayersDuringReverseDrag() {
        val callbacks = listOf(Callback(null), Callback(Owner()),
            Callback(Owner(LazyHeader(null))), Callback(Owner(LazyHeader(Header()))),
            Callback(Owner(LazyHeader(Header(AlphaView())))))
        for (callback in callbacks) for (progress in listOf(.7f, .3f)) {
            val motion = StackedPanelMotion(callback); val copy = AlphaView(); val target = AlphaView()
            seed(motion, copy, target)
            motion.update(listOf(DuoSignalRow(11, 0, 3)), Picture(), progress, true, .5f)
            assertReleased(motion, copy, target)
        }
    }
    @Test fun realIconRootLossDuringPreDrawReleasesExistingLayers() {
        val motion = StackedPanelMotion(Callback(Owner())); val copy = AlphaView(); val target = AlphaView()
        set(motion, "root", Root()); set(motion, "picture", Picture()); set(motion, "splitFraction", .5f)
        seed(motion, copy, target)
        (field(motion, "listener").get(motion) as android.view.ViewTreeObserver.OnPreDrawListener).onPreDraw()
        assertReleased(motion, copy, target)
    }

    @Test fun preDrawOwnerLossCancelAndDetachRestoreOnlyOwnedAlphaAndAllowReuse() {
        val motion = StackedPanelMotion(Callback(null)); val copy = AlphaView(); val target = AlphaView()
        val root = Root(); set(motion, "root", root); set(motion, "picture", Picture()); set(motion, "splitFraction", .5f)
        seed(motion, copy, target)
        (field(motion, "listener").get(motion) as android.view.ViewTreeObserver.OnPreDrawListener).onPreDraw()
        assertReleased(motion, copy, target); assertEquals(1, root.removals)
        seed(motion, copy, target); copy.opacity = .4f // A newer host alpha must survive cancellation.
        motion.update(emptyList(), null, 0f, false, null)
        assertEquals(.4f, copy.alpha); assertEquals(.8f, target.alpha)
        copy.opacity = 0f; target.opacity = 0f; seed(motion, copy, target)
        (field(motion, "detachListener").get(motion) as View.OnAttachStateChangeListener).onViewDetachedFromWindow(root)
        assertReleased(motion, copy, target)
        motion.clear(); assertEquals(.7f, copy.alpha)
    }
}
