package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar

@Composable
fun ScreenshotPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = LocalRestartScopeHandled.current
    val selection = remember { RestartScopeSelection(additionalPackages = setOf("com.miui.screenshot")) }
    val behavior = MiuixScrollBehavior()
    var enabled by rememberSaveable {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_SCREENSHOT_AUTO_FRAME, false))
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
                            Preferences.flush()
                        },
                        title = stringResource(R.string.screenshot_auto_frame_title),
                        summary = stringResource(R.string.screenshot_auto_frame_summary)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.screenshot_restart_title),
                        summary = stringResource(R.string.screenshot_restart_summary),
                        enabled = !restartInProgress(selection),
                        onClick = {
                            Preferences.flush()
                            RestartUtils.restartScope(context, scope, selection, onCompleted = handled)
                        }
                    )
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
        }
    }
}
