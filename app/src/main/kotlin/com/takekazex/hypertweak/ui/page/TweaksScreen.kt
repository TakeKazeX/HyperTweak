package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import com.takekazex.hypertweak.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun TweaksScreenContent(
    padding: PaddingValues,
    onNavigateToSystemUi: () -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    fullScreenTranslate: Boolean,
    onFullScreenTranslateChange: (Boolean) -> Unit,
    askAboutScreen: Boolean,
    onAskAboutScreenChange: (Boolean) -> Unit,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    backdrop: LayerBackdrop
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.tweaks_features),
                modifier = if (contentReady) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                        ))
                    )
                } else {
                    Modifier
                },
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (contentReady) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
            Spacer(modifier = Modifier.height(8.dp))

            // System UI entry: the SystemUI-scoped tweaks (lockscreen & display, control-center
            // sliders, navigation bar, media cards, monet refresh) live in the second-level page.
            SmallTitle(text = stringResource(R.string.settings_system_ui))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_system_ui),
                    summary = stringResource(R.string.settings_system_ui_summary),
                    onClick = onNavigateToSystemUi
                )
            }

            // Scope 4: System Core
            SmallTitle(text = stringResource(R.string.tweaks_system_core_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = removeGms,
                        onCheckedChange = onRemoveGmsChange,
                        title = stringResource(R.string.tweaks_gms_bypass_title),
                        summary = stringResource(R.string.tweaks_gms_bypass_summary)
                    )
                    // Bypassing the GMS China ROM restrictions already removes the CN markers that
                    // gate Quick Share, so the phenotype override is redundant while it is on; grey
                    // the switch out instead of force-unlocking share with it.
                    SwitchPreference(
                        checked = quickShareEnabled,
                        onCheckedChange = onQuickShareEnabledChange,
                        title = stringResource(R.string.tweaks_quick_share_title),
                        summary = stringResource(R.string.tweaks_quick_share_summary),
                        enabled = !removeGms
                    )
                    SwitchPreference(
                        checked = fullScreenTranslate,
                        onCheckedChange = onFullScreenTranslateChange,
                        title = stringResource(R.string.tweaks_full_screen_translate_title),
                        summary = stringResource(R.string.tweaks_full_screen_translate_summary)
                    )
                    SwitchPreference(
                        checked = askAboutScreen,
                        onCheckedChange = onAskAboutScreenChange,
                        title = stringResource(R.string.tweaks_ask_about_screen_title),
                        summary = stringResource(R.string.tweaks_ask_about_screen_summary)
                    )
                    SwitchPreference(
                        checked = unlockPasskey,
                        onCheckedChange = onUnlockPasskeyChange,
                        title = stringResource(R.string.tweaks_passkey_title),
                        summary = stringResource(R.string.tweaks_passkey_summary)
                    )
                    SwitchPreference(
                        checked = fcmLiveEnabled,
                        onCheckedChange = onFcmLiveEnabledChange,
                        title = stringResource(R.string.tweaks_fcm_live_title),
                        summary = stringResource(R.string.tweaks_fcm_live_summary)
                    )
                }
            }

            // Scope 5: Bluetooth
            SmallTitle(text = stringResource(R.string.tweaks_bluetooth_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = disableSpatialAudio,
                        onCheckedChange = onDisableSpatialAudioChange,
                        title = stringResource(R.string.tweaks_block_spatial_audio_title),
                        summary = stringResource(R.string.tweaks_block_spatial_audio_summary)
                    )
                    SwitchPreference(
                        checked = forceAdaptiveAnc,
                        onCheckedChange = onForceAdaptiveAncChange,
                        title = stringResource(R.string.tweaks_force_adaptive_anc_title),
                        summary = stringResource(R.string.tweaks_force_adaptive_anc_summary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
