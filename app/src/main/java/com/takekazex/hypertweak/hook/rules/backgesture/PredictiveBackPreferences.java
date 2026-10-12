package com.takekazex.hypertweak.hook.rules.backgesture;

public final class PredictiveBackPreferences {
    public static final String GROUP = "hypertweak_settings";
    public static final String KEY_PACKAGES = "back_gesture_predictive_packages";
    public static final String KEY_HYPEROS_INDICATOR = "hyperos_indicator_style";
    public static final boolean DEFAULT_HYPEROS_INDICATOR = false;
    public static final String KEY_HYPEROS_HAPTICS = "hyperos_indicator_haptics";
    public static final boolean DEFAULT_HYPEROS_HAPTICS = false;
    /** Only used to migrate the removed AOSP-specific switch once. */
    public static final String LEGACY_KEY_AOSP_HYPEROS_HAPTICS =
            "aosp_gesture_hyperos_haptics";
    public static final String KEY_HYPEROS_HAPTICS_ENHANCED =
            "hyperos_indicator_haptics_enhanced";
    public static final boolean DEFAULT_HYPEROS_HAPTICS_ENHANCED = false;
    public static final String KEY_HYPEROS_SLIDE_ANIMATION =
            "hyperos_slide_back_animation";
    public static final boolean DEFAULT_HYPEROS_SLIDE_ANIMATION = false;
    public static final String KEY_CROSS_ACTIVITY_WALLPAPER = "back_gesture_cross_activity_wallpaper";
    public static final boolean DEFAULT_CROSS_ACTIVITY_WALLPAPER = false;
    public static final String KEY_CROSS_TASK_WALLPAPER = "back_gesture_cross_task_wallpaper";
    public static final boolean DEFAULT_CROSS_TASK_WALLPAPER = false;
    public static final String KEY_ONEUI_CROSS_TASK_ANIMATION =
            "oneui_cross_task_animation";
    public static final boolean DEFAULT_ONEUI_CROSS_TASK_ANIMATION = false;
    public static final String KEY_MODULE_LOGGING = "module_logging";
    public static final boolean DEFAULT_MODULE_LOGGING = true;
    public static final String KEY_CONTEXTUAL_SEARCH_LONG_PRESS =
            "contextual_search_long_press";
    public static final boolean DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS = false;
    public static final String KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE =
            "contextual_search_live_translate";
    public static final boolean DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE = false;
    public static final String KEY_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX =
            "google_lens_contextual_searchbox";
    public static final boolean DEFAULT_GOOGLE_LENS_CONTEXTUAL_SEARCHBOX = false;
    public static final String KEY_CONTEXTUAL_SEARCH_HAPTICS =
            "contextual_search_haptics";
    public static final boolean DEFAULT_CONTEXTUAL_SEARCH_HAPTICS = false;

    /** Skip the reveal-system-bars step for an immersive side-back gesture. */
    public static final String KEY_FULLSCREEN_DIRECT_BACK = "fullscreen_direct_back";
    public static final boolean DEFAULT_FULLSCREEN_DIRECT_BACK = false;

    /** The vertical size of both Xiaomi side trigger areas, expressed as a percentage. */
    public static final String KEY_GESTURE_TRIGGER_HEIGHT_PERCENT =
            "gesture_trigger_height_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;
    public static final int MIN_GESTURE_TRIGGER_HEIGHT_PERCENT = 10;
    public static final int MAX_GESTURE_TRIGGER_HEIGHT_PERCENT = 100;

    /** The top offset of both side trigger areas within their available vertical travel. */
    public static final String KEY_GESTURE_TRIGGER_POSITION_PERCENT =
            "gesture_trigger_position_percent";
    public static final int DEFAULT_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MIN_GESTURE_TRIGGER_POSITION_PERCENT = 0;
    public static final int MAX_GESTURE_TRIGGER_POSITION_PERCENT = 100;

    private PredictiveBackPreferences() {
    }
}
