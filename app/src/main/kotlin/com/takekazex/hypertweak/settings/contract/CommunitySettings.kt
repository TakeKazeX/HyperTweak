package com.takekazex.hypertweak.settings.contract

object CommunitySettings {
    const val PACKAGE = "com.xiaomi.vipaccount"
    val keys = setOf(CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY, CommunitySettingsKeys.KEY_COMMUNITY_MODEL)
    fun model(enabled: Boolean, value: String): String? = value.trim().takeIf { enabled && it.isNotEmpty() }
    fun blocked(packageName: String?): Boolean = packageName in setOf(
        "com.takekazex.hypertweak", "com.sevtinge.hyperceiler")
}
