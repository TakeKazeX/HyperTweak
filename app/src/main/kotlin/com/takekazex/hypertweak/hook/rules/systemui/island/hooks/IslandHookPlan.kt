package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import android.view.MotionEvent
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandAccess
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandHost
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaRenderContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime

internal class IslandHookContext(
    val access: NativeIslandAccess,
    val host: NativeIslandHost,
    val runtime: IslandRuntime,
    val registrar: IslandHookRegistrar,
    val spectrum: NativeMusicSpectrum?,
    val media: NativeMediaRenderContract?,
    val refreshSettings: () -> Unit,
)

/** Resolve and validate every target before mutating, then install in one fixed order. */
internal class IslandHookPlan(private val context: IslandHookContext) {
    private val access = context.access

    fun validate() {
        checkNotNull(access.templateWrite) { "native template cutout writer unavailable" }
        val handle = access.method(access.coordinator, "handleEvent")
        check(handle.parameterTypes.toList() == listOf(access.event, access.content))
        check(access.method(access.handler, "stop").parameterCount == 0)
        check(access.method(access.touch, "onInterceptTouchEvent").parameterTypes.first() == MotionEvent::class.java)
        check(access.method(access.content, "calculateBigIslandWidth").parameterCount == 0)
        check(access.method(access.content, "getSmallIslandViewWidth").parameterCount == 0)
        check(access.method(access.fake, "getSmallIslandViewWidth").parameterCount == 0)
        check(access.method(access.content, "getSmallIslandViewWidth").returnType == Int::class.javaPrimitiveType)
        check(access.method(access.content, "doBigIslandAnim").parameterCount > 8)
        check(access.widthSetTo.returnType == access.widthTween.returnType)
    }

    fun deoptimizeTargets() {
        listOf(
            access.method(access.coordinator, "handleEvent"),
            access.method(access.handler, "stop"),
            access.method(access.handler, "addState"),
            access.method(access.touch, "onInterceptTouchEvent"),
            access.method(access.content, "calculateBigIslandWidth"),
            access.method(access.coords, "getSmallIslandX"),
            access.method(access.window, "dispatchTouchEvent"),
            access.method(access.content, "onIslandClick"),
            access.method(access.touch, "performClick"),
            access.method(access.touch, "onTouchEvent"),
            access.method(access.coordinator, "resetSwipe"),
            access.method(access.coordinator, "dispatchSwipe"),
            access.method(access.anim, "onStateChange"),
            access.method(access.resolver, "resolve"),
            access.method(access.content, "getCurrentBigIslandX"),
            access.method(access.content, "getCurrentBigIslandWidth"),
            access.method(access.content, "getSmallIslandViewWidth"),
            access.method(access.fake, "getSmallIslandViewWidth"),
        ).forEach(context.registrar::deoptimize)
    }

    fun install() {
        TemplatePublicationBinder.install(context)
        TransactionBinder.install(context)
        ConfigurationBinder.install(context)
        MediaRenderBinder.install(context)
        TemplateBindingBinder.install(context)
        MeasurementBinder.install(context)
        CoordinatesBinder.install(context)
        RegionBinder.install(context)
        TouchRoutingBinder.install(context)
        SummaryGestureBinder.install(context)
        PresentationBinder.install(context)
    }
}
