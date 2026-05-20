# Agent Contribution & Development Log

This file documents the AI developer's (Antigravity's) contributions, architectural decisions, and key implementation notes for HyperTweak.

## AI Assistant Role & Context

HyperTweak was refactored and built in collaboration with an AI coding assistant from Google DeepMind. The primary objectives were:
1. Re-architect the codebase to completely remove YukiHook and migrate to native `libxposed` (API 101).
2. Establish a modular design pattern inspired by projects like `XiaomiHelper` and `HyperCeiler`.
3. Resolve system-specific issues such as fingerprint icon hiding and always-on display fullscreen configurations in HyperOS settings.
4. Redesign the UI using Compose & Miuix UI.

---

## Architectural Changes & Transformations

### 1. Framework Migration (YukiHook -> libxposed API 101)
- **Problem**: The original codebase relied on the YukiHook library, which added dependency bloat and is less flexible for low-level platform APIs.
- **Solution**: Re-implemented the module entry using pure `libxposed` APIs. Designed a custom `BaseHooker` pattern wrapping classes dynamically, making hooks modular and standalone.
- **IPC SharedPreferences**: Bound the settings screen preferences seamlessly across different hooked processes via standard `io.github.libxposed:service` IPC shared preferences, solving key sync latency issues.

### 2. Multi-Process Settings & AOD Hooking
- **Problem**: Fullscreen AOD settings were not showing up in the Settings app under the "AOD & Lock Screen" section.
- **Investigation**: Analysis of `dumpsys activity` showed the AOD category settings Activity is hosted in the `com.miui.aod` process, not `SystemUI`.
- **Solution**: Expanded the hooked packages scope (`scope.list` and `arrays.xml`) to include `com.miui.aod`. Registered the `AODHooker` for both `com.android.settings` and `com.miui.aod` processes to intercept `miui.util.FeatureParser.getBoolean("support_aod_fullscreen")` and return `true` globally.

### 3. Dual-Layer Lockscreen Fingerprint Hiding
- **Problem**: The lockscreen fingerprint icon was showing up dynamically during transitions or AOD states.
- **Solution**: Engineered a robust dual-layer mechanism:
  1. *Drawable Substitution*: Intercepted `MiuiGxzwFrameAnimation.draw` and returned `android.R.color.transparent` if the resource starts with `finger_circle` (targeting `_normal`, `_light`, and `_aod`).
  2. *View Alpha Force*: Hooked constructors of `MiuiGxzwIconView` via reflection, injecting `alpha = 0f` to hide the static view while keeping the touchscreen touch-target active for fingerprint scans.

### 4. Dynamic Launcher Icon Toggling
- Added support for hiding the launcher icon dynamically via `PackageManager.setComponentEnabledSetting` targeting `<activity-alias>` `com.takekazex.hypertweak.MainActivityAlias`.

---

## Technical Stack & Dependencies

- **API Level Support**: Tested up to compileSdk 37 (Android 15 QPR) on HyperOS (Android 14/15 base).
- **Core Dependencies**:
  - `io.github.libxposed:api:101.0.1`
  - `io.github.libxposed:service:101.0.0`
  - `com.highcapable.kavaref:kavaref-core:1.0.2`
  - `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`
  - `top.yukonga.miuix.kmp:miuix-ui:0.9.0`
  - `androidx.compose.material:material-icons-core:1.7.6`
