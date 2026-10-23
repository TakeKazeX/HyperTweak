package com.takekazex.hypertweak.hook.rules.voiceassist

import org.junit.Assert.*
import org.junit.Test

class XiaoaiGesturePolicyTest {
    @Test fun onlyNewTextAndVoiceRoutesChange() {
        assertTrue(XiaoaiGesturePolicy.textRoute(true, "NavLongPress", "VoiceAssistant", "wake_up_voice_assist"))
        assertTrue(XiaoaiGesturePolicy.voiceRoute(true, "NavDoubleClick", "VoiceAssistantVoiceInput", "wake_up_voice_assist"))
        for (enabled in listOf(false, true)) for (trigger in listOf(null, "NavLongPress", "NavDoubleClick", "SideSlideHold")) {
            for (entity in listOf(null, "None", "VoiceAssistantScreenRecognizer", "VoiceAssistantIntentBall")) {
                assertFalse(XiaoaiGesturePolicy.textRoute(enabled, trigger, entity, "wake_up_voice_assist"))
                assertFalse(XiaoaiGesturePolicy.voiceRoute(enabled, trigger, entity, "wake_up_voice_assist"))
            }
        }
        assertFalse(XiaoaiGesturePolicy.textRoute(false, "NavLongPress", "VoiceAssistant", "wake_up_voice_assist"))
        assertFalse(XiaoaiGesturePolicy.voiceRoute(false, "NavDoubleClick", "VoiceAssistantVoiceInput", "wake_up_voice_assist"))
        assertFalse(XiaoaiGesturePolicy.textRoute(true, "NavLongPress", "VoiceAssistant", "start_screen_recognition"))
        assertFalse(XiaoaiGesturePolicy.voiceRoute(true, "NavDoubleClick", "VoiceAssistantVoiceInput", "start_screen_recognition"))
    }
}
