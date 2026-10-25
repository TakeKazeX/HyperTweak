package com.takekazex.hypertweak.settings.contract

internal interface SystemUiAppSettings {
    fun slotKey(slot: String): String
    fun getNotificationHeaderTimeScale(): Float
    fun iconStackedSignalStyle(): Int
    fun notificationBadge(): Boolean
    fun notificationBlockFold(): Boolean
    fun notificationMoreSettings(): Boolean
}
