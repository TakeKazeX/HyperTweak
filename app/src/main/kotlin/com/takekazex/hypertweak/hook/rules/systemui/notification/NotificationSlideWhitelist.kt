package com.takekazex.hypertweak.hook.rules.systemui.notification

/** Preserve cloud/local contents; only the package membership decision follows the switch. */
internal class NotificationSlideWhitelist(
    val source: List<String>,
    private val enabled: () -> Boolean,
) : AbstractList<String>() {
    override val size: Int get() = source.size
    override fun get(index: Int): String = source[index]
    override fun contains(element: String): Boolean =
        (enabled() && element.isNotBlank()) || source.contains(element)
}
