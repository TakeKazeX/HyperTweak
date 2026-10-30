#!/usr/bin/env python3
"""Regenerate the reviewed debug inventory from typed setting reads; --check detects drift.
No runtime source parsing/reflection. Unresolved/context-dependent defaults stay explicitly unknown.
"""
import pathlib, re, sys
ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'app/src/main/kotlin/com/takekazex/hypertweak'
OUT = SRC / 'settings/contract/debug/DebugSettingsCatalog.kt'
keys = {}
for p in sorted((SRC/'settings/contract').glob('*Keys.kt')):
    for n, v in re.findall(r'const val (KEY_\w+) = "([^"$]+)"', p.read_text()):
        keys[p.stem+'.'+n] = v
for cls, n, val in [('DockConfig','KEY','launcher_dock_background'), ('IconTunerContract','KEY_POSITION','icon_tuner_position'),('IconTunerContract','KEY_POSITION_VALUES','icon_tuner_position_val'),('IconTunerContract','KEY_POSITION_REORDER','icon_tuner_position_reorder'),('IconTunerContract','KEY_LEFT_MODE','icon_left_container_mode')]:
    if cls == 'DockConfig':
        val = re.search(r'const val KEY = "([^"]+)"',(SRC/'settings/contract/DockConfig.kt').read_text())[1]
    keys[cls+'.'+n] = val
reads = {}
for p in sorted(SRC.rglob('*.kt')):
    if p == OUT: continue
    text = p.read_text()
    imports = {x.rsplit('.',1)[-1]:x for x in re.findall(r'^import ([\w.]+)$',text,re.M)}
    for m in re.finditer(r'\b(getBoolean|getInt|getFloat|getStringSet|getString|pref|backGestureBoolean)\(\s*((?:\w+Keys|IconTunerContract|DockConfig)\.\w+)\s*',text):
        method, key = m[1], m[2]
        if key not in keys: continue
        tail = text[m.end():]
        default = ''
        if tail.startswith(','):
            # Defaults used here are literals, constant references, or emptySet(); all other
            # expressions are intentionally rejected instead of guessed.
            dm = re.match(r',\s*("[^"\n]*"|true\b|false\b|-?\d+(?:\.\d+)?[fL]?\b|emptySet(?:<String>)?\(\)|[A-Za-z_]\w*(?:\.\w+)+)',tail)
            if not dm: continue
            default = dm[1]
        typ = method.removeprefix('get')
        if method == 'backGestureBoolean': typ = 'Boolean'
        if method == 'pref':
            if default in ('true','false'): typ = 'Boolean'
            elif default.startswith('"'): typ = 'String'
            elif default.startswith('emptySet'): typ = 'StringSet'
            elif re.fullmatch(r'-?\d+[f]?',default): typ = 'Float' if default.endswith('f') else 'Int'
            else: continue
        if not default:
            default = {'Boolean':'false','Int':'0','Float':'1f','String':'""','StringSet':'emptySet<String>()'}[typ]
        if '.' in default and re.match(r'[A-Za-z_]',default) and not default.startswith('emptySet'):
            head = default.split('.')[0]
            if head in imports: default = imports[head]+default[len(head):]
            elif (SRC/'settings/contract'/f'{head}.kt').exists(): default = 'com.takekazex.hypertweak.settings.contract.'+default
            else: continue
        # Prefer app UI's displayed defaults over lower-level fallback sentinel values.
        rank = 0 if '/ui/' in str(p) else 1
        if key not in reads or rank < reads[key][2]: reads[key] = (typ,default,rank)
# Reviewed defaults for settings read through shared helper loops/migration readers.
# Missing display mode inherits the legacy boolean; the fresh-install default is off.
reads['SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE'] = ('Int', '0', 0)
for key in keys:
    if key.startswith('IconSettingsKeys.KEY_ICON_LEFT_'): reads.setdefault(key,('Boolean','false',0))
    if key.startswith('SystemUiSettingsKeys.KEY_CC_CORNER_') and key != 'SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED': reads[key]=('Float','0f',0)
    if key.startswith('PlatformSettingsKeys.KEY_KEEP_') or key.endswith(('KEY_NATIVE_PHOTO_PICKER','KEY_NATIVE_FILE_PICKER')): reads.setdefault(key,('Boolean','false',0))
    if key in ('SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD','SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN','SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH'): reads[key]=('Boolean','false',0)
    if key=='BackGestureKeys.KEY_BACK_GESTURE_ENABLED': reads[key]=('Boolean','false',0)
manual = {
 'AppSettingsKeys.KEY_APP_SHORTCUTS':('StringSet','setOf("lsposed", "installerx", "dev_settings")'),
 'AppSettingsKeys.KEY_UPDATE_CHANNEL':('String','"stable"'),
 'AppSettingsKeys.KEY_UPDATE_CHECK_INTERVAL':('Int','0'),
 'AppSettingsKeys.KEY_UPDATE_PROXY_MODE':('String','"official"'),
 'IconSettingsKeys.KEY_ICON_STACKED_ALPHA_FG':('Float','1f'),
 'IconSettingsKeys.KEY_ICON_STACKED_ALPHA_BG':('Float','0.4f'),
 'IconSettingsKeys.KEY_ICON_STACKED_ALPHA_ERROR':('Float','0.4f'),
 'IconSettingsKeys.KEY_ICON_STACKED_PADDING_START':('Float','0f'),
 'IconSettingsKeys.KEY_ICON_STACKED_PADDING_END':('Float','0f'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_SIZE':('Float','13f'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_WEIGHT':('Int','400'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_SINGLE_WEIGHT':('Int','400'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_WIDTH_CONDENSED':('Boolean','false'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_PADDING_START':('Float','0f'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_PADDING_END':('Float','0f'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_VERTICAL_OFFSET':('Float','0f'),
 'IconSettingsKeys.KEY_ICON_STACKED_TYPE_FONT':('String','""'),
 'IconSettingsKeys.KEY_ICON_EXT_BLOCKED':('String','""'),
 'IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE':('Boolean','false'),
 'IconSettingsKeys.KEY_ICON_HIDE_PRIVACY':('Boolean','false'),
 'IconTunerContract.KEY_POSITION':('Int','0'),
 'IconTunerContract.KEY_POSITION_VALUES':('StringSet','emptySet<String>()'),
 'IconTunerContract.KEY_POSITION_REORDER':('Boolean','false'),
 'IconTunerContract.KEY_LEFT_MODE':('Int','0'),
 'DockConfig.KEY':('String','com.takekazex.hypertweak.settings.contract.DockConfig().encode()'),
}
# Unused legacy artwork constants have no executing reader; never invent a default or
# advertise a supported write merely because an old key remains in the contract.
for k,(t,d) in manual.items():
    if k.startswith('IconSettingsKeys.KEY_ICON_STACKED_') and k not in reads:
        reads[k] = ('Unknown','null',0)
    else: reads.setdefault(k,(t,d,0))
lines = ['// Generated by .github/scripts/generate_debug_catalog.py; review helper overrides there.',
'package com.takekazex.hypertweak.settings.contract.debug', '',
'internal object DebugSettingsCatalog {', '    val entries: Map<String, DebugSetting> = listOf(']
for k,v in sorted(keys.items(),key=lambda kv:kv[1]):
    typ,default,_=reads.get(k,('Unknown','null',0))
    # Avoid inventing effective defaults for helper-only legacy artwork configuration.
    if k in manual and k.startswith('IconSettingsKeys.KEY_ICON_STACKED_') and k not in reads: default='null'
    if default == 'emptySet()': default='emptySet<String>()'
    if v == 'camera_legendary_moment_mode': default='"off"'
    if v == 'power_button_long_press_action': default='0'
    if 'util.logging.DebugLog.DEFAULT_LEVEL' in default: default='4'
    if 'presentation.icon.duo.DuoLayout.DEFAULT_ICON_SIZE_DP' in default: default='26'
    lines.append(f'        DebugSetting("{v}", "{typ}", {default}),')
# Dynamic slot family is bounded by the same host-independent slot contract used by the UI.
slot_text=(SRC/'ui/page/systemui/icon/IconSlotCatalog.kt').read_text()
slot_block=slot_text.split('val slots: List<String> = listOf(',1)[1].split(')',1)[0]
for slot in re.findall(r'"([\w_]+)"',slot_block): lines.append(f'        DebugSetting("icon_tuner_slot_{slot}", "Int", 0),')
lines += ['    ).associateBy { it.key }','}', '']
result='\n'.join(lines)
if '--check' in sys.argv:
    if not OUT.exists() or OUT.read_text()!=result: sys.exit('Debug settings catalog is stale; regenerate and review it')
else: OUT.write_text(result)
print(f'Debug catalog: {len(keys)} named settings plus slot family')
