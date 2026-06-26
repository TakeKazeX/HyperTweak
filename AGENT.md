# Agent Contribution & Development Log

This file documents the AI developer's contributions, architectural decisions, and key implementation notes for HyperTweak.

## AI Assistant Role & Context

HyperTweak was refactored and built in collaboration with AI coding assistants. The primary objectives were:
1. Re-architect the codebase to completely remove YukiHook and migrate to native `libxposed` (API 102).
2. Establish a modular design pattern inspired by projects like `XiaomiHelper` and `HyperCeiler`.
3. Resolve system-specific issues such as fingerprint icon hiding and always-on display fullscreen configurations in HyperOS settings.
4. Redesign the UI using Compose & Miuix UI.

---

## Architectural Changes & Transformations

### 1. Framework Migration (YukiHook -> libxposed API 102)
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

### 5. DexKit-Based Class Resolution
- **Problem**: HyperOS plugin classes are obfuscated and change between versions.
- **Solution**: Built a `DexKitManager` with property-file caching (keyed by APK last-modified time) to resolve obfuscated class names at runtime. `BaseHooker.resolveAppClass()` provides a unified API: try DexKit first, fall back to `toClassOrNull()`.

### 6. Slider Percentage Display System
- Hooked `BrightnessSliderController` and `VolumeSliderController` to show percentage text on sliders.
- Uses `SliderHookHelper` for shared logic: cached reflection (`findHolder`, `getTopTextFromHolder`), blur mode helpers, and dark-mode text color resolution.
- `MiBlurMethodCache` caches MIUI blur View extension methods per-class to avoid repeated reflection in animation hot paths.

---

## Technical Stack & Dependencies

- **API Level Support**: minSdk 35 (Android 15), compileSdk 37 (Android 16)
- **Architecture**: arm64-v8a only
- **Core Dependencies**:
- `io.github.libxposed:api:102.0.0` / `io.github.libxposed:service:102.0.0`
- `io.github.lingqiqi5211.ezhooktool:core:1.1.0-rc04` / `hook-xposed-102:1.1.0-rc04`
  - `org.luckypray:dexkit:2.2.0`
  - `top.yukonga.miuix.kmp:miuix-*:0.9.2`
  - `androidx.compose.material:material-icons-*:1.7.8`
- **Build**: Gradle with Kotlin DSL, AGP 9.2.1, Kotlin Compose plugin 2.4.0, JDK 25
- **CI/CD**: GitHub Actions with automated changelog and release publishing

---

## Key Patterns & Conventions

### Hook Architecture
- `BaseHooker` → `StaticHooker` (lifetime of process) / `DynamicHooker` (can be unhooked)
- `attach()` for parent-child hooker relationships with cascading enable/disable
- `Preferences` for cross-process IPC settings with local cache fallback

### Performance Guidelines
- Cache all reflection `Field`/`Method` lookups — never call `getDeclaredField`/`getMethod` in hot paths
- Use `@Volatile` + lazy-init pattern for one-time cached lookups (see `SliderHookHelper`)
- Use `ConcurrentHashMap` for per-class method caches (see `MiBlurMethodCache`)
- Avoid `Log.d` in animation callbacks

### Stability Guidelines
- Use safe casts (`as?`) instead of force casts (`as`) or `!!` in hook callbacks
- Wrap all hook callback logic in `runCatching` to prevent crashes in hooked processes
- Provide `clearActiveColorCache()` style invalidation for runtime-changeable caches

---

## Version History

| Version | Key Changes |
|---------|------------|
| 1.3.4 | Null safety, reflection caching, dead code removal, code deduplication |
| 1.3.3 | Slider percentage, badge styling, performance optimizations, language switcher |
| 1.3.2 | Google Passkey unlock, GMS bypass crash fix, hook rule restructuring |
| 1.3.1 | GPLv3 relicense, CI/CD pipeline |
| 1.3.0 | Initial libxposed rewrite, Compose + Miuix UI |
