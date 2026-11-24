package com.takekazex.hypertweak.ui.page.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.search.rememberSearchLandingTab
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppTabRow
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.basic.TabRowWithContour

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
    // Selected tab survives process death; a search result can ask for a specific tab.
    var selectedTab by rememberSearchLandingTab(Route.CameraWatermark)

    val tabs = listOf(
        stringResource(R.string.camera_unlock_title),
        stringResource(R.string.watermark_title)
    )

    AppScrollPage(
        title = stringResource(R.string.camera_unlock_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.camera_unlock_back),
        topBarBottom = {
            AppTabRow(tabs = tabs, selectedIndex = selectedTab, onSelect = { selectedTab = it }, segmented = true)
        },
    ) {

        AnimatedContent(
            targetState = selectedTab.coerceIn(0, tabs.lastIndex),
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                // Slide toward the selected tab, matching the indicator's travel direction.
                val direction = if (targetState > initialState) 1 else -1
                val slide = spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)
                val fade = spring<Float>(stiffness = Spring.StiffnessMediumLow)
                (slideInHorizontally(slide) { it * direction } + fadeIn(fade))
                    .togetherWith(slideOutHorizontally(slide) { -it * direction } + fadeOut(fade))
                    .using(SizeTransform(clip = true))
            },
            label = "cameraWatermarkTab",
        ) { tab ->
            Column(Modifier.fillMaxWidth()) {
                MissingScopeCard(if (tab == 0) "com.android.camera" else "com.miui.mediaeditor")
                when (tab) {
                    0 -> CameraUnlockContent(
                        modifier = Modifier.fillMaxWidth(),
                        topPadding = 0.dp,
                        bottomPadding = 0.dp
                    )

                    else -> WatermarkUnlockContent(
                        modifier = Modifier.fillMaxWidth(),
                        topPadding = 0.dp,
                        bottomPadding = 0.dp
                    )
                }
            }
        }
    }
}
