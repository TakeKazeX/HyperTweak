package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar

/** Shared shell for app-specific purification pages, with page-local scope guidance. */
@Composable
internal fun PurificationAppPage(title: String, packageName: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    Scaffold(topBar = {
        TopAppBar(title = title, scrollBehavior = scrollBehavior, navigationIcon = {
            IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.settings_system_ui_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(scrollState)) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            MissingScopeCard(packageName)
            content()
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
internal fun CommunityPage(onBack: () -> Unit) {
    PurificationAppPage(stringResource(R.string.community_title), "com.xiaomi.vipaccount", onBack) { CommunitySection() }
}
