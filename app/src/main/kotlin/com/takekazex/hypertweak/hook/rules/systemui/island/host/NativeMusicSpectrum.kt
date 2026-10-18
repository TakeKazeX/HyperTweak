package com.takekazex.hypertweak.hook.rules.systemui.island.host

/** Resolve the canonical album + native waveform template, excluding lyric/custom templates. */
internal class NativeMusicSpectrum(content: Class<*>) {
    private fun getter(owner: Class<*>, name: String) = NativeIslandMembers.named(owner, name, emptyList())
    private val media = getter(content, "isMediaApp")
    private val template = getter(content, "getTemplate")
    private val big = getter(template.returnType, "getBigIslandArea")
    private val left = getter(big.returnType, "getImageTextInfoLeft")
    private val right = getter(big.returnType, "getImageTextInfoRight")
    private val type = getter(left.returnType, "getType")
    private val picture = getter(left.returnType, "getPicInfo")
    private val text = getter(left.returnType, "getTextInfo")
    private val textValues = listOf("getTitle", "getContent", "getFrontTitle").mapNotNull { name ->
        text.returnType.methods.singleOrNull { it.name == name && it.parameterCount == 0 && it.returnType == String::class.java }
    }
    private val progress = getter(left.returnType, "getProgressInfo")
    private val pic = getter(picture.returnType, "getPic")
    private val picType = getter(picture.returnType, "getType")
    private val otherContent = listOf("getTextInfo", "getProgressTextInfo", "getFixedWidthDigitInfo", "getSameWidthDigitInfo")
        .map { getter(big.returnType, it) }

    init {
        check(media.returnType == Boolean::class.javaObjectType)
        check(left.returnType == right.returnType)
        check(type.returnType == Int::class.javaObjectType && picType.returnType == Int::class.javaObjectType)
        check(pic.returnType == String::class.java)
        check(textValues.isNotEmpty())
    }

    private fun hasText(value: Any?): Boolean = value != null &&
        textValues.any { !(it.invoke(value) as? String).isNullOrBlank() }

    fun matches(view: Any): Boolean {
        if (media.invoke(view) != true) return false
        val area = template.invoke(view)?.let { big.invoke(it) } ?: return false
        if (otherContent.any { member ->
                val value = member.invoke(area)
                if (member.name == "getTextInfo") hasText(value) else value != null
            }) return false
        val first = left.invoke(area) ?: return false
        val second = right.invoke(area) ?: return false
        if (type.invoke(first) != 1 || type.invoke(second) != 2 ||
            listOf(first, second).any { hasText(text.invoke(it)) || progress.invoke(it) != null }) return false
        val cover = picture.invoke(first) ?: return false
        val wave = picture.invoke(second) ?: return false
        return picType.invoke(cover) == 1 && pic.invoke(cover) == "miui_media_album_icon" &&
            picType.invoke(wave) == 2 && pic.invoke(wave) in setOf("musicWave", "musicPause")
    }

    /** Shape only: no titles, lyrics, package/user data or arbitrary picture values. */
    fun describe(view: Any): String {
        val area = template.invoke(view)?.let { big.invoke(it) }
        val a = area?.let { left.invoke(it) }
        val b = area?.let { right.invoke(it) }
        fun shape(info: Any?): String {
            if (info == null) return "missing"
            val image = picture.invoke(info)
            val key = image?.let { pic.invoke(it) }
            val kind = when (key) { "miui_media_album_icon" -> "cover"; "musicWave" -> "wave"; "musicPause" -> "pause"; else -> "other" }
            return "${type.invoke(info)}/$kind/text=${hasText(text.invoke(info))}"
        }
        return "media=${media.invoke(view)} left=${shape(a)} right=${shape(b)}"
    }
}
