package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar

@Composable
fun ScreenshotPage(onBack: () -> Unit) {
    val behavior = MiuixScrollBehavior()
    var enabled by rememberSaveable {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_SCREENSHOT_AUTO_FRAME, false))
    }
    var editorMode by rememberSaveable {
        mutableIntStateOf(Preferences.getInt(Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE, 0).let { if (it == 1) 1 else 0 })
    }
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.screenshot_section),
            scrollBehavior = behavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .overScrollVertical().nestedScroll(behavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            Preferences.putBoolean(Preferences.KEY_SCREENSHOT_AUTO_FRAME, it)
                        },
                        title = stringResource(R.string.screenshot_auto_frame_title),
                        summary = stringResource(R.string.screenshot_auto_frame_summary)
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.screenshot_frame_editor_mode_title),
                        summary = stringResource(R.string.screenshot_frame_editor_mode_summary),
                        items = listOf(
                            stringResource(R.string.screenshot_frame_editor_removable),
                            stringResource(R.string.screenshot_frame_editor_crop)
                        ),
                        selectedIndex = editorMode,
                        onSelectedIndexChange = { mode ->
                            editorMode = mode
                            Preferences.putInt(Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE, mode)
                        }
                    )

                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
        }
    }
}
