package com.takekazex.hypertweak.settings.contract

internal object FreeformSettingsKeys {
    const val KEY_COUNT = "system_freeform_count"
    const val KEY_FORCE_ALLOW = "system_freeform_force_allow"
    const val KEY_JUMP = "system_freeform_jump"
    const val KEY_JUMP_NOTIFICATION = "system_freeform_jump_notification"
    const val KEY_JUMP_CONTENT_LINKS = "system_freeform_jump_content_links"
    const val KEY_COPY_DIRECT_COMMAND = "system_freeform_copy_direct_command"
    const val KEY_COPY_DIRECT_LOCATION = "system_freeform_copy_direct_location"
    const val KEY_COPY_DIRECT_TRACKING = "system_freeform_copy_direct_tracking"
    const val KEY_JUMP_SHARE = "system_freeform_jump_share"
    const val KEY_FOREGROUND_PIN = "system_freeform_foreground_pin"

    val copyDirectKeys = setOf(KEY_COPY_DIRECT_COMMAND, KEY_COPY_DIRECT_LOCATION, KEY_COPY_DIRECT_TRACKING)

    val keys = copyDirectKeys + setOf(
        KEY_COUNT, KEY_FORCE_ALLOW, KEY_FOREGROUND_PIN, KEY_JUMP,
        KEY_JUMP_NOTIFICATION, KEY_JUMP_CONTENT_LINKS, KEY_JUMP_SHARE,
    )
}
