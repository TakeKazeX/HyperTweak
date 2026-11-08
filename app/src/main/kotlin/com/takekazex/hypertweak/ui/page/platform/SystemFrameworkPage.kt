package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.effect.AppScaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

@Composable
internal fun SystemFrameworkPage(
    onBack: () -> Unit,
    onNavigateToFreeform: () -> Unit,
    onNavigateToPackageManager: () -> Unit,
    allowThirdPartyTheme: Boolean,
    onAllowThirdPartyThemeChange: (Boolean) -> Unit,
    unlockThirdPartyDarkMode: Boolean,
    onUnlockThirdPartyDarkModeChange: (Boolean) -> Unit,
) {
    val settingsStore = LocalAppSettingsStore.current
    var forceScreenshot by remember { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            forceScreenshot = settingsStore.getBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, false)
        }
    }
    val scrollBehavior = MiuixScrollBehavior()
    AppScaffold(topBar = {
        AppTopAppBar(
            title = stringResource(R.string.force_screenshot_section),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.aosp_back)) }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            MissingScopeCard("system", "com.android.systemui", "com.android.thememanager")

            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.package_manager_title),
                    summary = stringResource(R.string.package_manager_summary),
                    onClick = onNavigateToPackageManager
                )
                ArrowPreference(
                    title = stringResource(R.string.system_freeform_title),
                    summary = stringResource(R.string.system_freeform_summary),
                    onClick = onNavigateToFreeform
                )
                SwitchPreference(
                    checked = forceScreenshot,
                    onCheckedChange = {
                        forceScreenshot = it
                        settingsStore.putBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, it)
                    },
                    title = stringResource(R.string.force_screenshot_title),
                    summary = stringResource(R.string.force_screenshot_summary)
                )
            }

            SmallTitle(stringResource(R.string.system_framework_appearance))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    checked = allowThirdPartyTheme,
                    onCheckedChange = onAllowThirdPartyThemeChange,
                    title = stringResource(R.string.tweaks_allow_third_party_theme_title),
                    summary = stringResource(R.string.tweaks_allow_third_party_theme_summary)
                )
                SwitchPreference(
                    checked = unlockThirdPartyDarkMode,
                    onCheckedChange = onUnlockThirdPartyDarkModeChange,
                    title = stringResource(R.string.settings_unlock_third_party_dark_mode_title),
                    summary = stringResource(R.string.settings_unlock_third_party_dark_mode_summary)
                )
            }
            VolumeKeyStepSection()

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun VolumeKeyStepSection() {
    val settingsStore = LocalAppSettingsStore.current
    var volumeKeyStepEnabled by rememberSaveable {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, false))
    }
    var volumeKeyStepCount by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT,
                PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT
            ).coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        )
    }
    var volumeKeyStepScope by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE,
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY
            ).coerceIn(
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
                PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM
            )
        )
    }
    var volumeKeySliderExpanded by remember { mutableStateOf(false) }
    val volumeKeySlider = rememberSliderEdit(volumeKeyStepCount.toFloat())
    val volumeKeySliderValue = volumeKeySlider.value
    val displayedVolumeKeyStepCount = volumeKeySliderValue.roundToInt().coerceIn(
        VolumeKeyStepPolicy.MIN_STEPS,
        VolumeKeyStepPolicy.MAX_STEPS
    )
    fun setVolumeKeyStepCount(value: Int) {
        val resolved = value.coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        volumeKeyStepCount = resolved
        settingsStore.putInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, resolved)
        settingsStore.flush()
    }
    fun setVolumeKeyStepScope(value: Int) {
        val resolved = value.coerceIn(
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM
        )
        volumeKeyStepScope = resolved
        settingsStore.putInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, resolved)
        settingsStore.flush()
    }

    SmallTitle(stringResource(R.string.volume_key_section))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = volumeKeyStepEnabled,
                onCheckedChange = {
                    volumeKeyStepEnabled = it
                    if (!it) volumeKeySliderExpanded = false
                    settingsStore.putBoolean(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, it)
                    settingsStore.flush()
                },
                title = stringResource(R.string.volume_key_step_enabled),
                summary = stringResource(R.string.volume_key_step_enabled_summary)
            )
            if (volumeKeyStepEnabled) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.volume_key_scope_title),
                    summary = stringResource(if (volumeKeyStepScope == PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY) R.string.volume_key_scope_media else R.string.volume_key_scope_active_stream),
                    showValue = false,
                    items = listOf(
                        stringResource(R.string.volume_key_scope_media),
                        stringResource(R.string.volume_key_scope_active_stream)
                    ),
                    selectedIndex = volumeKeyStepScope,
                    onSelectedIndexChange = ::setVolumeKeyStepScope
                )
                ArrowPreference(
                    title = stringResource(R.string.volume_key_step_title),
                    summary = pluralStringResource(
                        R.plurals.volume_key_step_summary,
                        displayedVolumeKeyStepCount,
                        displayedVolumeKeyStepCount,
                        VolumeKeyStepPolicy.percentStep(displayedVolumeKeyStepCount)
                    ),
                    endActions = {
                        Text(
                            text = pluralStringResource(
                                R.plurals.volume_key_step_value,
                                displayedVolumeKeyStepCount,
                                displayedVolumeKeyStepCount
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = { volumeKeySliderExpanded = !volumeKeySliderExpanded },
                    holdDownState = volumeKeySliderExpanded,
                    bottomAction = {
                        Slider(
                            value = volumeKeySliderValue.coerceIn(
                                VolumeKeyStepPolicy.MIN_STEPS.toFloat(),
                                VolumeKeyStepPolicy.MAX_STEPS.toFloat()
                            ),
                            onValueChange = volumeKeySlider::update,
                            onValueChangeFinished = {
                                volumeKeySlider.finish { it.roundToInt().toFloat() }?.let { setVolumeKeyStepCount(it.toInt()) }
                            },
                            valueRange = VolumeKeyStepPolicy.MIN_STEPS.toFloat()..
                                VolumeKeyStepPolicy.MAX_STEPS.toFloat(),
                            steps = VolumeKeyStepPolicy.MAX_STEPS -
                                VolumeKeyStepPolicy.MIN_STEPS - 1,
                            showKeyPoints = true,
                            keyPoints = listOf(5f, 15f, 100f),
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step
                        )
                    }
                )
            }
        }
    }
    IntValueDialog(
        show = volumeKeyStepEnabled && volumeKeySliderExpanded,
        title = stringResource(R.string.volume_key_step_title),
        summary = stringResource(R.string.volume_key_step_dialog_summary),
        suffix = stringResource(R.string.volume_key_step_suffix),
        range = VolumeKeyStepPolicy.MIN_STEPS..VolumeKeyStepPolicy.MAX_STEPS,
        currentValue = { volumeKeyStepCount },
        emptyValue = volumeKeyStepCount,
        onValueConfirmed = ::setVolumeKeyStepCount,
        onDismissRequest = { volumeKeySliderExpanded = false }
    )
}
