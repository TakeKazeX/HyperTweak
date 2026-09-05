# HyperTweak Agent Rules

`AGENTS.md` contains operating rules. `CLAUDE.md` contains project facts;
`docs/FEATURE_DETAIL.md` contains detailed feature research. If documentation
conflicts with source, trust the repository and update the docs.

## Before Editing
- Inspect `git status`, relevant implementation, tests, and build files.
- Preserve user changes; never use destructive commands such as `git reset --hard` or `git checkout --`.
- Keep changes scoped and reuse existing architecture and helpers.
- Before hook changes, read the relevant feature detail and verify the target build's class, method, process, and final effect.

## Hook Safety
- Isolate exceptions at process, reflection, and system-callback boundaries.
- Use null-safe casts at platform boundaries and cache reflection on hot paths.
- Read runtime state through `Preferences`, including cross-process state.
- Attach hooks to every manifest-declared process that executes the code.
- Resolve obfuscated targets by verified signatures/behaviour, not names alone; re-check after an OTA.

## Reverse Engineering
Keep extracted APK/JARs and decompiler output outside the repository at `/Users/ink/developer/reverse`. Store derived output in `reverse/cache/<component>-<first-16-SHA-256>/` with applicable `input/`, `jadx/`, `apktool/`, and `SHA256SUMS`; verify the complete source hash before reuse. Use JADX for navigation and APKTool/smali for manifests, resources, and uncertain behaviour.

## Validation and Delivery
- Start with narrow checks, then for hook changes run `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` and `./gradlew :app:assembleRelease`; add lint for UI/lint-sensitive changes.
- Test the release APK when reflection or R8 can affect class names and verify required constants survive shrinking.
- Install only for requested/device delivery: `adb install -r app/build/outputs/apk/release/<apk>`, then force-stop affected processes.
- The user performs UI testing. Do not capture screenshots or automate device UI; agent checks are builds, tests, logs, and state queries.

## UI, Comments, Git
Keep the existing Compose/Miuix language and account for narrow screens, large font scales, scrolling, loading/empty states, and state retention. Add comments only for non-obvious logic. Before committing, inspect staged files and use a Conventional Commit prefix (`feat:`, `fix:`, `docs:`, etc.).
