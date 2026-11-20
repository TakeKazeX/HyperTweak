package com.takekazex.hypertweak.hook.base

import android.graphics.Region
import android.view.ViewTreeObserver
import java.lang.reflect.Proxy

/** The framework's per-window internal insets callback, including hidden SDK members. */
internal class WindowTouchableInsets {
    private val listener = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
    private val info = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
    private val add = HostAccess.requirePublicMethod(
        ViewTreeObserver::class.java, "addOnComputeInternalInsetsListener", listener, accessible = true,
    )
    private val remove = HostAccess.requirePublicMethod(
        ViewTreeObserver::class.java, "removeOnComputeInternalInsetsListener", listener, accessible = true,
    )
    private val setMode = HostAccess.requirePublicMethod(
        info, "setTouchableInsets", Int::class.javaPrimitiveType, accessible = true,
    )
    private val region = HostAccess.requirePublicField(info, "touchableRegion", accessible = true)

    fun listener(tag: String, onCompute: (Any) -> Unit): Any =
        Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { self, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> tag
                "onComputeInternalInsets" -> {
                    args?.firstOrNull()?.let(onCompute)
                    null
                }
                else -> null
            }
        }

    fun add(observer: ViewTreeObserver, callback: Any) { add.invoke(observer, callback) }
    fun remove(observer: ViewTreeObserver, callback: Any) { remove.invoke(observer, callback) }

    fun empty(value: Any) {
        val touchable = region.get(value) as? Region ?: return
        setMode.invoke(value, TOUCHABLE_INSETS_REGION)
        touchable.setEmpty()
    }

    private companion object {
        const val TOUCHABLE_INSETS_REGION = 3
    }
}
