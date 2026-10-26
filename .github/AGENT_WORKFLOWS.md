# HyperTweak task workflows

Read only the section needed for the current task. These workflows supplement [AGENTS.md](../AGENTS.md); they are not a sequential checklist for every change.

## Choose validation by changed behavior

| Change or request | Applicable checks |
| --- | --- |
| Read-only research or review | Source-backed findings and limitations; no build. |
| Documentation or comments only | Inspect content, links, and diff; no Gradle tasks. |
| Ordinary Kotlin/Java logic | Relevant compilation and existing focused JVM tests; add regression tests for meaningful behavior changes. Broaden tests for shared infrastructure. |
| UI or resources | Relevant compilation/resource build; `:app:lintDebug` for Compose/UI-sensitive changes; relevant tests for behavior changes. |
| Hook registration, reflection, host/process routing, lifecycle, or compatibility | Relevant JVM tests plus `:app:assembleRelease`; inspect affected DEX symbols, retained constants, or keep rules when shrinking can change behavior. |
| Dependencies, build configuration, or R8 rules | `:app:testDebugUnitTest :app:lintDebug :app:assembleRelease`, plus checks specific to the changed build mechanism. |
| Formal release readiness | Full applicable test/lint/release checks plus the release section below. |

Run tasks with `./gradlew`. For Kotlin-only compilation use `:app:compileDebugKotlin`; include `:app:compileDebugJavaWithJavac` when Java is affected, or use `:app:assembleDebug` when a complete debug artifact is needed. Filter JVM tests with `:app:testDebugUnitTest --tests '<test-class-or-pattern>'` when the affected behavior is isolated.

Architecture changes also run `python3 .github/scripts/check_architecture.py` and
`python3 -m unittest discover -s .github/scripts -p 'test_*.py'`. The guard covers
Kotlin/Java imports and fully qualified source references, not reflection or runtime wiring.
Standalone native compiler commands must include the NativeRules root plus its `bridge`,
`resolver`, `rules`, `runtime`, `support` and `transport` subdirectories; Python runners
share these explicit flags via `app/src/test/cpp/native_test_paths.py`.
Portable native ownership/IPC/transport checks run through
`python3 app/src/test/cpp/run_portable_native_tests.py` (ASan/UBSan, no target artifacts).
Linux CI adds `--include-linux-worker` for eventfd/fork/remap tests. On macOS use
the dedicated Android compiler/Bionic worker command below when device checks are requested.
Host APK/ELF and assembly ABI checks remain separately selected; this suite does not replace them.

API-102 retirement has a commit boundary: state capture and framework saved-state publication
must succeed before destructive cleanup. Preflight failures reject replacement; after cleanup
starts, run every retirement step and accept the next generation even if one step fails.
Do not report a partially retired owner as a healthy old generation. Settings queue deadlines
may cancel unstarted bulk/Dock work; started commits must return their actual receipt before
the caller updates local mirrors or reports success/failure. These waits belong on IO dispatchers.

Configuration Cache is enabled with `problems=fail`; fix incompatibilities rather than
switching artifact builds to warn mode. AGP is pinned to `9.5.0-rc01` because it removes
the internal `Configuration.setVisible` calls deprecated by Gradle 9.8; `9.4.1` still
emits them. When changing the build toolchain, run the applicable checks with
`--warning-mode all`, then repeat the same task set to verify Configuration Cache reuse.

For stacked cellular signal artwork, run the `SignalSvgStyle`, `PreferencesSignalSvgStyle`,
`SignalAssetLoadState`, `SignalArtworkGeometry`, `SignalWeight`, `MobileTypePolicy` and
`IconTunerPreferenceState` JVM regressions plus the shared settings/hot-reload tests.
UI/lifecycle changes also require lint and a release build. Inspect all APK DEX files for the
new `icon_stacked_signal_style` key and the retained old stacked compatibility key; verify
five bundled SVGs and SHA256SUMS, without iOS26 or the removed internal type-badge settings.
The geometry test reads real SVG paths and verifies stroke-expanded per-SIM crops; it does
not prove visible handoff quality. Device acceptance is user-driven: both styles, control-center
handoff, enable/disable, reset/restore and upgrading existing hosts. Do not automate device UI
or equate asset-loading/HOOK_OK logs with visible correctness.

For native photo/file picker routing, run `PickerReroute*` and runtime-settings JVM
regressions with `PICKER_APK`, `PICKER_SERVICES` and `PICKER_MIUI_SERVICES` set to the
verified Photopicker APK, services.jar and miui-services.jar. The host tests verify the
private vendor helper and its caller, the PM nine-argument overload, and Hyper's browse
component chain. After the release build, inspect retained host class/method strings and
both scope declarations. User checks cover new media/file requests, Hyper browse, disable,
reset and module upgrade; existing picker windows must be reopened.

For Copy Direct browser routing, set `AICR_APK` to the verified installed AI engine APK when
running the AICR tests. `AicrPanelDispatchHostContractTest` verifies the unique native AI Panel
handler reads ToolRequest input before serializing/reporting its EventManager event. An unused
service parser alone is not evidence of the production dispatch path. The same host test checks
the native browser allowlist renderer, model and persistent state loader. After `:app:assembleRelease`,
run `python3 app/src/test/android/aicr/run_panel_dispatch_probe.py <local-AICR.apk>
--module-apk <release-HyperTweak.apk> --browsers
<current-default-package> [other-installed-browser-packages] --output
/Users/ink/developer/reverse/research/<task>/probe` to exercise production rewriting with the
host's actual ToolRequest/Kotlin JSON classes and installed application labels in an isolated
ART process. The Release APK uses a separate loader with a boot parent: sharing the host
parent can collide with independently obfuscated class names. It also reports the installed
browser catalog. The same probe instantiates the installed host's unshown BubbleView,
executes its actual binder, and measures/draws the title into an offscreen Bitmap. Check
colored pixels, exact span boundaries, preserved widths at 15/24/32 sp, narrow ellipsis,
repeat-bind idempotence, disable cleanup and existing-view recovery. It initializes the
system font map explicitly because app_process does not run normal application bootstrap.
No windows are added and no device screenshots are captured. It checks APK identity and starts no activities; it does not install/restart the
module or establish visible gradient/color acceptance.

For Dart state-observer assembly changes, execute the production observer offline
with Unicorn and pyelftools using the installed Android NDK compiler:

```sh
python3 app/src/test/cpp/run_dart_state_abi_test.py /absolute/path/to/NDK-clang
```

This checks independent Overview exit publication, owner fencing, exact source and
boolean validation, retirement, and preservation of registers, SIMD, SP and NZCV.
Actual recent-task exit and next-gesture behavior remain manual device acceptance.

For recent-task card haptics, compile `recents_haptic_policy_test.cpp` and
`recents_haptic_rule_test.cpp` (the latter links `dart_image.cpp` and
`dart_targets.cpp`) with the explicit NativeRules include directories from the rule above and
ASan/UBSan. Pass
an externally stored, verified `libapp.so` to the rule test. It exercises the
production resolver/decoder, duplicate and corrupt targets, dynamic CIDs,
notification samples, disable/re-enable and patch ownership/remap handling.
Run `run_recents_haptic_abi_test.py <NDK-clang>` with Unicorn and pyelftools for
the production observer's enabled/disabled state and complete register/FP state.
Actual card-switch timing and haptic strength remain manual device acceptance.

For stacked-folder preview changes, compile `folder_stack_rule_test.cpp` with
`dart_image.cpp`, `dart_targets.cpp` and `dart_resolution_cache.cpp` under ASan/UBSan against the verified external
snapshot. It covers complete target contracts, corrupt/ambiguous discovery, dynamic
allocation CIDs, whole-function relocation, shifted strategy fields and inconsistent-field rejection, target-cache identity/evidence, existing-cache enable/disable, foreign geometry, fresh hosts, and
three-layer count/order mapping without changing the source application list,
all-stage rollback, callback lock contention, refresh generations, scaled singleton
slots, scheduler call relationships and patch ownership/remap. Execute
`run_folder_stack_abi_test.py <NDK-clang>` with Unicorn and pyelftools to verify
the production getter/count/order/frame/identity/completion shims, including compressed return pointers,
primitive count/index results, replayed frame stores, continuations, native/Dart
stacks, SIMD and condition/FP registers. Cover pending/equal epochs, disable
restoration, uninitialized model/DI, missing targets and a newer epoch arriving
during refresh; parent layout ID versus original monochrome ID, foreign-isolate
isolation, actual compressed-array stores and completion ordering. Verify the original frame is popped before the Dart tail transfer. A successful
lookup is not a frame repaint: preview refresh timing, opening/closing, clicks and
drag/drop remain manual device acceptance. Run `native_rule_settings_record_test.cpp`
under ASan/UBSan for persisted schemas 1–5, disabled defaults on migration and
invalid-field/checksum rejection.

For native launcher preparation/cache/remap changes, also run the host regression harness against the verified target `libapp.so`:

```sh
python3 app/src/test/cpp/run_dart_rule_cache_test.py /absolute/path/to/verified/libapp.so
```

It covers cache identity and corruption, relocation, readiness/input deferral, repeated application, remap recovery, preservation of another owner's patch, and the event-generation gate including self-generated library notifications. The executable and compiler output are temporary; target artifacts remain outside the repository.

For Android-widget-stack target or shim changes, compile `widget_stack_target_test.cpp`
with `dart_image.cpp` / `dart_targets.cpp` / `dart_resolution_cache.cpp` under ASan/UBSan
against the verified snapshot. It covers the paired drag/target gates, the shared
serializer agreement, shifted CID/field operands, span/capacity/serialization/branch
rejection, whole-family relocation, ambiguous duplicates and persistent-cache evidence.
Execute the production assembly offline with `run_widget_stack_abi_test.py <NDK-clang>`
(Unicorn + pyelftools): two gates x enable/disable/partial preparation x MIUI/native flag x
shifted field x compressed heap base x three NZCV states, asserting register, SIMD, SP, FPCR/FPSR
and object-memory preservation. `run_dart_rule_cache_test.py` additionally exercises its
preparation deferral, paired install, remap repair, foreign-patch rejection and passive disable.
Provider binding, RemoteViews rendering, card paging, drag/dissolve and restart recovery remain
manual device acceptance.

For assistant-widget target or shim changes, compile `assistant_widget_target_test.cpp`
with `dart_image.cpp` / `dart_targets.cpp` against the verified snapshot, and execute
the actual production assembly offline with `run_assistant_widget_abi_test.py <NDK-clang>`
(Unicorn and pyelftools). The tests cover shifted field/CID operands, mismatched
serialization, branch/size contracts, ambiguous candidates, native flag preservation,
Dart registers/SP and NZCV, ordinary-widget size bypass, native-success receiver restoration and unchanged MIUI/card paths. The shared cache harness includes this rule's disable,
remap and foreign-hook ownership checks. Actual widget binding, placement, RemoteViews
updates and restart restoration remain manual device acceptance.

For assistant catalog changes, set `PERSONAL_ASSISTANT_APK` to the verified
assistant APK when running `:app:testDebugUnitTest`; the target-bytecode test
checks the independent native footer lifecycle, both catalog producers, navigator, model, cell geometry and native Miuix chooser contracts. Native receiver dispatch changes also require the host
`native_rule_settings_dispatch_test.cpp` under ASan/UBSan, which covers an original
receiver consuming its Intent and ensures capture precedes forwarding.

For native back-gesture status query or receiver changes, run
`python3 app/src/test/cpp/run_backgesture_status_query_test.py`. It executes the
production capture and dispatch functions with a consuming OEM receiver under
ASan/UBSan: ordinary settings publications, authenticated queries, foreign/malformed
requests, unavailable APIs, owner retirement and original-callback failure. Capture
borrowed Intent data before forwarding and reply only after the original succeeds.

For native broadcast allocation/cleanup or Dart publication receipt/retry changes, run
`python3 app/src/test/cpp/run_broadcast_transport_test.py`. It compiles the actual
`broadcast_transport.cpp` and `dart_state_publisher.cpp` under ASan/UBSan with consuming
host setters, allocator failures, missing APIs, runtime/options failures, balanced cleanup,
Rust result-tag semantics, owner/generation fencing, failed-send retries and a blocked
publisher racing a newer observation. `run_owned_rstring_test.py` remains a compatibility
entry for the same production tests. Run the existing status-query, GOT-chain, owner and
IPC harnesses when changing their integration. State-observer changes also need the
production assembly ABI test above. Host stubs do not prove device broadcast delivery.

For changes to the actual eventfd worker or loader callbacks, also compile `app/src/test/cpp/native_rule_runtime_test.cpp` together with `native_rule_runtime.cpp` using the installed Android NDK `clang++`, `--target=aarch64-linux-android35`, `-std=c++17`, `-static-libstdc++`, the explicit NativeRules include directories from the rule above and `-Wl,--wrap=dlopen -Wl,--wrap=dlclose`. With device verification requested, run the resulting executable in `/data/local/tmp` and remove it afterward. This Bionic regression simulates LSPosed synchronous callback re-entry, rejects stale/conflicting revisions, and checks completed-worker idleness and updates arriving during preparation. A host-only scheduling test does not exercise Bionic TLS/loader behavior.

For Dart publication eventfd/fork or observer-remap retirement changes, run
`python3 app/src/test/cpp/run_dart_state_worker_test.py --android-compiler <NDK-clang++>`
when device verification is requested. It executes the production worker and retirement
fence on Bionic: idle sleep, failed-publication retry without another wake, owner changes
during drain, child fd closure/reset/restart, paired Overview fencing and rejection keeping
the gate closed. The runner removes its device executable. Linux can run the same harness
with ASan/UBSan by omitting the compiler; macOS lacks eventfd and requires the Android path.
Keep the broadcast publisher/transport, native rule runtime and cache/remap regressions above.

For folder Dart shim/register/stack changes, compile and execute `app/src/test/cpp/folder_columns_abi_test.cpp`, `folder_columns_abi_test.S` and the actual `folder_columns_hook.S` using the same Android NDK target and static runtime. This verifies the real assembly with synthetic Dart thread/static storage, including cache changes, native-default restoration, sentinels and receiver/argument/NZCV preservation. Host hook stubs cannot establish these ABI properties; actual folder open/close, Home return and preview acceptance remain manual.

Combine selected tasks in one invocation where useful; do not separately repeat compilation already covered by a selected task. Preserve the requested build channel. Use `BUILD_CHANNEL=stable` only for stable-channel builds, not every release-variant check.

Fix failures caused by the change and rerun affected checks. Report unrelated failures or unavailable dependencies accurately; they do not justify unrelated repairs or repeated identical attempts. A code-delivery task can finish with explicitly pending manual acceptance; a request to prove device behavior cannot be marked verified without that evidence.

For back-gesture IPC changes, run
`python3 app/src/test/cpp/run_backgesture_ipc_test.py` and
`python3 app/src/test/cpp/run_backgesture_owner_test.py`, plus
`python3 app/src/test/cpp/run_backgesture_broadcast_test.py` in addition to the focused
back-gesture JVM/host contracts and release build. This compiles the production
native marker/admission path under ASan/UBSan to reject foreign generations and
untrusted senders while retaining local generation fencing and feature ownership.
The owner harness executes the production library callback in both ownership
modes: upstream owns shared input/contextual/Dart-state hooks while independent
HyperTweak rules and their authenticated settings receiver continue to run.
The broadcast harness covers direct/upstream/unknown GOT owners, retaining the
upstream continuation, install serialization, concurrent mutation and rollback.
For broadcast-metadata resolver changes, also run `run_launcher_profile_target_test.py`
against the verified launcher ELF; its side-patched negative control must retain
read-only broadcast metadata while rejecting shared gesture target discovery.
Protocol namespace changes require both SystemUI Java and launcher native refresh
for device delivery; Java hot reload alone cannot update a mapped native payload.

## Reverse engineering

Use this section when inspecting host APK/JAR behavior or changing the target baseline.

- Root: `/Users/ink/developer/reverse`; derived cache: `/Users/ink/developer/reverse/cache/<component>-<first-16-SHA-256>/`.
- Keep applicable `input/`, `jadx/`, `apktool/`, and `SHA256SUMS` entries. Verify the full input SHA-256 before first reuse in a task; retain that evidence while the input remains unchanged. The short directory suffix alone is not verification.
- Reuse matching cached output. Use JADX for navigation and APKTool/smali for manifests, resources, or uncertain decompilation; run only the tools needed for the question.
- Select baselines by component and target build. Dated research is historical evidence; an OTA or changed target APK invalidates assumptions about affected signatures and behavior.

## Formal release preparation

Trigger: the user asks for release readiness, a stable release, or a release audit; merely compiling the release variant does not trigger this section.

- Inspect the current version, intended tag/channel, relevant local/remote tags, and current release workflow. Check version/tag collisions for a new release; distinguish that from verifying an already published artifact.
- Check scope registration and presentation parity, allowing documented dynamic/platform-specific differences. Review security-sensitive changes when present in the release scope.
- Run the full applicable validation set once. Inspect the actual APK identity/version and verify the expected signing certificate using `apksigner verify --print-certs`; signature validity alone is insufficient.
- Check the intended release diff and release notes. Local `docs/` files are not build or publication prerequisites. Preparing/auditing a release does not itself authorize publishing it.
- Report readiness, specific blockers, and pending manual acceptance separately. Do not require a connected device unless device verification is part of the request.

## Rolling CI publication

All build jobs use `.github/actions/setup-android` before configuring Gradle.
It accepts SDK licenses and installs the NDK and CMake versions pinned in
`app/build.gradle.kts`, including preview-channel packages. Keep toolchain
versions in that source file so CI and formal releases use the same versions.

`ci.yml` retains the existing `ci-latest` prerelease and fixed download URLs. After
the monotonicity guard permits publication and the anonymous asset self-check
passes, it moves the rolling `ci-latest` tag to the verified build commit and
saves the existing release's target to refresh GitHub's cached creation date.
This refreshes its commit/date metadata without recreating the release or
changing the stable `Latest` release. GitHub's default list is not guaranteed to
follow `created_at`; this is not a permanent pin. Skipped or failed publications
must not move the tag; formal version tags remain unchanged.
The formal release workflow fetches only `v*` tags for its changelog so a rolling
CI tag moving during the build cannot cause a tag-fetch rejection.

## Device delivery

Trigger: requested installation or device verification. Reuse that authorization throughout the delivery.

- Identify the actual APK, requested channel, signing compatibility, connected device, and affected packages. Do not guess an artifact filename or change the signing/channel to make installation succeed.
- Install with `adb install -r app/build/outputs/apk/release/<actual-apk>` for the requested release artifact.
- Restart affected app processes as appropriate. Services and system processes may require a different restart mechanism; do not infer permission to reboot the device from a routine app install request.
- Check relevant logs/state and distinguish code registration from enabled LSPosed scope. If device access is unavailable, finish artifact verification and report the remaining installation step.
- Record build, install/restart, and runtime evidence separately. The user performs UI interactions; do not capture device screenshots or automate UI. Never turn build success or `HOOK_OK` into a claim of visual/audio correctness.

## Commit and cleanup

Trigger: a commit or cleanup request, not every implementation task.

- Inspect the final diff and selectively stage intended files; run `git diff --check` and `git diff --cached --check`. Commit with a Conventional Commit prefix, then report the hash and remaining status. Do not rerun passed tests solely because a commit was requested.
- For an authorized PR merge, reuse matching local build/test/lint and device evidence. Do not wait for a duplicate remote build unless branch protection or the user requires it; inspect available CI status and report pending checks accurately. A real failure still needs investigation.
- Choose the target branch before organizing PR commits. When linear history is requested, rebase onto that baseline and use GitHub rebase merge; avoid creating a temporary merge graph and then rebuilding it. Preserve contributor authorship and unrelated history.
- Keep local research in ignored `docs/` unless the user explicitly requests tracking it; do not force-add it as part of a general commit.
- Limit cleanup to requested items or disposable outputs created for this task. Do not infer deletion of pre-existing caches, research, branches, or uncommitted changes. Record any retained evidence needed for reproducibility before deleting task outputs.
