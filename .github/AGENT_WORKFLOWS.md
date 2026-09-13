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

Combine selected tasks in one invocation where useful; do not separately repeat compilation already covered by a selected task. Preserve the requested build channel. Use `BUILD_CHANNEL=stable` only for stable-channel builds, not every release-variant check.

Fix failures caused by the change and rerun affected checks. Report unrelated failures or unavailable dependencies accurately; they do not justify unrelated repairs or repeated identical attempts. A code-delivery task can finish with explicitly pending manual acceptance; a request to prove device behavior cannot be marked verified without that evidence.

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
- Keep local research in ignored `docs/` unless the user explicitly requests tracking it; do not force-add it as part of a general commit.
- Limit cleanup to requested items or disposable outputs created for this task. Do not infer deletion of pre-existing caches, research, branches, or uncommitted changes. Record any retained evidence needed for reproducibility before deleting task outputs.
