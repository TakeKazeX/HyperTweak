# Security Center and PowerKeeper

Part of the [feature reference](../features.md). Search by feature or class name and read only the matching section.

## Security Center app link management

The default-off `app_link_management` switch lives under App details. On OS4, the
Security Center main process reuses the host's `app_default_pref` row. The DEX contract
checks the preference binding, click branch, loader bridge and package parsing, and
resolves the viewed app from the host's `ApplicationInfo`. The title and link-permission
summary refresh after native data loads and when returning from Android Settings.
Clicking an owned row opens the system open-by-default page for that package. A failed
launch restores the row and consumes that click to prevent an accidental clear.

Removed rows (including the browser on the verified build), apps belonging to a different
user, and unavailable Settings routes retain native behavior. Android's public domain
state and Settings action address the calling user. Missing domain state displays the
disallowed summary. The preference key and order stay available to the AOSP app info
entry. Settings changes and new generations bind live fragments through the activity's
typed fragment field. Disable, reset and retirement restore native text.

## Security Center privacy

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

## App Lock PIN scramble
- Code: `hook/rules/securitycenter/AppLockPinScrambleHooker.kt` (Security Center main process, `securitycenter_app_lock_scramble_pin`, default off; Security Center → App Lock; runtime group `securitycenter.app_lock`). Behavior follows HyperCeiler's `AppLockPinScramble`; independently implemented. Key moves go through the shared `hook/rules/shared/DigitKeypadShuffle`, also used by the lock-screen PIN.
- Host (`com.miui.securitycenter` 13.8.0-261020.0.1, sha256 `e53a09e3…`): `com.miui.applicationlock.widget.MiuiNumericInputView` (a `LinearLayout`) builds itself in its only constructor: four fields of one nested `LinearLayout` row class (three nested `FrameLayout` keys each) and one nested-key array indexed by key code, 0..9 digits and 10/11 the bottom-row action keys around 0. Contract: exactly one such array (12 entries at use), four row fields of one type, four row children of that type with three keys each, and the two bottom-row ends being codes 10/11. Anything else leaves the pad native.
- The after-constructor hook shuffles each pad once; listeners and the key-code array are untouched. Every Security Center screen using this widget is shuffled (unlock, set/change PIN). `com.miui.auth.widget.MiuiNumericInputView` (privacy password / biometric PIN) is a different widget and is not covered.
- Live and lifecycle: enabling shuffles living pads, disabling restores keypad order; retirement restores keypad order, and a new generation finds living pads through `WindowInspector`. Keypad order is derived from the key-code array, so a pad left shuffled by a build that saved no state is repaired too.

## Security Center sidebar app shortcuts

`SecurityCenterDockShortcutsHooker` (`com.miui.securitycenter:ui`, switch
`securitycenter_dock_app_shortcuts`, own runtime group `securitycenter.dock_shortcuts` so toggling
never rebuilds dock models) adds a card of the long-pressed app's App Shortcuts under the native
full-screen/split card. Security Center is UID 1000, which `checkPermission(ACCESS_SHORTCUTS)`
grants, so `LauncherApps.getShortcuts`/`startShortcut` work in-process with no system_server hook.
`SecurityCenterDockShortcutsResolver` anchors the window-manager show method
(`showOrHideShortCutMenu: invalid!!!`, `(panel, item, View, callback, Z)V`), the CardView field it
positions, the dismiss method (`dismissShortCut mShortCutMenu: `), and the app model via the
callback base type's uid/package accessors; the model's `AppInfo{uid=` printer proves the int is
a uid. Only an after-hook on the show method is installed. The card is a host `CardView` sibling in
the same panel `FrameLayout`, copying radius, colour, compat padding, elevation and `setMiShadow`
from the native card. A pre-draw listener mirrors its visibility, alpha and scale, so every native
dismiss path also hides the card. The horizontal-sidebar branch (`Z = true`) stays native.
Rows use the host's Miuix drop-down metrics/typography (`miuix_appcompat_popup_menu_item_min_height`,
`drop_down_item_text_size`, `miuix_font_weight_headline2`, immersion text colours). Each row's
foreground is the host's `drawable/miuix_popup_window_list_item_fg` (`PressEffectDrawable`: inset,
rounded, Folme-animated alpha over the whole row, light/night variants). Folme touch tint is not
used: on a transparent row it only darkens the icon pixels. After `startShortcut` the hooker calls the
window manager's animated collapse (`hideNewDockSidebar withAnim`, `(I)V`, gesture 0), which SystemUI
normally requests over the overlay binder for native launches. Results are cached per user/package and invalidated by `LauncherApps.Callback`.
Ordering follows the launcher (manifest first, then rank) with no count cap; a card taller than the
window scrolls. `securitycenter_dock_app_shortcuts_freeform` passes the framework
`MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true)` to `startShortcut`; a null result (app
without small-window support) opens normally. Validation: `SecurityCenterDockShortcutsContractTest`
(set `SECURITY_CENTER_APK`); the user checks placement, tapping, dark mode and hot reload on device.

## PowerKeeper allowlist restore block

`PowerKeeperWhitelistRestoreHooker` runs in every `com.miui.powerkeeper` process. Its switch is on the Features tab, key `powerkeeper_block_whitelist_restore`, group `PASSIVE_POLICIES`. Verified on PowerKeeper 4.2.00 / OS4.0.6.0.XPMCNXM (APK SHA-256 `164f2b62…5448c6`).

**Where PowerKeeper adds apps.** `DeviceIdlePolicyHelper.c(String[]) → CommonAdapter.addPowerSaveWhitelistApps → IDeviceIdleController.addPowerSaveWhitelistApps(List)` is its only allowlist add. Three sources feed it:
- `DeviceIdleController.onHandleMessage(what=1)` re-adds `sAlwaysWhiteApps` on every rule pass. On CN builds that set is `array/doze_always_white_apps` (WeChat, QQ, QQ Lite) plus `pkg_deskclock`.
- Apps whose rule allows them, such as those in the cloud `dozeWhiteListApps` list or set to user `noRestrict`, are added through `setUidState`.
- The force-idle and power-save restores.

PowerKeeper removes apps only through `IDeviceIdleController.removePowerSaveWhitelistApp(String)`. Rule flips also remove apps that the user allowlisted in Settings. Natively, PowerKeeper adds them back later.

**What the hook does.** It sits on the framework `IDeviceIdleController$Stub$Proxy`, so it is independent of PowerKeeper obfuscation:
- **Remove:** before each removal, it queries `getUserPowerWhitelist()`. If the package is on the list, it records the package in `AllowlistRestoreLedger`, whether or not the switch is on.
- **Add, switch on:** it passes only recorded packages, each once, plus GMS while FCM Live is on, because FCM Live feeds GMS through `getDozeWhiteListApps`. It drops everything else. When the switch is off, adds pass unchanged but still consume their records.
- **Settings:** writes from its own process and is unaffected.
- **Hot reload:** the ledger crosses as a `HashSet<String>`.
- **Limitations:**
  - A PowerKeeper process restart, or an upgrade from a build that saved no state, forgets pending restores.
  - If the user restricts an app in Settings while PowerKeeper has it removed, PowerKeeper's later restore overrides that change.

**Compared with upstream.** HyperCeiler's `PreventBatteryWitelist` hooks `DeviceIdlePolicyHelper.c` and blocks only arrays longer than 1. It misses single-package adds, and it blocks PowerKeeper's legitimate restores whenever they are batched.

**Inlining.** `CommonAdapter.addPowerSaveWhitelistApps` has no AOT code in `PowerKeeper.odex`, so nothing inlines the proxy.
