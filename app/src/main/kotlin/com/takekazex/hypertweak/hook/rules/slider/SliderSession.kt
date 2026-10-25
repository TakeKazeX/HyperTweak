package com.takekazex.hypertweak.hook.rules.slider

/** Live settings and resolution lent by the generation owner to its children. */
interface SliderSession {
    val showPercentageEnabled: Boolean
    val sameStyleEnabled: Boolean
    fun resolveClass(className: String, initialize: Boolean = false): Class<Any>?
}
