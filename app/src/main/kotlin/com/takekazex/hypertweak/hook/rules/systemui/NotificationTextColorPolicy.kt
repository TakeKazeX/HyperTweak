package com.takekazex.hypertweak.hook.rules.systemui

/** Pure policy shared by palette resolution, native binding and hot-reload recovery. */
internal object NotificationTextColorPolicy {
    private const val WRAPPER = "com.android.systemui.statusbar.notification.row.wrapper."
    val standardWrappers = setOf(
        "MiuiNotificationTemplateViewWrapper", "MiuiNotificationBigTextViewWrapper",
        "MiuiNotificationInboxViewWrapper", "MiuiNotificationOneLineViewWrapper",
        "MiuiNotificationBigPictureViewWrapper", "MiuiNotificationProgressViewWrapper",
        "NotificationTemplateViewWrapper", "NotificationBigTextTemplateViewWrapper",
        "NotificationBigPictureTemplateViewWrapper", "NotificationMessagingTemplateViewWrapper",
        "NotificationConversationTemplateViewWrapper", "NotificationMediaTemplateViewWrapper"
    ).map { WRAPPER + it }.toSet()

    fun ownsTemplate(hierarchy: List<String>): Boolean =
        hierarchy.none { it.contains("CustomViewWrapper") } && hierarchy.any { it in standardWrappers }

    /** Cached native callbacks must not replace the original with our own previous override. */
    fun <T> original(current: T, previous: T?, applied: T?): T =
        if (previous != null && current == applied) previous else current

    /** Match the native binder's heads-up branch through its drag-down handover. */
    fun isHeadsUp(headUpState: Boolean, dragDownDisappear: Boolean): Boolean =
        headUpState || dragDownDisappear

    /** Material/style selection already happened in the native context producer. */
    fun usesDarkTextContext(nativeNight: Boolean, fullAod: Boolean, transparent: Boolean): Boolean =
        nativeNight || fullAod || transparent

    fun neutral(night: Boolean, alpha: Int = 255): Int =
        (alpha.coerceIn(0, 255) shl 24) or if (night) 0x00ffffff else 0

    fun secondary(night: Boolean): Int = neutral(night, if (night) 0xb3 else 0x99)

    /** Only a stock text-resource match is eligible in the remainder of a standard tree. */
    fun replaceStock(color: Int, stock: Set<Int>, night: Boolean): Int =
        if (color in stock) neutral(night, color ushr 24) else color
}
