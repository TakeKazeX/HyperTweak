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
    const val KEY_LANGUAGE = "app_language"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_APP_SHORTCUTS = "app_shortcuts"
    const val KEY_APP_SHORTCUTS_ORDER = "app_shortcuts_order"
    const val KEY_DISABLE_SPATIAL_AUDIO = "disable_spatial_audio"
    const val KEY_FORCE_ADAPTIVE_ANC = "force_adaptive_anc"
    const val KEY_FCM_LIVE_ENABLED = "fcm_live_enabled"
    const val KEY_IMMEDIATE_MONET_REFRESH = "immediate_monet_refresh"
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
    // FLUX_DECOR_STACKED_SIGNAL_PLAN.md). The former SVG-render keys (styles, paddings, alphas,
    // type text, show-single/stacked/roaming) were deleted with the renderer.
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
     * Camera app (com.android.camera) flagship impersonation unlock; see
     * `CameraImpersonationHooker`. Keys are read live (100 ms memo) inside the hooks; only the
     * first enable of KEY_CAMERA_IMPERSONATE needs a camera app restart (to install the hooks).
     * While impersonating, the on-picture watermark is ALWAYS re-forced back to this device's own
     * brand + model (unconditional), so impersonation can never change the watermark model.
     */
    const val KEY_CAMERA_IMPERSONATE = "camera_impersonate"
    const val KEY_CAMERA_IMPERSONATE_THEME_LCC = "camera_impersonate_theme_lcc"

    /**
     * While impersonating a flagship, keep this device's own focal-length line-up (焦段) by
     * delegating the config's focal getters (B1/q0/e1/A1/C1/v1/x1/y0/h1) to the real device
     * config instance. Default on (true).
     */
    const val KEY_CAMERA_KEEP_FOCAL = "camera_keep_focal"

    /**
     * While impersonating a flagship, ALSO delegate the *imaging identity* getters
     * (O1/D/q1/r1/o0/S6/M/K2) back to the real device config instance, so every mode whose
     * output is colour-sensitive (Leica Classic + tele on LCC/RAW, gallery re-processing)
     * feeds the REAL sensor/lens identity to MIVI/HAL CCM·WB selection. Capability booleans
     * (`instanceof`-based gates) stay flagship; only the colour/imaging identity returns to
     * native. This is the fix for "徕卡经典 + 长焦 → 相册后期处理变紫" (see
     * `RESEARCH_LEICA_CLASSIC_PURPLE.md`). Default on (true).
     */
    const val KEY_CAMERA_KEEP_IMAGING = "camera_keep_imaging"

    /**
     * Keep the hardware-dependent flagship modes CLOSED while impersonating a flagship on a
     * device that lacks the physical hardware: 实况运镜 / 街拍 / 装备街拍 / 传奇人像
     * (`C1178#y4()`/`C1178#a3()`/`Je.c#M()`/`LegendaryEnter.support()`). Without the tele
     * periscope, SMVR-HSR and the flagship camera ids (8/13/3001) these modes open a camera
     * that does not exist and freeze/crash (见 `RESEARCH_LIVE_MOTION.md` /
     * `RESEARCH_STREET_MODE.md`). On a real flagship (real nezha) the delegated values are
     * the flagship values, so this is a no-op there. Default on (true).
     */
    const val KEY_CAMERA_GUARD_MODES = "camera_guard_modes"

    /**
     * Root fix for the flagship camera-id scheme: delegate `C1178#b6()` back to the real
     * device config, so `C3550e.e()/f()` stop reporting the flagship main cameras
     * (8/13/3001) and the mode-opening path falls back to the real camera. This is the switch
     * that makes 街拍 usable on the real camera *if* `KEY_CAMERA_GUARD_MODES` is off (modes
     * stay visible) — at the cost of closing the `b6`-gated flagship extras (8K video etc.).
     * Default off (false); keep on-guard modes unless you specifically want that tradeoff.
     */
    const val KEY_CAMERA_GUARD_CAMERA_ID = "camera_guard_camera_id"

    /**
     * Impersonation target config class. `"k100promax"` (default) = REDMI K100 Pro Max / POCO
     * F9 Ultra (jadx C1151): sensor axis `q1={17}/O1="3"/D=6579300/r1=6` — byte-identical to
     * myron's own `com.mi.device.Myron` (C1209), so MIVI/HAL CCM·WB selection is correct
     * (徕卡经典 no longer turns purple after gallery re-processing); `y4()=true` with a REDMI
     * MasterLive effect table (ends 15x via `Standalone`, no 17U tele/12.9x crash path); REDMI
     * watermark strings. `"nezha"` = legacy 17 Ultra unlock (old behaviour; on non-flagships it
     * opens hardware that does not exist → MasterLive/Street freeze and purple).
     */
    const val KEY_CAMERA_IMPERSONATE_TARGET = "camera_impersonate_target"

    /**
     * Force the street-support gate (`a3()`) true on the impersonated config so 街拍 (Street
     * 225) becomes visible AND the quick-launch STREET route stays consistent with a working
     * mode (it opens the HAL role-0 main camera). Only meaningful with the K100 Pro Max target
     * — no REDMI config ships `a3=true`. Default on.
     */
    const val KEY_CAMERA_STREET_ENABLE = "camera_street_enable"

    /**
     * Restore the Leica photography-style (摄影风格 cv_type 徕卡经典 ↔ 徕卡生动) switcher while
     * impersonating. The 摄影风格 component and the top-bar style entries gate on the config's
     * `F3()` (Leica-level device flag; `X2()` for the specific-capture path): `true` on the
     * CommonFlagship (Nezha) branch, `false` on the REDMI C1199 branch that both C1151 (K100
     * Pro Max) and myron's own C1209 inherit — so the K100 Pro Max impersonation drops the
     * Leica style switcher that the legacy 17-Ultra impersonation had. Forcing `F3()`/`X2()`
     * to true on the impersonated config brings it back. Does NOT reopen Legendary (gated on
     * `W0()=instanceof C1178`) nor the 231 LCC-RAW stream (`M()` stays keep-imaging-delegated),
     * so no purple/RAW regression. Side effect: the shutter-sound list gains the four Leica
     * entries (`f2.c.b()` adds them when `F3()` is true). Only meaningful with the K100 Pro Max
     * target. Default on.
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
     * MasterLive (实况运镜) op-mode safety net. On Qualcomm the MasterLive session would normally
     * run CONFIRMED_HIGH_SPEED (op-mode 1); if the HAL does not produce frames in that mode the
     * capture never completes and the mode appears frozen. When this switch is on, the MasterLive
     * branch of the module device's getOperatingMode is forced to ALGO_UP_SAT (36866), a plain
     * session that always produces frames — at the cost of the high-speed motion semantics (the
     * K100 Pro Max effect table has no 120fps type anyway). Default off; enable only if on-device
     * logs show op-mode 1 does not produce frames. Requires a camera restart after changing.
     */
    const val KEY_CAMERA_MASTERLIVE_OPMODE_SAFE = "camera_masterlive_opmode_safe"

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
     * `return this instanceof C1148` (flagship-only marker) that neither this device's own
     * `com.mi.device.Myron` config nor the K100 Pro Max impersonation target
     * `com.mi.device.Songyuan` overrides, so 超高 is hidden on both the native and the
     * impersonated path. The hook forces that one gate (declared once on the config base and
     * inherited by both) to this preference's live value: on = 超高 offered and the quality
     * clamp `j#t()` caps at `F1.g3.SUPER` (JPEG quality 100); off = forced false, exactly the
     * stock behaviour here, which also re-clamps a stale stored 超高 selection back to 高.
     * Plain JPEG-quality values with no HAL dependency. Read live (100 ms memo); default ON.
     */
    const val KEY_CAMERA_ULTRA_HD_QUALITY = "camera_ultra_hd_quality"

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
            runCatching { remotePrefs.edit { block() } }
                .onFailure { DebugLog.w("Preferences", "remote pref write failed; retrying on next write", it) }
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
        memoGet(key)?.let { return ((it as? Set<*>) ?: default).toSet() as Set<String> }
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
