#!/usr/bin/env python3
"""Guard callback settings snapshots and the single framework View router.

IconTunerOptions is the explicit legacy/default migration reader used to build an icon snapshot;
it has no hook callbacks. This is a source guard, not a transitive Kotlin call-graph proof.
"""
from pathlib import Path
import re
import sys
from check_architecture import NON_CODE

ROOT = Path(__file__).resolve().parents[2]
# Any new Preferences API is rejected by default, including function references and properties.
READ = re.compile(r'\bPreferences\s*(?:\.|::)\s*(\w+)')
ALLOWED_OPERATIONS = frozenset({
    "flush", "putStringSynchronous", "initLocalCache", "invalidateRuntimeReadCache",
    "isInitialized",
})
# The Preferences object is the storage boundary, frozen at its current surface. A feature reads
# with getBoolean/getInt(Key, default) inside hookSetting, or through its own reader next to the
# feature; it never adds an accessor here. Removing a member must also remove it from this list.
PREFERENCES = ROOT / 'app/src/main/kotlin/com/takekazex/hypertweak/hook/Preferences.kt'
PREFERENCES_API = frozenset({
    "appLogs", "appendDebugLog", "appendDebugLogs", "backGestureBoolean", "backGestureEnabled",
    "backGesturePreferences", "batteryAutoPowerSaveThreshold", "cameraLegendaryMomentMode",
    "clearAllSettings", "clearDebugLog", "commitDesktopGrid", "commitDesktopIconPercent", "contains",
    "desktopGrid", "desktopIconPercent", "exportSettings", "flush", "getBoolean", "getDebugLog", "getFloat",
    "getInt", "getString", "getStringSet", "hasRemoteConfig", "hideFingerprintAodEnabled",
    "hideFingerprintAppAuthEnabled", "hideFingerprintLockscreenEnabled", "iconConfig", "init",
    "initLocalCache", "inspectDebugSettings", "invalidateRuntimeReadCache", "isDebugUserSetting",
    "isInitialized", "isRemoteBackendUnavailable", "keepHapticFeedbackInPowerSave",
    "keepRefreshRateInPowerSave", "keepSystemSoundsInPowerSave", "keepWakeupGesturesInPowerSave", "logLevel",
    "migrateLegacyDockBackground", "nativePickerEnabled", "nativeRuleSnapshot",
    "noteRemoteBackendUnavailable", "openedFolderColumns", "patchDebugSettings", "powerButtonAction",
    "putBackGestureAppearance", "putBoolean", "putFloat", "putInt", "putString", "putStringSet",
    "putStringSynchronous", "recoverUnpublishedPowerSaveSelection",
    "recoverUnpublishedSavedNetworksPosition", "restoreSettings", "rotateLogSessionIfNeeded",
    "runtimePreferenceSource", "setCameraLegendaryMomentMode", "setPowerButtonAction", "setPowerSaveFeature",
    "settingsEpochForUi", "upstreamBackGestureOwnsInput", "useLocalBackend",
})
MEMBER = re.compile(r'^    (?!private\b)(?:@\w+(?:\([^)]*\))?\s+)*'
                    r'(?:(?:internal|public|override|inline|lateinit|const|@Volatile)\s+)*'
                    r'(?:fun|val|var)\s+(?:<[^>]+>\s*)?(\w+)', re.M)
VIEW = re.compile(r'(?:require\w*Method|find\w*Method)\s*\(\s*View\s*::\s*class\s*\.\s*java\s*,\s*'
                  r'"set(?:Visibility|Alpha)"')


def violations(source, migration_reader=False):
    code = NON_CODE.sub(lambda m: ' ' * len(m[0]), source)
    # A settings source is legal only inside a declared capture lambda. Preserve offsets.
    masked = list(code)
    for match in re.finditer(r'\bhookSetting\b', code):
        end = match.end()
        while end < len(code) and code[end].isspace():
            end += 1
        if end < len(code) and code[end] == '(':
            depth, end = 1, end + 1
            while depth and end < len(code):
                depth += (code[end] == '(') - (code[end] == ')')
                end += 1
        while end < len(code) and code[end].isspace():
            end += 1
        if end >= len(code) or code[end] != '{':
            continue
        body_start = end
        depth, end = 1, end + 1
        while depth and end < len(code):
            depth += (code[end] == '{') - (code[end] == '}')
            end += 1
        masked[body_start:end] = ' ' * (end - body_start)
    remaining = ''.join(masked)
    errors = [] if migration_reader else [(source.count('\n', 0, m.start()) + 1,
                                          'settings read outside a published hookSetting snapshot')
                                         for m in READ.finditer(remaining) if m[1] not in ALLOWED_OPERATIONS]
    # Use original strings for framework target detection; comments cannot install a hook.
    for match in VIEW.finditer(source):
        if code[match.start():match.start() + 5].strip():
            errors.append((source.count('\n', 0, match.start()) + 1, 'framework View setter must use ViewVisibilityRouter'))
    return errors


def preferences_api_violations(source, allowed=PREFERENCES_API):
    members = set(MEMBER.findall(NON_CODE.sub(lambda m: ' ' * len(m[0]), source)))
    return ([f'new Preferences member `{name}`: read with getBoolean/getInt(Key, default) inside hookSetting, '
             f'or add a reader next to the feature' for name in sorted(members - allowed)] +
            [f'PREFERENCES_API lists removed member `{name}`; delete it from the list'
             for name in sorted(allowed - members)])


def main():
    failures = [f'{PREFERENCES.relative_to(ROOT)}: {message}'
                for message in preferences_api_violations(PREFERENCES.read_text())]
    base = ROOT / 'app/src/main/kotlin/com/takekazex/hypertweak/hook/rules'
    for path in sorted(base.rglob('*.kt')):
        failures.extend(f'{path.relative_to(ROOT)}:{line}: {message}' for line, message in
                        violations(path.read_text(), path.name == 'IconTunerOptions.kt'))
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        return 1
    print('Hook callback snapshots and framework View routing passed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
