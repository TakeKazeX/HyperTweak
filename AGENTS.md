# HyperTweak Agent Rules

## Scope and completion
- Complete the requested work, applicable checks, fixes caused by the change, and directly affected documentation before reporting back. Reuse existing authorization for routine steps; honor explicit research-only or review-before-edit requests.
- Inspect `git status` before editing and preserve unrelated changes. Read the implementation and references needed for this task, not a fixed stack of documents. Reuse unchanged evidence within the task.
- Report the result, checks performed, and remaining limitations. If external input blocks one step, finish independent work and identify the specific blocker; do not claim unverified behavior passed.

## Read on demand
- Use [CLAUDE.md](CLAUDE.md) for project boundaries and source locations when unfamiliar with the affected area.
- For host signatures, process routing, compatibility, or known regressions, search the relevant section of local `docs/FEATURE_DETAIL.md`. It is optional, git-ignored research: do not read it wholesale or block solely because it is absent. Current source and verified target artifacts take precedence; update only directly affected notes.
- Use [task workflows](.github/AGENT_WORKFLOWS.md) for validation selection, reverse-engineering cache details, release preparation, device delivery, or Git cleanup. Load only the relevant section; historical plans are evidence, not standing instructions to repeat completed work.

## Hook and UI changes
- Before changing a host hook, establish the target build's class, signature, executing processes, and effect path. Reuse a verified matching baseline; recheck affected targets after an OTA or target APK change.
- Isolate exceptions at process, reflection, and system-callback boundaries; use null-safe platform casts and cache reflection on hot paths. Resolve obfuscated targets by signatures and behavior, not names alone.
- Read runtime and cross-process settings through `Preferences`; keep hook handles in the existing lifecycle layer. Cover every executing manifest-declared process and the applicable LSPosed scope.
- Preserve the Compose/Miuix design. For affected UI, account for narrow screens, large fonts, scrolling, applicable loading/empty states, and state retention.
- Keep extracted APK/JARs and decompiler output outside the repository under `/Users/ink/developer/reverse`; identify reused artifacts by complete SHA-256.

## Verification and delivery boundaries
- Match checks to changed behavior: documentation needs no Gradle build; logic needs relevant compilation/tests; UI-sensitive changes need lint; hook mechanics and R8-sensitive changes need a release build and targeted artifact checks. Use the workflow table for commands and wider checks.
- Once applicable checks pass, repeat or broaden them only for new changes, failures, or unresolved risks. Do not add tests that merely restate an implementation.
- Install and restart only when device delivery is requested. The user performs device UI testing; do not capture screenshots or automate device UI. Builds, installation, and `HOOK_OK` do not prove visible or audio behavior.
- On a commit request, selectively stage intended changes, inspect the staged diff, run whitespace checks, and use a Conventional Commit prefix. Report the commit and remaining status; push only when requested. Cleanup does not implicitly authorize deleting research, branches, or user changes.
