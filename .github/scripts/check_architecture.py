#!/usr/bin/env python3
"""Guard source-level layer boundaries, including imports and fully qualified references."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = {
    "ui": {"hook"},
    "util": {"hook", "ui", "app"},
    "settings": {"hook", "ui", "util", "app", "presentation"},
    "presentation": {"hook", "ui", "app"},
    "hook": {"ui", "app"},
}
# Ignore comments and literals, including KDoc and Kotlin raw strings. This is a source guard,
# not a compiler dependency graph; reflection, generated code and runtime wiring need review.
NON_CODE = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
REFERENCE = re.compile(r'\bcom\s*\.\s*takekazex\s*\.\s*hypertweak\s*\.\s*(\w+)\b')
HOOK_REFERENCE = re.compile(
    r'\bcom\s*\.\s*takekazex\s*\.\s*hypertweak\s*\.\s*hook\s*\.\s*(\w+)(?:\s*\.\s*(\w+))?')

# Inside `hook`, shared infrastructure never reaches feature rules or the bootstrap plans that
# compose them, and the hook-package entry files reach features only through bootstrap.
HOOK_INFRASTRUCTURE = {"base", "runtime", "preferences", "nativebridge", "logging"}
# Feature families depend on `rules.shared` and on these deliberate collaborations only; any
# other cross-family use belongs in a shared capability or in bootstrap wiring.
FEATURE_COLLABORATIONS = {
    ("freeform", "aicr"),        # Copy Direct browser redirection inside freeform windows
    ("module", "settings"),      # module entry refreshes Google services settings
    ("system", "googleapp"),     # power-button Lens launch
    ("system", "thememanager"),  # system_server DRM revalidation
    ("systemui", "backgesture"), # gesture bar shares the authenticated Overview bridge
}

# Status-bar icon renderers reach each other only through IconHostPorts, wired in bootstrap.
# Each renderer object is owned by its own package (or, for stacked, its own file family).
ICON_RENDERERS = {
    "StackedRenderer": lambda parts: len(parts) == 1 and parts[0].startswith("Stacked"),
    "DuoRenderer": lambda parts: parts[0] == "duo",
    "CarrierRenderer": lambda parts: parts[0] == "carrier",
    "LeftRenderer": lambda parts: parts[0] == "left",
}
ICON_RENDERER_REFERENCE = re.compile(r'\b(' + "|".join(ICON_RENDERERS) + r')\b')
ICON_ROOT = ("rules", "systemui", "icon")
# (systemui subpackage, renderer) pairs allowed outside the icon tree.
RENDERER_COLLABORATIONS = {
    ("aod", "DuoRenderer"),  # AOD draws the same Duo view in its own surface
}


def renderer_violations(hook_parts, source):
    """`hook_parts` is a source path relative to `hook/`; bootstrap is the composition root."""
    if hook_parts[:1] == ("bootstrap",):
        return []
    in_icon = tuple(hook_parts[:3]) == ICON_ROOT and len(hook_parts) > 3
    in_systemui = tuple(hook_parts[:2]) == ICON_ROOT[:2] and len(hook_parts) > 2
    code = strip_non_code(source)
    found = []
    for match in ICON_RENDERER_REFERENCE.finditer(code):
        name = match[1]
        if in_icon and ICON_RENDERERS[name](hook_parts[3:]):
            continue
        if in_systemui and not in_icon and (hook_parts[2], name) in RENDERER_COLLABORATIONS:
            continue
        found.append((source.count("\n", 0, match.start()) + 1, name))
    return found


def violations(layer, source):
    code = strip_non_code(source)
    return [(source.count("\n", 0, match.start()) + 1, match[1])
            for match in REFERENCE.finditer(code) if match[1] in FORBIDDEN.get(layer, set())]


def strip_non_code(source):
    return NON_CODE.sub(lambda match: " " * len(match[0]), source)


def hook_unit(parts):
    """`parts` is a source path relative to `hook/`."""
    if len(parts) == 1:
        return "root"
    if parts[0] == "rules":
        return f"rules.{parts[1]}" if len(parts) > 2 else "rules"
    return parts[0]


def hook_target(first, second):
    if first[:1].isupper():
        return "root"
    if first == "rules":
        return f"rules.{second}" if second and second[:1].islower() else "rules"
    return first


def hook_violations(unit, source):
    code = strip_non_code(source)
    found = []
    for match in HOOK_REFERENCE.finditer(code):
        target = hook_target(match[1], match[2])
        if target == unit:
            continue
        feature_target = target == "rules" or target.startswith("rules.")
        if unit == "root" or unit in HOOK_INFRASTRUCTURE:
            forbidden = feature_target or (unit != "root" and target == "bootstrap")
        elif unit.startswith("rules."):
            feature = unit.split(".", 1)[1]
            forbidden = target == "bootstrap" or (
                feature_target and target != "rules.shared"
                and (feature, target.split(".", 1)[-1]) not in FEATURE_COLLABORATIONS)
        else:
            forbidden = False
        if forbidden:
            found.append((source.count("\n", 0, match.start()) + 1, target))
    return found


# `Executors.new*` wrappers register a JDK Cleaner action that references the executor and its
# ThreadFactory, and their default core threads never time out. One that is never shut down (for
# example created in an object initializer whose hooker never installs, or recreated by a late
# callback of a retiring generation) pins the whole module generation. Hook code uses
# HookExecutors / generationWorker, which register no Cleaner and whose threads exit when idle.
EXECUTOR_FACTORY = re.compile(r'\bExecutors\s*\.\s*new\w+')


# A generation's own kotlinx scheduler never retires its workers, which pin the generation, and
# terminating it makes every late resume throw on the host thread. Hook code uses HookDispatchers.IO.
GLOBAL_DISPATCHER = re.compile(r'\bDispatchers\s*\.\s*(?:IO|Default)\b')


def dispatcher_violations(source):
    code = strip_non_code(source)
    return [code.count("\n", 0, match.start()) + 1 for match in GLOBAL_DISPATCHER.finditer(code)]


def executor_violations(source):
    code = strip_non_code(source)
    return [code.count("\n", 0, match.start()) + 1 for match in EXECUTOR_FACTORY.finditer(code)]


# `firstOrNull` over declared members silently takes whichever match the runtime lists first.
# New host member selection goes through HostSelect (exactly one match, ambiguity logged).
# Remaining uses are hierarchy-walking/shared helpers or unverified host contracts; counts may only fall.
FIRST_MATCH = re.compile(r'declared(?:Methods|Fields|Constructors)\??\.(?:firstOrNull|first)\b')
FIRST_MATCH_ALLOWED = {
    "hook/rules/downloads/DownloadUiHooker.kt": 3,
    "hook/rules/mediaeditor/MediaEditorWatermarkHooker.kt": 2,
    "hook/rules/securitycenter/battery/PowerRankingHooker.kt": 1,
    "hook/rules/settings/ChannelKeyguardToggleHooker.kt": 1,
    "hook/rules/settings/NotificationBadgeSettingsHooker.kt": 1,
    "hook/rules/settings/NotificationMoreSettingsHooker.kt": 1,
    "hook/rules/system/ForceDarkAppListHooker.kt": 1,
    "hook/rules/system/PowerButtonCtsHooker.kt": 3,
    "hook/rules/systemui/aod/AodStatusIconHooker.kt": 1,
    "hook/rules/systemui/freeform/FreeformBlurTransitionHooker.kt": 3,
    "hook/rules/systemui/icon/CellularTypeIconHooker.kt": 1,
    "hook/rules/systemui/icon/HostIconBridge.kt": 1,
    "hook/rules/systemui/icon/IconSlotTintHooker.kt": 1,
    "hook/rules/systemui/icon/MobileSignalVisibility.kt": 1,
    "hook/rules/systemui/notification/NotificationFontWeightHooker.kt": 1,
    "hook/rules/systemui/slider/ControlCenterCornerHooker.kt": 1,
}


def first_match_violation(relative, source):
    count = len(FIRST_MATCH.findall(strip_non_code(source)))
    allowed = FIRST_MATCH_ALLOWED.get(relative, 0)
    return count if count > allowed else None


def main():
    failures = []
    for language in ("kotlin", "java"):
        base = ROOT / f"app/src/main/{language}/com/takekazex/hypertweak"
        for path in sorted(base.rglob("*")):
            if path.suffix not in (".kt", ".java"):
                continue
            layer = path.relative_to(base).parts[0]
            source = path.read_text()
            failures.extend(f"{path.relative_to(ROOT)}:{line}: forbidden {layer} -> {target}"
                            for line, target in violations(layer, source))
            if layer == "hook":
                relative = path.relative_to(base).as_posix()
                if (count := first_match_violation(relative, source)) is not None:
                    failures.append(f"{path.relative_to(ROOT)}: {count} declared-member firstOrNull/first "
                                    f"(allowed {FIRST_MATCH_ALLOWED.get(relative, 0)}); use HostSelect")
                unit = hook_unit(path.relative_to(base / "hook").parts)
                failures.extend(f"{path.relative_to(ROOT)}:{line}: forbidden hook.{unit} -> hook.{target}"
                                for line, target in hook_violations(unit, source))
                hook_parts = path.relative_to(base / "hook").parts
                failures.extend(f"{path.relative_to(ROOT)}:{line}: Executors factory in hook code; "
                                "use HookExecutors or generationWorker"
                                for line in executor_violations(source))
                failures.extend(f"{path.relative_to(ROOT)}:{line}: kotlinx global dispatcher in hook code; "
                                "use HookDispatchers.IO"
                                for line in dispatcher_violations(source))
                failures.extend(f"{path.relative_to(ROOT)}:{line}: icon renderer {name} used outside its owner; "
                                "go through IconHostPorts"
                                for line, name in renderer_violations(hook_parts, source))
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Architecture source boundaries passed (Kotlin and Java, including hook internals).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
