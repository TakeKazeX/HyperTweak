package com.takekazex.hypertweak.hook.rules.securitycenter

/** Evidence from the provider's synchronous enter/leave transaction, independent of obfuscation. */
internal object PowerSaveTransitionContract {
    val markers = listOf(
        "Open power save mode, battery percent ",
        "Close power save mode, battery percent ",
        "miui.intent.action.POWER_SAVE_MODE_CHANGED",
    )
    val parameterTypes = arrayOf("boolean", "boolean", "java.lang.String", "boolean")
}
