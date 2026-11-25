package com.takekazex.hypertweak.ui.page.update

import com.takekazex.hypertweak.ui.chrome.AppTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppIconButton as IconButton
import com.takekazex.hypertweak.ui.chrome.AppLazyPage
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.util.update.ProxyTestState
import com.takekazex.hypertweak.util.update.UpdateChannel
import com.takekazex.hypertweak.util.update.UpdateCheckInterval
import com.takekazex.hypertweak.util.update.UpdateManager
import com.takekazex.hypertweak.util.update.UpdateNetworkPolicy
import com.takekazex.hypertweak.util.update.UpdateProxyMode
import com.takekazex.hypertweak.util.update.UpdateUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Check / download / install surface for the update system.
 *
 * Everything here runs in the module's own settings process. [UpdateManager] owns the network and
 * install work; this page only mirrors its state and forwards user intent, so no hooked process can
 * reach the update machinery.
 *
 * Layout follows the Miuix examples: a `LazyColumn` of `SmallTitle` + `Card` groups, standard
 * preference rows for anything the user can change, one capsule button for the primary download /
 * install action, and icon buttons for secondary actions. The installed version lives in the app-bar
 * subtitle rather than in its own block, which keeps the page short.
 */
@Composable
fun UpdatePage(
    onBack: () -> Unit,
    manager: UpdateManager
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val proxyTestState by manager.proxyTestState.collectAsStateWithLifecycle()

    var channel by remember { mutableStateOf(manager.channel()) }
    var interval by remember { mutableStateOf(manager.interval()) }
    var proxyMode by remember { mutableStateOf(manager.proxyMode()) }
    val proxyInput = remember(manager) { TextFieldState(manager.proxyAddress()) }
    val proxyText = proxyInput.text.toString()
    val proxyInputValid = proxyText.isBlank() || UpdateNetworkPolicy.normalizeProxy(proxyText) != null
    LaunchedEffect(manager, proxyInput) {
        // Partial or invalid addresses stay in the field and never become the active proxy.
        snapshotFlow { proxyInput.text.toString() }.collect { raw ->
            if (raw.trim() != manager.proxyAddress() &&
                (raw.isBlank() || UpdateNetworkPolicy.normalizeProxy(raw) != null)
            ) {
                manager.setProxyAddress(raw)
            }
        }
    }
    var showAllChanges by remember { mutableStateOf(false) }
    var showOtherChanges by remember { mutableStateOf(false) }
    var showRawCommits by remember { mutableStateOf(false) }
    var freedBytes by remember { mutableStateOf<Long?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(manager.downloadedBytes()) }

    val isChinese = LocalConfiguration.current.locales[0].language.equals("zh", ignoreCase = true)
    val releaseInfo = state.releaseInfo
    // Include the state class: a completed download changes Available -> AwaitingUnknownSources or
    // Error without changing the release key, and that transition must immediately reveal Install.
    val hasCachedApk = remember(state::class, releaseInfo?.cacheKey, downloadedBytes) {
        releaseInfo?.let { manager.hasCachedDownload(it) } == true
    }

    AppLazyPage(
        title = stringResource(R.string.update_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.update_back),
        subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    ) {
        // A discovered build goes first: it is the reason the page was opened, so it should not
        // sit below the controls.
        if (state.hasReleaseSection) {
            item(key = "available") {
                Column {
                    SmallTitle(stringResource(R.string.update_section_available))
                    ReleaseCard(
                        state = state,
                        hasCachedApk = hasCachedApk,
                        isChinese = isChinese,
                        showAllChanges = showAllChanges,
                        showOtherChanges = showOtherChanges,
                        showRawCommits = showRawCommits,
                        onToggleShowAll = { showAllChanges = !showAllChanges },
                        onToggleOther = { showOtherChanges = !showOtherChanges },
                        onToggleRaw = { showRawCommits = !showRawCommits },
                        onDownload = { manager.downloadAndInstall() },
                        onCancelDownload = { manager.cancelDownload() },
                        onOpenUnknownSources = { manager.retryPendingInstall() },
                        onShareDownloaded = { manager.shareDownloaded() },
                        onShare = { manager.shareDirect() },
                        onOpenReleasePage = { manager.openReleasePage() },
                        onSkip = {
                            // `state` is a delegate, so it cannot smart-cast; read the branch
                            // through an explicit cast instead.
                            val skippedInfo = (state as? UpdateUiState.Skipped)?.info
                            if (skippedInfo != null) {
                                manager.unskip(skippedInfo)
                            } else {
                                manager.skipCurrent()
                            }
                        }
                    )
                }
            }
        }

        item(key = "overview") {
            Column {
                SmallTitle(stringResource(R.string.update_section_overview))
                Card(Modifier.cardSpacing()) {
                    CurrentVersionRow()
                    IconActionRow(
                        title = stringResource(R.string.update_check_title),
                        summary = updateStatusSummary(state, manager.lastCheckedAt()),
                        icon = MiuixIcons.Refresh,
                        onClick = { manager.checkUpdate(force = true) }
                    )
                    IconActionRow(
                        title = stringResource(R.string.update_open_release_page),
                        icon = MiuixIcons.Forward,
                        onClick = { manager.openReleasePage() }
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.update_section_channel),
                        summary = stringResource(channelSummaryRes(channel)),
                        items = CHANNEL_ORDER.map { stringResource(channelLabelRes(it)) },
                        selectedIndex = CHANNEL_ORDER.indexOf(channel).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            CHANNEL_ORDER.getOrNull(index)?.takeIf { it != channel }?.let { selected ->
                                channel = selected
                                manager.setChannel(selected)
                            }
                        }
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.update_check_interval_title),
                        items = UpdateCheckInterval.entries.map { intervalLabel(it) },
                        selectedIndex = interval.index.coerceIn(0, UpdateCheckInterval.entries.lastIndex),
                        onSelectedIndexChange = { index ->
                            UpdateCheckInterval.fromIndex(index).let { selected ->
                                interval = selected
                                manager.setInterval(selected)
                            }
                        }
                    )
                }
            }
        }

        // A failed check is feedback on the action just taken, so it stays with the check row
        // rather than being pushed to the top with the "new version" card.
        val error = (state as? UpdateUiState.Error)?.error
        if (error != null) {
            item(key = "error") {
                Column {
                    SmallTitle(stringResource(R.string.update_error_title))
                    ErrorCard(error) { manager.checkUpdate(force = true) }
                }
            }
        }

        item(key = "network") {
            Column {
                SmallTitle(stringResource(R.string.update_section_proxy))
                Card(Modifier.cardSpacing()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.update_proxy_title),
                        summary = stringResource(proxyModeSummaryRes(proxyMode)),
                        items = PROXY_MODE_ORDER.map { stringResource(proxyModeLabelRes(it)) },
                        selectedIndex = PROXY_MODE_ORDER.indexOf(proxyMode).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            PROXY_MODE_ORDER.getOrNull(index)?.let { selected ->
                                proxyMode = selected
                                manager.setProxyMode(selected)
                            }
                        }
                    )
                    // Only the chosen route's own setting is shown, the way the Miuix examples
                    // reveal a dependent row.
                    MiuixExpandableContent(visible = proxyMode == UpdateProxyMode.THIRD_PARTY) {
                        Column(Modifier.fillMaxWidth()) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.update_proxy_url_title),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_proxy_url_summary,
                                        UpdateNetworkPolicy.PRESET_PROXY
                                    ),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Spacer(Modifier.height(8.dp))
                                AppTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    state = proxyInput,
                                    enabled = proxyMode == UpdateProxyMode.THIRD_PARTY,
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                )
                                if (!proxyInputValid) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.update_proxy_url_invalid),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                val testing = proxyTestState is ProxyTestState.Testing
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (testing) R.string.update_proxy_testing
                                            else R.string.update_proxy_test
                                        ),
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { manager.testProxy() },
                                        enabled = proxyMode == UpdateProxyMode.THIRD_PARTY && !testing && proxyInputValid
                                    ) {
                                        Icon(
                                            MiuixIcons.Search,
                                            contentDescription = stringResource(R.string.update_proxy_test)
                                        )
                                    }
                                }
                                ProxyTestResult(proxyTestState)
                            }
                        }
                    }
                }
            }
        }

        item(key = "storage") {
            Column {
                SmallTitle(stringResource(R.string.update_section_storage))
                Card(Modifier.cardSpacing()) {
                    IconActionRow(
                        title = stringResource(R.string.update_clean_title),
                        summary = when {
                            freedBytes != null ->
                                stringResource(R.string.update_clean_done, formatBytes(freedBytes!!))
                            downloadedBytes > 0L ->
                                stringResource(R.string.update_clean_summary, formatBytes(downloadedBytes))
                            else -> stringResource(R.string.update_clean_empty)
                        },
                        onClick = {
                            freedBytes = manager.downloadedBytes()
                            manager.clearDownloadedUpdates()
                            downloadedBytes = manager.downloadedBytes()
                        },
                        icon = MiuixIcons.Delete
                    )
                }
            }
        }
    }
}

/** Card spacing shared by every group, matching the Miuix examples. */
internal fun Modifier.cardSpacing(): Modifier =
    this.padding(horizontal = 12.dp).padding(bottom = 12.dp)

/** A labelled secondary action whose actual control is always an icon button. */
@Composable
internal fun IconActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, contentDescription = title)
            }
        }
    )
}

/** Miuix's filled Button is the capsule-shaped primary action used for download/install. */
@Composable
internal fun CapsuleActionButton(
    text: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            cornerRadius = 100.dp,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text)
        }
        if (summary != null) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ProxyTestResult(state: ProxyTestState) {
    val message = when (state) {
        ProxyTestState.Idle, ProxyTestState.Testing -> null
        is ProxyTestState.Success -> stringResource(R.string.update_proxy_test_ok)
        is ProxyTestState.Failure -> when {
            state.apiReachable -> stringResource(R.string.update_proxy_test_fail_asset)
            state.assetReachable -> stringResource(R.string.update_proxy_test_fail_api)
            else -> stringResource(R.string.update_proxy_test_fail_both)
        }
    }
    if (message != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

/** Stable first: the dropdown lists the release line a user is most likely to want at the top. */
private val CHANNEL_ORDER = listOf(UpdateChannel.STABLE, UpdateChannel.CI)

private val PROXY_MODE_ORDER = listOf(UpdateProxyMode.OFFICIAL, UpdateProxyMode.THIRD_PARTY)

private fun channelLabelRes(channel: UpdateChannel): Int =
    if (channel == UpdateChannel.CI) R.string.update_channel_ci else R.string.update_channel_stable

private fun channelSummaryRes(channel: UpdateChannel): Int =
    if (channel == UpdateChannel.CI) R.string.update_channel_ci_summary else R.string.update_channel_stable_summary

private fun proxyModeLabelRes(mode: UpdateProxyMode): Int =
    if (mode == UpdateProxyMode.THIRD_PARTY) R.string.update_proxy_third_party else R.string.update_proxy_official

private fun proxyModeSummaryRes(mode: UpdateProxyMode): Int =
    if (mode == UpdateProxyMode.THIRD_PARTY) {
        R.string.update_proxy_third_party_summary
    } else {
        R.string.update_proxy_official_summary
    }
