package com.takekazex.hypertweak.ui.page.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun LauncherIconThemePreferences() {
    val store = LocalAppSettingsStore.current
    var mode by rememberSaveable { mutableIntStateOf(store.getInt(LauncherDesktopKeys.KEY_ICON_THEME_MODE, 0).coerceIn(0, 4)) }
    var color by rememberSaveable { mutableIntStateOf(store.getInt(LauncherDesktopKeys.KEY_ICON_THEME_COLOR, LauncherDesktopKeys.DEFAULT_ICON_COLOR)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        mode = store.getInt(LauncherDesktopKeys.KEY_ICON_THEME_MODE, 0).coerceIn(0, 4)
        color = store.getInt(LauncherDesktopKeys.KEY_ICON_THEME_COLOR, LauncherDesktopKeys.DEFAULT_ICON_COLOR)
    }
    SmallTitle(stringResource(R.string.desktop_icon_theme_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(title = stringResource(R.string.desktop_icon_theme_mode),
            summary = stringResource(R.string.desktop_icon_theme_summary),
            items = listOf(stringResource(R.string.desktop_icon_theme_stock),
                stringResource(R.string.desktop_icon_theme_unlock), stringResource(R.string.desktop_icon_theme_mono),
                stringResource(R.string.desktop_icon_theme_monet), stringResource(R.string.desktop_icon_theme_custom)),
            selectedIndex = mode, onSelectedIndexChange = { index ->
                mode = index
                store.putInt(LauncherDesktopKeys.KEY_ICON_THEME_MODE, index)
            })
        if (mode == 4) ArrowPreference(title = stringResource(R.string.desktop_icon_theme_color),
            summary = "#%06X".format(color and 0xffffff), onClick = {
                text = "%06X".format(color and 0xffffff); editing = true
            })

    }
    val rgb = text.takeIf { it.length == 6 }?.toIntOrNull(16)
    OverlayDialog(show = editing, title = stringResource(R.string.desktop_icon_theme_color),
        summary = stringResource(R.string.desktop_icon_theme_color_hint), onDismissRequest = { editing = false },
        content = {
            TextField(value = text, onValueChange = { value ->
                val normalized = value.removePrefix("#")
                if (normalized.length <= 6 && normalized.all { it.digitToIntOrNull(16) != null }) text = normalized
            }, maxLines = 1, modifier = Modifier.padding(bottom = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(text = stringResource(R.string.scale_cancel), modifier = Modifier.weight(1f), onClick = { editing = false })
                TextButton(text = stringResource(R.string.scale_ok), modifier = Modifier.weight(1f), enabled = rgb != null,
                    colors = ButtonDefaults.textButtonColorsPrimary(), onClick = {
                        rgb?.let { color = it or -0x1000000; store.putInt(LauncherDesktopKeys.KEY_ICON_THEME_COLOR, color) }
                        editing = false
                    })
            }
        })
}
