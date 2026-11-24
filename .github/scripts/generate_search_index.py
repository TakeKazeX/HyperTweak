#!/usr/bin/env python3
"""Regenerate the settings search index from the Compose page sources; --check detects drift.

Every preference row whose title is a plain `stringResource(R.string.x)` becomes one entry. Its
destination is derived structurally: navigation `entry<Route.X>` blocks name the page composable,
and helper composables inherit the destination of the composables that call them. Sections come
from the enclosing `AppSection(title = ...)`. A composable that holds rows but resolves to no
destination, or to several, fails the run instead of being guessed; classify it below.
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
UI = ROOT / 'app/src/main/kotlin/com/takekazex/hypertweak/ui'
OUT = UI / 'search/SettingsSearchIndex.kt'

# Composables whose `title` is a searchable row. Dialog, page and section titles are not rows.
ROW_CALLEES = {
    'SwitchPreference', 'ArrowPreference', 'OverlayDropdownPreference', 'AppDropdownPreference',
    'BasicComponent', 'GlassSliderPreference', 'IconTextPreference', 'BottomTextSwitch',
    'ChargingFieldSwitch', 'TokenSizeRow', 'TypographySlider', 'DockSlider', 'CardSizeRow',
    'CornerRadiusRow',
}
PAGE_CALLEES = {'AppScrollPage', 'AppLazyPage', 'AppPage'}
SECTION_CALLEE = 'AppSection'

# The main pager tabs are not routes; results open Route.Main on that tab.
MAIN_TABS = {
    'HomeScreenContent': (0, 'main_tab_home'),
    'TweaksScreenContent': (1, 'main_tab_tweaks'),
    'SettingsScreenContent': (2, 'main_tab_settings'),
}
# Composables rendered on one tab of a tabbed page (the page's `when (selected…)` branches).
PAGE_TABS = {
    'CameraWatermark': {'CameraUnlockContent': 0, 'WatermarkUnlockContent': 1},
    'IconTuner': {
        'PositionSection': 0, 'LeftContainerSection': 0, 'SlotsSection': 0,
        'SignalStyleSection': 1, 'DuoSignalSection': 1, 'CellularIndicatorsSection': 1,
        'CellularTypeSection': 1, 'SimVisibilitySection': 1,
        'WifiSection': 2,
        'CarrierSection': 3, 'BatteryStyleSection': 3, 'RotationLockSection': 3,
        'AirplaneSection': 3, 'StatusBarMiscSection': 3, 'NotificationSection': 3,
    },
}
# Pages that are documents or live readouts rather than settings.
EXCLUDED_ROUTES = {'Search', 'Update', 'Credits', 'HtmlViewer', 'DeviceInfo', 'BatteryInfo', 'DebugLogs'}


def strip(text):
    """Blank comments and string-literal contents, keeping offsets, so braces/parens balance."""
    out, i, n = list(text), 0, len(text)
    def blank(a, b):
        for k in range(a, b):
            if out[k] != '\n': out[k] = ' '
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i); j = n if j < 0 else j; blank(i, j); i = j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2); j = n if j < 0 else j + 2; blank(i, j); i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3); j = n if j < 0 else j + 3; blank(i + 3, j - 3); i = j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            blank(i + 1, j); i = j + 1
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == '\\' else 1
            blank(i + 1, j); i = j + 1
        else:
            i += 1
    return ''.join(out)


def match_close(text, open_pos):
    pair = {'(': ')', '{': '}'}[text[open_pos]]
    depth = 0
    for k in range(open_pos, len(text)):
        if text[k] in '({': depth += 1
        elif text[k] in ')}':
            depth -= 1
            if depth == 0: return k
    raise ValueError('unbalanced')


def match_open(text, close_pos):
    depth = 0
    for k in range(close_pos, -1, -1):
        if text[k] in ')}': depth += 1
        elif text[k] in '({':
            depth -= 1
            if depth == 0: return k
    return -1


def callee_before(text, paren):
    m = re.search(r'([A-Za-z_][\w.]*)\s*$', text[:paren])
    return m[1].rsplit('.', 1)[-1] if m else None


def enclosing_calls(text, pos, lo):
    """Yield (callee, arg_open, arg_close) for calls enclosing pos, innermost first, staying above lo.

    A call encloses pos either through its argument list or through its trailing lambda.
    """
    depth = 0
    k = pos
    while k > lo:
        k -= 1
        c = text[k]
        if c in ')}': depth += 1
        elif c in '({':
            if depth:
                depth -= 1
                continue
            if c == '(':
                name = callee_before(text, k)
                if name: yield name, k, match_close(text, k)
            else:
                before = text[:k].rstrip()
                if before.endswith(')'):
                    o = match_open(text, len(before) - 1)
                    name = callee_before(text, o)
                    if name: yield name, o, len(before) - 1


def string_arg(text, a, b, name):
    """R.string id of `name = stringResource(R.string.x)` at the call's own argument level."""
    depth = 0
    k = a + 1
    while k < b:
        c = text[k]
        if c in '({': depth += 1
        elif c in ')}': depth -= 1
        elif depth == 0:
            m = re.compile(r'\b' + name + r'\s*=\s*').match(text, k)
            if m and (k == a + 1 or not (text[k - 1].isalnum() or text[k - 1] == '_')):
                v = re.compile(r'stringResource\(\s*(?:id\s*=\s*)?R\.string\.(\w+)\s*\)').match(text, m.end())
                return v[1] if v else None
        k += 1
    return None


def functions(text):
    starts = [(m.start(), m[1]) for m in re.finditer(r'^(?:@\w+\s+)*(?:(?:internal|private|public)\s+)?fun\s+(\w+)\s*\(', text, re.M)]
    for i, (s, name) in enumerate(starts):
        yield name, s, starts[i + 1][0] if i + 1 < len(starts) else len(text)


def load():
    funcs = {}  # name -> (file, start, end, stripped text)
    for p in sorted((UI / 'page').rglob('*.kt')):
        t = strip(p.read_text())
        for name, s, e in functions(t):
            key = name if name not in funcs else f'{p.stem}.{name}'
            funcs[key] = (p, s, e, t)
    nav, opens = {}, {}  # page composable -> route; page callback -> route it opens
    for p in sorted((UI / 'navigation').glob('*.kt')):
        t = strip(p.read_text())
        for m in re.finditer(r'entry<Route\.(\w+)>\s*\{', t):
            body = t[m.end():match_close(t, m.end() - 1)]
            c = re.search(r'([A-Za-z_][\w.]*)\s*\(', body)
            if c: nav[c[1].rsplit('.', 1)[-1]] = m[1]
        for m in re.finditer(r'\b(on\w+)\s*=\s*\{\s*navController\.navigateTo\(\s*Route\.(\w+)\s*\)\s*\}', t):
            opens[m[1]] = m[2]
    return funcs, nav, opens


def resolve(funcs, nav):
    """Destination (route, tab) set for every composable, propagated from callers."""
    targets = {}
    for name in funcs:
        bare = name.rsplit('.', 1)[-1]
        if bare in MAIN_TABS: targets[name] = {('Main', MAIN_TABS[bare][0])}
        elif bare in nav: targets[name] = {(nav[bare], -1)}
    calls = {}
    for name, (p, s, e, t) in funcs.items():
        body = t[s:e]
        bare = name.rsplit('.', 1)[-1]
        calls[name] = {other for other in funcs if other != name and re.search(r'(?<!\w)' + re.escape(other.rsplit('.', 1)[-1]) + r'\s*\(', body)
                       and ('.' not in other or funcs[other][0] == p)}
    changed = True
    while changed:
        changed = False
        for caller, callees in calls.items():
            for callee in callees:
                if callee in targets and callee.rsplit('.', 1)[-1] in set(nav) | set(MAIN_TABS): continue
                inherited = set()
                for route, tab in targets.get(caller, ()):
                    tab = PAGE_TABS.get(route, {}).get(callee.rsplit('.', 1)[-1], tab)
                    inherited.add((route, tab))
                if not inherited <= targets.setdefault(callee, set()):
                    targets[callee] |= inherited
                    changed = True
    return targets


def page_title(funcs, name):
    p, s, e, t = funcs[name]
    for m in re.finditer(r'\b(\w+)\s*\(', t[s:e]):
        if m[1] in PAGE_CALLEES:
            o = s + m.end() - 1
            return string_arg(t, o, match_close(t, o), 'title')
    return None


def main():
    funcs, nav, opens = load()
    targets = resolve(funcs, nav)
    pages = {}
    for name in funcs:
        bare = name.rsplit('.', 1)[-1]
        if bare in MAIN_TABS: pages[('Main', MAIN_TABS[bare][0])] = MAIN_TABS[bare][1]
        elif bare in nav and nav[bare] not in EXCLUDED_ROUTES:
            title = page_title(funcs, name)
            if title: pages[(nav[bare], -1)] = title
    route_by_title = {title: key for key, title in pages.items() if key[0] != 'Main'}

    entries, seen, errors = [], set(), []
    # Every page is reachable by its own title.
    for (route, tab), title in pages.items():
        if route == 'Main': continue
        seen.add((route, -1, title))
        entries.append((route, -1, title, None, title, None))
    for name, (p, s, e, t) in funcs.items():
        rows = []
        for m in re.finditer(r'(?<![\w.])title\s*=\s*', t[s:e]):
            pos = s + m.start()
            calls = enclosing_calls(t, pos, s)
            first = next(calls, None)
            if not first or first[0] not in ROW_CALLEES: continue
            callee, a, b = first
            title = string_arg(t, a, b, 'title')
            if not title: continue
            section = None
            for outer, oa, ob in calls:
                if outer == SECTION_CALLEE:
                    section = string_arg(t, oa, ob, 'title'); break
            click = re.search(r'\bonClick\s*=\s*\{?\s*(on\w+)\b', t[a:b])
            rows.append((title, string_arg(t, a, b, 'summary'), section, opens.get(click[1]) if click else None))
        if not rows: continue
        dest = {d for d in targets.get(name, ()) if d[0] not in EXCLUDED_ROUTES}
        if not targets.get(name):
            errors.append(f'{p.relative_to(ROOT)}: {name} has rows but no destination')
            continue
        if len({r for r, _ in dest}) > 1 or len(dest) > 1:
            errors.append(f'{p.relative_to(ROOT)}: {name} reaches several destinations {sorted(dest)}')
            continue
        for route, tab in dest:
            page = pages.get((route, tab)) or pages.get((route, -1))
            for title, summary, section, opened in rows:
                # A row that opens another page leads to that page, not to the row.
                if opened in EXCLUDED_ROUTES: continue
                r, tb = (opened, -1) if opened else route_by_title.get(title, (route, tab))
                pg = pages.get((r, tb)) if (r, tb) != (route, tab) else page
                sec = None if (r, tb) != (route, tab) else section
                if (r, tb, title) in seen: continue
                seen.add((r, tb, title))
                entries.append((r, tb, pg, sec, title, summary))
    if errors:
        sys.exit('\n'.join(errors))

    def ref(x): return f'R.string.{x}' if x else '0'
    lines = [
        '// Generated by .github/scripts/generate_search_index.py; do not edit.',
        'package com.takekazex.hypertweak.ui.search',
        '',
        'import com.takekazex.hypertweak.R',
        'import com.takekazex.hypertweak.ui.page.Route',
        '',
        '/** Every searchable row in source order; 0 marks a missing page, section or summary. */',
        'internal val SettingsSearchIndex: List<SearchEntry> = listOf(',
    ]
    for route, tab, page, section, title, summary in entries:
        lines.append(f'    SearchEntry(Route.{route}, {tab}, {ref(page)}, {ref(section)}, {ref(title)}, {ref(summary)}),')
    lines.append(')')
    text = '\n'.join(lines) + '\n'
    if '--check' in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            sys.exit(f'{OUT.relative_to(ROOT)} is stale; run {pathlib.Path(__file__).relative_to(ROOT)}')
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f'{len(entries)} entries -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
