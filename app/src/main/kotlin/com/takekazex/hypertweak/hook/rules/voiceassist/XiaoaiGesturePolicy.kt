package com.takekazex.hypertweak.hook.rules.voiceassist

/** Native entity values, independent of the input gesture's old default mode. */
internal object XiaoaiGesturePolicy {
    const val LONG_PRESS = "NavLongPress"
    const val DOUBLE_CLICK = "NavDoubleClick"
    const val TEXT = "VoiceAssistant"
    const val VOICE = "VoiceAssistantVoiceInput"
    fun textRoute(enabled: Boolean, trigger: String?, selected: String?, function: String?): Boolean =
        enabled && trigger == LONG_PRESS && selected == TEXT && function == "wake_up_voice_assist"
    fun voiceRoute(enabled: Boolean, trigger: String?, selected: String?, function: String?): Boolean =
        enabled && trigger == DOUBLE_CLICK && selected == VOICE && function == "wake_up_voice_assist"
}
