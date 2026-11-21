package com.takekazex.hypertweak.settings.contract

internal object OtaNotificationReplay {
    const val VIEWER = "com.android.htmlviewer"
    const val SETTINGS = "com.android.settings"
    const val VIEWER_AUTHORITY = "com.android.htmlviewer.ota.notification.BannerProvider"
    const val SETTINGS_AUTHORITY = "com.android.settings.suggestions.status"
    const val METHOD = "com.takekazex.hypertweak.REPLAY_OTA_NOTIFICATION"
    const val RESULT = "result"
    const val POSTED = 1
    const val UNAVAILABLE = 2
    const val BLOCKED = 3
}
