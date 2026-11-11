package com.takekazex.hypertweak.hook.rules.systemui.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stretches and blurs expanded-island content while it is pulled down toward the mini window.
 *
 * The host's fake view publishes every drag and release-rebound frame through
 * `onFakeViewTrackingParamsUpdated()`: the outline grows downward from its rest height and
 * `alpha` falls toward the trigger. Natively only the outline grows, and the media island also
 * slides its album background by `pull_down_action_offset_y`, the tracker's sole consumer.
 * While enabled, that offset is published as zero and the fake expanded view's children stretch
 * from the top to the outline instead, with the host's content-blur scale. Each frame is derived
 * from the host's parameters alone, so drag, Folme rebound and mini-window takeover share one
 * path; the rest frame, a new gesture, a hidden or detached fake view, disabling the switch and
 * hot reload restore the captured native transforms.
 */
class IslandPullDownHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIslandPullDownStretch by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_PULL_DOWN_STRETCH, false)
    }

    private class Target(val view: View, val scaleY: Float, val pivotY: Float?)
    private class Stretch(val container: ViewGroup, val targets: List<Target>) {
        var blur = 0
    }

    private val main = Handler(Looper.getMainLooper())
    /** Fake view to its captured content; main-thread only. */
    private val stretches = WeakHashMap<View, Stretch>()
    @Volatile private var retiring = false
    private var setBlur: ((View, Int) -> Unit)? = null

    private val refresh = Runnable {
        if (!retiring && !settingIslandPullDownStretch) {
            HookFailurePolicy.open(TAG, "restore pulled-down islands", Unit) { releaseAll() }
        }
    }

    fun refreshSettings() {
        main.removeCallbacks(refresh)
        if (!retiring) main.post(refresh)
    }

    override fun onHook() {
        val fake = FAKE_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, FAKE_VIEW, "not found on host")
            return
        }
        val base = classLoader.loadClass("miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView")
        check(base.isAssignableFrom(fake))
        val params = classLoader.loadClass("miui.systemui.dynamicisland.window.content.FakeViewTrackingParams")
        val trackingParams = HostAccess.requireDeclaredField(fake, "mFakeViewTrackingParams", accessible = true).apply {
            isAccessible = true
            check(type == params)
        }
        val restHeight = HostAccess.requireDeclaredField(fake, "mFakeViewHeight", accessible = true).apply {
            isAccessible = true
            check(type == Int::class.javaPrimitiveType)
        }
        val int = Int::class.javaPrimitiveType!!
        val top = HostAccess.requirePublicMethod(params, "getTop", accessible = false).apply { check(returnType == int) }
        val bottom = HostAccess.requirePublicMethod(params, "getBottom", accessible = false).apply { check(returnType == int) }
        val alpha = HostAccess.requirePublicMethod(params, "getAlpha", accessible = false).apply {
            check(returnType == Float::class.javaPrimitiveType)
        }
        val expanded = HostAccess.requirePublicMethod(base, "getFakeExpandedView", accessible = false).apply {
            check(ViewGroup::class.java.isAssignableFrom(returnType))
        }
        val updated = HostAccess.requireDeclaredMethod(fake, "onFakeViewTrackingParamsUpdated", accessible = false).apply {
            check(returnType == Void.TYPE)
        }
        val tracker = classLoader.loadClass("$FAKE_VIEW\$ExpandedTracker")
        val updateTrack = HostAccess.requireDeclaredMethod(tracker, "updateTrack", Float::class.javaPrimitiveType, accessible = false)
        // The host's own content-blur path: radius 40 * progress while background material is on.
        val controller = classLoader.loadClass("miui.systemui.dynamicisland.anim.DynamicIslandAnimationController")
        val blurCompat = HostAccess.requireDeclaredMethod(controller, "setMiSelfBlurCompat", View::class.java, int, accessible = false)
            .apply { check(Modifier.isStatic(modifiers) && returnType == Void.TYPE) }
        val blurUtil = classLoader.loadClass("miui.systemui.util.MiBlurCompat")
        val material = HostAccess.requireDeclaredMethod(blurUtil, "getBackgroundMaterialOpened", Context::class.java, accessible = false)
            .apply { check(Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType) }
        val releasePoints = listOf(
            HostAccess.requireDeclaredMethod(fake, "onTrackingFakeViewStart", accessible = false),
            HostAccess.requireDeclaredMethod(fake, "onTrackingFakeViewReset", accessible = false),
            HostAccess.requireDeclaredMethod(fake, "resetFakeViewState", accessible = false),
            HostAccess.requireDeclaredMethod(fake, "onDetachedFromWindow", accessible = false)
        )
        val visibility = HostAccess.requireDeclaredMethod(fake, "setVisibility", int, accessible = false)
        setBlur = { view, radius -> blurCompat.invoke(null, view, radius) }

        fun frame(fakeView: View) {
            if (!settingIslandPullDownStretch) return release(fakeView)
            val state = trackingParams.get(fakeView) ?: return release(fakeView)
            val frame = IslandPullDownFrame.of(
                restHeight = restHeight.getInt(fakeView),
                outlineHeight = (bottom.invoke(state) as Int) - (top.invoke(state) as Int),
                alpha = alpha.invoke(state) as Float,
                blurSupported = material.invoke(null, fakeView.context) == true
            ) ?: return release(fakeView)
            val container = expanded.invoke(fakeView) as? ViewGroup ?: return release(fakeView)
            val stretch = stretches[fakeView]?.takeIf { it.container === container } ?: run {
                release(fakeView)
                val targets = (0 until container.childCount).mapNotNull(container::getChildAt).map {
                    Target(it, it.scaleY, it.pivotY.takeIf { _ -> it.isPivotSet })
                }
                if (targets.isEmpty()) return
                DebugLog.d(TAG, "stretch started targets=${targets.size} rest=${restHeight.getInt(fakeView)}")
                Stretch(container, targets).also { stretches[fakeView] = it }
            }
            stretch.targets.forEach { target ->
                target.view.pivotY = 0f
                target.view.scaleY = frame.scale
            }
            if (stretch.blur != frame.blurRadius) {
                stretch.targets.forEach { setBlur?.invoke(it.view, frame.blurRadius) }
                stretch.blur = frame.blurRadius
            }
        }

        // Natively the media binder slides only its album background by this offset; the
        // stretched content already fills the grown outline, so publish no slide while enabled.
        deoptimize(updateTrack)
        updateTrack.hook {
            before { param ->
                if (!retiring && settingIslandPullDownStretch) param.args[0] = 0f
            }
        }
        deoptimize(updated)
        updated.hook {
            after { param ->
                if (retiring || Looper.myLooper() != main.looper) return@after
                val fakeView = param.thisObject as? View ?: return@after
                HookFailurePolicy.open(TAG, "pull-down frame", Unit) { frame(fakeView) }
            }
        }
        releasePoints.forEach { method ->
            deoptimize(method)
            method.hook {
                before { param -> releaseFrom(param.thisObject) }
            }
        }
        deoptimize(visibility)
        visibility.hook {
            after { param -> if (param.args[0] != View.VISIBLE) releaseFrom(param.thisObject) }
        }
        DebugLog.hookRegistered(TAG, "expanded-island pull-down stretch and blur")
    }

    private fun releaseFrom(owner: Any?) {
        if (Looper.myLooper() != main.looper) return
        val fakeView = owner as? View ?: return
        HookFailurePolicy.open(TAG, "restore pulled-down island", Unit) { release(fakeView) }
    }

    private fun release(fakeView: View) {
        val stretch = stretches.remove(fakeView) ?: return
        stretch.targets.forEach { target ->
            target.view.scaleY = target.scaleY
            target.pivotY?.let { target.view.pivotY = it } ?: target.view.resetPivot()
            if (stretch.blur != 0) setBlur?.invoke(target.view, 0)
        }
        DebugLog.d(TAG, "stretch restored targets=${stretch.targets.size}")
    }

    private fun releaseAll() = stretches.keys.toList().forEach(::release)

    override fun onPrepareHotReload() {
        retiring = true
        main.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            HookFailurePolicy.open(TAG, "restore pulled-down islands", Unit) { releaseAll() }
            setBlur = null
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "island pull-down restoration timed out" }
        }
    }

    private companion object {
        const val TAG = "IslandPullStretch"
        const val FAKE_VIEW = "miui.systemui.dynamicisland.window.content.DynamicIslandContentFakeView"
    }
}
