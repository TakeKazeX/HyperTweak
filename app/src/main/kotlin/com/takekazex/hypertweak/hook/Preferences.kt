package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Preferences {
    const val NAME = "hypertweak_settings"
    const val DEFAULT_SEED_COLOR = 0
    const val DEFAULT_IMMEDIATE_MONET_REFRESH = true

    // Key names
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_REMOVE_GMS_RESTRICTION = "remove_gms_restriction"

    /**
     * Unlocks Nearby Share (Quick Share) on CN Google Play services by overriding the
     * `sharing_supports_latchsky` phenotype flag to true in GMS's CE `phenotype.db`.
     * GMS must be in the Xposed scope (requested when the switch is turned on).
     */
    const val KEY_QUICK_SHARE_ENABLED = "quick_share_enabled"

    /**
     * Shows the full-screen live-translate (屏幕实时翻译) button inside Circle to Search
     * (即圈即搜) on the Google app (`com.google.android.googlequicksearchbox`).
     * `GoogleAppLiveTranslateHooker` opens the three gates that keep the button hidden (the
     * `com.google.android.apps.search.lens.user/45785436` master flag, the
     * `CONTEXTUAL_SEARCH_LIVE_TRANSLATE` system feature, and the EXTRA_MEDIA_PROJECTION display
     * predicate). The Google app is a declared required Xposed scope (see `scope.list`); the
     * switch flips the preference and restarts the app, and disabled (default) installs nothing
     * and leaves the app untouched.
     */
    const val KEY_FULL_SCREEN_TRANSLATE = "circle_to_search_full_screen_translate"

    /**
     * Shows "Ask about this screen" (针对屏幕内容提问) in the Circle to Search (即圈即搜)
     * Lensient searchbox on the Google app (`com.google.android.googlequicksearchbox`).
     * `GoogleAppAskAboutScreenHooker` resolves the native Lensient screen-capability gate
     * (`bydc.c()`, lazily server-fetched and false on stock) through the AIM model DI chain
     * (`wry.iX()` → `doqf.<init>` → `djyp` coordinator) and force-opens it, mirroring upstream
     * MiuiBackGestureHook commit `0f603b1d`. The Google app is a declared required Xposed scope
     * (see `scope.list`); the switch flips the preference and restarts the app, and disabled
     * (default) installs nothing.
     */
    const val KEY_ASK_ABOUT_SCREEN = "circle_to_search_ask_about_screen"
    const val KEY_HIDE_FINGERPRINT = "hide_fingerprint"
    const val KEY_HIDE_LOCKSCREEN_STATUS_BAR = "hide_lockscreen_status_bar"

    /**
     * Lockscreen notification fingerprint avoidance (锁屏通知指纹避让), OS4 SystemUI. Read by
     * `KeyguardFingerprintAvoidHooker` at hook-install time; requires a SystemUI restart.
     * 0 = follow the system (avoid when fingerprint unlock is enabled and templates are enrolled),
     * 1 = never avoid the in-display fingerprint icon, 2 = always avoid it.
     */
    const val KEY_LOCKSCREEN_FINGERPRINT_AVOID = "lockscreen_fingerprint_avoid"
    const val LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT = 0
    const val LOCKSCREEN_FINGERPRINT_AVOID_NO = 1
    const val LOCKSCREEN_FINGERPRINT_AVOID_ALWAYS = 2

    /**
     * Appends live charging telemetry (wattage / voltage / current / temperature) to the lock
     * screen's bottom charging indication line, replacing the plain "充电中xx% / 已充满电" text
     * with e.g. "极速充电 50% · 12.3W · 9.0V 1.4A · 35°C". Read by
     * `LockscreenChargingDetailHooker`; requires a SystemUI restart (restart-scope key).
     */
    const val KEY_LOCKSCREEN_CHARGING_DETAIL = "lockscreen_charging_detail"

    /**
     * Live-read by the hooker each render (sub-options of the charging detail feature): which
     * telemetry fields to show (bitmask of [LockscreenChargingDetailHooker]'s FIELD_* bits,
     * all four on by default), how often the values refresh (ms), and whether the detail goes
     * on its own line below the charging text instead of extending the single scrolling line.
     * These three are read on every indication render, so they take effect without a SystemUI
     * restart once the main switch is on.
     */
    const val KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS = "lockscreen_charging_detail_fields"
    const val KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS = "lockscreen_charging_detail_interval_ms"
    const val KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE = "lockscreen_charging_detail_multiline"

    const val KEY_HIDE_GESTURE_BAR = "hide_gesture_bar"
    const val KEY_GESTURE_BAR_RAISE_LAYOUT = "gesture_bar_raise_layout"
    const val KEY_GESTURE_BAR_ACTIONS_ENABLED = "gesture_bar_actions_enabled"
    const val KEY_GESTURE_BAR_LONG_PRESS_ACTION = "gesture_bar_long_press_action"
    const val KEY_GESTURE_BAR_DOUBLE_TAP_ACTION = "gesture_bar_double_tap_action"

    /**
     * Long-press power button action (长按电源键操作). `PowerButtonCtsHooker` intercepts
     * `PowerKeyRule.onMiuiLongPress` (the MIUI 快捷手势 layer) and
     * `PhoneWindowManager.powerLongPress` (the AOSP fallback) in system_server and dispatches
     * the selected action: [POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH] starts the contextual-search
     * service through `ContextualSearchSystemHooker`, [POWER_BUTTON_ACTION_DEFAULT_ASSISTANT]
     * launches the user's default digital assistant (Google Assistant / Gemini / 小爱) through
     * the platform assist pipeline. [POWER_BUTTON_ACTION_DISABLED] leaves the system's own
     * long-press action untouched. The action and the haptic toggle are read live at dispatch
     * time, so switching between actions (or off) takes effect without a reboot once the
     * system-server hooks are installed; turning the feature on from disabled still needs a
     * reboot for those hooks (and the CTS bridge) to install.
     */
    const val KEY_POWER_BUTTON_ACTION = "power_button_long_press_action"
    const val KEY_POWER_BUTTON_HAPTIC = "power_button_long_press_haptic"
    const val POWER_BUTTON_ACTION_DISABLED = 0
    const val POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH = 1
    const val POWER_BUTTON_ACTION_DEFAULT_ASSISTANT = 2
    const val DEFAULT_POWER_BUTTON_HAPTIC = true

    /** Legacy single-switch Circle to Search enable; superseded by [KEY_POWER_BUTTON_ACTION]. */
    const val KEY_POWER_BUTTON_CTS = "power_button_circle_to_search"
    const val KEY_SHOW_IN_SETTINGS = "show_in_settings"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_SLIDER_SHOW_PERCENTAGE = "systemui_control_center_slider_show_percentage_enabled"
    const val KEY_SLIDER_SAME_PERCENTAGE_STYLE = "systemui_control_center_slider_same_percentage_style_enabled"

    // Control Center corner-radius overrides. Each group keeps its own dp value (Float);
    // 0 means "no override" (follow the system design), any positive value is applied as the
    // GradientDrawable/outline corner radius of the corresponding control center element.
    // The hooker converts dp → px at hook time (plugins expect px for these APIs).
    const val KEY_CC_CORNER_ENABLED = "systemui_control_center_corner_enabled"
    const val KEY_CC_CORNER_SLIDER = "systemui_control_center_corner_slider_dp"
    const val KEY_CC_CORNER_TILE = "systemui_control_center_corner_tile_dp"
    const val KEY_CC_CORNER_CARD = "systemui_control_center_corner_card_dp"
    const val KEY_CC_CORNER_DEVICE = "systemui_control_center_corner_device_dp"
    const val KEY_CC_CORNER_MEDIA = "systemui_control_center_corner_media_dp"
    /** Comma-separated top-card specs, e.g. `cell,wifi`. Empty = follow the system order. */
    const val KEY_CC_TOP_CARD_ORDER = "systemui_control_center_top_card_order"
    /**
     * Comma-separated main-panel section keys (`qscards,media,brightness,volume,devicecenter,qslist`),
     * written by the editor drag hook and re-applied by [KEY_CC_EDIT_ENABLED]'s ordering hook.
     */
    const val KEY_CC_MAIN_CONTENT_ORDER = "systemui_control_center_main_content_order"
    /**
     * Master switch for the control-center editor cards feature: shows the fixed main-panel
     * contents (big cards, media player, brightness/volume sliders, device center) inside
     * 编辑与排序 and makes them drag-reorderable like the quick actions.
     */
    const val KEY_CC_EDIT_ENABLED = "systemui_control_center_edit_enabled"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_USE_MONET = "theme_use_monet"
    const val KEY_SEED_COLOR = "theme_seed_color"
    const val KEY_THEME_PALETTE_STYLE = "theme_palette_style"
    const val KEY_PURE_BLACK_DARK_THEME = "pure_black_dark_theme"
    const val KEY_USE_FLOATING_BOTTOM_BAR = "use_floating_bottom_bar"
    const val KEY_FLOATING_BAR_STYLE = "floating_bar_style"
    const val KEY_PREDICTIVE_BACK_STYLE = "predictive_back_style"
    const val KEY_PREDICTIVE_BACK_FOLLOW_GESTURE = "predictive_back_follow_gesture"
    const val KEY_MIUI_BACK_GESTURE_HOOK = "miui_back_gesture_hook"
    const val KEY_CROSS_TASK_WALLPAPER_BACKGROUND = "cross_task_wallpaper_background"

    // AOSP back gesture, ported through MiuiBackGestureHook v0.8.5 + git a5f1ae5.
    /** Per-app predictive-back opt-in, read in the system server. */
    const val KEY_AOSP_BACK_OPT_IN_PACKAGES = "aosp_back_opt_in_packages"
    const val KEY_AOSP_BACK_HYPEROS_INDICATOR = "aosp_back_hyperos_indicator"
    const val KEY_AOSP_BACK_HYPEROS_HAPTICS = "aosp_back_hyperos_haptics"
    const val KEY_AOSP_BACK_HYPEROS_HAPTICS_ENHANCED = "aosp_back_hyperos_haptics_enhanced"
    const val KEY_AOSP_BACK_SLIDE_ANIMATION = "aosp_back_slide_animation"

    /**
     * Launcher-side hook route. Only Launcher 7 exposes the `com.miui.home` Java classes the
     * predictive return-home animation hooks, so this defaults off on Launcher 8 and newer.
     * See [KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET].
     */
    const val KEY_AOSP_BACK_MIUI_HOME_HOOKS = "aosp_back_miui_home_hooks"
    const val KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET = "aosp_back_miui_home_hooks_user_set"

    /** Cached `com.miui.home` version, so hook processes can gate without a PackageManager. */
    const val KEY_LAUNCHER_MAJOR = "launcher_version_major"
    const val KEY_LAUNCHER_VERSION_NAME = "launcher_version_name"
    const val KEY_ALLOW_LANDSCAPE = "allow_landscape"
    const val KEY_UNLOCK_PASSKEY = "unlock_passkey"

    // AOSP restore.
    /**
     * Hands package installs back to the AOSP installer. Relaxes MIUI install verification while
     * the installer-selection methods run; see `AospPackageInstallerHooker`.
     */
    const val KEY_AOSP_PACKAGE_INSTALLER = "aosp_package_installer"

    /** Blocks MIUI's global-actions plugin so SystemUI falls back to the AOSP power menu. */
    const val KEY_AOSP_POWER_MENU = "aosp_power_menu"

    /** Blocks MIUI's volume-dialog plugin so SystemUI falls back to the AOSP volume panel. */
    const val KEY_AOSP_VOLUME_PANEL = "aosp_volume_panel"

    /** Re-derives keyguard trust state from TrustManagerService when SystemUI's cache goes stale. */
    const val KEY_EXTEND_UNLOCK_FIX = "extend_unlock_fix"

    /** Lets AOSP's clipboard overlay editor appear for real copies, not just CTS. */
    const val KEY_AOSP_CLIPBOARD_EDITOR = "aosp_clipboard_editor"

    /** Security Center entry points into Settings' AOSP app screens. */
    const val KEY_AOSP_APP_INFO_ENTRY = "aosp_app_info_entry"
    const val KEY_AOSP_APP_MANAGER_ENTRY = "aosp_app_manager_entry"

    /** Restores AOSP's full-screen IME navigation bar. */
    const val KEY_AOSP_IME_ENABLED = "aosp_ime_fullscreen"

    /** Input methods the AOSP IME hooks apply to; also drives the dynamic scope request. */
    const val KEY_AOSP_IME_PACKAGES = "aosp_ime_packages"
    const val KEY_AOSP_IME_NAV_BAR_START = "aosp_ime_nav_bar_start"
    const val KEY_AOSP_IME_NAV_BAR_END = "aosp_ime_nav_bar_end"

    /** Lists every enabled input method in MIUI's keyboard switcher. Unverified off-device. */
    const val KEY_AOSP_IME_MIUI_IME_LIST = "aosp_ime_miui_ime_list"

    /**
     * Forces the AOSP bar on MIUI-customized keyboards (搜狗小米版 / 百度小米版 / 讯飞小米版 etc.)
     * whose own 全面屏优化 bottom view would otherwise win, instead of leaving them alone.
     */
    const val KEY_AOSP_IME_FORCE_ALL = "aosp_ime_force_all"

    /**
     * How the keyboard content is positioned relative to the AOSP bar: 0 raises the content so it
     * ends exactly at the bar top (AOSP style), 1 leaves the keyboard's own bottom handling alone
     * (MIUI style). See [AospImeConfig.RAISE_STYLE_AOSP].
     */
    const val KEY_AOSP_IME_RAISE_STYLE = "aosp_ime_raise_style"
    const val KEY_LANGUAGE = "app_language"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_APP_SHORTCUTS = "app_shortcuts"
    const val KEY_APP_SHORTCUTS_ORDER = "app_shortcuts_order"
    const val KEY_DISABLE_SPATIAL_AUDIO = "disable_spatial_audio"
    const val KEY_FORCE_ADAPTIVE_ANC = "force_adaptive_anc"
    const val KEY_FCM_LIVE_ENABLED = "fcm_live_enabled"
    const val KEY_IMMEDIATE_MONET_REFRESH = "immediate_monet_refresh"

    /**
     * Hides the source-app icon overlay on the media cards (封面隐藏来源应用图标): the 24dp
     * `CachingIconView` on the top-left corner of the album cover in both the notification-shade
     * card (`MiuiMediaViewControllerImpl`) and the island card
     * (`MiuiIslandMediaViewBinderImpl`). Read by `MediaCardHideAppIconHooker` at hook-install
     * time; requires a SystemUI restart.
     */
    const val KEY_MEDIA_CARD_HIDE_APP_ICON = "media_card_hide_app_icon"

    /**
     * Hides the device-switch button on the media cards (隐藏设备切换按钮): the top-right
     * `media_seamless` on the notification-shade card (`MiuiMediaViewControllerImpl.setSeamless`)
     * and the island card (`MiuiIslandMediaViewBinderImpl.setSeamless`), plus the plugin main
     * card's `device_icon` (`MediaPlayerDeviceIconHooker`, plugin scope). The SystemUI half is
     * read by `MediaCardHideDeviceSwitchHooker` at hook-install time; the plugin half gates
     * attachment at plugin load and re-reads live per callback. Requires a SystemUI restart.
     */
    const val KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH = "media_card_hide_device_switch"

    const val KEY_PENDING_RESTART_SCOPES = "pending_restart_scopes"
    const val KEY_LOG_LEVEL = "debug_log_level"
    const val KEY_RECORD_LOGS = "record_logs"
    const val KEY_AOSP_BACK_LOGS = "aosp_back_logs"

    // Status-bar icon tuner, ported from Hyper Helper's icon tuner (see the reverse-engineering
    // workspace, cache/xiaomihelper-2bfd4873a4138764). Each switch is read by a SystemUI hook at
    // hook-install time and requires a SystemUI restart; the page keeps its own state like
    // AospRestorePage, so these keys are deliberately absent from TWEAK_RESTART_SCOPES.
    const val KEY_ICON_HIDE_CELLULAR_ACTIVITY = "icon_hide_cellular_activity"
    const val KEY_ICON_HIDE_CELLULAR_TYPE = "icon_hide_cellular_type"
    const val KEY_ICON_HIDE_CELLULAR_ROAM = "icon_hide_cellular_roam"
    const val KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL = "icon_hide_cellular_roam_global"
    const val KEY_ICON_HIDE_CELLULAR_SMALL_ROAM = "icon_hide_cellular_small_roam"
    const val KEY_ICON_HIDE_CELLULAR_VOWIFI = "icon_hide_cellular_vowifi"
    const val KEY_ICON_HIDE_CELLULAR_VOLTE = "icon_hide_cellular_volte"
    const val KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE = "icon_hide_cellular_volte_no_service"
    const val KEY_ICON_HIDE_CELLULAR_SPEECH_HD = "icon_hide_cellular_speech_hd"
    const val KEY_ICON_HIDE_WIFI_ACTIVITY = "icon_hide_wifi_activity"
    const val KEY_ICON_HIDE_WIFI_TYPE = "icon_hide_wifi_type"
    const val KEY_ICON_HIDE_WIFI_UNAVAILABLE = "icon_hide_wifi_unavailable"
    const val KEY_ICON_WIFI_ACTIVITY_RIGHT = "icon_wifi_activity_right"

    // Hide the cellular icon of the non-default SIM when multiple SIMs are active.
    const val KEY_ICON_HIDE_NON_DEFAULT_SIM = "icon_hide_non_default_sim"
    const val KEY_ICON_HIDE_SIM_AUTO = "icon_hide_sim_auto"

    // Cellular type display, ported from Hyper Helper's `CellularTypeIcon` (keys match upstream
    // f32.m/q/r). Forces the single-carrier type text and/or a custom type name.
    const val KEY_ICON_CELLULAR_TYPE_SINGLE = "icon_tuner_cellular_type_single"
    const val KEY_ICON_CELLULAR_TYPE_CUSTOM = "icon_tuner_cellular_type_custom"
    const val KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL = "icon_tuner_cellular_type_custom_val"

    // Stacked mobile signal — rebuilt on Flux Decor 2.0.3's view-level model (see
    // docs/FLUX_DECOR_STACKED_SIGNAL_PLAN.md). The former SVG-render keys (styles, paddings,
    // alphas, type text, show-single/stacked/roaming) were deleted with the renderer.
    const val KEY_ICON_STACKED_ENABLED = "icon_stacked_enabled"
    const val KEY_ICON_STACKED_SCALE = "icon_stacked_scale"

    /**
     * 图标左置 (left-container icon placement), ported from Hyper Helper's `LeftContainer`
     * (OS4_ADAPTATION_PLAN.md T2) and rebuilt for the OS4 home status bar. The master switch
     * installs the left container next to the clock; each per-slot toggle moves that status-bar
     * slot's icon out of the right cluster into the left container (e.g. 勿扰/静音/热点 to the
     * right of the clock). All keys are read at `onHook()`; a SystemUI restart applies changes.
     */
    const val KEY_ICON_LEFT_CONTAINER_ENABLED = "icon_left_container_enabled"
    const val KEY_ICON_LEFT_ZEN = "icon_left_zen"
    const val KEY_ICON_LEFT_VOLUME = "icon_left_volume"
    const val KEY_ICON_LEFT_HOTSPOT = "icon_left_hotspot"
    const val KEY_ICON_LEFT_ALARM_CLOCK = "icon_left_alarm_clock"
    const val KEY_ICON_LEFT_LOCATION = "icon_left_location"
    const val KEY_ICON_LEFT_BLUETOOTH = "icon_left_bluetooth"
    const val KEY_ICON_LEFT_NFC = "icon_left_nfc"
    const val KEY_ICON_LEFT_VPN = "icon_left_vpn"
    const val KEY_ICON_LEFT_AIRPLANE = "icon_left_airplane"
    const val KEY_ICON_LEFT_HEADSET = "icon_left_headset"

    // Compound icon feature (merges alarm / DND / location / volume into one status-bar icon),
    // ported from Hyper Helper's `CompoundIcon`. The individual source toggles match upstream
    // g32.K..N; the master switch is the `compound_icon` slot mode (1..3, see IconManagerHooker).
    // `KEY_ICON_COMPOUND_PRIORITY` is the comma-separated priority order (upstream g32.O,
    // default "location,alarm_clock,zen,volume"); the earlier entry wins when several states
    // are active at once.
    const val KEY_ICON_COMPOUND_ALARM = "icon_tuner_compound_icon_alarm"
    const val KEY_ICON_COMPOUND_ZEN = "icon_tuner_compound_icon_zen"
    const val KEY_ICON_COMPOUND_LOCATION = "icon_tuner_compound_icon_location"
    const val KEY_ICON_COMPOUND_VOLUME = "icon_tuner_compound_icon_volume"
    const val KEY_ICON_COMPOUND_PRIORITY = "icon_tuner_compound_priority"

    // Carrier label hiding, ported from Hyper Helper's `HideCarrierLabel` (keys match upstream
    // w22.* / x22.e-f). `KEY_ICON_HIDE_CARRIER_*` cover the status-bar / control-center carrier
    // rows and the HD icon; `KEY_ICON_HIDE_LS_CARRIER_*` cover the lockscreen header.
    const val KEY_ICON_HIDE_CARRIER_ONE = "statusbar_hide_carrier_one"
    const val KEY_ICON_HIDE_CARRIER_TWO = "statusbar_hide_carrier_two"
    const val KEY_ICON_HIDE_CARRIER_HD = "statusbar_hide_carrier_hd"
    const val KEY_ICON_HIDE_LS_CARRIER_ONE = "systemui_ls_hide_carrier_one"
    const val KEY_ICON_HIDE_LS_CARRIER_TWO = "systemui_ls_hide_carrier_two"

    // Force status-bar region sampling (light-bar samples the region behind the status bar so
    // icons/background adapt), ported from Hyper Helper's `RegionSampling` (key matches upstream
    // i32.c). Int-typed: 0 off, 1 on.
    const val KEY_STATUSBAR_REGION_SAMPLING = "statusbar_region_sampling"

    // Slot show/hide modes (0 = follow system, 1 = hide, 2 = status bar only, 3 = control center
    // only, 4 = show everywhere). Keys are generated by [slotKey].
    const val KEY_ICON_EXT_BLOCKED = "icon_ext_blocked"

    // Ignore the system's own icon hiding.
    const val KEY_ICON_IGNORE_SYS_HIDE = "icon_ignore_sys_hide"
    const val KEY_ICON_HIDE_PRIVACY = "icon_hide_privacy"

    // OS4 material style glass tuning (Settings → Display → Visual style, 材质风格). The
    // SystemUI hook intercepts the blur/blend resources behind 清透磨砂 and 柔光玻璃, so the
    // page keeps its own state like IconTunerPage and these keys are deliberately absent from
    // TWEAK_RESTART_SCOPES; the page offers its own SystemUI restart.
    const val KEY_GLASS_TUNER_ENABLED = "glass_tuner_enabled"

    /** Blend opacity multiplier (0.1..1.0, 1.0 = original); scales every blend color alpha. */
    const val KEY_GLASS_TUNER_BLEND_ALPHA = "glass_tuner_blend_alpha"

    /**
     * Blend color lightness (0.5..1.5, 1.0 = original). Below 1.0 mixes the blend colors toward
     * black (darker tint), above 1.0 toward white (lighter, more transparent-looking tint).
     */
    const val KEY_GLASS_TUNER_BLEND_LIGHTNESS = "glass_tuner_blend_lightness"

    /** Blur max-radius multiplier (0.0..2.0, 1.0 = original); scales every blur radius dimen. */
    const val KEY_GLASS_TUNER_RADIUS_SCALE = "glass_tuner_radius_scale"

    /**
     * Glass card effect multiplier (0.1..1.0, 1.0 = original). Scales the glass-shader params
     * behind the notification / media card material (darkening, white tint, highlights).
     */
    const val KEY_GLASS_TUNER_GLASS_OPACITY = "glass_tuner_glass_opacity"

    /**
     * Glass tone multiplier (0.0..2.0, 1.0 = original). Scales the shader's base cast —
     * luminance mix, brightness and blurred-backdrop saturation — that survives at zero
     * opacity and reads as a grey, heavy look; lower is lighter and more see-through.
     */
    const val KEY_GLASS_TUNER_GLASS_TONE = "glass_tuner_glass_tone"

    // Status-bar slot preference key for [slot]; shared by the hooks and the settings UI.
    fun slotKey(slot: String): String = "icon_tuner_slot_$slot"

    /**
     * The current long-press power action ([POWER_BUTTON_ACTION_*]). Reads
     * [KEY_POWER_BUTTON_ACTION]; when it is unset it migrates the legacy
     * [KEY_POWER_BUTTON_CTS] boolean (on → [POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH]), so
     * existing users keep their Circle to Search binding without touching the setting.
     */
    fun powerButtonAction(): Int {
        val action = getInt(KEY_POWER_BUTTON_ACTION, -1)
        if (action == POWER_BUTTON_ACTION_DISABLED ||
            action == POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH ||
            action == POWER_BUTTON_ACTION_DEFAULT_ASSISTANT
        ) {
            return action
        }
        return if (getBoolean(KEY_POWER_BUTTON_CTS, false)) {
            POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH
        } else {
            POWER_BUTTON_ACTION_DISABLED
        }
    }

    /** Persists the long-press power action and drops the superseded legacy boolean. */
    fun setPowerButtonAction(action: Int) {
        memoInvalidate(KEY_POWER_BUTTON_ACTION)
        memoInvalidate(KEY_POWER_BUTTON_CTS)
        write {
            putInt(KEY_POWER_BUTTON_ACTION, action)
            remove(KEY_POWER_BUTTON_CTS)
        }
    }

    /**
     * The current street-snap unlock mode (a [CameraStreetMode] constant). Reads
     * [KEY_CAMERA_STREET_MODE]; when that key was never written it migrates the legacy
     * [LEGACY_KEY_CAMERA_STREET_ENABLE] boolean in memory (true → `"new"`, false → `"off"`),
     * so existing users keep their stored street behaviour without a data rewrite. An
     * unparsable stored value falls back to [CameraStreetMode.DEFAULT]. Read live by the
     * camera hooks (100 ms memo), so switching modes applies without a restart once the hooks
     * are installed — except entry VISIBILITY, which the camera caches per process.
     */
    fun cameraStreetMode(): String {
        val stored = if (containsKey(KEY_CAMERA_STREET_MODE)) getString(KEY_CAMERA_STREET_MODE, "") else null
        val legacy = if (stored == null) getBoolean(LEGACY_KEY_CAMERA_STREET_ENABLE, true) else null
        return CameraStreetMode.resolve(stored, legacy)
    }

    /** Persists the street mode and drops the superseded legacy boolean. */
    fun setCameraStreetMode(mode: String) {
        memoInvalidate(KEY_CAMERA_STREET_MODE)
        memoInvalidate(LEGACY_KEY_CAMERA_STREET_ENABLE)
        write {
            putString(KEY_CAMERA_STREET_MODE, mode)
            remove(LEGACY_KEY_CAMERA_STREET_ENABLE)
        }
    }

    /** True when [key] exists in the authoritative remote store (memo bypassed). */
    private fun containsKey(key: String): Boolean =
        isInitialized && runCatching { remotePrefs.contains(key) }.getOrDefault(false)

    // Media-editor watermark unlock (com.miui.mediaeditor). See
    // `MediaEditorWatermarkHooker`; switches are read live, so only the first enable of
    // KEY_WM_UNLOCK_MASTER needs the editor process restarted (to install the hooks).
    const val KEY_WM_UNLOCK_MASTER = "wm_unlock_master"
    const val KEY_WM_LEICA = "wm_leica"
    const val KEY_WM_XIAOMI = "wm_xiaomi"
    const val KEY_WM_REDMI = "wm_redmi"
    const val KEY_WM_POCO = "wm_poco"
    const val KEY_WM_DISNEY1 = "wm_disney1"
    const val KEY_WM_DISNEY2 = "wm_disney2"
    const val KEY_WM_DISNEY3 = "wm_disney3"
    const val KEY_WM_VICTORIA = "wm_victoria"
    const val KEY_WM_LCC = "wm_lcc"
    const val KEY_WM_DOWNLOAD_ALL = "wm_download_all"

    /** Camera app (com.android.camera) watermark unlock; see `CameraWatermarkHooker`. */
    const val KEY_WM_CAMERA = "wm_camera"

    /**
     * Fake the Leica LCC theme gate (`Je.c#V()`) for the camera app; see
     * `CameraImpersonationHooker`. Unlocks features gated on the LCC theme (e.g. 徕卡一瞬)
     * and keeps the 相机配色 settings entry visible, WITHOUT the flagship config swap and
     * without changing any real device theme property. Read live (100 ms memo).
     */
    const val KEY_CAMERA_IMPERSONATE_THEME_LCC = "camera_impersonate_theme_lcc"

    /**
     * Restore the Leica photography-style (摄影风格 cv_type 徕卡经典 ↔ 徕卡生动) switcher. The
     * 摄影风格 component and the top-bar style entries gate on the config's `F3()`
     * (Leica-level device flag; `X2()` for the specific-capture path): `true` on the
     * CommonFlagship / Nezha branch, `false` on the REDMI C1199 branch that myron's own
     * C1209 inherits — so the stock REDMI config drops the Leica style switcher. The hook
     * forces `F3()`/`X2()` true RAISE-ONLY (native values are never lowered) on the base
     * Methods the real config dispatches to. Does NOT reopen Legendary (gated on
     * `W0()=instanceof C1178`) nor the 231 LCC-RAW stream, so no purple/RAW regression.
     * Side effect: the shutter-sound list gains the four Leica entries (`f2.c.b()` adds them
     * when `F3()` is true; the resident bounds clamp keeps an old out-of-range selection
     * from crashing the list). Default on.
     */
    const val KEY_CAMERA_LEICA_STYLE = "camera_impersonate_leica_style"

    /**
     * MasterLive (实况运镜) role-23 (`Standalone`) -> role-20 (`tele`) fallback on the role
     * adapter (`u6.e`/jadx `p703u6.e` `M()`), so the 15x endpoint of the K100 Pro Max effect
     * table resolves on devices whose tele is only labelled role 20 (Samsung JN5). Harmless
     * when role 23 exists (falls back only when `get(23)==-1`). Default on.
     */
    const val KEY_CAMERA_MASTERLIVE_TELE_FALLBACK = "camera_masterlive_tele_fallback"

    /**
     * MasterLive (实况运镜) video-size probe — enabled automatically on myron. On myron the
     * MasterLive live-video stream is sized with the HAL masterlive ratio tag (`G()`,
     * `C3545f#G`, the `masterLivePhotoEISCropFactor` vendor tag) into 16:9 sizes
     * (2560x1440 / per-role HAL pairs from `com.xiaomi.camera.livePhoto.videoSize`) whose
     * frames arrive damaged (content squeezed, zero-chroma green; the still stays clean).
     * When on, the `getLivePhotoVideoSize` computation for mode 231 AND the video-compose
     * surface size (`Kj.D#c()`, mode-gated — its 2304x1296 fallback crashed CamX when it
     * mismatched the stream) are bound PER EFFECT TYPE ([CameraMasterLiveSizeBinding]):
     * movement effects (红毯/主角/自由, types 1/2/3) pin to 16:9 2304x1296 — user-verified
     * clean captures — while the ultra-pixel 超清实况 effect (type 0, 4:3) pins to the
     * device's own clean 4:3 geometry 1728x1296 (a global 16:9 pin broke it with green
     * frames again; RESEARCH_MYRON_11). The current type comes from the camera's own
     * MasterLive component value (`j#A(231)` → `pref_master_live_key`); unreadable falls
     * back to the verified 16:9. Requires a camera restart after changing.
     */
    const val KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE = "camera_masterlive_video_size_probe"

    /**
     * MasterLive (实况运镜) 红毯运镜 injection (default ON). The K100 Pro Max effect table
     * (`q0()`) ships only types "0" (超清实况), "2" (主角非线性) and "3" (自由线性) — the
     * 17-Ultra-exclusive "1" 红毯运镜 (slow-motion tail, `master_live_slow_motion`) is
     * missing, so it never appeared in the effect selector even though every UI resource for
     * it ships on every ROM. When on (and [KEY_CAMERA_MASTERLIVE_ENABLE] is on), the served
     * table gains a synthesized "1" entry cloned from the proven-working linear entry with a
     * cleared default flag (`CameraMasterLiveRedCarpet`): all decrypted role/range strings
     * stay byte-identical to what already resolves on this device, the panel/guide UI pick
     * the entry up natively (type-switch driven), and the default effect stays 超清实况.
     * Live-read (100 ms memo); visibility needs a camera restart.
     */
    const val KEY_CAMERA_MASTERLIVE_RED_CARPET = "camera_masterlive_red_carpet"

    /**
     * MasterLive (实况运镜) full focal line-up (超清实况焦段条, default ON). The zoom toggle
     * strip inside 实况运镜 reads the config's per-mode zoom stops `v1()` keyed by mode id:
     * the real myron config has NO 231 key, so the camera falls back to the hardcoded
     * `{1.0x, 2.0x}` pair and 超清实况 shows only 1x/2x where a full unlock shows the whole
     * line-up. When on (and [KEY_CAMERA_MASTERLIVE_ENABLE] is on), the original config's
     * `v1()` result gains `231 → {0.7, 1.0, 2.0, 5.0, 10.0}` (the K100 Pro Max stops —
     * bit-identical sensor axis to myron and exactly myron's real optics: 0.7x OV50M ultra /
     * 1x OV50Q main / 2x digital / 5x·120mm JN5 / 10x digital) whenever the key is absent;
     * an existing key is never touched, and no other mode's stops change. Read live; takes
     * effect on the next mode entry (camera restart if open).
     */
    const val KEY_CAMERA_MASTERLIVE_FULL_FOCAL = "camera_masterlive_full_focal"

    /**
     * Custom watermark master switch. When on, the user-typed brand / model overrides
     * (`KEY_CAMERA_WM_CUSTOM_BRAND` / `KEY_CAMERA_WM_CUSTOM_MODEL`) replace the device's own
     * watermark text in `CameraImpersonationHooker` and `CameraWatermarkHooker.hookDeviceLogo`;
     * when off, the values are ignored. Default off.
     */
    const val KEY_CAMERA_WM_CUSTOM = "camera_wm_custom"

    /**
     * User-typed custom watermark brand / model, honoured only while `KEY_CAMERA_WM_CUSTOM`
     * is on; blank = fall back to the device's own brand / marketname.
     */
    const val KEY_CAMERA_WM_CUSTOM_BRAND = "camera_wm_custom_brand"
    const val KEY_CAMERA_WM_CUSTOM_MODEL = "camera_wm_custom_model"

    /**
     * Camera app (com.android.camera) 超高图片质量 (ultra-high image quality) fixed unlock;
     * see `CameraUltraQualityHooker`. The 设置→图片质量 option list (`SettingImageQuality`,
     * pref key `pref_camera_jpegquality_key`) gains its 超高 entry only while the per-device
     * capability config reports `l7() == true` — a base-class method declared as
     * `return this instanceof C1148` (flagship-only marker) that this device's own
     * `com.mi.device.Myron` config does not override, so 超高 is hidden natively. The hook forces that one gate (declared once on the config base and
     * inherited by both) to this preference's live value: on = 超高 offered and the quality
     * clamp `j#t()` caps at `F1.g3.SUPER` (JPEG quality 100); off = forced false, exactly the
     * stock behaviour here, which also re-clamps a stale stored 超高 selection back to 高.
     * Plain JPEG-quality values with no HAL dependency. Read live (100 ms memo); default ON.
     */
    const val KEY_CAMERA_ULTRA_HD_QUALITY = "camera_ultra_hd_quality"

    /**
     * 徕卡一瞬 (Leica Moment, camera mode id 256, jadx class `LegendaryEnter`) unlock; enabled
     * automatically on myron and manual on other devices. The entry registry (`p666t3.a.d()`) keeps a module entry only while its
     * `support()` is true, and `LegendaryEnter.support()` is
     * `Je.c.W0() && Je.c.V()`: W0() demands the 17-Ultra Nezha config class
     * (`instanceof com.mi.device.Nezha`, jadx C1209 on 6.6.000510.0) and V() the LCC
     * theme customisation (`ro.theme_customize == "lcc"`), so every non-flagship,
     * non-LCC device ships the mode closed. With this switch on,
     * [com.takekazex.hypertweak.hook.rules.camera.CameraImpersonationHooker] raises
     * `LegendaryEnter.support()` to true, which registers mode 256 into the 更多 overflow
     * grid — no verified config `M()` order array carries 256. Needs a camera app restart
     * (the registry caches per process). The RAW/re-processing pipeline behind the mode is
     * NOT validated on non-flagship HALs; turn it off if colours misbehave.
     */
    const val KEY_CAMERA_LEGENDARY_MOMENT = "camera_legendary_moment"

    /**
     * 智能构图 (smart composition) unlock; default OFF. Three independent levers, one switch:
     *
     *  1. The 设置→拍照 entry (`pref_camera_crop_preferred_key`, added by the photo-preferences
     *     builder, rendered inside the pre-existing 「AI智能推荐」 sub-page) is gated on the
     *     device-config `D3()` getter, declared once on the config base as
     *     `return this instanceof <REDMI-flagship-branch marker>` (jadx C1199 on 510); this
     *     device's own config (`com.mi.device.Myron`) sits on a sibling branch, so D3 is false
     *     natively and under the K100 Pro Max impersonation alike. The hook raises `D3()` on the
     *     union of dispatch classes (original config class + flagship class + config base class),
     *     which shows the setting AND consistently enables the capture-time consumers of the
     *     same gate (the 超清-mode composition metadata paths).
     *  2. Because the whole recommendation-toggle list always collapses into the 「AI智能推荐」
     *     sub-page on this device (size > 1 is guaranteed), a second hook injects the checkbox
     *     as a TOP-LEVEL row directly in 拍照设置 (`CameraCapturePreferenceFragment.
     *     addPhotoPreferences` after-hook reusing the fragment's own `addCheckBoxPreference`
     *     helper) so it is findable like 超高画质/内容凭证/自适应镜头.
     *  3. The viewfinder feature-bar entry (id 2853) is gated on the capabilities-util
     *     `M3()` = HAL `com.xiaomi.camera.autoCrop.autoCropVersion == 2`; the hook raises it
     *     too. On this device the HAL has NO autoCrop implementation at all (verified in
     *     /odm binaries + dumpsys media.camera, 2026-08-29), so this lever is an EMPTY SWITCH:
     *     the icon appears and is clickable, clicking shows the "not supported" hint, capture
     *     skips the wiring safely, and no composition guidance can ever render.
     *
     * Read live; reopen the settings page to refresh the rows (the D3/M3 callbacks and the
     * top-row injection all re-read this key per call).
     */
    const val KEY_CAMERA_SMART_COMPOSITION = "camera_smart_composition"

    /**
     * 内容凭证 (Content Credentials, C2PA) setting unlock; enabled automatically on myron and
     * manual on other devices. The 设置→水印 entry
     * (`pref_cai_type_key` → `CaiSettingFragment`) is gated on a static final boolean in the
     * camera's debug/capability holder class (540 `Qa.b.x`, JADX alias `f11706x`; older 510
     * builds used `u`/`f13393u`) initialised once from the system property
     * `ro.product.odm.support_cai`; absent on devices whose ODM does not declare it.
     * The hook force-initialises the holder class and flips the flag to true through
     * `StaticFieldWriter` at camera-process start, so the entry (and the credential
     * copyright/username sub-page) appears. Because the value is baked into a static final,
     * BOTH enabling and disabling need a camera app restart, and whether photos actually
     * carry verifiable credentials still depends on the HAL/mivi pipeline.
     */
    const val KEY_CAMERA_CONTENT_CREDENTIAL = "camera_content_credential"

    /**
     * 自适应镜头 (adaptive lens / auto fallback) setting unlock; default OFF, experimental.
     * The 设置→拍照 entry (`pref_camera_auto_fallback` → `AutoFallbackFragment`) shows only
     * while TWO capabilities-util gates report true: the near-range smooth-transition gate
     * (HAL characteristics `xiaomi.smoothTransition.nearRangeMode` present and true plus the
     * `disablefallback`/`fallbackRole` keys available) and the tele-fallback gate
     * (`com.xiaomi.teleFallback.isSupported`). Devices missing either ship no entry. The hook
     * forces both static util getters true (raise-only), covering the entry, the sub-page and
     * the module-level consumers consistently. Read live; reopen the settings page to refresh.
     * On a HAL that does not implement the vendor keys the zoom path may behave oddly — turn
     * it off if switching lenses glitches.
     */
    const val KEY_CAMERA_ADAPTIVE_LENS = "camera_adaptive_lens"

    /**
     * 街拍 (Street snap, camera mode id 225) unlock mode. One of [CameraStreetMode.MODES]:
     *  - `"off"` — street stays stock (hidden on myron and every other REDMI config);
     *  - `"new"` (新街拍) — force the street-support gate (`a3()`) true on the real device
     *    config (no REDMI config ships `a3=true` natively). The mode then registers
     *    (`StreetModuleEntry.support()`) and the quick-launch photo route re-classifies
     *    consistently with a working street;
     *  - `"compat"` (兼容模式街拍) — force `StreetModuleEntry.support()` itself true on the
     *    REAL device config, touching nothing else (`a3()` stays native so quick-launch keeps
     *    its stock classification), and still opens the HAL role-0 main camera.
     *
     * In both non-off modes the entry lands in the camera's 更多 overflow grid (no verified
     * config `M()` order array carries 225), which is exactly where natively street-capable
     * devices show it; visibility changes need a camera app restart because `p666t3.a`
     * caches its support()-filtered entry registry for the process lifetime. 装备街拍 (229)
     * depends on 17-Ultra modular-lens cameras (13/7) and stays closed in every mode.
     *
     * Supersedes the legacy boolean [LEGACY_KEY_CAMERA_STREET_ENABLE]; read through
     * [cameraStreetMode], written through [setCameraStreetMode]. Default `"new"`.
     */
    const val KEY_CAMERA_STREET_MODE = "camera_street_mode"

    /** Legacy single-switch street enable; superseded by [KEY_CAMERA_STREET_MODE]. */
    const val LEGACY_KEY_CAMERA_STREET_ENABLE = "camera_street_enable"

    /**
     * 快捷抢拍走街拍 (street quick-launch completion, default OFF): makes the lock-screen fast
     * camera route (设置→锁屏→其他→急速相机「打开相机并拍照」, `Settings.System.volumekey_launch_camera`
     * = 2 → system_server double-tap volume-down → `STILL_IMAGE_CAMERA` intent with
     * `camera_launch_source=launch_camera_and_take_photo`) classify as 街拍 (module 225) instead
     * of stock CAPTURE. Stock classification is
     * `CameraIntentManager.e()` = `a3() && v()` (`vr.l`/`vr.m`, jadx p757vr.C4755l/C4751m); the
     * compat street mode keeps `a3()` native, so the quick-launch route stays CAPTURE there.
     * This hook forces `e()` → "STREET" when the launch source is exactly
     * `launch_camera_and_take_photo`, and forces the guide gate (`Q5.J#f()`) true so
     * `StreetModule.setParameter` actually consumes the launch source. Read live (100 ms memo),
     * so it complements [KEY_CAMERA_STREET_MODE] without a restart; needs a camera app restart
     * for the hooks to install.
     *
     * Settings side ([rules.settings.FastCameraSettingsHooker]): the same switch forces
     * `LockscreenOthersHelper.supportCameraStreetMode()` true in the Settings process, so
     * 设置→锁屏→其他→急速相机 shows the「打开相机并拍照」 dropdown option instead of only the
     * plain switch — without it that option is `removePreference`d away on devices whose
     * `persist.vendor.camera.IsVariableApertureSupported`/`IsStreetModeSupported` are unset.
     */
    const val KEY_CAMERA_STREET_QUICK_LAUNCH = "camera_street_quick_launch"

    /** True when the lock-screen quick-capture route should classify as street. */
    fun cameraStreetQuickLaunch(): Boolean =
        getBoolean(KEY_CAMERA_STREET_QUICK_LAUNCH, false)

    /**
     * 实况运镜 (MasterLive, camera mode id 231) unlock master; default ON. While on, the
     * registry gate `y4()` (`MasterLiveModuleEntry.support()`) is forced true on the REAL
     * device config's base Method (C1143 — the one stock Redmi configs dispatch to, false on
     * myron), and the REDMI K100 effect table (`q0()`) is borrowed when the real config has
     * none. Carousel placement is handled by the `u2.P#y(Q)` order funnel plus the config
     * `M()` fronting; capture sizing can be tuned with
     * [KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE]; the role-23→20 fallback is gated on its own
     * key. Turn off to restore the stock / guarded behaviour.
     */
    const val KEY_CAMERA_MASTERLIVE_ENABLE = "camera_masterlive_enable"

    private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
    private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
    private const val KEY_LOG_SESSION = "debug_log_session"
    private const val MAX_DEBUG_LOG_LENGTH = 40_000

    private lateinit var remotePrefs: SharedPreferences
    private var localSourcePrefs: SharedPreferences? = null
    @Volatile
    private var localCachePrefs: SharedPreferences? = null
    private var isLocalOnly = false
    private val serializedWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "HyperTweak-Prefs").apply { isDaemon = true } }

    /**
     * Bumped on every full reset ([clearAllSettings]). Hooked processes keep a local cache
     * (`hypertweak_cache.xml` in each app's data dir) that the module cannot reach; the
     * epoch stored in the authoritative remote prefs invalidates those caches so a reset
     * actually takes effect instead of serving stale values.
     */
    private const val KEY_PREFS_EPOCH = "prefs_epoch"
    private const val INITIAL_EPOCH = 0L

    /**
     * Wipes every setting in every storage location the module uses:
     * - the module's own `hypertweak_settings` / `hypertweak_cache` files (deleted on
     *   uninstall anyway);
     * - the LSPosed daemon's remote copy (`modules_config.db`), which survives uninstall
     *   and is why settings come back after a reinstall.
     * The epoch bump makes hooked processes fall back to defaults instead of their local
     * caches. After this call every setting is at its default; hook processes pick the new
     * state up on their next read without a reboot.
     */
    fun clearAllSettings() {
        synchronized(logLock) {
            if (!isInitialized) return
            memoClear()
            val epoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH) + 1
            runCatching {
                remotePrefs.edit().clear().putLong(KEY_PREFS_EPOCH, epoch).commit()
            }
            runCatching { localSourcePrefs?.edit(commit = true) { clear() } }
            val cache = getLocalCache()
            runCatching { cache?.edit(commit = true) { clear().putLong(KEY_PREFS_EPOCH, epoch) } }
        }
    }

    /** True when the remote copy still holds settings (i.e. a previous install left config behind). */
    fun hasRemoteConfig(): Boolean {
        if (!isInitialized) return false
        return runCatching { remotePrefs.all.keys.any { it != KEY_PREFS_EPOCH } }.getOrDefault(false)
    }

    /**
     * False when the remote prefs were wiped after this process last cached values, so the
     * cache must not be trusted and reads fall back to defaults.
     */
    private fun cacheEpochMatchesRemote(): Boolean {
        val cache = getLocalCache() ?: return true
        val remoteEpoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        val cacheEpoch = runCatching { cache.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        return remoteEpoch == cacheEpoch
    }

    /** Serializes debug-log writes so log I/O never shares the object monitor with preference reads. */
    private val logLock = Any()

    /**
     * Short-lived process-local read memo for the getters below. Without it every getter call
     * pays at least one binder transaction to the LSPosed daemon even when the value has not
     * changed, which adds up on per-event and per-frame preference reads (touch dispatch, wallpaper
     * events, log gates, broadcast hooks). Entries expire after [MEMO_TTL_NANOS], so a setting
     * written by the module is picked up on the next read shortly afterwards without turning every
     * hot-path read into a binder round-trip. Puts, resets and full wipes invalidate eagerly.
     */
    private const val MEMO_TTL_NANOS = 100_000_000L // 100 ms

    private class MemoEntry(val value: Any?, val expiresAtNanos: Long)

    private val memo = ConcurrentHashMap<String, MemoEntry>()

    private fun memoGet(key: String): Any? {
        val entry = memo[key] ?: return null
        if (entry.expiresAtNanos <= System.nanoTime()) {
            memo.remove(key)
            return null
        }
        return entry.value
    }

    private fun memoPut(key: String, value: Any?) {
        memo[key] = MemoEntry(value, System.nanoTime() + MEMO_TTL_NANOS)
    }

    private fun memoInvalidate(key: String) {
        memo.remove(key)
    }

    private fun memoClear() {
        memo.clear()
    }

    fun init(prefs: SharedPreferences, useLocalOnly: Boolean = false) {
        memoClear()
        if (useLocalOnly) {
            localSourcePrefs = prefs
            // Only apply local prefs as fallback if remote prefs haven't been set yet
            if (!this::remotePrefs.isInitialized || isLocalOnly) {
                remotePrefs = prefs
                isLocalOnly = true
            }
        } else {
            // Remote prefs always win and can upgrade a local-only instance
            remotePrefs = prefs
            isLocalOnly = false
        }
    }

    fun useLocalBackend() {
        memoClear()
        localSourcePrefs?.let { remotePrefs = it; isLocalOnly = true }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        if (!isInitialized) return
        val local = localSourcePrefs
        runCatching { local?.edit { block() } }
        if (isLocalOnly || local === remotePrefs) return
        serializedWriter.execute {
            // Synchronous commit: libxposed's RemotePreferences Editor.apply() is asynchronous on
            // its own executor, so a process killed right after a setting change (e.g. the in-page
            // "Restart SystemUI" action or the generic restart dialog) could die before the daemon
            // write lands — and Preferences.flush() only drains this queue, not libxposed's. A
            // blocking commit makes flush() honest: once this queue is drained, every setting that
            // was written is already visible to the hooked processes that read the daemon copy.
            runCatching {
                val editor = remotePrefs.edit()
                block(editor)
                val committed = editor.commit()
                if (!committed) {
                    DebugLog.w("Preferences", "remote pref commit rejected by daemon (settings not synced)")
                }
            }.onFailure { t ->
                DebugLog.w("Preferences", "remote pref write failed; retrying on next write", t)
            }
        }
    }

    /**
     * Blocks until every queued remote write has been applied. The remote copy lives in the
     * LSPosed daemon and is written asynchronously through [serializedWriter], so a hooked
     * process restarted right after a setting change (e.g. the Restart SystemUI action on a
     * tuning page) can start before the daemon has the new values and read stale ones. Call
     * this before restarting hooked processes that must observe the latest settings.
     */
    fun flush() {
        if (!isInitialized || isLocalOnly) return
        val latch = CountDownLatch(1)
        serializedWriter.execute { latch.countDown() }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    fun initLocalCache(context: Context) {
        localCachePrefs = context.getSharedPreferences("hypertweak_cache", Context.MODE_PRIVATE)
    }

    private fun getLocalCache(): SharedPreferences? {
        return localCachePrefs
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (!isInitialized) return default
        memoGet(key)?.let { return it as Boolean }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getBoolean(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getBoolean(key, !v) != v)) {
                    cache.edit { putBoolean(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getBoolean(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getBoolean(key, default) ?: getLocalCache()?.getBoolean(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (!isInitialized) return default
        memoGet(key)?.let { return it as Int }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getInt(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getInt(key, v - 1) != v)) {
                    cache.edit { putInt(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getInt(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getInt(key, default) ?: getLocalCache()?.getInt(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun getFloat(key: String, default: Float = 1f): Float {
        if (!isInitialized) return default
        memoGet(key)?.let { return it as Float }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getFloat(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getFloat(key, v - 1f) != v)) {
                    cache.edit { putFloat(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getFloat(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getFloat(key, default) ?: getLocalCache()?.getFloat(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun putBoolean(key: String, value: Boolean) {
        memoInvalidate(key)
        write { putBoolean(key, value) }
    }

    fun putInt(key: String, value: Int) {
        memoInvalidate(key)
        write { putInt(key, value) }
    }

    fun putFloat(key: String, value: Float) {
        memoInvalidate(key)
        write { putFloat(key, value) }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (!isInitialized) return default
        memoGet(key)?.let { return ((it as? Set<*>) ?: default).map { element -> element as String }.toSet() }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getStringSet(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getStringSet(key, emptySet()) != v)) {
                    cache.edit { putStringSet(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getStringSet(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getStringSet(key, default) ?: getLocalCache()?.getStringSet(key, default) ?: default
        }
        // Defensive copy: SharedPreferences returns a fresh set per call, and callers may hold on
        // to the result, so the memoized instance must never be handed out directly.
        val copy = value.toSet()
        memoPut(key, copy)
        return copy
    }

    fun putStringSet(key: String, value: Set<String>) {
        memoInvalidate(key)
        write { putStringSet(key, value) }
    }

    fun getString(key: String, default: String = ""): String {
        if (!isInitialized) return default
        memoGet(key)?.let { return it as String }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getString(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getString(key, "") != v)) {
                    cache.edit { putString(key, v) }
                }
                v
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.getString(key, default) ?: default
            } else {
                default
            }
        } catch (_: Throwable) {
            localSourcePrefs?.getString(key, default) ?: getLocalCache()?.getString(key, default) ?: default
        }
        memoPut(key, value)
        return value
    }

    fun putString(key: String, value: String) {
        memoInvalidate(key)
        write { putString(key, value) }
    }

    /**
     * Writes a string and waits for the remote preferences commit to finish. This is used for
     * state that is immediately followed by restarting a hooked process, where an asynchronous
     * daemon write can otherwise race process startup.
     */
    fun putStringSynchronous(key: String, value: String) {
        memoInvalidate(key)
        if (!isInitialized) return
        val local = localSourcePrefs
        runCatching { local?.edit(commit = true) { putString(key, value) } }
        if (isLocalOnly || local === remotePrefs) return
        val task = serializedWriter.submit {
            runCatching {
                remotePrefs.edit().putString(key, value).commit()
            }.onFailure { t ->
                DebugLog.w("Preferences", "synchronous remote pref write failed", t)
            }
        }
        runCatching { task.get(3, TimeUnit.SECONDS) }
    }

    fun appendDebugLog(processTag: String, line: String) {
        synchronized(logLock) {
            appendDebugLogs(processTag, listOf(line))
        }
    }

    fun appendDebugLogs(processTag: String, lines: List<String>) {
        synchronized(logLock) {
            if (!isInitialized) return
            if (!getBoolean(KEY_RECORD_LOGS, true)) return
            if (lines.isEmpty()) return
            val key = debugLogKeyFor(processTag)
            val local = localSourcePrefs
            val old = runCatching { remotePrefs.getString(key, "") }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: runCatching { local?.getString(key, "") }.getOrNull().orEmpty()
            val appended = lines.joinToString("\n")
            var next = if (old.isEmpty()) appended else "$old\n$appended"
            if (next.length > MAX_DEBUG_LOG_LENGTH) {
                next = next.takeLast(MAX_DEBUG_LOG_LENGTH)
                val firstNewLine = next.indexOf('\n')
                if (firstNewLine >= 0 && firstNewLine < next.lastIndex) {
                    next = next.substring(firstNewLine + 1)
                }
            }
            runCatching { local?.edit(commit = true) { putString(key, next) } }
            if (!isLocalOnly) runCatching { remotePrefs.edit(commit = true) { putString(key, next) } }
        }
    }

    fun getDebugLog(): String {
        if (!isInitialized) return ""
        val blocks = runCatching { remotePrefs.all.entries }.getOrElse { emptySet() }
            .filter { it.key.startsWith(KEY_DEBUG_LOG_PREFIX) || it.key == LEGACY_KEY_DEBUG_LOG }
            .mapNotNull { (it.value as? String)?.takeIf(String::isNotEmpty) }
        if (blocks.isNotEmpty()) return blocks.joinToString("\n")
        return runCatching {
            localSourcePrefs?.all.orEmpty().entries
                .filter { it.key.startsWith(KEY_DEBUG_LOG_PREFIX) || it.key == LEGACY_KEY_DEBUG_LOG }
                .mapNotNull { (it.value as? String)?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
        }.getOrDefault("")
    }

    fun clearDebugLog() {
        synchronized(logLock) {
            if (!isInitialized) return
            val keys = (runCatching { remotePrefs.all.keys }.getOrElse { emptySet() } +
                runCatching { localSourcePrefs?.all.orEmpty().keys }.getOrElse { emptySet() })
                .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            if (keys.isEmpty()) return
            runCatching { localSourcePrefs?.edit(commit = true) { keys.forEach(::remove) } }
            runCatching { remotePrefs.edit(commit = true) { keys.forEach(::remove) } }
        }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    fun rotateLogSessionIfNeeded(token: String) {
        synchronized(logLock) {
            if (!isInitialized) return
            val currentToken = runCatching { remotePrefs.getString(KEY_LOG_SESSION, null) }.getOrNull()
                ?: runCatching { localSourcePrefs?.getString(KEY_LOG_SESSION, null) }.getOrNull()
            if (currentToken == token) return
            val keys = (runCatching { remotePrefs.all.keys }.getOrElse { emptySet() } +
                runCatching { localSourcePrefs?.all.orEmpty().keys }.getOrElse { emptySet() })
                .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            localSourcePrefs?.edit(commit = true) {
                keys.forEach(::remove)
                putString(KEY_LOG_SESSION, token)
            }
            if (!isLocalOnly) runCatching { remotePrefs.edit(commit = true) {
                keys.forEach(::remove)
                putString(KEY_LOG_SESSION, token)
            } }
        }
    }

    private fun debugLogKeyFor(processTag: String): String {
        val sanitized = processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        return "$KEY_DEBUG_LOG_PREFIX$sanitized"
    }
}
