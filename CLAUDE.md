# HyperTweak Project Reference

Project rules: [AGENTS.md](AGENTS.md). This reference is a map, not a prerequisite reading list.

## Platform and build facts
HyperTweak is a Xiaomi HyperOS libxposed API 102 module with a Compose/Miuix settings UI and `arm64-v8a` native code. The support floor is OS3 / Android 16 (API 36); primary development targets OS4 / Android 17 (API 37). Android 15 is outside support.

Use `build.gradle.kts` and `app/build.gradle.kts` for current plugins, SDKs, Java level, dependencies, signing, and shrinking; `gradle/wrapper/gradle-wrapper.properties` for Gradle; `gradle.properties` for the project version. Release builds enable shrinking and may fall back to debug signing when the release keystore is absent. A release variant is not necessarily a stable-channel or production-signed artifact.

## Source map
Kotlin sources: `app/src/main/kotlin/com/takekazex/hypertweak/`.
- `hook/`: process entry and feature hooks; `hook/base/`: shared resolution and lifecycle infrastructure.
- `ui/`: Compose screens; `util/`: shared utilities, including `PlatformLevel.kt` for OS3/OS4 gates.
- `HookEntry` initializes processes; `Preferences` is the runtime/cross-process settings boundary; `BaseHooker` owns lifecycle/resolution; `DexKitManager` resolves and caches obfuscated targets.
- The back-gesture port also has Java sources in `app/src/main/java/com/takekazex/hypertweak/hook/rules/backgesture/`, vendored from MiuiBackGestureHook under Apache-2.0. Include this tree when a change touches that feature or shared platform behavior.
- JVM tests: `app/src/test/`. Scope metadata: `app/src/main/resources/META-INF/xposed/scope.list`; also inspect `app/src/main/res/values/arrays.xml` when changing scope presentation or checking release parity.

## Task references
- Validation, reverse engineering, release/device delivery, and Git: [.github/AGENT_WORKFLOWS.md](.github/AGENT_WORKFLOWS.md), relevant section only.
- CI behavior: `.github/workflows/ci.yml` and `.github/workflows/release.yml`; published release-note format: [.github/release-notes/README.md](.github/release-notes/README.md).
- Local feature mechanisms, baseline hashes, and regressions: `docs/FEATURE_DETAIL.md`, searched by feature or symbol. `docs/` is git-ignored and may be absent in another checkout; its dated findings are not automatically the current device baseline.
