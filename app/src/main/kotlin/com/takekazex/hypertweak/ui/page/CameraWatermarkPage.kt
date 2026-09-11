package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Combined second-level page for the two camera-adjacent unlocks, split by a TabRow so neither long
 * list has to share one undifferentiated scroll:
 * - Camera Unlock ([CameraUnlockContent]) for `com.android.camera`
 * - Watermark Unlock ([WatermarkUnlockContent]) for `com.miui.mediaeditor`
 *
 * The camera icon lives on the Features → System components entry row only; the tabs themselves are
 * text-only because Miuix's [TabRowWithContour] takes a plain label list.
 */
@Composable
fun CameraWatermarkUnlockPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    // Selected tab survives process death so the page reopens where the user left it.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val tabs = listOf(
        stringResource(R.string.camera_unlock_title),
        stringResource(R.string.watermark_title)
    )

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.camera_unlock_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.camera_unlock_back))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))

            // The page backdrop is already Miuix `surface`, which is also TabRowWithContour's
            // default outer color; use the container surface so the contour keeps a visible base.
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex),
                onTabSelected = { selectedTab = it },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                    selectedBackgroundColor = MiuixTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab.coerceIn(0, tabs.lastIndex)) {
                    0 -> CameraUnlockContent(
                        modifier = Modifier.fillMaxWidth(),
                        topPadding = 0.dp,
                        bottomPadding = padding.calculateBottomPadding()
                    )

                    else -> WatermarkUnlockContent(
                        modifier = Modifier.fillMaxWidth(),
                        topPadding = 0.dp,
                        bottomPadding = padding.calculateBottomPadding()
                    )
                }
            }
        }
    }
}
