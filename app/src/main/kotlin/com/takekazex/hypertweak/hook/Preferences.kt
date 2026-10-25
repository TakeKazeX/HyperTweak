// The KTX SharedPreferences.edit helper returns Unit, but every call in this file inspects the
// boolean result of commit() to detect a rejected write (log persistence and the Dock rollback
// path). Swallowing that signal would turn a storage failure into a silent success, so the raw
// editor stays. Suppressed at file scope because the pattern repeats across the log and Dock writers.
@file:Suppress("UseKtx")

package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.CameraSettingsKeys

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.BackGestureKeys

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsLedger
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsSnapshot
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.settings.contract.PowerSaveOverrideSelection
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.SettingsDiagnostics
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Preferences {
    private val liveBackSettings = BackGestureSettingsLedger()
    fun backGestureEnabled(): Boolean = !com.takekazex.hypertweak.util.UpstreamBackGesture.installed &&
        backGestureBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENABLED, false)
    fun backGestureBoolean(key: String, defaultValue: Boolean): Boolean =
        if (RuntimeSettingsStore.owns(key)) getBoolean(key, defaultValue) else liveBackSettings.latest()?.value(key) ?: runCatching {
            if (isInitialized) remotePrefs.getBoolean(key, defaultValue) else defaultValue
        }.getOrDefault(defaultValue)
    internal fun acceptBackGestureSettings(snapshot: BackGestureSettingsSnapshot): Boolean =
        snapshot != liveBackSettings.latest() && liveBackSettings.offer(snapshot)
    internal fun saveBackGestureSettings(): Array<Any>? = liveBackSettings.latest()?.save()
    internal fun restoreBackGestureSettings(state: Any?) {
        BackGestureSettingsSnapshot.restore(state)?.let(liveBackSettings::offer)
    }

    /** The vendored runtime uses the same authoritative source and listeners as other hooks. */
    fun backGesturePreferences(): SharedPreferences = RuntimeSettingsPreferences
    internal fun runtimePreferenceSource(): SharedPreferences? = if (isInitialized) remotePrefs else null
    private const val CACHE_NAME = "hypertweak_cache"
    // Key names
    /**
     * Stops the CN Download Manager's optional Xunlei log files from creating the shared-storage
     * `.xlDownload` directory. The hook is scoped to `com.android.providers.downloads`, is off by
     * default, and is read when that process is restarted.
     */
    /** Shows the original URL instead of a task description in Download Manager details. */
    /** Hides the Download Manager's Xunlei action-bar entry. */
    /** Adds an independent new-download action-bar button to the Download Manager. */
    /**
     * Unlocks Nearby Share (Quick Share) on CN Google Play services by overriding the
     * `sharing_supports_latchsky` phenotype flag to true in GMS's CE `phenotype.db`.
     * GMS must be in the Xposed scope (requested when the switch is turned on).
     */
    /**
     * Shows the full-screen live-translate (屏幕实时翻译) button inside Circle to Search
     * (即圈即搜) on the Google app (`com.google.android.googlequicksearchbox`).
     * `GoogleAppLiveTranslateHooker` opens the three gates that keep the button hidden (the
     * `com.google.android.apps.search.lens.user/45785436` master flag, the
     * `CONTEXTUAL_SEARCH_LIVE_TRANSLATE` system feature, and the EXTRA_MEDIA_PROJECTION display
     * predicate). The Google app is a declared required Xposed scope (see `scope.list`); the
     * switch flips the preference and queues the Google app in the Home restart dialog. The
     * runtime may prepare its bridge while disabled, but callbacks preserve the native result.
     */
    /**
     * Shows "Ask about this screen" (针对屏幕内容提问) in the Circle to Search (即圈即搜)
     * Lensient searchbox on the Google app (`com.google.android.googlequicksearchbox`).
     * `GoogleAppAskAboutScreenHooker` resolves the native Lensient bridge from the `vidcip` and
     * AIM hint markers, validates Google's OMNI entrypoint, and opens the capability plus the
     * constructor-time AIM booleans. The Google app is a declared required Xposed scope (see
     * `scope.list`); the switch flips the preference and queues the Google app in the Home restart
     * dialog. The runtime may prepare the native bridge while disabled, but every callback
     * preserves Google's original behavior until this switch is enabled.
     */
    /** Legacy aggregate switch kept as a fallback for upgrades from the original single toggle. */
    /** Independently hides the fingerprint icon and halo on the always-on display (AOD). */
    /** Independently hides the fingerprint icon and halo on the interactive lockscreen. */
    /** Copies each screenshot into the clipboard through the host's own write path. */
    private val screenshotSettings = ScreenshotSettingsLedger()
    fun screenshotAutoFrameEnabled(): Boolean = if (RuntimeSettingsStore.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME)) getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, false) else screenshotSettings.latest()?.enabled
        ?: getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, false)
    fun screenshotEditorMode(): Int = if (RuntimeSettingsStore.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE)) getInt(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE, 0).coerceIn(0, 1) else screenshotSettings.latest()?.editorMode
        ?: getInt(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE, 0).let { if (it == 1) 1 else 0 }
    fun screenshotCopyToClipboard(): Boolean = if (RuntimeSettingsStore.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD)) getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, false) else screenshotSettings.latest()?.copyToClipboard
        ?: getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, false)
    fun screenshotPicturesDirectory(): Boolean = if (RuntimeSettingsStore.owns(ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY)) getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY, false) else screenshotSettings.latest()?.picturesDirectory
        ?: getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY, false)
    internal fun acceptScreenshotSettings(snapshot: ScreenshotSettingsSnapshot): Boolean = screenshotSettings.accept(snapshot)
    internal fun saveScreenshotSettings(): Array<Any>? = screenshotSettings.save()
    internal fun restoreScreenshotSettings(state: Any?) = screenshotSettings.restore(state)

    /**
     * Filters the text shown at the bottom of the interactive lockscreen. The hook keeps the
     * unlock gesture and notification cards intact; only the bottom indication text and the
     * compact DND/notification-count labels are filtered.
     */
    /** Adds seconds to the large clock shown when the notification shade is expanded. */
    /** OS4 new-control-center notification header customization. */
    /** Disables Monet-derived colors for framework-generated notification text. */
    /** Restores framework notification typefaces after MIUI's notification styling runs. */
    /**
     * Lockscreen notification fingerprint avoidance (锁屏通知指纹避让), OS4 SystemUI. Read by
     * `KeyguardFingerprintAvoidHooker` at hook-install time; requires a SystemUI restart.
     * 0 = follow the system (avoid when fingerprint unlock is enabled and templates are enrolled),
     * 1 = never avoid the in-display fingerprint icon, 2 = always avoid it.
     */
    /**
     * Appends live charging telemetry (wattage / voltage / current / temperature) to the lock
     * screen's bottom charging indication line, replacing the plain "充电中xx% / 已充满电" text
     * with a configurable one- or two-row layout. Read by
     * `LockscreenChargingDetailHooker`; requires a SystemUI restart (restart-scope key).
     */
    /** Two rows by default; use a new key so removed legacy false values do not opt users out. */
    /**
     * Live-read by the hooker each render (sub-options of the charging detail feature): which
     * telemetry fields to show (bitmask of [LockscreenChargingDetailHooker]'s FIELD_* bits,
     * all four on by default), and how often the values refresh (ms). These are read on every
     * indication render, so they take effect without a SystemUI restart once the main switch is on.
     */
    /**
     * Hides the gesture bar only while the launcher's Home surface owns the primary display
     * (桌面隐藏小白条). Read live by `HideBottomBarHooker` in SystemUI, which re-reads the
     * top task on the host events that change what the bottom of the screen belongs to.
     */
    /**
     * Keeps the gesture bar attached to the app while the swipe-up recents transition plays
     * (小白条跟手上滑). HyperOS's enabled `com.android.systemui.gesture.line.overlay` sets
     * `config_attachNavBarToAppDuringTransition` to false, so the navigation bar stays a separate
     * layer and does not follow the finger. Read live by `GestureBarTransitionHooker` in
     * system_server on every configuration change; the hooker itself installs at boot.
     */
    /**
     * Long-press power button action (长按电源键操作). `PowerButtonCtsHooker` intercepts
     * `PhoneWindowManager$PowerKeyRule.onLongPress(SingleKeyGestureEvent)` (the single entry
     * point shared by MIUI's assistant route and the `powerLongPress` fallback) and
     * `PhoneWindowManager.powerLongPress` in system_server, then dispatches the selected action:
     * [POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH] starts the contextual-search service through
     * `ContextualSearchSystemHooker`, [POWER_BUTTON_ACTION_GOOGLE_LENS] opens the Google App's
     * Google Lens (Google 智能镜头) camera surface through its exported `google://lens` entry point
     * (`GoogleLensLauncher`), and [POWER_BUTTON_ACTION_DEFAULT_ASSISTANT] launches the user's
     * default digital assistant (Google Assistant / Gemini / 小爱) through the platform assist
     * pipeline. [POWER_BUTTON_ACTION_DISABLED] leaves the system's own long-press action
     * untouched. The action is read live at dispatch time, so switching between actions (or off)
     * takes effect without a reboot once the system-server hooks are installed; turning the
     * feature on from disabled still needs a reboot for those hooks (and the CTS bridge) to
     * install. The power-long-press haptic has no preference: it fires whenever a custom action
     * is bound.
     */
    /**
     * Google Lens (Google 智能镜头), the camera/image search surface. Unlike
     * [POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH] this is not the contextual-search overlay: it needs no
     * platform bridge and no `ContextualSearchSystemHooker` install, so it works while the
     * Circle to Search feature is off and has no Google App process requirements of its own.
     */
    /** Legacy single-switch Circle to Search enable; superseded by [KEY_POWER_BUTTON_ACTION]. */
    /** Restores Settings' native Google services home-page entry on domestic builds. */
    /** Makes Settings follow its own international-build UI branches in this process. */
    /**
     * Shows the 小米澎湃AI page's 端侧大模型 row. The ROM hides it because the on-device model
     * capability answer for this model is negative, while the activity it opens is functional;
     * see [com.takekazex.hypertweak.hook.rules.settings.HyperOsAiEntriesHooker].
     */
    /**
     * Shows the 小米澎湃AI page's Gemini row next to the unchanged native 超级小爱 card.
     */
    // Verified mirrors of device-persistent vendor properties; never replayed at boot or upgrade.
    /** Forces the Phone service to keep video ringback disabled and exposes its native switch. */
    /** Replaces the LBE clipboard-reading overlay with a standard Android Toast. */
    /** Routes Xiaomi AI Engine Copy Direct web actions through the current system browser. */
    /** Disables MiTrustService's MRM risk-monitoring initialization gate. */
    /** Makes GuardProvider's verified su/root environment predicates report a clean device. */
    /** Skips Security Center's four verified local File.exists() su probes. */
    /** Disables the independent SafePay automatic app-scan preference. */
    /** Hides the independent root scan item from Security Center's scan configuration. */
    /** Disables Security Center's USB-install permission interception. */
    /** Skips network verification only when enabling USB installation (is_input=false). */
    /** Ignores carrier offline metadata in Network Assistant's business flows. */
    /** Ignores carrier offline metadata in Contacts' mobile business hall. */
    /** Stops GuardProvider from serializing/uploading its non-system installed-app list. */
    /** Prevents MiLink's HPPlay/LeBo integration from creating optional external files. */
    /**
     * Lets the Theme Manager apply locally imported (third-party) themes by disabling MIUI's OMA
     * DRM rights verification. Gates both the app-side rights check that blocks the apply and the
     * system_server re-validation that would otherwise restore the default theme afterwards.
     */
    // Control Center corner-radius overrides. Each group keeps its own dp value (Float);
    // 0 means "no override" (follow the system design), any positive value is applied as the
    // GradientDrawable/outline corner radius of the corresponding control center element.
    // The hooker converts dp → px at hook time (plugins expect px for these APIs).
    /** Native tile-state header dwell after the show animation; milliseconds, 0..5000. */
    /** Comma-separated top-card specs, e.g. `cell,wifi`. Empty = follow the system order. */
    /**
     * Comma-separated main-panel section keys (`qscards,media,brightness,volume,devicecenter,qslist`),
     * written by the editor drag hook and re-applied by [KEY_CC_EDIT_ENABLED]'s ordering hook.
     */
    /**
     * Master switch for the control-center editor cards feature: shows the fixed main-panel
     * contents (big cards, media player, brightness/volume sliders, device center) inside
     * 编辑与排序 and makes them drag-reorderable like the quick actions.
     */
    /**
     * Master switch for control-center element sizes ([ControlCenterCardResizeHooker]). The
     * hooks are installed at plugin load, so enabling needs a SystemUI restart; every size
     * below is re-read live on each bind.
     */
    /**
     * Per-card sizes as comma-separated `spec=CxR` entries (`wifi=1x1`), C = span columns,
     * R = row units (1 row = `qs_card_item_height`). An absent spec or a 0 dimension means
     * follow the system.
     */
    /** Fixed-content sizes as a single `CxR` token; empty = follow the system. */
    /**
     * Comma-separated quick-switch tile specs that render with the big-card view (2 columns
     * × 1 row) instead of the small tile view.
     */
    /** Shows third-party apps in HyperOS's per-app force-dark list without enabling them automatically. */
    // AOSP restore.
    /** Legacy combined switch: absent independent keys inherit it on upgrade. */
    /**
     * Hands package installs back to the AOSP installer. Relaxes MIUI install verification while
     * the installer-selection methods run; see `AospPackageInstallerHooker`.
     */
    /** Blocks MIUI's global-actions plugin so SystemUI falls back to the AOSP power menu. */
    /** Blocks MIUI's volume-dialog plugin so SystemUI falls back to the AOSP volume panel. */
    /**
     * When the AOSP volume panel is active, replaces its generic MSDL "continuous" slider haptic
     * (the strong sustained buzz on every volume change) with MIUI's own per-scene adapted waveform
     * (`miui.util.HapticFeedbackUtil`), matching the feel of the stock MIUI volume panel.
     * Only meaningful while [KEY_AOSP_VOLUME_PANEL] is on. Defaults off.
     */
    /**
     * Number of volume-index steps from minimum to maximum for hardware volume-key adjustments.
     * Applied live through the remote preference listener in system_server and SystemUI.
     * The next key adjustment uses the new count or [KEY_VOLUME_KEY_STEP_SCOPE].
     */
    /** Re-derives keyguard trust state from TrustManagerService when SystemUI's cache goes stale. */
    /** Lets AOSP's clipboard overlay editor appear for real copies, not just CTS. */
    /** Security Center entry points into Settings' AOSP app screens. */
    /** Expands Security Center's bubble-app list while keeping per-app and edge-bubble gates. */
    /** Legacy aggregate low-battery warning switch kept for preference migration. */
    /**
     * Security Center low-battery warning mode: 0 follows stock, 1 hides only the dialog, and 2
     * suppresses both the dialog and the sound.
     */
    /**
     * Security Center permission-warning intercept mode (危险权限确认倒计时). Read by
     * `WarningCountdownHooker` at hook-install time; requires a Security Center restart.
     * 0 follows the system, 1 skips the countdown (wait time 0, page still shown), and 2
     * directly executes the native Allow path after the page is created. The hard-excluded
     * `miui_close_optimization` permission keeps the native page, countdown and manual Allow
     * in every mode.
     */
    /** Exposes Security Center's screen-use and detailed power-ranking data path. */
    /** Keeps Security Center's app-ranking card when the stock percentage threshold hides it. */
    /** Makes Security Center expose its device-specific Wild/Berserk performance label. */
    /** Keeps and extends the native battery-protection information rows. */
    /** Independently exposes Security Center's native privacy-call preference. */
    /** Selects a native anti-peeping implementation, or leaves Security Center unchanged. */
    /** Independently exposes native front-camera assistant and beauty preferences. */
    /** Independently exposes native call privacy-camera preferences. */
    /** Restores AOSP's full-screen IME navigation bar. */
    /** Input methods the AOSP IME hooks apply to; also drives the dynamic scope request. */
    /** Lists every enabled input method in MIUI's keyboard switcher. Unverified off-device. */
    /**
     * Forces the AOSP bar on MIUI-customized keyboards (搜狗小米版 / 百度小米版 / 讯飞小米版 etc.)
     * whose own 全面屏优化 bottom view would otherwise win, instead of leaving them alone.
     */
    /**
     * How the keyboard content is positioned relative to the AOSP bar: 0 raises the content so it
     * ends exactly at the bar top (AOSP style), 1 leaves the keyboard's own bottom handling alone
     * (MIUI style). See [AospImeConfig.RAISE_STYLE_AOSP].
     */
    /**
     * Hides the source-app icon overlay on the media cards (封面隐藏来源应用图标): the 24dp
     * `CachingIconView` on the top-left corner of the album cover in both the notification-shade
     * card (`MiuiMediaViewControllerImpl`) and the island card
     * (`MiuiIslandMediaViewBinderImpl`). Read by `MediaCardHideAppIconHooker` at hook-install
     * time; requires a SystemUI restart.
     */
    /**
     * Hides the device-switch button on the media cards (隐藏设备切换按钮): the top-right
     * `media_seamless` on the notification-shade card (`MiuiMediaViewControllerImpl.setSeamless`)
     * and the island card (`MiuiIslandMediaViewBinderImpl.setSeamless`), plus the plugin main
     * card's `device_icon` (`MediaPlayerDeviceIconHooker`, plugin scope). The SystemUI half is
     * read by `MediaCardHideDeviceSwitchHooker` at hook-install time; the plugin half gates
     * attachment at plugin load and re-reads live per callback. Requires a SystemUI restart.
     */
    /**
     * Lets every notification show on the lockscreen (锁屏通知限制): forces
     * `NotificationSettingsManager.canShowOnKeyguard(Context, pkg, channel)` to return true, so
     * both the package-level and the CN whitelist channel-level gates pass. Read by
     * `LockscreenAllNotificationsHooker` at hook-install time; requires a SystemUI restart.
     */
    /**
     * Keeps notifications on the lockscreen after they were already seen (解锁后保留通知): hooks
     * `NotificationFilterController.forceHideOnKeyguard` to skip the `mHasShownAfterUnlock`
     * branch while keeping the `canShowOnKeyguard()` gate. Read by
     * `LockscreenKeepNotificationsHooker` at hook-install time; requires a SystemUI restart.
     */
    // ─── Notification channel page + fold / silent status-bar filters (experimental 通知) ───

    /**
     * 恢复更多通知设置: repairs the notification settings HyperOS ships as shells, in both processes
     * that own them.
     *
     * - Settings (`NotificationMoreSettingsHooker`): puts the channel details page's 「重要性」
     *   dropdown back and — the part HyperOS never shipped — wires up its write-back listener.
     *   `removeDefaultPrefs()` removes the drop-down, `BaseNotificationSettings.mImportance` is never
     *   assigned, and the ROM's own listener class is dead code (the AOSP variant does not even
     *   contain it), so revealing the row alone leaves it unable to save.
     * - SystemUI (`StatusBarHideSilentHooker`): repairs `ActiveNotificationsStoreBuilder.toModel`'s
     *   `isSilent`, which MIUI's empty `RankingCoordinatorInjectorImpl` stub pins to false, so AOSP's
     *   `importance < IMPORTANCE_DEFAULT(3)` filter starts working.
     *
     * Both are code repairs rather than behavior switches: each restores the stock control the ROM
     * already has (the channel 「重要性」 dropdown, the system 「隐藏状态栏中的静音通知」 switch, which
     * the user reaches through 隐藏功能 → 通知设置) and leaves that control's value alone. Requires a
     * Settings and a SystemUI restart to install.
     */
    /**
     * 通知角标: the same defect as [KEY_NOTIFICATION_MORE_SETTINGS], for the channel page's
     * 「显示角标」 checkbox (key `setting_badge`, field `mBadge`). Read by
     * `NotificationBadgeSettingsHooker` at hook-install time; requires a Settings restart.
     */
    /**
     * 禁止折叠通知: forces `MiuiBaseNotifUtil.shouldSuppressFold()` to true, i.e. the international
     * build's "never fold" behavior. That single ROM gate is consulted by ~35 call sites covering
     * every fold entry point. Read by `NotificationBlockFoldHooker` at hook-install time; requires
     * a SystemUI restart.
     */
    /** Ignore only the native floating-notification whitelist fallback. */
    /**
     * 多任务过渡模糊 (SystemUI): restores the dynamic gaussian-blur transition HyperOS uses when a
     * freeform / split-screen / full-screen window is swapped, and removes the opaque bottom board
     * that otherwise hides it. Some regional or device builds ship the three
     * `persist.sys.background_blur_*` / `persist.sys.multi_task.mask_blur_enable` props disabled, so
     * `MultiTaskingCommonUtils` reports blur off and the mask cross-fade collapses to a hard switch.
     *
     * The hooker forces those props, opens the two `MultiTaskingCommonUtils` gates, blanks the
     * bottom-board colours, and drives the cover-layer / Folme mask from blur=100 down to 0 with the
     * ROM's own hide easings. Read by `FreeformBlurTransitionHooker` at hook-install time; requires a
     * SystemUI restart.
     */
    /** Experimental native one-big/two-small scheduling; disabled by default. */
    /** Independent landscape native full-template layout for occupied summaries. */
    /** Hide Super Island through the plugin temporary-hide path while Control Center appears. */
    /** Native condensed digits for Clock and Recorder island timers. */
    /** Restore contrast for dark monochrome icons from promoted ongoing notifications. */
    /**
     * Removes the Super Island notification whitelist (SystemUI): hooks
     * `NotificationSettingsManager.canShowFocusState` / `canShowFocusStateApp` to treat every
     * package as Super Island-capable. The per-app `<pkg>_focus` preference is respected — a
     * user who explicitly turned an app off keeps it off, while everything without an explicit
     * off is unlocked. Read live by `FocusNotificationWhitelistHooker`.
     */
    /**
     * Removes the media Super Island dropdown whitelist (MIUI SystemUI plugin): hooks
     * `miui.systemui.notification.NotificationSettingsManager.mediaIslandSupportMiniWindow` to
     * allow media mini-windows/dropdowns from every package. The hook is attached when the plugin
     * loads unconditionally and reads this preference live at callback time.
     */
    /**
     * Unlocks the Super Island whitelist signature verification (com.xiaomi.xmsf): hooks
     * `com.xiaomi.xms.auth.AuthSession`'s error dispatch (R8-obfuscated, resolved by signature)
     * to force every auth request to succeed, so arbitrary apps pass xmsf's whitelist signature
     * check. `UnlockFocusAuthHooker` attaches unconditionally and reads the switch live.
     */
    /** Scope restore/remove prompts the user chose to ignore permanently. */
    /**
     * 机型伪装 (device-model spoof) for the Smart Assistant (`com.miui.personalassistant`). When
     * on, `ModelSpoofHooker` re-writes the `phoneModel` / `phoneDevice` fields that the assistant
     * sends to Xiaomi's assistant/theme server (built in `network.util.a#a(Context,String)`), so
     * the server delivers the 澎湃G1-gated "智能测算" MAML suit (which contains the 精准电量
     * widget). The two values default to the Xiaomi 12S Ultra (`2203121C` / `thor`). Read live in
     * the hook callback, so toggling or editing takes effect on the next request without an
     * assistant restart. Requires `com.miui.personalassistant` in the module scope.
     */
    // Status-bar icon tuner, ported from Hyper Helper's icon tuner (see the reverse-engineering
    // workspace, cache/xiaomihelper-2bfd4873a4138764). Each switch is read by a SystemUI hook at
    // hook-install time and requires a SystemUI restart; IconTunerPage keeps its own state and
    // reports that restart through LocalRestartScopeRequest.
    // Hide the cellular icon of the non-default SIM when multiple SIMs are active.
    // Hide the whole mobile signal icon (signal bars and network type) while WiFi is the connected
    // default network. Applies to the native cellular row and, when stacked signal is enabled, to
    // the module-owned stacked slot as well.
    // Cellular type display, ported from Hyper Helper's `CellularTypeIcon` (keys match upstream
    // f32.m/q/r). Forces the single-carrier type text and/or a custom type name.
    // Stacked mobile signal — rebuilt on Flux Decor 2.0.3's view-level model (see
    // docs/FLUX_DECOR_STACKED_SIGNAL_PLAN.md). The SVG keys match Hyper Helper's t32.f-o:
    // single/stacked styles are independent because their source documents have different id
    // matrices; style 2 means the validated imported SVG for that side.
    /** Duo glyph height in dp, clamped by `DuoLayout` to its own range. */
    private const val LEGACY_KEY_ICON_STACKED_SVG_STACKED = "icon_tuner_stacked_icon_svg_stacked"

    /** Compatibility read only: new 0/1 wins; old stacked 3 selects the surviving iOS family. */
    fun iconStackedSignalStyle(): Int {
        if (RuntimeSettingsStore.owns(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE)) {
            val values = RuntimeSettingsStore.values().orEmpty()
            return SignalSvgStyle.resolve(
                values.containsKey(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE),
                values[IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE] as? Int,
                values[LEGACY_KEY_ICON_STACKED_SVG_STACKED] as? Int
            ).storedValue
        }
        val currentPresent = contains(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE)
        return SignalSvgStyle.resolve(
            currentPresent,
            if (currentPresent) runCatching { getInt(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE, 0) }.getOrDefault(0) else null,
            if (!currentPresent) runCatching { getInt(LEGACY_KEY_ICON_STACKED_SVG_STACKED, 0) }.getOrDefault(0) else null
        ).storedValue
    }

    /**
     * 连接 WiFi 时依旧显示蜂窝类型.
     *
     * The cellular type is hidden whenever Wi-Fi carries the data connection — the host's own
     * `MiuiCellularIconVM.mobileTypeSingleVisible` already does that for the native single type
     * (`showMobileDataTypeSingle && dataConnected && !wifiAvailable`), and the module's own type
     * renderers (stacked type slot, two-line control-center carrier block) now follow it. This
     * switch is the opt-out that keeps the type visible on Wi-Fi.
     *
     * [KEY_ICON_STACKED_TYPE_HIDE_WIFI] was the inverse, opt-in switch; it stays only as the
     * migration source for [cellularTypeKeepsOnWifi].
     */
    /**
     * 图标左置 (left-container icon placement), ported from Hyper Helper's `LeftContainer`
     * (OS4_ADAPTATION_PLAN.md T2) and rebuilt for the OS4 home status bar. The master switch
     * installs the left container next to the clock; each per-slot toggle moves that status-bar
     * slot's icon out of the right cluster into the left container (e.g. 勿扰/静音/热点 to the
     * right of the clock). All keys are read at `onHook()`; a SystemUI restart applies changes.
     */
    // Carrier label hiding, ported from Hyper Helper's `HideCarrierLabel` (keys match upstream
    // w22.* / x22.e-f). `KEY_ICON_HIDE_CARRIER_*` cover the status-bar / control-center carrier
    // rows and the HD icon; `KEY_ICON_HIDE_LS_CARRIER_*` cover the lockscreen header.
    // Control-center header carrier block (see `ControlCenterHeaderHooker` and
    // `ControlCenterCarrierBlockHooker`):
    //   KEY_CC_CARRIER_LEFT              — 开关 1: draw the carrier label on the leading edge.
    //   KEY_CC_HIDE_DATE                 — 开关 2: hide the control center date; also the enabling
    //                                      switch of 开关 3/4 (a disabled parent greys them out).
    //   KEY_CC_CARRIER_TWO_LINE          — 开关 3: two rows, [signal] carrier [type/WiFi].
    //   KEY_CC_CARRIER_SHOW_NON_DATA_TYPE— 开关 4: also draw the non-data SIM's type.
    // Shared by carrier rows, stacked signal and Duo: 0 = SIM slot, 1 = data SIM first.
    // Control-center battery percentage style (see `CcBatteryStyleHooker`). Both switches target
    // only the control center's own `MiuiBatteryMeterView` instances (the CC header icons and the
    // fake status bar used while the CC expands); the home status bar, lockscreen and notification
    // shade keep the system `battery_indicator_style`. When Duo replaces a control-center battery,
    // its left-percent mode draws the same host percentage beside the three-in-one glyph.
    //   KEY_CC_BATTERY_PERCENT_OUTSIDE — 开关 1: solid icon with the percentage outside it,
    //                                    instead of the system's in-glyph number.
    //   KEY_CC_BATTERY_PERCENT_LEFT — 开关 2: put that outside percentage on the leading edge
    //                                 (visual left in LTR). The in-glyph number has no left/right
    //                                 side, so this switch also selects the outside style.
    // Force status-bar region sampling (light-bar samples the region behind the status bar so
    // icons/background adapt), ported from Hyper Helper's `RegionSampling` (key matches upstream
    // i32.c). Int-typed: 0 off, 1 on.
    // Limit how many notification icons the status bar keeps (ported from Hyper Helper's u32.a/b;
    // the switch is off by default). The count is Int-typed: minimum 0 (keep no notification
    // icons), default 3, maximum 15 — see `NotificationIconLimit`, which the settings row and
    // `NotificationMaxNumberHooker` both use.
    // Slot show/hide modes (0 = follow system, 1 = show everywhere, 2 = status bar only,
    // 3 = control center only, 4 = hide everywhere). Keys are generated by [slotKey].
    // Ignore the system's own icon hiding.
    // OS4 material style glass tuning (Settings → Display → Visual style, 材质风格). The
    // SystemUI hook intercepts the blur/blend resources behind 清透磨砂 and 柔光玻璃, so the
    // page keeps its own state and reports a SystemUI restart through LocalRestartScopeRequest.
    /** Blend opacity multiplier (0.1..1.0, 1.0 = original); scales every blend color alpha. */
    /**
     * Blend color lightness (0.5..1.5, 1.0 = original). Below 1.0 mixes the blend colors toward
     * black (darker tint), above 1.0 toward white (lighter, more transparent-looking tint).
     */
    /** Blur max-radius multiplier (0.0..2.0, 1.0 = original); scales every blur radius dimen. */
    /**
     * Glass card effect multiplier (0.1..1.0, 1.0 = original). Scales the glass-shader params
     * behind the notification / media card material (darkening, white tint, highlights).
     */
    /**
     * Glass tone multiplier (0.0..2.0, 1.0 = original). Scales the shader's base cast —
     * luminance mix, brightness and blurred-backdrop saturation — that survives at zero
     * opacity and reads as a grey, heavy look; lower is lighter and more see-through.
     */
    // Status-bar slot preference key for [slot]; shared by the hooks and the settings UI.
    fun slotKey(slot: String): String = "icon_tuner_slot_$slot"

    /**
     * The current long-press power action ([POWER_BUTTON_ACTION_*]). Reads
     * [KEY_POWER_BUTTON_ACTION]; when it is unset it migrates the legacy
     * [KEY_POWER_BUTTON_CTS] boolean (on → [POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH]), so
     * existing users keep their Circle to Search binding without touching the setting.
     */
    fun powerButtonAction(): Int {
        val action = getInt(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION, -1)
        if (action == PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT ||
            action == PlatformSettingsKeys.POWER_BUTTON_ACTION_GOOGLE_LENS
        ) {
            return action
        }
        return if (getBoolean(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS, false)) {
            PlatformSettingsKeys.POWER_BUTTON_ACTION_CIRCLE_TO_SEARCH
        } else {
            PlatformSettingsKeys.POWER_BUTTON_ACTION_DISABLED
        }
    }

    /** Persists the long-press power action and drops the superseded legacy boolean. */
    fun setPowerButtonAction(action: Int) {
        memoInvalidate(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION)
        memoInvalidate(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS)
        write {
            putInt(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION, action)
            remove(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS)
        }
    }

    /**
     * Current Leica/Legendary Moment selector. Leica Moment opens its entry on the native
     * config; Legendary Moment uses Madrid only when the camera package contains that config.
     * A missing new key migrates the old Boolean (true → Leica Moment).
     */
    fun cameraLegendaryMomentMode(): String {
        val stored = if (contains(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE)) {
            getString(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE, "")
        } else {
            null
        }
        val legacy = if (stored == null && contains(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT)) {
            getBoolean(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT, false)
        } else {
            null
        }
        return CameraLegendaryMomentMode.resolve(stored, legacy)
    }

    /** Persists the selected name and drops the superseded Boolean. */
    fun setCameraLegendaryMomentMode(mode: String) {
        memoInvalidate(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE)
        memoInvalidate(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT)
        write {
            putString(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE, mode)
            remove(CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT)
        }
    }

    // Media-editor watermark unlock (com.miui.mediaeditor). See
    // `MediaEditorWatermarkHooker`; switches are read live, so only the first enable of
    // KEY_WM_UNLOCK_MASTER needs the editor process restarted (to install the hooks).
    /** Camera app (com.android.camera) watermark unlock; see `CameraWatermarkHooker`. */
    /**
     * Continue past the camera APK's device-configuration mismatch guard; see
     * [com.takekazex.hypertweak.hook.rules.camera.CameraDeviceMismatchHooker]. Default off.
     * Selecting the Nezha/Madrid Legendary profile also bypasses the guard after the target ABI
     * has been verified.
     */
    /**
     * Fake the Leica LCC theme gate (`Je.c#V()`) for the camera app; see
     * `CameraImpersonationHooker`. Unlocks features gated on the LCC theme (e.g. 徕卡一瞬)
     * and keeps the 相机配色 settings entry visible, WITHOUT the flagship config swap and
     * without changing any real device theme property. Read live (100 ms memo).
     */
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
     * from crashing the list). Default off.
     */
    /** Adds the Leica shutter-sound choices without requiring a flagship camera profile. */
    /**
     * MasterLive (实况运镜) role-23 (`Standalone`) -> role-20 (`tele`) fallback on the role
     * adapter (`u6.e`/jadx `p703u6.e` `M()`), so the 15x endpoint of the K100 Pro Max effect
     * table resolves on devices whose tele is only labelled role 20 (Samsung JN5). Harmless
     * when role 23 exists (falls back only when `get(23)==-1`). Default off.
     */
    /**
     * MasterLive (实况运镜) video-size probe — opt-in on every device. On myron the
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
    /**
     * MasterLive (实况运镜) 红毯运镜 injection (default off). The K100 Pro Max effect table
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
    /**
     * MasterLive (实况运镜) full focal line-up (超清实况焦段条, default off). The zoom toggle
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
    /**
     * Custom watermark master switch. When on, the user-typed brand / model overrides
     * (`KEY_CAMERA_WM_CUSTOM_BRAND` / `KEY_CAMERA_WM_CUSTOM_MODEL`) replace the device's own
     * watermark text in `CameraImpersonationHooker` and `CameraWatermarkHooker.hookDeviceLogo`;
     * when off, the values are ignored. Default off.
     */
    /**
     * User-typed custom watermark brand / model, honoured only while `KEY_CAMERA_WM_CUSTOM`
     * is on; blank = fall back to the device's own brand / marketname.
     */
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
     * Plain JPEG-quality values with no HAL dependency. Read live (100 ms memo); default off.
     */
    /** Put 自拍镜像 inside the camera's 自拍设置 page instead of 通用设置. */
    /** Current Leica/Legendary Moment target-profile selector. */
    /** Optional 200MP Pixel item and Cinematic mode entry. */
    /** Legacy single-switch value, migrated by [cameraLegendaryMomentMode]. */
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
    /**
     * 内容凭证 (Content Credentials, C2PA) setting unlock; manual on every device and off by
     * default. The 设置→水印 entry
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
    /**
     * 自适应刷新率Pro (Mimotion PWM dimming) unlock. On HyperOS the 显示与亮度 row
     * `mimotion_pwm_enable` is only shown when the vendor build sets `ro.display.enable_pwm_switch`
     * (read once at `MiuiDisplaySettings` class load into the `static final MIMOTION_PWM_SUPPORTED`),
     * and the system_server `DisplayFeatureManagerService` skips its boot-time dimming-mode apply for
     * the same reason. This switch forces both prop reads to true so the Settings row stays visible
     * and the runtime re-applies the saved mode after reboot; see
     * [rules.settings.AdaptiveRefreshSettingsHooker] / [rules.system.AdaptiveRefreshRuntimeHooker] and
     * docs/FEATURE_DETAIL.md. Whether extra dynamic refresh gears actually appear still depends on the
     * panel/vendor displayfeature HAL (effect 20) support.
     */
    /**
     * Power-save overrides: keep the features HyperOS pins while 省电模式 is active. Security Center
     * (手机管家), from its remote process, writes fixed values into Settings on entering power save
     * (60 Hz into `user_refresh_rate`/`peak_refresh_rate`/`miui_refresh_rate`, `haptic_feedback_enabled`
     * = 0, five sound switches = 0, `pick_up_gesture_wakeup_mode` and
     * `wakeup_for_keyguard_notification` = 0) and restores them from `power_center_*` backups on
     * leaving. The module drops only those exact writes, so the rest of the policy stays active. See
     * [rules.securitycenter.PowerSaveOverrideHooker] / [rules.securitycenter.PowerSaveOverridePolicy]
     * and docs/FEATURE_DETAIL.md.
     *
     * The four feature switches are independent. [KEY_POWER_SAVE_OVERRIDE_MASTER] is a legacy
     * compatibility gate, flattened to enabled on the first individual edit.
     */
    /**
     * Automatic power-save control. SystemUI watches battery events and requests the native
     * Security Center changePowerMode protocol used by the control-center tile. See
     * [rules.system.BatteryAutoPowerSaveHooker] / [rules.system.BatteryAutoPowerSavePolicy].
     *
     * [KEY_BATTERY_AUTO_POWER_SAVE_ENABLED] turns power save on at or below
     * [KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD] percent while unplugged;
     * [KEY_EXIT_POWER_SAVE_WHEN_CHARGING] leaves power save once on connection, then selects
     * on/off on unplug from the same threshold. Manual changes while charging are preserved.
     * Both switches and the threshold apply live through the runtime settings group
     * `systemui.power_save`; the SystemUI battery monitor is registered regardless of them.
     */
    /**
     * Removes the recents "clean up background apps" button. The button is a Dart widget inside
     * MiuiHome's Flutter AOT snapshot, so the module suppresses the overlay insertion through the
     * native payload rather than through an ART hook. It is read when `com.miui.home` loads the
     * module, which is a separate process: changing it takes effect after the launcher restarts.
     */
    /** Native gesture-line choices and XiaoAI input routing. */
    fun xiaoaiGestureOptions(): Boolean = getBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, false)

    /** Native same-span Android AppWidget stack eligibility. */
    /** Live stacked preview in the final small-icon cell of native large folders. */
    /** Live opt-in feedback for user-driven horizontal recent-task card changes. */
    /**
     * Number of application columns in an opened MiuiHome folder. Three preserves the launcher's
     * native behavior; four and five are applied by the native Dart AOT rule after a launcher
     * restart. This does not affect the small folder preview on the desktop.
     */
    /** Allow ordinary AppWidget providers through the OS4 native drag eligibility gate. */
    /**
     * 长按底部手势指示器启动圈定即搜. The launcher's own long-press terminal is replaced by the
     * native payload so the gesture starts Circle to Search instead of Xiaomi's route, which
     * rejects NavLongPress on China builds. Like the other native launcher rules the value reaches
     * the payload through `NativeRules.applyRuleSwitches` when `com.miui.home` loads the module,
     * so changing it takes effect after a launcher restart. It is independent of
     * [KEY_POWER_BUTTON_ACTION]: either entry point may be on.
     */
    fun unlockMoreVisualPerception(): Boolean = getBoolean(PlatformSettingsKeys.KEY_UNLOCK_MORE_VISUAL_PERCEPTION, false)
    fun unlockMoreAonGestures(): Boolean = getBoolean(PlatformSettingsKeys.KEY_UNLOCK_MORE_AON_GESTURES, false)
    fun unlockAdaptiveRefreshPro(): Boolean = getBoolean(PlatformSettingsKeys.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO, false)

    // The removed master is retained only to interpret legacy snapshots. Each new individual
    // edit atomically flattens the effective selection and permanently opens that legacy gate.
    private fun powerSaveFeatureEnabled(key: String): Boolean =
        getBoolean(PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER, false) && getBoolean(key, false)

    fun keepRefreshRateInPowerSave(): Boolean = powerSaveFeatureEnabled(PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE)
    fun keepHapticFeedbackInPowerSave(): Boolean = powerSaveFeatureEnabled(PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE)
    fun keepSystemSoundsInPowerSave(): Boolean = powerSaveFeatureEnabled(PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE)
    fun keepWakeupGesturesInPowerSave(): Boolean = powerSaveFeatureEnabled(PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE)

    /** Whether the module enables power save below [batteryAutoPowerSaveThreshold]. */
    fun batteryAutoPowerSaveEnabled(): Boolean = getBoolean(PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED, false)

    /** Whether a new charger connection requests one native power-save exit. */
    fun exitPowerSaveWhenCharging(): Boolean = getBoolean(PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING, false)

    /** The low-battery threshold in percent, bounded to the slider's band. */
    fun batteryAutoPowerSaveThreshold(): Int = getInt(
        PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
        PlatformSettingsKeys.DEFAULT_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
    ).coerceIn(PlatformSettingsKeys.MIN_BATTERY_AUTO_POWER_SAVE_THRESHOLD, PlatformSettingsKeys.MAX_BATTERY_AUTO_POWER_SAVE_THRESHOLD)

    /** Preserve the effective legacy selection when editing one independent feature. */
    fun setPowerSaveFeature(key: String, enabled: Boolean) {
        val keys = listOf(
            PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE, PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
            PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE, PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
        )
        require(key in keys)
        val values = PowerSaveOverrideSelection.edit(
            getBoolean(PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER, false),
            keys.associateWith { getBoolean(it, false) }, key, enabled,
        )
        (keys + PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER).forEach(::memoInvalidate)
        write {
            putBoolean(PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER, true)
            values.forEach { (featureKey, value) -> putBoolean(featureKey, value) }
        }
    }

    fun androidWidgetStack(): Boolean = getBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, false)
    fun folderStackPreview(): Boolean = getBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, false)

    fun recentsCardHaptic(): Boolean = getBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, false)

    fun hideRecentsClearButton(): Boolean = getBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, false)

    /** Whether MIUI's theme DRM rights verification is bypassed for local/third-party themes. */
    fun allowThirdPartyTheme(): Boolean = getBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, false)

    /**
     * Whether the module-rendered cellular type stays visible while Wi-Fi carries the data
     * connection. Default off: Wi-Fi connected ⇒ no cellular type, matching the host's own single
     * type rule.
     *
     * The legacy [KEY_ICON_STACKED_TYPE_HIDE_WIFI] switch meant the opposite and defaulted to
     * showing the type. An explicit `false` there was the user turning the hiding *off*, so it
     * migrates to "keep showing"; an absent or `true` value stays hidden. The new key always wins
     * once the user touches the replacement switch.
     */
    fun cellularTypeKeepsOnWifi(): Boolean = when {
        contains(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI) ->
            getBoolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, false)
        contains(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI) ->
            !getBoolean(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI, false)
        else -> false
    }

    fun allowAndroidWidgetsToAssistant(): Boolean = getBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, false)

    fun contextualSearchLongPress(): Boolean = getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, false)

    fun openedFolderColumns(): Int = getInt(
        PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
        PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS
    ).coerceIn(PlatformSettingsKeys.MIN_OPENED_FOLDER_COLUMNS, PlatformSettingsKeys.MAX_OPENED_FOLDER_COLUMNS)

    fun notificationMoreSettings(): Boolean = getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS, false)

    fun notificationBadge(): Boolean = getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_BADGE, false)

    fun notificationBlockFold(): Boolean = getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_BLOCK_FOLD, false)

    fun freeformBlurTransition(): Boolean = getBoolean(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION, false)

    /**
     * 实况运镜 (MasterLive, camera mode id 231) unlock master; default off. While on, the
     * registry gate `y4()` (`MasterLiveModuleEntry.support()`) is forced true on the REAL
     * device config's base Method (C1143 — the one stock Redmi configs dispatch to, false on
     * myron), and the REDMI K100 effect table (`q0()`) is borrowed when the real config has
     * none. Carousel placement is handled by the `u2.P#y(Q)` order funnel plus the config
     * `M()` fronting; capture sizing can be tuned with
     * [KEY_CAMERA_MASTERLIVE_VIDEO_SIZE_PROBE]; the role-23→20 fallback is gated on its own
     * key. Turn off to restore the stock / guarded behaviour.
     */
    // --- Update system (module's own process only) ---
    // Only user-visible settings live here, so they travel with a settings backup exactly like the
    // rest of the configuration. Everything transient (last-attempt/last-success timestamps, the
    // per-channel response cache, skipped versions, the recovery arm and download sidecars) is kept
    // in the separate app-local `hypertweak_update` file by `util/update/UpdateStateStore`, which is
    // what keeps that churn out of the user's backup.
    /** `UpdateChannel.wireValue` of the release line to follow. */
    /** `UpdateCheckInterval.index`: 0 = daily, 1 = weekly, 2 = monthly, 3 = never. */
    /** `UpdateProxyMode.wireValue`; see [com.takekazex.hypertweak.util.update.UpdateNetworkPolicy]. */
    /** User-supplied `https://` proxy base; blank means "use the built-in preset". */
    private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
    private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
    private const val KEY_LOG_SESSION = "debug_log_session"
    private const val MAX_DEBUG_LOG_LENGTH = 256_000

    private lateinit var remotePrefs: SharedPreferences
    private var localSourcePrefs: SharedPreferences? = null
    @Volatile
    private var localCachePrefs: SharedPreferences? = null
    @Volatile
    private var cachedAppContext: Context? = null
    private val appCache = com.takekazex.hypertweak.hook.base.DeferredAppCache<Pair<Context, SharedPreferences>>(
        clock = android.os.SystemClock::elapsedRealtime
    )

    /**
     * True once the LSPosed daemon's remote-preferences channel is known to be unavailable
     * (e.g. `getRemotePreferences` throws "Framework returns null"). When this is set the getters
     * fall back to the per-process cache instead of silently returning defaults, so a daemon
     * hiccup cannot make every preference-gated hook read its "off" default.
     */
    @Volatile
    private var remoteBackendUnavailable = false

    private var isLocalOnly = false
    private val serializedWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "HyperTweak-Prefs").apply { isDaemon = true } }
    /** Serializes bulk mutations with the ordinary preference writes queued below. */
    private val settingsMutationLock = Any()

    /**
     * Bumped on every full reset ([clearAllSettings]). Hooked processes keep a local cache
     * (`hypertweak_cache.xml` in each app's data dir) that the module cannot reach; the
     * epoch stored in the authoritative remote prefs invalidates those caches so a reset
     * actually takes effect instead of serving stale values.
     */
    private const val KEY_PREFS_EPOCH = "prefs_epoch"
    private const val INITIAL_EPOCH = 0L

    /** Module-page saveable state identity; unavailable storage must not invalidate pending edits. */
    fun settingsEpochForUi(): Long? {
        if (isInitialized) runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }
            .getOrNull()?.let { return it }
        runCatching { localSourcePrefs?.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }
            .getOrNull()?.let { return it }
        return runCatching { getLocalCache()?.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrNull()
    }

    private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
    private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
    private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"
    private const val KEY_MANUAL_PENDING_RESTART_SCOPES = "manual_pending_restart_scopes"
    private const val KEY_FIRST_RUN_TOKEN = "first_run_token"
    private const val KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED = "fingerprint_split_baseline_migrated"

    /** Keys used by the module runtime itself rather than by user-facing configuration. */
    private fun isRuntimeKey(key: String): Boolean =
        key == RuntimeSettingsGroups.REVISION ||
            key == ScreenshotSettingsSnapshot.REVISION ||
            // Device-side properties are not portable configuration. Reset/restore does not
            // authorize root commands; the page always re-reads the actual device state.
            key == PlatformSettingsKeys.KEY_WIFI_CNSS_DIAG_ENABLED ||
            key == PlatformSettingsKeys.KEY_WIFI_TCPDUMP_ENABLED ||
            key == KEY_PREFS_EPOCH ||
            key == PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION ||
            key == BackGestureSettingsSnapshot.REVISION ||
            key == KEY_PENDING_RESTART_BOOT_TOKEN ||
            key == KEY_DIRTY_TWEAK_KEYS ||
            key.startsWith(KEY_TWEAK_BASELINE_PREFIX) ||
            key == KEY_MANUAL_PENDING_RESTART_SCOPES ||
            key == PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES ||
            key == KEY_FIRST_RUN_TOKEN ||
            // Obsolete per-device package snapshot from the former batch implementation.
            key == "notification_whitelist_bypass" ||
            key == KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED ||
            key == LEGACY_KEY_DEBUG_LOG ||
            key.startsWith(KEY_DEBUG_LOG_PREFIX) ||
            key == "debug_log_processes" || // Retired log registry; exclude from settings backups.
            key == KEY_LOG_SESSION ||
            key.startsWith("debug_log_level_") // Retired overrides are never configuration.

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
    fun clearAllSettings(): Boolean {
        synchronized(logLock) {
            synchronized(settingsMutationLock) {
                if (!isInitialized) return false
                memoClear()
                val epoch = runCatching {
                    remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH)
                }.getOrDefault(INITIAL_EPOCH) + 1
                val nativeRevision = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
                val nativeReset = NativeRuleStateSnapshot(false, 3, false, nativeRevision, epoch)

                // Queue the clear behind every ordinary async write and wait for it. Without this
                // ordering, a setting changed immediately before the clear could be committed
                // after the clear and silently resurrect the old configuration.
                requireNotNull(BackGestureSettingsSnapshot.read(mapOf(
                    BackGestureSettingsSnapshot.REVISION to nativeRevision, KEY_PREFS_EPOCH to epoch)))
                val committed = commitRemoteMutation(nativeReset, emptyMap<String, Any>()) {
                    clear()
                    putLong(KEY_PREFS_EPOCH, epoch)
                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
                }
                if (!committed) return false
                runCatching { localSourcePrefs?.edit(commit = true) { clear().putLong(KEY_PREFS_EPOCH, epoch).putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision) } }
                val cache = getLocalCache()
                runCatching { cache?.edit(commit = true) { clear().putLong(KEY_PREFS_EPOCH, epoch) } }
                return true
            }
        }
    }

    /** Serializes the user-facing configuration into a portable, versioned JSON document. */
    fun exportSettings(): String {
        synchronized(settingsMutationLock) {
            // Ordinary puts update the app-local source immediately but reach the daemon through
            // the serialized writer. Drain that queue so a backup taken right after a toggle also
            // contains the latest remote value.
            flush()
            val source = runCatching {
                if (isInitialized) remotePrefs.all else emptyMap()
            }.getOrElse { localSourcePrefs?.all.orEmpty() }
            return SettingsBackupCodec.encode(source.filterKeys { !isRuntimeKey(it) })
        }
    }

    /**
     * Replaces the current user-facing configuration from a backup document.
     *
     * Runtime bookkeeping (restart baselines, debug logs, and preference epochs) is intentionally
     * kept out of the backup. A new epoch invalidates old hooked-process caches so restored values
     * are read from the daemon instead of being shadowed by stale per-process copies.
     *
     * @return number of restored settings.
     */
    fun restoreSettings(json: String): Int {
        // Ignore runtime bookkeeping even if a hand-edited backup contains those reserved keys.
        val restored = SettingsBackupCodec.decode(json).filterKeys { !isRuntimeKey(it) }
        synchronized(settingsMutationLock) {
            check(isInitialized) { "Preferences are not initialized" }
            flush()
            val epoch = runCatching {
                remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH)
            }.getOrDefault(INITIAL_EPOCH) + 1
            val nativeRevision = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
            val nativeReset = requireNotNull(NativeRuleStateSnapshot.read {
                restored + mapOf(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to nativeRevision, KEY_PREFS_EPOCH to epoch)
            }) { "Invalid native settings in backup" }
            val currentKeys = buildSet {
                runCatching { remotePrefs.all.keys }.getOrDefault(emptySet())
                    .filterNot(::isRuntimeKey)
                    .forEach(::add)
                localSourcePrefs?.all.orEmpty().keys
                    .filterNot(::isRuntimeKey)
                    .forEach(::add)
            }

            requireNotNull(BackGestureSettingsSnapshot.read(restored + mapOf(
                BackGestureSettingsSnapshot.REVISION to nativeRevision, KEY_PREFS_EPOCH to epoch))) { "Invalid back gesture settings in backup" }
            val committed = commitRemoteMutation(nativeReset, restored) {
                currentKeys.forEach(::remove)
                restored.forEach { (key, value) -> putSharedPreferenceValue(key, value) }
                putLong(KEY_PREFS_EPOCH, epoch)
                putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
            }
            if (!committed) {
                // The serialized transaction restores the captured map on rejection.
                memoClear()
                error("Unable to write restored settings")
            }

            runCatching {
                localSourcePrefs?.edit(commit = true) {
                    currentKeys.forEach(::remove)
                    restored.forEach { (key, value) -> putSharedPreferenceValue(key, value) }
                    putLong(KEY_PREFS_EPOCH, epoch)
                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, nativeRevision)
                }
            }
            val cache = getLocalCache()
            runCatching {
                cache?.edit(commit = true) {
                    clear()
                    restored.forEach { (key, value) -> putSharedPreferenceValue(key, value) }
                    putLong(KEY_PREFS_EPOCH, epoch)
                }
            }
            memoClear()
            return restored.size
        }
    }

    /** True when the remote copy still holds settings (i.e. a previous install left config behind). */
    fun hasRemoteConfig(): Boolean {
        if (!isInitialized) return false
        return runCatching { remotePrefs.all.keys.any { it != KEY_PREFS_EPOCH } }.getOrDefault(false)
    }

    /**
     * Writes the value under [block] into the local cache together with the current remote epoch.
     * The cache only stores values that were read authoritatively from the daemon, so the epoch is
     * stamped at the same moment. This is what lets hook processes (e.g. SystemUI) use their local
     * cache as a fallback for keys the daemon does not carry, while still treating the cache as
     * stale after a [clearAllSettings] bumps the remote epoch.
     */
    private fun cacheWrite(cache: SharedPreferences?, block: SharedPreferences.Editor.() -> Unit) {
        if (cache == null) return
        // Async apply, matching the historical getter writes: the cache is only a fallback, so a
        // hot-path read (per frame / per touch) must not pay synchronous disk I/O. The epoch is
        // stamped in the same transaction so the cache always carries it alongside the value.
        runCatching {
            cache.edit {
                block()
                putLong(KEY_PREFS_EPOCH, remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH))
            }
        }
    }

    /**
     * False when the remote prefs were wiped after this process last cached values, so the
     * cache must not be trusted and reads fall back to defaults.
     */
    private fun cacheEpochMatchesRemote(): Boolean {
        val cache = getLocalCache() ?: return true
        val remoteEpoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        if (!cache.contains(KEY_PREFS_EPOCH)) {
            // A cache without the epoch key was populated by authoritative daemon reads from a
            // build that predates the epoch stamping (one-time transition). The values are fresh,
            // so adopt the current remote epoch and treat the cache as matching. From here on the
            // cache carries the epoch, so a later clearAllSettings bump makes it mismatch and the
            // cache is correctly treated as stale instead of serving pre-reset values forever.
            cacheWrite(cache) { /* only stamp the epoch */ }
            return true
        }
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

    internal fun nativeRuleSnapshot(): NativeRuleStateSnapshot? {
        if (RuntimeSettingsStore.owns(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION)) return RuntimeSettingsStore.values()?.let { values -> NativeRuleStateSnapshot.read { values } }
        if (!this::remotePrefs.isInitialized || isLocalOnly) return null
        return NativeRuleStateSnapshot.read { remotePrefs.all }
    }

    /** A remote change callback is authoritative; do not reuse pre-notification reads. */
    fun invalidateRuntimeReadCache() = memoClear()

    fun init(prefs: SharedPreferences, useLocalOnly: Boolean = false) {
        memoClear()
        remoteBackendUnavailable = false
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

    private val nativeMirrorLock = Any()

    private fun write(nativeMutation: Pair<String, Any>? = null, runtimeMutation: Pair<String, Any>? = null, runtimeChanges: Map<String, Any> = emptyMap(), block: SharedPreferences.Editor.() -> Unit) {
        synchronized(settingsMutationLock) {
            val nativeChange = nativeMutation != null
            if (!isInitialized) return
            val local = localSourcePrefs
            val revision = if (nativeChange) runCatching {
                NativeSnapshotLedger.nextRevision(maxOf(remotePrefs.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0),
                    local?.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0) ?: 0), android.os.SystemClock.elapsedRealtimeNanos())
            }.onFailure { SettingsDiagnostics.e("NativeRules", "native settings commit identity invalid", it) }.getOrNull() ?: return else 0
            runCatching { synchronized(nativeMirrorLock) {
                local?.edit { block(); if (nativeChange) putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, revision) }
            } }
                .onFailure { SettingsDiagnostics.e("Preferences", "local settings write failed", it) }
            if (isLocalOnly || local === remotePrefs) return
            serializedWriter.execute {
                // Synchronous commit: libxposed's RemotePreferences Editor.apply() is asynchronous on
                // its own executor, so a process killed right after a setting change (e.g. the in-page
                // "Restart SystemUI" action or the generic restart dialog) could die before the daemon
                // write lands — and Preferences.flush() only drains this queue, not libxposed's. A
                // blocking commit makes flush() honest: once this queue is drained, every setting that
                // was written is already visible to the hooked processes that read the daemon copy.
                runCatching {
                    val snapshot = nativeMutation?.let { mutation ->
                        val baseline = RuntimeSettingsSignal.lastAcknowledged()?.values?.let { values -> NativeRuleStateSnapshot.read { values } }
                            ?: error("native settings baseline unavailable")
                        NativeRuleMutation.apply(baseline, mutation.first, mutation.second, revision)
                    }
                    val changes = runtimeMutation?.let { mapOf(it) }.orEmpty() + runtimeChanges + snapshot?.settingsValues().orEmpty()
                    val runtime = if (changes.isNotEmpty()) RuntimeSettingsSignal.prepare(changes)
                        else RuntimeSettingsSignal.lastAcknowledged()
                    val editor = remotePrefs.edit()
                    block(editor)
                    if (runtime != null) RuntimeSettingsSignal.write(editor, runtime)
                    // Commit the entire native subset atomically, so a prior rejected editor's
                    // shadow values cannot hitchhike into this acknowledged transaction.
                    if (snapshot != null) {
                        editor.putBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, snapshot.hidden)
                        editor.putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, snapshot.columns)
                        editor.putBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, snapshot.contextualSearch)
                        editor.putBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, snapshot.assistantWidgets)
                        editor.putBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, snapshot.recentsHaptic)
                        editor.putBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, snapshot.folderStack)
                        editor.putBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, snapshot.widgetStack)
                        editor.putBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, snapshot.xiaoaiOptions)
                        editor.putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, snapshot.revision)
                        editor.putLong(KEY_PREFS_EPOCH, snapshot.epoch)
                    }
                    val committed = editor.commit()
                    if (committed && changes.isNotEmpty() && runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                    if (!committed) {
                        SettingsDiagnostics.w("Preferences", "remote pref commit rejected by daemon (settings not synced)")
                    } else if (snapshot != null) {
                        // Do not overwrite newer app-local edits already queued behind this one.
                        synchronized(nativeMirrorLock) {
                            if (local != null && NativeRuleMutation.canMirror(snapshot,
                                    local.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH),
                                    local.getLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, 0))) {
                                local.edit {
                                    putBoolean(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, snapshot.hidden)
                                    putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, snapshot.columns)
                                    putBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, snapshot.contextualSearch)
                                    putBoolean(PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, snapshot.assistantWidgets)
                                    putBoolean(PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, snapshot.recentsHaptic)
                                    putBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, snapshot.folderStack)
                                    putBoolean(PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, snapshot.widgetStack)
                                    putBoolean(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, snapshot.xiaoaiOptions)
                                    putLong(PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, snapshot.revision)
                                    putLong(KEY_PREFS_EPOCH, snapshot.epoch)
                                }
                            }
                        }
                    }
                }.onFailure { t ->
                    SettingsDiagnostics.w("Preferences", "remote pref write failed; retrying on next write", t)
                }
            }
        }
    }

    /** Applies one remote mutation after all previously queued writes and waits for its result.
     * Blocking commit on the writer is required before an acknowledged snapshot is published. */
    @Suppress("UseKtx", "ApplySharedPref")
    private fun commitRemoteMutation(nativeSnapshot: NativeRuleStateSnapshot,
        replacement: Map<String, *>, block: SharedPreferences.Editor.() -> Unit): Boolean {
        if (!isInitialized) return false
        fun commit(): Boolean {
            val previous = HashMap(remotePrefs.all)
            val previousRuntime = if (isLocalOnly) null else RuntimeSettingsSignal.lastAcknowledged()
            fun rollback() = runCatching {
                val editor = remotePrefs.edit().clear()
                previous.forEach { (key, value) -> if (value != null) editor.putSharedPreferenceValue(key, value) }
                previousRuntime?.let { RuntimeSettingsSignal.write(editor, it) }
                check(editor.commit()) { "settings mutation rollback rejected" }
            }.onFailure { SettingsDiagnostics.w("Preferences", "settings mutation rollback failed", it) }
            return try {
                val runtime = if (isLocalOnly) null else RuntimeSettingsSignal.prepareReplacement(replacement + nativeSnapshot.settingsValues())
                val editor = remotePrefs.edit()
                block(editor)
                runtime?.let { RuntimeSettingsSignal.write(editor, it) }
                editor.commit().also { committed ->
                    if (!committed) { SettingsDiagnostics.w("Preferences", "settings mutation commit rejected"); rollback() }
                    else if (runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                }
            } catch (error: Exception) {
                SettingsDiagnostics.e("Preferences", "settings mutation failed", error)
                rollback()
                false
            }
        }
        if (isLocalOnly || localSourcePrefs === remotePrefs) return commit()
        return runCatching { serializedWriter.submit<Boolean> { commit() }.get(5, TimeUnit.SECONDS) }
            .onFailure { SettingsDiagnostics.e("Preferences", "waiting for settings mutation failed", it) }.getOrDefault(false)
    }

    private fun SharedPreferences.Editor.putSharedPreferenceValue(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported restored setting type: ${value::class.java.name}")
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

    fun initLocalCache(context: Context): Boolean {
        val result = appCache.open {
            // A package-ready Context can arrive before credential storage is unlocked.
            // This is a normal deferral; retain the remote backend without touching CE files.
            if (!context.isDeviceProtectedStorage &&
                context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false) {
                Result.success(null)
            } else com.takekazex.hypertweak.hook.base.OptionalAppCache.open(
                directory = { context.dataDir },
                create = { context to context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE) }
            )
        }
        val cache = result.onFailure {
            SettingsDiagnostics.w("Preferences", "host storage cache unavailable; using remote preferences", it)
        }.getOrNull() ?: return false
        // Publish a complete, usable pair only. A provider's system context must not displace a
        // valid application cache, or prevent package-ready consumers from being registered.
        cachedAppContext = cache.first
        localCachePrefs = cache.second
        return true
    }

    /** Logging must never initialize storage, mirror settings, or log a backend failure. */
    internal fun logLevel(default: Int): Int {
        (memoGet(AppSettingsKeys.KEY_LOG_LEVEL) as? Int)?.let { return it }
        if (isInitialized) runCatching { remotePrefs.getInt(AppSettingsKeys.KEY_LOG_LEVEL, default) }
            .getOrNull()?.let { memoPut(AppSettingsKeys.KEY_LOG_LEVEL, it); return it }
        return runCatching { (localSourcePrefs ?: localCachePrefs)?.getInt(AppSettingsKeys.KEY_LOG_LEVEL, default) }
            .getOrNull() ?: default
    }

    /**
     * Lazily resolves an app context so the cache can be loaded before [initLocalCache] runs
     * (e.g. during hook attach, on the same event a gated hooker reads its preference). Best
     * effort: returns null when no application context is available yet (e.g. system_server).
     */
    private fun resolveAppContext(): Context? {
        cachedAppContext?.let { return it }
        val ctx = runCatching { EzXposed.appContextOrNull }.getOrNull()
        return ctx
    }

    private fun getLocalCache(): SharedPreferences? {
        localCachePrefs?.let { return it }
        val ctx = runCatching { resolveAppContext() }.getOrNull() ?: return null
        return if (initLocalCache(ctx)) localCachePrefs else null
    }

    /** True when the remote prefs channel is unavailable and [getLocalCache] is the only source. */
    fun isRemoteBackendUnavailable(): Boolean = remoteBackendUnavailable

    /** Marks the remote daemon channel unavailable (called when [init] fails with remote prefs). */
    fun noteRemoteBackendUnavailable() {
        if (remoteBackendUnavailable) return
        remoteBackendUnavailable = true
        memoClear()
        SettingsDiagnostics.w("Preferences", "remote preferences backend unavailable; falling back to local cache")
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    /**
     * Returns whether a key is explicitly present in the authoritative store (or its valid local
     * fallback). This is needed for aliased settings where an explicit `false`/`0` must beat an
     * older key, rather than being indistinguishable from the default value.
     */
    fun contains(key: String): Boolean {
        if (RuntimeSettingsStore.owns(key)) return RuntimeSettingsStore.value(key) != null
        if (!isInitialized) return getLocalCache()?.contains(key) == true
        return runCatching {
            if (remotePrefs.contains(key)) {
                true
            } else if (cacheEpochMatchesRemote()) {
                getLocalCache()?.contains(key) == true
            } else {
                false
            }
        }.getOrElse {
            localSourcePrefs?.contains(key) == true || getLocalCache()?.contains(key) == true
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Boolean) ?: default
        if (!isInitialized) return getLocalCache()?.getBoolean(key, default) ?: default
        // Safe cast: the memo map is keyed by key alone, so a key read through two different typed
        // getters would otherwise throw ClassCastException at a hook boundary with no fallback.
        (memoGet(key) as? Boolean)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getBoolean(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getBoolean(key, !v) != v)) {
                    cacheWrite(cache) { putBoolean(key, v) }
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

    /** Explicit false must beat the legacy combined switch, including runtime snapshot reset. */
    fun nativePickerEnabled(key: String): Boolean =
        com.takekazex.hypertweak.hook.rules.system.PickerReroutePolicy.enabled(
            if (contains(key)) getBoolean(key, false) else null,
            getBoolean(PlatformSettingsKeys.KEY_NATIVE_PICKER, false)
        )

    /**
     * Reads one scoped fingerprint-hiding switch while preserving the original aggregate switch
     * for upgrades. An explicit scoped false must beat a legacy true, hence the contains() check
     * rather than a simple OR.
     */
    private fun getFingerprintHideScope(key: String): Boolean {
        return if (contains(key)) {
            getBoolean(key, false)
        } else {
            getBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, false)
        }
    }

    /** Reads the independent always-on-display fingerprint switch. */
    fun hideFingerprintAodEnabled(): Boolean =
        getFingerprintHideScope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD)

    /** Reads the independent interactive-lockscreen fingerprint switch. */
    fun hideFingerprintLockscreenEnabled(): Boolean =
        getFingerprintHideScope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN)

    fun hideFingerprintAppAuthEnabled(): Boolean =
        getFingerprintHideScope(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH)

    fun getInt(key: String, default: Int = 0): Int {
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Int) ?: default
        if (!isInitialized) return getLocalCache()?.getInt(key, default) ?: default
        (memoGet(key) as? Int)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getInt(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getInt(key, v - 1) != v)) {
                    cacheWrite(cache) { putInt(key, v) }
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
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? Float) ?: default
        if (!isInitialized) return getLocalCache()?.getFloat(key, default) ?: default
        (memoGet(key) as? Float)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getFloat(key, default)
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getFloat(key, v - 1f) != v)) {
                    cacheWrite(cache) { putFloat(key, v) }
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

    fun getNotificationHeaderTimeScale(): Float = getFloat(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_TIME_SCALE, 1f)

    fun putBoolean(key: String, value: Boolean) {
        memoInvalidate(key)
        write(nativeMutation = if (key == PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON || key == PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS || key == PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT || key == PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC || key == PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW || key == PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK || key == PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS) key to value else null,
            runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putBoolean(key, value) }
    }

    /** Publish a dropdown selection as one acknowledged snapshot, without intermediate styles. */
    fun putBackGestureAppearance(values: Map<String, Boolean>) {
        val allowed = setOf(BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR, BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR,
            BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS, BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED)
        require(values.isNotEmpty() && values.keys.all { it in allowed })
        val changes = values.toMap()
        changes.keys.forEach(::memoInvalidate)
        write(runtimeChanges = changes) {
            changes.forEach { (key, value) -> putBoolean(key, value) }
        }
    }

    fun putInt(key: String, value: Int) {
        memoInvalidate(key)
        write(nativeMutation = if (key == PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS) key to value else null,
            runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putInt(key, value) }
    }

    fun putFloat(key: String, value: Float) {
        memoInvalidate(key)
        write(runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putFloat(key, value) }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (RuntimeSettingsStore.owns(key)) {
            @Suppress("UNCHECKED_CAST")
            return (RuntimeSettingsStore.value(key) as? Set<String>) ?: default
        }
        if (!isInitialized) return getLocalCache()?.getStringSet(key, default) ?: default
        memoGet(key)?.let { return ((it as? Set<*>) ?: default).map { element -> element as String }.toSet() }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getStringSet(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getStringSet(key, emptySet()) != v)) {
                    cacheWrite(cache) { putStringSet(key, v) }
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
        write(runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putStringSet(key, value) }
    }

    fun getString(key: String, default: String = ""): String {
        if (RuntimeSettingsStore.owns(key)) return (RuntimeSettingsStore.value(key) as? String) ?: default
        if (!isInitialized) return getLocalCache()?.getString(key, default) ?: default
        (memoGet(key) as? String)?.let { return it }
        val value = try {
            if (remotePrefs.contains(key)) {
                val v = remotePrefs.getString(key, default) ?: default
                val cache = getLocalCache()
                if (cache != null && (!cache.contains(key) || cache.getString(key, "") != v)) {
                    cacheWrite(cache) { putString(key, v) }
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
        write(runtimeMutation = if (RuntimeSettingsSnapshot.owns(key)) key to value else null) { putString(key, value) }
    }

    /** Atomically commits the complete Dock configuration; only an acknowledgement updates the mirror. */
    fun commitDockBackground(value: String): Boolean {
        if (com.takekazex.hypertweak.settings.contract.DockConfig.decode(value) == null || !isInitialized) return false
        val key = com.takekazex.hypertweak.settings.contract.DockConfig.KEY
        fun commit(): Boolean = synchronized(settingsMutationLock) {
            val previousRuntime = if (isLocalOnly) null else RuntimeSettingsSignal.lastAcknowledged()
            val previous = if (previousRuntime != null) previousRuntime.values[key] as? String else remotePrefs.getString(key, null)
            try {
                val runtime = if (isLocalOnly) null else RuntimeSettingsSignal.prepare(key, value)
                val editor = remotePrefs.edit().putString(key, value)
                runtime?.let { RuntimeSettingsSignal.write(editor, it) }
                if (!editor.commit()) {
                    val restore = remotePrefs.edit()
                    if (previous == null) restore.remove(key) else restore.putString(key, previous)
                    previousRuntime?.let { RuntimeSettingsSignal.write(restore, it) }
                    check(restore.commit()) { "Dock settings rollback rejected" }
                    false
                } else {
                    localSourcePrefs?.takeIf { it !== remotePrefs }?.edit(commit = true) { putString(key, value) }
                    if (runtime != null) RuntimeSettingsSignal.acknowledge(runtime)
                    memoInvalidate(key)
                    SettingsDiagnostics.i("DockBackground", "settings committed $value")
                    true
                }
            } catch (error: Exception) {
                runCatching {
                    val restore = remotePrefs.edit()
                    if (previous == null) restore.remove(key) else restore.putString(key, previous)
                    previousRuntime?.let { RuntimeSettingsSignal.write(restore, it) }
                    check(restore.commit()) { "Dock settings rollback rejected" }
                }.onFailure { SettingsDiagnostics.w("DockBackground", "settings rollback failed", it) }
                SettingsDiagnostics.w("DockBackground", "settings commit failed", error)
                false
            }
        }
        return if (isLocalOnly || localSourcePrefs === remotePrefs) commit()
        else runCatching { serializedWriter.submit<Boolean> { commit() }.get() }
            .onFailure { SettingsDiagnostics.w("DockBackground", "settings acknowledgement failed", it) }.getOrDefault(false)
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
                val runtime = if (RuntimeSettingsSnapshot.owns(key)) RuntimeSettingsSignal.prepare(key, value)
                    else RuntimeSettingsSignal.lastAcknowledged()
                val editor = remotePrefs.edit().putString(key, value)
                if (runtime != null) RuntimeSettingsSignal.write(editor, runtime)
                val committed = editor.commit()
                if (committed && runtime != null && RuntimeSettingsSnapshot.owns(key)) RuntimeSettingsSignal.acknowledge(runtime)
            }.onFailure { t ->
                SettingsDiagnostics.w("Preferences", "synchronous remote pref write failed", t)
            }
        }
        runCatching { task.get(3, TimeUnit.SECONDS) }
    }

    fun appendDebugLog(processTag: String, line: String) = appendDebugLogs(processTag, listOf(line))

    /** App-owned records only. Hook-side records belong to the LSPosed daemon. */
    fun appendDebugLogs(processTag: String, lines: List<String>) {
        synchronized(logLock) {
            val local = localSourcePrefs ?: return
            if (lines.isEmpty() || !getBoolean(PlatformSettingsKeys.KEY_RECORD_LOGS, true)) return
            val key = debugLogKeyFor(processTag)
            val old = local.getString(key, "").orEmpty()
            val appended = lines.joinToString("\n")
            val next = com.takekazex.hypertweak.util.LogCodec.boundedTail(
                if (old.isBlank()) appended else "$old\n$appended", MAX_DEBUG_LOG_LENGTH
            )
            check(local.edit().putString(key, next).commit()) {
                "App log storage rejected commit"
            }
        }
    }

    fun getDebugLog(): String = synchronized(logLock) {
        val local = localSourcePrefs ?: return@synchronized ""
        local.all.filterKeys { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            .values.filterIsInstance<String>().flatMap { it.lines() }.distinct().joinToString("\n")
    }

    /** Clears app-owned logs. LSPosed files remain owned by the framework. */
    fun clearDebugLog() {
        synchronized(logLock) {
            val local = localSourcePrefs ?: return
            val keys = local.all.keys.filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            check(local.edit().apply { keys.forEach(::remove); remove("debug_log_processes") }.commit()) {
                "App log clearing rejected commit"
            }
        }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    fun rotateLogSessionIfNeeded(token: String) {
        synchronized(logLock) {
            val local = localSourcePrefs ?: return
            if (local.getString(KEY_LOG_SESSION, null) == token) return
            val keys = local.all.keys.filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            check(local.edit().apply {
                keys.forEach(::remove)
                remove("debug_log_processes")
                putString(KEY_LOG_SESSION, token)
            }.commit()) { "App log session rotation rejected commit" }
        }
    }

    private fun debugLogKeyFor(processTag: String): String =
        "$KEY_DEBUG_LOG_PREFIX${sanitizeProcessTag(processTag)}"

    /** Process tags are prefs keys, so reduce them to `[A-Za-z0-9_]`. */
    private fun sanitizeProcessTag(processTag: String): String =
        processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
}
