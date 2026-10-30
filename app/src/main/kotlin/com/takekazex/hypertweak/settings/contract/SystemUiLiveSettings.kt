package com.takekazex.hypertweak.settings.contract

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys


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
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_CARRIER,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_TIME,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_HIDE_DATE,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_SCALE,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_REGION,
        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_WEATHER_TYPE
    )

    /** Text styling of already displayed notifications. */
    val NOTIFICATION_TEXT_KEYS = setOf(
        SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR,
        SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME,
        SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT
    )

    /** Media-card renderer switches cached by the host hooks and the control-center plugin. */
    val MEDIA_CARD_KEYS = setOf(
        SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON,
        SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH
    )

    /** Control-center plugin sub-hooks whose presence the session chooses when it is created. */
    val CONTROL_CENTER_PLUGIN_KEYS = setOf(
        SystemUiSettingsKeys.KEY_CC_TILE_MESSAGE_DURATION_MS,
        SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED,
        SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED,
        SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE,
        SystemUiSettingsKeys.KEY_SLIDER_VALUE_MODE,
        SystemUiSettingsKeys.KEY_SLIDER_POPUP_POSITION,
        SystemUiSettingsKeys.KEY_SLIDER_POPUP_STYLE,
        SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE,
        SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE,
        SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED,
        SystemUiSettingsKeys.KEY_CC_CORNER_SLIDER,
        SystemUiSettingsKeys.KEY_CC_CORNER_TILE,
        SystemUiSettingsKeys.KEY_CC_CORNER_CARD,
        SystemUiSettingsKeys.KEY_CC_CORNER_DEVICE,
        SystemUiSettingsKeys.KEY_CC_CORNER_MEDIA,
        SystemUiSettingsKeys.KEY_CC_TOP_CARD_ORDER,
        SystemUiSettingsKeys.KEY_CC_MAIN_CONTENT_ORDER,
        SystemUiSettingsKeys.KEY_CC_CARD_SIZES,
        SystemUiSettingsKeys.KEY_CC_TILE_CARD_SPECS,
        SystemUiSettingsKeys.KEY_CC_SIZE_MEDIA,
        SystemUiSettingsKeys.KEY_CC_SIZE_BRIGHTNESS,
        SystemUiSettingsKeys.KEY_CC_SIZE_VOLUME,
        SystemUiSettingsKeys.KEY_CC_SIZE_DEVICE
    )

    /** Glass material tuner coefficients cached at install time. */
    /** Lock-screen indication surfaces: bottom text, charging detail, fingerprint avoidance. */
    val LOCKSCREEN_KEYS = setOf(
        SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID
    )

    val GLASS_KEYS = setOf(
        SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED,
        SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA,
        SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS,
        SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE,
        SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY,
        SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE
    )
}
