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
- JVM tests: `app/src/test/`. Scope metadata: `app/src/main/resources/META-INF/xposed/scope.list`; also inspect `app/src/main/res/values/arrays.xml` when changing scope presentation or checking release parity.

## Native payload
`app/src/main/cpp/NativeRules/` builds `libhypertweak_native.so` from `app/CMakeLists.txt`; LSPosed injects it via `app/src/main/resources/META-INF/xposed/native_init.list` and calls the upstream `miui_home_native_hook.cpp` entry. The launcher lifecycle, profile resolvers, AOT state hooks, remap repair, fork owner reset, and LSPlt backend are copied from the Apache-2.0 upstream implementation. `clear_button_rule.cpp` is the feature-specific target check and uses upstream's validated Dart image and action-down maintenance paths; its load callback consumes the supplied handle and never calls `dlopen`. `liblsplt.so` comes from the vendored AAR in `app/libs/`. The payload is fail-closed and runs its native launcher hooks only in the upstream HYOS launcher process family.

## Task references

Native AOT identity: `_kDartSnapshotBuildId` points to the 32-byte GNU note (16-byte header followed by the 16-byte ID), not the ID alone. The upstream Dart resolver validates this note and the mapped executable ranges before feature code reads the target.

Dart feature targets are resolved **structurally**, not from a per-build table: `dart_image.cpp` (ELF validation, AArch64 decoding, instruction-kind classification, masked patterns) plus `dart_targets.cpp` (one spec per site, tiered evidence) re-derive each address from instruction shape and call-graph relationships, so a launcher update does not need a new table row. `dart_rule_support.cpp` is the Android-only bridge that turns a spec into runtime addresses for a rule; the two rules carry no build id and no RVA. Any site that is not uniquely resolvable is rejected rather than guessed.

Authoring a new hook:
- Declare a `TargetSpec` in `dart_targets.cpp` and a rule that consumes it. Nothing in `miui_home_native_hook.cpp` changes.
- A byte pattern is only usable when it is both **stable across builds and unique**. Verify both: many Dart prologues/epilogues are stock shared code, and a masked pattern is strictly more general than the bytes it masks. A site whose stable prefix is short, or whose match count is large, needs structural evidence instead.
- Use the host probe to develop and regression-test offline; it shares `dart_image.cpp`/`dart_targets.cpp` with the payload, so its verdict is the payload's verdict:
  `clang++ -std=c++17 -O2 -Wall -Wextra -Werror -o /tmp/dart_probe dart_probe.cpp dart_image.cpp dart_targets.cpp`
  then `dart_probe <libapp.so>` (resolve + report), `--expect site=0xADDR` (assert a known RVA), `--mutate 0xADDR` (fail-closed negative control), `--classes 0xADDR` (dump the instruction-class sequence used for structural specs).
- `dart_runtime_resolver.cpp` and `runtime_profile_resolver.cpp` stay byte-identical to upstream; the toolkit deliberately duplicates a few primitives rather than refactoring them.
- Validation, reverse engineering, release/device delivery, and Git: [.github/AGENT_WORKFLOWS.md](.github/AGENT_WORKFLOWS.md), relevant section only.
- CI behavior: `.github/workflows/ci.yml` and `.github/workflows/release.yml`; published release-note format: [.github/release-notes/README.md](.github/release-notes/README.md).
- Local feature mechanisms, baseline hashes, and regressions: `docs/FEATURE_DETAIL.md`, searched by feature or symbol. `docs/` is git-ignored and may be absent in another checkout; its dated findings are not automatically the current device baseline.
