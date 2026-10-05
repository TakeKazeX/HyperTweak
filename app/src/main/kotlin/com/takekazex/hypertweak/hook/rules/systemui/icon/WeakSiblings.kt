package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.lang.ref.WeakReference

/** Siblings can lead back to a weak-map owner through their common parent. */
internal class WeakSiblings<T : Any>(first: T, second: T?) {
    val first = WeakReference(first)
    val second = WeakReference(second)
}
