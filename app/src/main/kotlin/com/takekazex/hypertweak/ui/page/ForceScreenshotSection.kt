package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ForceScreenshotSection() {
    var enabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FORCE_SCREENSHOT, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            enabled = Preferences.getBoolean(Preferences.KEY_FORCE_SCREENSHOT, false)
        }
    }
    SmallTitle(stringResource(R.string.force_screenshot_section))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SwitchPreference(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                Preferences.putBoolean(Preferences.KEY_FORCE_SCREENSHOT, it)
            },
            title = stringResource(R.string.force_screenshot_title),
            summary = stringResource(R.string.force_screenshot_summary)
        )
    }
}
