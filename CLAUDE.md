# CLAUDE.md — Project Instructions for HyperTweak

## Project Overview

HyperTweak is an Xposed module for Xiaomi HyperOS 3 / MIUI that provides system-level customizations not available through standard settings UI. Built with Jetpack Compose + Miuix UI, targeting the native libxposed API 101.

## Build & Test

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Compile check (faster than full build)
./gradlew compileDebugKotlin

# Lint
./gradlew lint
```

No test suite exists — verify changes by compiling.

## Architecture

```
app/src/main/kotlin/com/takekazex/hypertweak/
├── hook/
│   ├── HookEntry.kt              # Xposed module entry point
│   ├── Preferences.kt            # IPC SharedPreferences with local cache
│   ├── XposedServiceManager.kt   # LSPosed service lifecycle
│   ├── base/
│   │   ├── BaseHooker.kt         # StaticHooker / DynamicHooker base
│   │   ├── DexKitManager.kt      # DexKit caching resolver
│   │   └── ModuleContext.kt       # Per-process hook metadata
│   └── rules/
│       ├── module/               # Self-hooks (settings injection, status)
│       ├── slider/               # Brightness/volume slider hooks
│       ├── system/               # system_server hooks (GMS, Passkey)
│       └── systemui/             # SystemUI hooks (AOD, fingerprint, navbar)
├── ui/
│   ├── page/                     # Compose screens
│   ├── effect/                   # Background effects, shaders
│   └── liquid/                   # iOS-like liquid glass nav bar
└── util/                         # Locale, restart helpers
```

## Conventions

- **Commit messages**: Conventional commits (`fix:`, `feat:`, `perf:`, `refactor:`, `chore:`)
- **Hook safety**: Always wrap hook callbacks in `runCatching`. Use `as?` not `as` or `!!`.
- **Reflection caching**: Never call `getDeclaredField`/`getMethod` in hot paths. Cache with `@Volatile` fields or `ConcurrentHashMap`.
- **Preferences**: Use `Preferences.getBoolean()`/`putBoolean()` — never direct `SharedPreferences`.
- **No comments**: Do not add code comments unless explicitly asked.
- **Version**: Defined in `app/build.gradle.kts` as `baseVersion`. README uses `<version>` placeholder.

## Scope & Target Processes

Defined in `app/src/main/resources/META-INF/xposed/scope.list`:
- `system` (system_server)
- `com.android.systemui`
- `com.android.settings`
- `com.miui.aod`
- `com.miui.securitycenter`
- `com.xiaomi.scanner`
- `com.takekazex.hypertweak` (self)
