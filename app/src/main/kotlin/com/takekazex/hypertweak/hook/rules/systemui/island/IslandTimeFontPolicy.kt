package com.takekazex.hypertweak.hook.rules.systemui.island

internal object IslandTimeFontPolicy {
    private val time = Regex("[+\\-−]?[\\p{Nd}]+(?:[:：][\\p{Nd}]{1,2}){1,2}(?:[.,٫][\\p{Nd}]+)?")

    fun eligible(packageName: String?, numericSlot: Boolean, text: CharSequence?): Boolean =
        (packageName == "com.android.deskclock" || packageName == "com.android.soundrecorder") &&
            (numericSlot || time.matches(text?.toString()?.trim().orEmpty()))
}
