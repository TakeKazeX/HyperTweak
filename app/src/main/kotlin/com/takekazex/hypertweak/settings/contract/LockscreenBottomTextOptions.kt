package com.takekazex.hypertweak.settings.contract

/** Persisted UI/host option protocol; values are stable across upgrades. */
object LockscreenBottomTextOptions {
    const val MODE_BLACKLIST = 0
    const val MODE_WHITELIST = 1

    const val CATEGORY_CHARGING = 1
    const val CATEGORY_DND = 1 shl 1
    const val CATEGORY_NOTIFICATION_COUNT = 1 shl 2
    const val CATEGORY_TRUST = 1 shl 3
    const val CATEGORY_SWIPE = 1 shl 4
    const val CATEGORY_OWNER_INFO = 1 shl 5
    const val CATEGORY_DISCLOSURE = 1 shl 6
    const val CATEGORY_LOGOUT = 1 shl 7
    const val CATEGORY_ALIGNMENT = 1 shl 8
    const val CATEGORY_TRANSIENT = 1 shl 9
    const val CATEGORY_PERSISTENT_UNLOCK = 1 shl 10
    const val CATEGORY_USER_LOCKED = 1 shl 11
    const val CATEGORY_BIOMETRIC = 1 shl 12
    const val CATEGORY_BIOMETRIC_FOLLOW_UP = 1 shl 13
    const val CATEGORY_ADAPTIVE_AUTH = 1 shl 14
    const val CATEGORY_WATCH_DISCONNECTED = 1 shl 15
    const val CATEGORY_SECURE_LOCK_DEVICE = 1 shl 16
    const val CATEGORY_CLICK_TO_UNLOCK = 1 shl 17
    const val CATEGORY_KEY_TO_UNLOCK = 1 shl 18
    const val CATEGORY_ENTER_TO_UNLOCK = 1 shl 19
    const val CATEGORY_UNKNOWN = 1 shl 20

    const val DEFAULT_MODE = MODE_BLACKLIST
    const val DEFAULT_MASK = 0
}
