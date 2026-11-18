package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.basic.*

/** Shared shell for app-specific purification pages, with page-local scope guidance. */
@Composable
internal fun PurificationAppPage(title: String, packageName: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    AppScrollPage(
        title = title,
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_system_ui_back),
        scrollState = scrollState,
    ) {
        MissingScopeCard(packageName)
        content()
    }
}

@Composable
internal fun CommunityPage(onBack: () -> Unit) {
    PurificationAppPage(stringResource(R.string.community_title), "com.xiaomi.vipaccount", onBack) { CommunitySection() }
}
