package com.takekazex.hypertweak.settings.contract

/** Shared fixed scope and reversible path mapping; never rewrites arbitrary caller paths. */
internal object AivsPathPolicy {
    val packages = setOf("com.miui.voiceassist", "com.milink.service", "com.xiaomi.finddevice")
    const val DESTINATION = "MIUI/AIVS"

    fun rootArgument(value: String?, packageName: String, enabled: Boolean): String? =
        if (enabled && packageName in packages && value?.trim('/') == packageName)
            "$DESTINATION/$packageName" else value

    fun audioPath(value: String?, storageRoot: String, packageName: String, enabled: Boolean): String? {
        if (value == null || packageName !in packages) return value
        val old = "$storageRoot/$packageName/audio/"
        val redirected = "$storageRoot/$DESTINATION/$packageName/audio/"
        val from = if (enabled) old else redirected
        val to = if (enabled) redirected else old
        if (!value.startsWith(from)) return value
        val leaf = value.removePrefix(from)
        return if (leaf in setOf("encode_audio.data", "origin_audio.pcm")) to + leaf else value
    }
}
