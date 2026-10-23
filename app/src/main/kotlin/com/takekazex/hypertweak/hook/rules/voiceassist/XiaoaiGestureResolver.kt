package com.takekazex.hypertweak.hook.rules.voiceassist

import org.luckypray.dexkit.DexKitBridge

/** Current APK evidence: explicit keyboard gate and its gesture-aware VoiceService caller. */
internal object XiaoaiGestureResolver {
    fun resolve(bridge: DexKitBridge): Map<String, String> {
        val keyboard = bridge.findMethod {
            matcher { usingEqStrings("isNeedPullUpKeyboard: standby mode", "pull_up_keyboard", "voice_assist_function_key") }
        }.filter {
            it.isMethod && it.returnTypeName == "boolean" && it.paramTypeNames == listOf("android.content.Intent")
        }.distinctBy { it.descriptor }.singleOrNull() ?: error("XiaoAI keyboard gate missing or ambiguous")
        val consumer = bridge.findMethod {
            matcher { usingEqStrings("processIntentInternal: enter local ai offline wakeup from gesture line v4",
                "double_click_fullscreen_gesture_line") }
        }.filter {
            it.isMethod && it.className == "com.xiaomi.voiceassistant.VoiceService" &&
                it.invokes.any { call -> call.descriptor == keyboard.descriptor }
        }.distinctBy { it.descriptor }.singleOrNull() ?: error("XiaoAI input consumer missing or ambiguous")
        return mapOf("keyboard" to keyboard.descriptor, "consumer" to consumer.descriptor)
    }
}
