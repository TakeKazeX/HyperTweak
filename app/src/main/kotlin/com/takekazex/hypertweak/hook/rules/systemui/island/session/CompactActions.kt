package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry

/** Camera-seated main template capability; independent of scheduling and handler ownership. */
internal interface CompactActions {
    /**
     * Final helper result for one measured content view. [claimed] is the session layout's
     * geometry, if any; otherwise placeholder removal may rewrite the native result.
     */
    fun afterMeasure(view: Any, input: Any, result: Any, claimed: IslandResultGeometry?): IslandResultGeometry?

    /** Redirected `doBigIslandAnim` arguments when they publish this view's camera-seated geometry. */
    fun translation(view: Any, args: Array<Any?>): Array<Any?>?

    /** Drop the view's geometry and area lease; the host's next animation returns it to native. */
    fun forget(view: Any): Unit

    /** Apply a switch change to views that are not republished by the frame relayout. */
    fun refresh(): Unit

    /** Restore every touched template before hooks are removed. */
    fun retire(): Unit
}
