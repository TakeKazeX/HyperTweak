# HyperTweak Project Reference

This file records stable project facts. Agent rules are in [`AGENTS.md`](AGENTS.md). Detailed per-feature mechanisms, reverse-engineering notes, regressions, and device verification live in [`docs/FEATURE_DETAIL.md`](docs/FEATURE_DETAIL.md); read the relevant section before changing a feature.

## Identity
HyperTweak is a Xiaomi HyperOS/MIUI libxposed API 102 module. Its settings UI uses Jetpack Compose and Miuix. Hook scope is defined by `app/src/main/resources/META-INF/xposed/scope.list`.

## Toolchain
- compile/target SDK 37, min SDK 35; Java 25; AGP 9.4.0; Kotlin Compose 2.4.10
- libxposed API/service 102.0.0; Miuix 0.9.3; DexKit 2.2.0
- native ABI: `arm64-v8a`

## Layout and Architecture
Sources are under `app/src/main/kotlin/com/takekazex/hypertweak/`: `hook/` contains process entry and feature hooks, `hook/base/` shared resolution/lifecycle infrastructure, `ui/` Compose screens, and `util/` utilities. JVM tests are under `app/src/test/`.

`HookEntry` initializes each process. `Preferences` is the runtime and cross-process settings boundary. `BaseHooker` owns lifecycle and resolution; `DexKitManager` resolves obfuscated classes and caches results. Hot-reload handles belong to the lifecycle layer.

## Feature Index
The module covers System/SystemUI, Launcher, Settings, AOD, Security Center, PowerKeeper, Scanner, MiLink, Bluetooth, GMS, media editor, and its own process. Feature names, switches, scope details, release/R8 rules, process constraints, storage, logging, camera resolution, and known regressions are kept in `docs/FEATURE_DETAIL.md`. Do not duplicate those details here.

## Reverse-Engineering Baselines
Artifacts are external to the repository under `/Users/ink/developer/reverse`; cache layout and hash requirements are defined in `AGENTS.md`. Baseline mappings and OTA-specific notes belong in `docs/FEATURE_DETAIL.md`.

## Build Commands
```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```
Release CI uses JDK 25, a signing keystore, `BUILD_CHANNEL=stable`, and verifies the APK certificate with `apksigner`.
