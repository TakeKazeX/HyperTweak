package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences

/**
 * Keys whose accepted change must reach a SystemUI hook that caches its policy at install time.
 *
 * The registry in `RuntimeSettingsGroups` owns routing; this list only names what the two
 * notification-state participants apply. Keeping it here lets the routing test and the
 * participants share one source of truth instead of repeating key strings.
 */
internal object SystemUiLiveSettings {
    /**
     * Notification header layout, size and weather (NotificationHeaderHooker + Weather), plus the
     * header clock seconds. The seconds key lives here because the same participant applies it:
     * a key may only be owned by the domain whose callback handles its change.
     */
    val HEADER_KEYS = setOf(
        Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
        Preferences.KEY_NOTIFICATION_HEADER_HIDE_CARRIER,
        Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME,
        Preferences.KEY_NOTIFICATION_HEADER_HIDE_DATE,
        Preferences.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME,
        Preferences.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT,
        Preferences.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT,
        Preferences.KEY_NOTIFICATION_HEADER_TIME_SCALE,
        Preferences.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED,
        Preferences.KEY_NOTIFICATION_HEADER_WEATHER_REGION,
        Preferences.KEY_NOTIFICATION_HEADER_WEATHER_TYPE
    )

    /** Text styling of already displayed notifications. */
    val NOTIFICATION_TEXT_KEYS = setOf(
        Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR,
        Preferences.KEY_NOTIFICATION_ABSOLUTE_TIME,
        Preferences.KEY_NOTIFICATION_FONT_WEIGHT
    )

    /** Media-card renderer switches cached by the host hooks and the control-center plugin. */
    val MEDIA_CARD_KEYS = setOf(
        Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON,
        Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH
    )

    /** Control-center plugin sub-hooks whose presence the session chooses when it is created. */
    val CONTROL_CENTER_PLUGIN_KEYS = setOf(
        Preferences.KEY_CC_EDIT_ENABLED,
        Preferences.KEY_CC_RESIZE_ENABLED,
        Preferences.KEY_SLIDER_SHOW_PERCENTAGE,
        Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE,
        Preferences.KEY_CC_CORNER_ENABLED,
        Preferences.KEY_CC_CORNER_SLIDER,
        Preferences.KEY_CC_CORNER_TILE,
        Preferences.KEY_CC_CORNER_CARD,
        Preferences.KEY_CC_CORNER_DEVICE,
        Preferences.KEY_CC_CORNER_MEDIA,
        Preferences.KEY_CC_TOP_CARD_ORDER,
        Preferences.KEY_CC_MAIN_CONTENT_ORDER,
        Preferences.KEY_CC_CARD_SIZES,
        Preferences.KEY_CC_TILE_CARD_SPECS,
        Preferences.KEY_CC_SIZE_MEDIA,
        Preferences.KEY_CC_SIZE_BRIGHTNESS,
        Preferences.KEY_CC_SIZE_VOLUME,
        Preferences.KEY_CC_SIZE_DEVICE
    )

    /** Glass material tuner coefficients cached at install time. */
    /** Lock-screen indication surfaces: bottom text, charging detail, fingerprint avoidance. */
    val LOCKSCREEN_KEYS = setOf(
        Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT,
        Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
        Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
        Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL,
        Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS,
        Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS,
        Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS,
        Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID
    )

    val GLASS_KEYS = setOf(
        Preferences.KEY_GLASS_TUNER_ENABLED,
        Preferences.KEY_GLASS_TUNER_BLEND_ALPHA,
        Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS,
        Preferences.KEY_GLASS_TUNER_RADIUS_SCALE,
        Preferences.KEY_GLASS_TUNER_GLASS_OPACITY,
        Preferences.KEY_GLASS_TUNER_GLASS_TONE
    )
}