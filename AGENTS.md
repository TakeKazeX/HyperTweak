# HyperTweak agent rules

HyperTweak hooks closed-source Xiaomi system apps. These apps change with every OTA, and most failures only show up on a real device. The rules below follow from that.

## Working style
- Finish the task end to end: the change, the checks that fit it, fixes for anything the change broke, and the docs it makes stale. If the user asked for research only, or to review before you edit, do only that.
- Check `git status` first and leave unrelated changes alone.
- Read only what the task needs. [CLAUDE.md](CLAUDE.md) maps the project, and `.github/agent/` holds the details for each area. Current source and verified host artifacts outrank any written note, including these.
- In the final report, keep three things apart: what you verified, what you only built or compiled, and what still needs the user to check on the device. Never report behavior you did not observe as working.

## Hooking host code
- Before changing a hook, confirm four things on the actual target build: the class, the signature, the processes that run it, and the path to the visible effect. You can reuse a baseline verified for the same build. After an OTA or a host APK change, recheck the affected targets.
- Resolve targets structurally, from signatures, operand roles, call/data flow and decoded field/CID operands. Never resolve them from names alone, from version/build-ID tables, or from fixed RVAs. This applies to DEX and native code alike. Build IDs and hashes only identify artifacts and key caches. If a contract is missing or ambiguous, reject it and leave native behavior untouched; never guess.
- Catch exceptions at process, reflection and system-callback boundaries. Use null-safe platform casts, and cache reflection on hot paths through `HostAccess`.
- Read settings through `Preferences`; UI code goes through `AppSettingsStore`. Keep hook handles in the `BaseHooker` lifecycle. Cover every process that runs the code, and the matching LSPosed scope.
- Every feature must handle live setting changes through the shared runtime-settings dispatcher. It must also survive hot reload and APK upgrade: refresh living host instances, restore state across generations, and recover hosts that were upgraded from a build that saved no state for the feature. Check enable, disable, reset and upgrade. Write down any limitation of the host.
- Do not modify LSPosed configuration, scopes or the LSPosed service while debugging.

## UI
- Follow the host's Miuix/HyperOS design language and reuse Miuix components. Do not invent custom styles.
- Check narrow screens, large fonts, scrolling, loading/empty states and state retention for any UI you touch.

## Files and artifacts
- Extracted APKs/JARs and decompiler output go outside the repo, in the `reverse/` directory next to the main checkout (`../reverse` from the repository root). Identify them by their full SHA-256.
- `docs/` holds local, git-ignored research. Agent-facing reference material lives in `.github/agent/`. When you add or change a mechanism, update the section of the matching reference file. Keep `CLAUDE.md` an index: it loads into every session, so detail added there costs context on every task.

## Verification and delivery
- Pick checks by what changed. The table is in [workflows](.github/agent/workflows.md#choose-validation-by-changed-behavior). Documentation-only changes need no Gradle run. Do not repeat checks that already passed unless the code changed again. Do not write tests that only restate the implementation.
- Standing authorization: after implementation changes pass their checks, install the APK on the connected device. Restart or refresh the affected processes or native payload, then read the relevant logs. An explicit no-install, research-only or review-first instruction overrides this.
- The user does the device UI testing. Do not take screenshots or automate the device UI. A successful build, a clean install, quiet logs or `HOOK_OK` do not prove visible or audible behavior.

## Git
- Commit only when asked. Fixes are committed after the user confirms on the device that they work.
- Stage only changes that belong to the commit, review the staged diff, run `git diff --cached --check`, and write an English Conventional Commit message. Push only when asked.
- Cleanup never implies deleting research, caches, branches or user changes.
