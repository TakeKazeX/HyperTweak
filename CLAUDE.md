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

Settings navigation uses Miuix `miuix-nav` in `ui/navigation/HyperTweakNavContainer.kt`.
`NavController` centralizes push/pop while `Route.saveKey` keeps the restored stack stable across
updates; Miuix owns gesture cancellation,
settling and entry state. `ui/effect/ScalePredictiveBack.kt` supplies the optional scale transition.
The app's global blur preference is `Preferences.KEY_APP_BLUR_MODE` (0 off, 1 uniform, 2 progressive).
`ui/effect/AppScaffold.kt` captures each page's content for its top bar; About supplies its raw
animated backdrop to avoid sampling the already blurred project card. Its expanded top bar stays
transparent, enabling global top-bar blur only after the logo/title has fully collapsed. Top bars
respect explicit transparent colors in the unblurred state. `AppBlur.kt` applies the
same policy to existing card and navigation-bar blur. Progressive mode uses Miuix's native
`progressiveTextureBlur` / `progressiveBlur` pipeline, with top and bottom gradients as appropriate.
The shared `CenteredDialog` delegates to Miuix's centered `WindowDialog`. MainPager uses the
cross-axis Pager gesture driver and spring tab navigation; the liquid indicator follows Pager
selection without redispatching tab clicks. Liquid rendering and its six auxiliary files follow
Miuix v0.9.4 at `39c40f99844227b853f0049a0933b1f3ae6c00ba`: auxiliary algorithms and indicator
input/animation are copied unchanged apart from package names. The complete upstream MainPagerState
also owns tab selection, page navigation and root-page back, with the upstream syncPage effect.
Local renderer adaptations are theme wiring and the centered 80%-width pill (maximum constraint
340dp). Global blur Off stops backdrop capture and selects upstream's solid fallback; both enabled
modes use the fixed upstream uniform glass chain. About's background animation pauses when transparent,
covered or when its lifecycle is not resumed.
MainActivity explicitly provides Activity, ActivityResultRegistry and back-dispatch owners before
replacing LocalContext with the localized ConfigurationContext. Navigation entries and separate
dialog windows need these owners for document/image pickers and system back dispatch.
RestartScopeDialog follows the native WideWindowDialog example (560dp landscape maximum):
landscape actions sit to the right of the list; portrait limits the list to half the window height
and keeps actions below it. HotReloadDialog scrolls details within native WindowDialog bounds
while keeping footer actions outside the scroll area. Scope choices are saveable.

## Logging

`util/DebugLog.kt` owns severity, bounded dispatch and counted repeat suppression. `LogRecord`/`LogCodec` is the shared wire format; errors retain stack traces and expected preference/process skips use DEBUG. Detailed hook registration, target resolution and high-frequency rendering diagnostics belong at DEBUG; INFO describes meaningful runtime state changes. Do not classify severity from words inside a message, and do not silently swallow failures at shared resolver, persistence or system-callback boundaries.

App records are stored locally through `Preferences`; injected processes forward to the LSPosed logger, without attempting to write hook-side remote preferences. `LogRepository` merges app records with `LsposedLogReader` records selected by exact module identity. The log page refreshes on entry/service rebinding or an explicit tap, displays source/process/PID, and exports the same snapshot. Reading LSPosed files requires root permission granted to HyperTweak; denial, missing files, timeout and read failure remain visible while app logs are still available. Reads are limited to the four newest active module log files, 2 MiB per file and 2,000 module entries. It does not modify framework files or collect other modules into the snapshot. A single global log level applies to the app and every injected process. Session rotation/clearing only clears app records. The diagnostic provider uses the same repository and retains its own/root/shell caller restriction.

## Host readiness and semantic repair

Host preference caches are optional storage capabilities. `Preferences.initLocalCache` publishes a usable context/cache pair only after checking the context's data directory and opening its preferences; a system/provider context without application storage continues to use the remote configuration and does not abort package-ready dispatch.

`SystemServerReadinessHooker` observes the framework boot-phase boundary. Assistant/gesture settings recovery probes live content/role services, binds observers before alignment, uses bounded polling windows and rearms pending work on lifecycle events. `SettingBackupTransaction` requires acknowledged backup/live writes and retains originals after failed restoration. `NativeRules` binds JNI only for the exact launcher owner; status inspection never loads a library, and a failed load is not repeatedly retried in an unrelated process.

Native settings have two event paths: framework remote preference callbacks and authenticated app-to-SystemUI notifications after acknowledged commits. Native setters serialize a complete native subset from the last acknowledgement; rejected editor shadow values cannot contaminate a later commit. Successful native commits reconcile the app-local mirror only when no newer edit is queued. Failed reset or backup restoration replaces the entire map captured on the serialized writer so newly introduced keys cannot survive rollback. Requests use the app acknowledgement ledger, not a possibly uncommitted mutable editor map. `native_rules_revision` is runtime bookkeeping, excluded from portable backups; reset epochs supersede older commits. `NativeSnapshotLedger` rejects stale/conflicting snapshots and carries only bootstrap primitive values across classloader replacement. SystemUI attaches its receiver even while its remote channel is recovering, requests an app snapshot on readiness/boot/unlock and finishes publication after restoring package state. All transport replays/retries are finite; retired publishers close their receivers and queued work. Normal setting changes never invoke native activation or root.

The hot-reload dialog includes a separate native-update action requiring root and an up-to-date SystemUI Java target. `NativeUpgradeManager` compares the actual payload hashes in the signed mapped/installed APKs. HYOS may label its root spawner `usap64`; ownership is established from the launcher's parent, exact executable, uid and `/proc` start time, not its displayed name or a ROM table. A changed native image is activated by guarded spawner replacement following upstream `abe7d4d6f880422d7faef973bd9c90457d662495`. Verification checks the new process family, current APK inode mapping and the native `runtime_identity` record (version/pid/start time/checksum). This is a launcher-process refresh, not same-process `.so` unloading. The previous complete signed APK is retained for explicit PackageManager rollback with data preserved. Root IO, output sizes and activation waits are bounded; an interrupted activation is reported after reopening. Temporary mapped APK copies are removed on inspection failure, and successful process checks are not restored as current facts from the journal. Rollback always resolves the current spawner afresh and records failures. Code loading/initialization does not prove native feature UI acceptance.

Download detail hooks select a semantically evidenced renderer, derive the download-model argument position and bind the original URL field from the host clipboard consumer. Same-prototype helper methods and other URL-like fields do not count as evidence. Credential row actions match the real Boolean-returning native launch call. Google AIM constructor overrides copy the immutable `Chain.args` and pass replacements to `proceed(array)` exactly once.

`SecurityCenterPrivacyResolver` reuses the operand/call-graph index to recover privacy gates, native function-card builders and the beauty initializer/support path. No obfuscated owner/member names or ROM-version branches are stored. Missing optional capabilities remain independent; selecting an absent anti-peeping variant preserves the native gates/visibility. Beauty support overrides are scoped to the native initializer with nested/exception-safe thread ownership.

## Native payload
`app/src/main/cpp/NativeRules/` builds `libhypertweak_native.so` from `app/CMakeLists.txt`; LSPosed injects it via `app/src/main/resources/META-INF/xposed/native_init.list` and calls the upstream `miui_home_native_hook.cpp` entry. The launcher lifecycle, profile resolvers, AOT state hooks, remap repair, fork owner reset, and LSPlt backend are copied from the Apache-2.0 upstream implementation. `clear_button_rule.cpp`, `folder_columns_rule.cpp`, and `assistant_widget_rule.cpp` are the feature-specific rules; they resolve their targets through the structural registry (see below) and use upstream's validated Dart image and action-down maintenance paths. Their load callbacks consume the supplied handle and never call `dlopen`. The launcher-side rule switches normally arrive from SystemUI, which reads complete remote `Preferences` snapshots and publishes an explicit `com.android.systemui.fsgesture` intent to `com.miui.home` with shared sender identity. `NativeRuleStatePublisher` performs finite replays at startup/settings changes/boot/unlock and closes its receiver and queued tasks on hot reload. The independent receiver-dispatch PLT hook captures and authenticates the actual SystemUI package and uid, schema and complete primitive payload before forwarding the borrowed Intent. After the original receiver returns it accepts the monotonic revision and applies cached rules without reading the consumed Intent. It preserves upstream's inline receiver and private-send hook ownership. Launcher JNI remains a fallback: the native HYOS launcher does not reliably execute the Java settings route. The switches are `hide_recents_clear`, `opened_folder_columns`, `contextual_search_long_press`, and `allow_android_widgets_to_assistant`. Unavailable or malformed remote Preferences never publish defaults. `native_rule_runtime.cpp` saves accepted snapshots in launcher-owned device-encrypted `hypertweak-native` storage and restores them without Java initialization. One eventfd worker prepares targets; input/receiver/load boundaries consume prepared results. A thread-local internal-library-query guard and generation gate prevent LSPosed's `RTLD_NOLOAD` callback from requeueing the worker; completed generations remain idle and failures retry only on bounded external events. `dart_resolution_cache.cpp` persists successful relative offsets keyed by Dart build ID, module version, schema and site contracts, with executable-range and exact 32-byte evidence checks before reuse. Failed results remain process-local; configuration/fork/identity changes permit fresh preparation. The opened grid reads a late-static column cache rather than recomputing the getter on each open. A separately resolved `onFolderOpen` Dart shim synchronizes that cache on the owning Dart thread before layout, using the field displacement decoded from the unique width-calculation reader. It captures and restores the native SMI on disable, leaves sentinels/objects untouched, and retains a passive open boundary after removing grid/preview overrides. It preserves the native frame, arguments and NZCV without entering a C++ or Dart trampoline. Device testing on 2026-10-03 confirmed live clear-button changes, idle preparation while dozing, and user-visible 4/5/native column changes on reopening, with folder close/Home return and preview intact. `liblsplt.so` comes from the vendored AAR in `app/libs/`. The payload is fail-closed and runs its native launcher hooks only in the upstream HYOS launcher process family.

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

The OS4 Android-widget rule resolves a unique eligibility/serializer family from the current Dart image, deriving the provider flag displacement and AppWidget CID. Its assembly replays the stolen instructions and bypasses the MIUI identity and size eligibility rejection only for ordinary AppWidgets of that CID. It derives and verifies the native success continuation; model dimensions/identity, Bundle metadata, placement, and drop acknowledgement remain stock. MIUI providers and other card types keep their size policy. It uses the same preparation/cache/settings/remap lifecycle and restores its owned hook when disabled. Test the structural contract with `assistant_widget_target_test.cpp` and the actual assembly offline with `run_assistant_widget_abi_test.py` (Unicorn + pyelftools, Android NDK clang). Current 7722 static/build verification is not manual drag acceptance.

The PA main-process `AndroidWidgetPickerHooker` resolves the existing native classic-widget footer lifecycle, navigator and source. Catalog gates resolve independently, including Kotlin accessors; chooser reflection is resolved lazily and cannot prevent entry registration. Assistant
source gets the native entry and a host Miuix provider chooser; desktop navigation
keeps its stock route. WidgetContainer owns AppWidget binding, host views, placement
and persistence; configuration uses the native bridge Activity. The resolver caches
reflection for interaction paths and rejects missing/ambiguous contracts. Set
`PERSONAL_ASSISTANT_APK` to the verified APK to exercise the bytecode contract test.

PA native addition selects the unique abstract WidgetContainer operation by
signature and modifiers; default startAddAnimation has the same parameters and
must not participate in uniqueness. The shared selector is tested against JVM
reflection fixtures and the real target DEX. A resolver/dialog exception shows a
load failure; the host empty-provider message is reserved for an actual empty list.
