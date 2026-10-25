package com.takekazex.hypertweak.hook.rules.systemui.island.session

internal sealed interface TemplateAnimation {
    /** `calculateBigIslandWidth` is animating native two-slot widths; swallow this call. */
    data object Suppress : TemplateAnimation
    data object Passthrough : TemplateAnimation
    data class Redirect(val args: Array<Any?>, val view: Any) : TemplateAnimation
}
