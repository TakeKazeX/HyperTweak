package com.takekazex.hypertweak.hook.rules.systemui.island.host

import android.graphics.Rect
import java.lang.reflect.Method

/** The vendor's complete BigIsland return path; no module animator or substitute content. */
internal class NativeMediaRenderContract(host: NativeIslandAccess) {
    data class Entry(val method: Method, val viewIndex: Int? = null, val destination: Method? = null)
    private val z = Boolean::class.javaPrimitiveType!!
    private val i = Int::class.javaPrimitiveType!!
    private val f = Float::class.javaPrimitiveType!!
    private fun method(owner: Class<*>, name: String, vararg args: Class<*>) =
        NativeIslandMembers.named(owner, name, args.toList())
    private fun defaultMethod(owner: Class<*>, name: String, vararg args: Class<*>) =
        NativeIslandMembers.namedStatic(owner, name, args.toList())

    private val returnArgs = arrayOf(host.content, z)
    val entries = listOf(
        Entry(method(host.delegate, "appExpandedToSmallIslandAnimation", *returnArgs), 0,
            method(host.delegate, "appExpandedToBigIslandAnimation", *returnArgs)),
        Entry(method(host.fakeAnimator, "fakeViewToSmallIsland", *returnArgs), 0,
            method(host.fakeAnimator, "fakeViewToBigIsland", *returnArgs)),
        Entry(method(host.fakeAnimator, "updateFakeViewStateForAppAnim", i, i, i, i, host.content, z, z), 4),
        Entry(method(host.fakeAnimator, "applyAppAnimByState", host.content, z, f, f, f), 0),
        Entry(method(host.fakeHelper, "applyAppAnimByState", host.content, z, f, f, f), 0),
        Entry(method(host.fake, "onStateChanged", host.content), 0),
        Entry(method(host.fake, "updateViewStateWhenCloseEnd", z, String::class.java)),
        Entry(method(host.fake, "syncIslandLottieProgress", z)),
        Entry(method(host.fake, "setVisibility", i)),
        Entry(method(host.content, "updateViewStateWhenCloseEnd")),
    )
    val callers = listOf(
        method(host.anim, "appExpandedToSmallIslandAnimation", host.content),
        method(host.delegate, "appExpandedToSmallIslandAnimation", host.content),
        method(host.delegate, "appExpandedToBigIslandAnimation", host.content),
        method(host.delegate, "appExpandedToBigIslandAnimation", *returnArgs),
        method(host.content, "getState"), method(host.content, "getLastState"),
        method(host.content, "getCurrentBigIslandX", Boolean::class.javaObjectType),
        method(host.content, "getCurrentBigIslandWidth", Boolean::class.javaObjectType),
        defaultMethod(host.base, "getCurrentBigIslandX\$default", host.base, Boolean::class.javaObjectType, i, Any::class.java),
        defaultMethod(host.base, "getCurrentBigIslandWidth\$default", host.base, Boolean::class.javaObjectType, i, Any::class.java),
        defaultMethod(host.base, "getBigIslandRect\$default", host.base, Boolean::class.javaObjectType, i, Any::class.java),
    )
    val appCloseTarget = host.windowController.declaredMethods.single {
        it.returnType == Rect::class.java && it.parameterTypes.toList() ==
            listOf(host.state, host.content, String::class.java, z)
    }.apply { isAccessible = true }
}
