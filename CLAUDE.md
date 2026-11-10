# HyperTweak

@AGENTS.md

## Platform
- HyperTweak is a Xiaomi HyperOS module on libxposed API 102. Its settings UI uses Compose/Miuix, and its native code is `arm64-v8a`.
- OS4 / Android 17 (API 37) is the only maintained target, and features are built and verified there. Gate on platform level with `util/platform/PlatformLevel.kt`.
- `minSdk` stays 36, so the APK still installs on OS3 / Android 16. OS3 is unmaintained and has no test device. Do not add OS3-specific paths or spend effort on OS3 parity. On OS3 a feature may simply be unavailable, but its resolution must still fail closed rather than crash.
- Android 15 is unsupported.
- Build facts (SDKs, plugins, signing, shrinking) live in `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties` and the Gradle wrapper.
- A `release` variant is not necessarily stable-channel or production-signed.
- The `assembleContractProbe` APK is test-only: never install or publish it.

## Where things live
Kotlin sources are under `app/src/main/kotlin/com/takekazex/hypertweak/`.
- `hook/`: `HookEntry` is the process entry, and features are grouped by family. Other subpackages:
  - `hook/base/`: `BaseHooker` lifecycle, `HostAccess` reflection, and `dex/` with `DexRuntime` for target resolution and caching.
  - `hook/bootstrap/`: install plans per process.
  - `hook/runtime/`: settings reload.
  - `hook/nativebridge/`, `hook/logging/`, `hook/preferences/`.
- `settings/contract/`: storage-independent keys, defaults and shared policies. UI and hooks share these.
- `presentation/`: rendering math shared by previews and hooks. No hook registration goes here.
- `ui/`: Compose screens. Pages are in `ui/page/<feature>/`, the app shell in `ui/app/`, and navigation in `ui/navigation/`.
- `app/`: app-process service wiring (`DefaultAppServices`, `AppServicePorts`).
- `util/`: logging, restart, scope, device, platform, diagnostics, update.
- Native launcher payload: `app/src/main/cpp/NativeRules/`.
- Tests: JVM tests in `app/src/test/`, native harnesses in `app/src/test/cpp/`.
- LSPosed scope: `app/src/main/resources/META-INF/xposed/scope.list`, presented through `res/values/arrays.xml`.

## Cross-cutting invariants
- **Layering**: `.github/scripts/check_architecture.py` enforces it. Hook infrastructure never references `rules`. Features reference only `rules.shared` plus the `FEATURE_COLLABORATIONS` allowlist. UI never imports `hook.rules` or `Preferences`; it uses `LocalAppSettingsStore` / `LocalAppServices`.
- **Settings**: `Preferences` is the runtime boundary for reads and acknowledged writes. Runtime settings groups are declared in `RuntimeSettingsDefinitions`. Callback settings use `hookSetting(default) { ... }` slots; never read storage under registry locks.
- **Lifecycle and hot reload**: transient state belongs in `BaseHooker.generation*` helpers. Living-host recovery is declared next to its feature with `hostRecovery(...)`; never add a central per-feature recovery list.
- **DEX targets**: go through `DexRuntime` / `BaseHooker.prepareDexTargets`. Never scan on the main thread. Until preparation finishes, a new host APK keeps its native behavior.
- **Native targets**: resolved structurally by `dart_image.cpp` and `dart_targets.cpp`. `dart_runtime_resolver.cpp` stays byte-identical to upstream.
- **Logging**: use DEBUG for registration, resolution and per-frame detail, and INFO for meaningful state changes. Never pick a severity from words in the message, and never swallow failures at resolver, persistence or system-callback boundaries.

## Reference index
Read only the section that matches the task.

| Task | Read |
| --- | --- |
| App shell, settings storage/backup, runtime settings reload, hot reload, reflection caches, logging, build/size | [.github/agent/architecture.md](.github/agent/architecture.md) |
| A specific feature: UI shell/navigation/blur, Purification/Copy Direct, pickers, gesture bar, back gesture, Security Center, PA widgets, screenshot/Dock/IME, shortcuts | [.github/agent/features.md](.github/agent/features.md) |
| Launcher native payload, Dart structural targets, native settings transport, native upgrade | [.github/agent/native.md](.github/agent/native.md) |
| Choosing checks, per-feature test recipes, reverse engineering, release, CI, device delivery, commits | [.github/agent/workflows.md](.github/agent/workflows.md) |
| Root settings debugging and diagnostic bundles | [docs/DEBUG_DIAGNOSTICS.md](docs/DEBUG_DIAGNOSTICS.md) |
| CI and release-note format | `.github/workflows/{ci,release}.yml`, [.github/release-notes/README.md](.github/release-notes/README.md) |

The local, git-ignored `docs/` may also hold dated research:
- `FEATURE_DETAIL.md`, searched by feature or symbol.
- The launcher research and native hook manuals.
- Per-feature reports.

These are not current baselines unless you re-verify them. They may also be absent in another checkout.
