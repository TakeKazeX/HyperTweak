package com.takekazex.hypertweak.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.update.DownloadProgress
import com.takekazex.hypertweak.util.update.ProxyTestState
import com.takekazex.hypertweak.util.update.UpdateChangeLog
import com.takekazex.hypertweak.util.update.UpdateChannel
import com.takekazex.hypertweak.util.update.UpdateCheckInterval
import com.takekazex.hypertweak.util.update.UpdateError
import com.takekazex.hypertweak.util.update.UpdateInfo
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
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val scrollBehavior = MiuixScrollBehavior()
    val state by manager.state.collectAsState()
    val proxyTestState by manager.proxyTestState.collectAsState()

    var channel by remember { mutableStateOf(manager.channel()) }
    var interval by remember { mutableStateOf(manager.interval()) }
    var proxyMode by remember { mutableStateOf(manager.proxyMode()) }
    var proxyInput by remember { mutableStateOf(manager.proxyAddress()) }
    var proxyInputValid by remember { mutableStateOf(true) }
    var showAllChanges by remember { mutableStateOf(false) }
    var showOtherChanges by remember { mutableStateOf(false) }
    var showRawCommits by remember { mutableStateOf(false) }
    var freedBytes by remember { mutableStateOf<Long?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(manager.downloadedBytes()) }

    val isChinese = remember { Locale.getDefault().language.equals("zh", ignoreCase = true) }
    val releaseInfo = state.releaseInfo
    // Include the state class: a completed download changes Available -> AwaitingUnknownSources or
    // Error without changing the release key, and that transition must immediately reveal Install.
    val hasCachedApk = remember(state::class, releaseInfo?.cacheKey, downloadedBytes) {
        releaseInfo?.let { manager.hasCachedDownload(it) } == true
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.update_title),
            subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.update_back))
                }
            }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)
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
                        AnimatedVisibility(visible = proxyMode == UpdateProxyMode.THIRD_PARTY) {
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
                                    TextField(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = proxyInput,
                                        maxLines = 1,
                                        onValueChange = { raw ->
                                            proxyInput = raw
                                            // Only https is accepted (this flow never permits
                                            // cleartext), so a half-typed address stays in the field
                                            // but is never persisted as a usable proxy.
                                            proxyInputValid = raw.isBlank() ||
                                                UpdateNetworkPolicy.normalizeProxy(raw) != null
                                            if (proxyInputValid) manager.setProxyAddress(raw)
                                        }
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
                                            enabled = !testing && proxyInputValid
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
}

/** Card spacing shared by every group, matching the Miuix examples. */
private fun Modifier.cardSpacing(): Modifier =
    this.padding(horizontal = 12.dp).padding(bottom = 12.dp)

/** A labelled secondary action whose actual control is always an icon button. */
@Composable
private fun IconActionRow(
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
private fun CapsuleActionButton(
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

/** Each state that has a version worth showing; the rest only have a status line. */
private val UpdateUiState.hasReleaseSection: Boolean
    get() = when (this) {
        is UpdateUiState.Available,
        is UpdateUiState.Skipped,
        is UpdateUiState.Downloading,
        is UpdateUiState.AwaitingUnknownSources -> true
        is UpdateUiState.Error -> info != null
        else -> false
    }

/** The version being offered, whichever state is currently showing it. */
private val UpdateUiState.releaseInfo: UpdateInfo?
    get() = when (this) {
        is UpdateUiState.Available -> info
        is UpdateUiState.Skipped -> info
        is UpdateUiState.Downloading -> info
        is UpdateUiState.AwaitingUnknownSources -> info
        is UpdateUiState.Error -> info
        else -> null
    }

@Composable
private fun CurrentVersionRow() {
    val revisionDate = formatIsoTimestamp(BuildConfig.BUILD_TIMESTAMP)
    val parts = buildList {
        if (BuildConfig.GIT_COMMIT_SHA.isNotBlank()) {
            add("${stringResource(R.string.update_current_commit)} ${BuildConfig.GIT_COMMIT_SHA.take(7)}")
        }
        // Deliberately not called a build time: the APK embeds the revision timestamp, because a
        // wall-clock build time cannot survive Gradle's configuration cache.
        if (revisionDate != null) {
            add("${stringResource(R.string.update_current_revision_date)} $revisionDate")
        }
    }
    BasicComponent(
        title = stringResource(R.string.update_section_current),
        summary = parts.joinToString("\n")
    )
}

@Composable
private fun ReleaseCard(
    state: UpdateUiState,
    hasCachedApk: Boolean,
    isChinese: Boolean,
    showAllChanges: Boolean,
    showOtherChanges: Boolean,
    showRawCommits: Boolean,
    onToggleShowAll: () -> Unit,
    onToggleOther: () -> Unit,
    onToggleRaw: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onShareDownloaded: () -> Unit,
    onShare: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onSkip: () -> Unit
) {
    val info = state.releaseInfo ?: return
    val plan = changelogPlan(info.distance)
    Card(Modifier.cardSpacing(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text(
                    text = "v${info.versionName}",
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface
                )
                val details = buildList {
                    info.apkSize?.let { add(stringResource(R.string.update_release_size, formatBytes(it))) }
                    formatIsoTimestamp(info.publishedAt)?.let {
                        add(stringResource(R.string.update_release_published, it))
                    }
                    info.distance?.takeIf { it > 0 }?.let {
                        add(pluralStringResource(R.plurals.update_release_distance, it, it))
                    }
                }
                if (details.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = details.joinToString(" · "),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            if (state is UpdateUiState.Available && state.staleAssetNotice) {
                NoticeRow(stringResource(R.string.update_stale_asset))
            }
            if (state is UpdateUiState.Available && state.usedProxyFallback) {
                NoticeRow(stringResource(R.string.update_proxy_fallback))
            }
            if (!info.hasIntegrityMetadata) {
                NoticeRow(stringResource(R.string.update_legacy_metadata_notice))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (info.commits.isNotEmpty() || !info.notes?.preferred(isChinese).isNullOrBlank()) {
                Text(
                    text = stringResource(plan.titleRes),
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                    color = MiuixTheme.colorScheme.onSurface
                )
                }
                if (plan.summaryOnly) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.update_changelog_truncated),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(Modifier.height(8.dp))
                ChangeList(
                    info = info,
                    isChinese = isChinese,
                    collapsed = plan.collapsed && !showAllChanges,
                    showOtherChanges = showOtherChanges,
                    onToggleShowAll = onToggleShowAll,
                    onToggleOther = onToggleOther
                )
                if (info.commits.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    IconActionRow(
                        title = stringResource(
                            if (showRawCommits) R.string.update_changelog_raw_hide
                            else R.string.update_changelog_raw_show
                        ),
                        icon = if (showRawCommits) MiuixIcons.ExpandLess else MiuixIcons.ListView,
                        onClick = onToggleRaw
                    )
                    if (showRawCommits) {
                        Spacer(Modifier.height(4.dp))
                        info.commits.forEach { commit ->
                            Text(
                                text = "${commit.hash.take(7)} ${commit.subject}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            if (info.channel == UpdateChannel.CI) {
                NoticeRow(stringResource(R.string.update_share_ci_notice))
            }

            Spacer(Modifier.height(4.dp))

            when (state) {
                is UpdateUiState.Downloading -> DownloadRow(state.progress, onCancelDownload)

                else -> {
                    if (state is UpdateUiState.AwaitingUnknownSources) {
                        NoticeRow(stringResource(R.string.update_unknown_sources_summary))
                    }
                    ReleaseButtons(
                        skipped = state is UpdateUiState.Skipped,
                        hasCachedApk = hasCachedApk || state is UpdateUiState.AwaitingUnknownSources,
                        downloading = false,
                        onDownload = onDownload,
                        onShare = onShare,
                        onOpenReleasePage = onOpenReleasePage,
                        onSkip = onSkip,
                        onShareDownloaded = if (hasCachedApk) onShareDownloaded else null,
                        onOpenSettings = if (state is UpdateUiState.AwaitingUnknownSources) onOpenUnknownSources else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** Shared compact action area for the page and discovery dialog. */
@Composable
private fun ReleaseButtons(
    skipped: Boolean,
    hasCachedApk: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onSkip: () -> Unit,
    // `modifier` leads the optional parameters, per the Compose convention.
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onShareDownloaded: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null
) {
    Column(modifier.padding(bottom = 12.dp)) {
        CapsuleActionButton(
            text = stringResource(
                if (hasCachedApk) R.string.update_install else R.string.update_download_install
            ),
            leadingIcon = if (hasCachedApk) MiuixIcons.Update else MiuixIcons.Download,
            onClick = onDownload,
            enabled = !downloading
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToolbarAction(R.string.update_action_link, R.string.update_share_direct, MiuixIcons.Link, onShare)
            if (onShareDownloaded != null) {
                ToolbarAction(R.string.update_action_apk, R.string.update_share_downloaded, MiuixIcons.Share, onShareDownloaded)
            }
            ToolbarAction(R.string.update_action_release, R.string.update_open_release_page, MiuixIcons.Forward, onOpenReleasePage)
            ToolbarAction(
                if (skipped) R.string.update_action_unskip else R.string.update_action_skip,
                if (skipped) R.string.update_unskip_version else R.string.update_skip_version,
                if (skipped) MiuixIcons.Show else MiuixIcons.Hide,
                onSkip,
                enabled = !downloading
            )
            if (onOpenSettings != null) {
                ToolbarAction(R.string.update_unknown_sources_action, R.string.update_unknown_sources_action, MiuixIcons.Settings, onOpenSettings)
            }
            if (onDismiss != null) {
                ToolbarAction(R.string.update_later, R.string.update_dismiss, MiuixIcons.Close, onDismiss)
            }
        }
    }
}

@Composable
private fun ToolbarAction(
    labelRes: Int,
    descriptionRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, stringResource(descriptionRes), modifier = Modifier.size(22.dp))
        }
        Text(
            text = stringResource(labelRes),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.4f),
            textAlign = TextAlign.Center
        )
    }
}

/** One rendered changelog group: curated notes carry a heading, CI commit types a known type. */
private class ChangeGroupView(val heading: String?, val type: String?, val items: List<String>)

@Composable
private fun ChangeList(
    info: UpdateInfo,
    isChinese: Boolean,
    collapsed: Boolean,
    showOtherChanges: Boolean,
    onToggleShowAll: () -> Unit,
    onToggleOther: () -> Unit
) {
    // Stable releases carry curated bilingual notes (D19); the CI channel has none and renders the
    // English commit subjects straight from the compare API.
    val curated = info.notes?.preferred(isChinese)?.takeIf { it.isNotBlank() }
    val groups: List<ChangeGroupView>
    val secondary: List<ChangeGroupView>
    if (curated != null) {
        groups = parseCuratedNotes(curated).map { ChangeGroupView(it.first, null, it.second) }
        secondary = emptyList()
    } else {
        val grouped = UpdateChangeLog.group(info.commits).map {
            ChangeGroupView(null, it.type, it.items)
        }
        groups = grouped.filterNot { it.type in SECONDARY_TYPES }
        // Hidden by default on the CI channel (plan §4.3): chore/build/ci/docs/refactor noise.
        secondary = grouped.filter { it.type in SECONDARY_TYPES }
    }

    if (groups.isEmpty() && secondary.isEmpty()) {
        Text(
            text = stringResource(R.string.update_changelog_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        return
    }

    val limit = if (collapsed) COLLAPSED_ITEMS else Int.MAX_VALUE
    val visible = if (showOtherChanges) groups + secondary else groups
    var hiddenItems = 0
    visible.forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(10.dp))
        val heading = group.heading ?: group.type?.let { stringResource(groupTitleRes(it)) }
        if (heading != null) {
            Text(
                text = heading,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurface
            )
        }
        group.items.take(limit).forEach { item ->
            Text(
                text = "• $item",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        hiddenItems += group.items.size - group.items.take(limit).size
    }

    if (visible.any { it.items.size > COLLAPSED_ITEMS }) {
        IconActionRow(
            title = if (collapsed) {
                pluralStringResource(
                    R.plurals.update_changelog_show_more,
                    hiddenItems,
                    hiddenItems
                )
            } else {
                stringResource(R.string.update_changelog_show_less)
            },
            icon = if (collapsed) MiuixIcons.ExpandMore else MiuixIcons.ExpandLess,
            onClick = onToggleShowAll
        )
    }
    if (secondary.isNotEmpty()) {
        IconActionRow(
            title = if (showOtherChanges) {
                stringResource(R.string.update_changelog_hide_other)
            } else {
                    val count = secondary.sumOf { it.items.size }
                    pluralStringResource(R.plurals.update_changelog_show_other, count, count)
            },
            icon = if (showOtherChanges) MiuixIcons.ExpandLess else MiuixIcons.ExpandMore,
            onClick = onToggleOther
        )
    }
}

@Composable
private fun DownloadRow(progress: DownloadProgress, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            // A missing content length (chunked responses, some proxies) degrades to an
            // indeterminate bar plus a byte count instead of inventing a percentage.
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = progress.fraction
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = progressText(progress),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        IconActionRow(
            title = stringResource(R.string.update_download_cancel),
            icon = MiuixIcons.Close,
            onClick = onCancel
        )
    }
}

@Composable
private fun ErrorCard(error: UpdateError, onRetry: () -> Unit) {
    Card(Modifier.cardSpacing(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = error.message.ifBlank { stringResource(R.string.update_error_unknown) },
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface
            )
            // A verification failure already deleted the download, so retrying the same file would
            // only fail again; only transport failures offer a retry.
            if (error.retryable) {
                IconActionRow(
                    title = stringResource(R.string.update_retry),
                    icon = MiuixIcons.Refresh,
                    onClick = onRetry
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(message: String) {
    Text(
        text = message,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/**
 * The "new version" prompt, hosted by the activity root rather than by the update page.
 *
 * The silent check runs at app launch, so a prompt owned by the update page would only ever be seen
 * after navigating there — which is exactly what a background check exists to avoid. Hosting it at
 * the root makes it appear wherever the user happens to be, and [CenteredDialog] needs no `Scaffold`
 * host because it renders in its own window.
 *
 * It is a one-shot notification: shown once per discovered version per process, and closed as soon as
 * the download it started concludes. "Later" is deliberately not a permanent answer — the next launch
 * asks again — while the persisted skip list behind "skip this version" (D16) is how a release is
 * silenced for good.
 */
@Composable
internal fun UpdateAvailablePrompt(manager: UpdateManager) {
    val state by manager.state.collectAsState()
    val isChinese = remember { Locale.getDefault().language.equals("zh", ignoreCase = true) }

    var promptedKey by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloadStarted by remember { mutableStateOf(false) }

    val offered = when (val current = state) {
        is UpdateUiState.Available -> current.info
        is UpdateUiState.Downloading -> current.info
        else -> null
    }

    LaunchedEffect(offered?.cacheKey) {
        val key = offered?.cacheKey ?: return@LaunchedEffect
        if (key != promptedKey) {
            promptedKey = key
            dismissed = false
            downloadStarted = false
        }
    }

    // Close once the work this prompt started has concluded. Without this, a hand-off that the user
    // cancels leaves the state back at `Available` and the dialog would bounce straight back up.
    LaunchedEffect(state) {
        when (state) {
            is UpdateUiState.Downloading -> downloadStarted = true
            is UpdateUiState.AwaitingUnknownSources, is UpdateUiState.Error -> dismissed = true
            is UpdateUiState.Available -> if (downloadStarted) dismissed = true
            else -> Unit
        }
    }

    val info = offered ?: return
    if (dismissed || promptedKey != info.cacheKey) return

    AvailableDialog(
        info = info,
        isChinese = isChinese,
        progress = (state as? UpdateUiState.Downloading)?.progress,
        hasCachedApk = remember(info.cacheKey, state::class) { manager.hasCachedDownload(info) },
        onDismiss = { dismissed = true },
        onDownload = { manager.downloadAndInstall() },
        onShare = { manager.shareDirect() },
        onOpenReleasePage = { manager.openReleasePage() },
        onSkip = {
            manager.skipCurrent()
            dismissed = true
        }
    )
}

/**
 * Discovery prompt for a newer build.
 *
 * A window-backed centred dialog, not an overlay attached to the page: this is a prompt about the
 * page, and it must stay reachable while the page behind it scrolls. The changelog is previewed
 * only — the page card carries the full log and the secondary actions — so the prompt stays a
 * two-choice decision.
 */
@Composable
private fun AvailableDialog(
    info: UpdateInfo,
    isChinese: Boolean,
    progress: DownloadProgress?,
    hasCachedApk: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onSkip: () -> Unit
) {
    val curated = info.notes?.preferred(isChinese)?.takeIf { it.isNotBlank() }
    val preview: List<ChangeGroupView> = if (curated != null) {
        parseCuratedNotes(curated).take(DIALOG_GROUPS)
            .map { ChangeGroupView(it.first, null, it.second.take(DIALOG_ITEMS)) }
    } else {
        UpdateChangeLog.group(info.commits).take(DIALOG_GROUPS)
            .map { ChangeGroupView(null, it.type, it.items.take(DIALOG_ITEMS)) }
    }
    val summary = buildList {
        add(
            stringResource(
                R.string.update_current_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
        )
        info.apkSize?.let { add(formatBytes(it)) }
        info.distance?.takeIf { it > 0 }?.let {
            add(pluralStringResource(R.plurals.update_release_distance, it, it))
        }
    }.joinToString(" · ")

    CenteredDialog(
        show = true,
        title = stringResource(R.string.update_status_available, info.versionName),
        summary = summary,
        onDismissRequest = onDismiss,
        content = {
            // A long changelog must not push the buttons out of the dialog, so the preview scrolls
            // within a bounded height while the actions stay pinned below it.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = DIALOG_PREVIEW_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
            ) {
                preview.forEachIndexed { index, group ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    val heading = group.heading ?: group.type?.let { stringResource(groupTitleRes(it)) }
                    if (heading != null) {
                        Text(
                            text = heading,
                            style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    group.items.forEach { item ->
                        Text(
                            text = "• $item",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = progress.fraction
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = progressText(progress),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.height(12.dp))
            ReleaseButtons(
                skipped = false,
                hasCachedApk = hasCachedApk,
                downloading = progress != null,
                onDownload = onDownload,
                onShare = onShare,
                onOpenReleasePage = onOpenReleasePage,
                onSkip = onSkip,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
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

/**
 * One-line status for the update entry points (the About row and the check row on this page).
 * Shared so both surfaces cannot drift apart.
 */
@Composable
internal fun updateStatusSummary(state: UpdateUiState, lastCheckedAt: Long): String {
    val text = when (state) {
        is UpdateUiState.Checking -> stringResource(R.string.update_status_checking)
        is UpdateUiState.Available -> stringResource(R.string.update_status_available, state.info.versionName)
        is UpdateUiState.Skipped -> stringResource(R.string.update_status_skipped, state.info.versionName)
        is UpdateUiState.UpToDate -> stringResource(R.string.update_status_up_to_date, state.info?.versionName.orEmpty())
        is UpdateUiState.Error -> stringResource(R.string.update_status_check_failed)
        is UpdateUiState.Downloading -> stringResource(R.string.update_status_available, state.info.versionName)
        is UpdateUiState.AwaitingUnknownSources -> stringResource(R.string.update_unknown_sources_title)
        UpdateUiState.Idle -> if (lastCheckedAt > 0L) {
            stringResource(R.string.update_last_checked, formatEpochMillis(lastCheckedAt))
        } else {
            stringResource(R.string.update_status_idle)
        }
    }
    // A cached answer also names when it was fetched, so a restored result cannot be mistaken for a
    // fresh one.
    val checkedAt = (state as? UpdateUiState.UpToDate)?.checkedAt ?: 0L
    return if (state is UpdateUiState.UpToDate && checkedAt > 0L) {
        "$text · ${stringResource(R.string.update_last_checked, formatEpochMillis(checkedAt))}"
    } else {
        text
    }
}

@Composable
private fun intervalLabel(interval: UpdateCheckInterval): String = stringResource(
    when (interval) {
        UpdateCheckInterval.DAILY -> R.string.update_check_interval_daily
        UpdateCheckInterval.WEEKLY -> R.string.update_check_interval_weekly
        UpdateCheckInterval.MONTHLY -> R.string.update_check_interval_monthly
        UpdateCheckInterval.NEVER -> R.string.update_check_interval_never
    }
)

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

private fun progressText(progress: DownloadProgress): String {
    val speed = progress.bytesPerSecond?.takeIf { it > 0L }?.let { formatBytes(it) }
    val total = progress.totalBytes?.takeIf { it > 0L }
    return when {
        total != null && speed != null -> "${formatBytes(progress.downloadedBytes)} / ${formatBytes(total)} · $speed/s"
        total != null -> "${formatBytes(progress.downloadedBytes)} / ${formatBytes(total)}"
        else -> formatBytes(progress.downloadedBytes)
    }
}

private data class ChangelogPlan(
    val titleRes: Int,
    val collapsed: Boolean,
    val summaryOnly: Boolean
)

/** Presentation strength scales with the distance from the installed build (plan §4.3). */
private fun changelogPlan(distance: Int?): ChangelogPlan = when {
    distance == null -> ChangelogPlan(R.string.update_changelog_recent, false, false)
    distance <= 10 -> ChangelogPlan(R.string.update_changelog_recent, false, false)
    distance <= 50 -> ChangelogPlan(R.string.update_changelog, true, false)
    distance <= 250 -> ChangelogPlan(R.string.update_changelog_major, true, false)
    else -> ChangelogPlan(R.string.update_changelog, true, true)
}

private fun groupTitleRes(type: String): Int = when (type.lowercase()) {
    "feat", "feature" -> R.string.update_group_feat
    "fix", "bugfix" -> R.string.update_group_fix
    "perf" -> R.string.update_group_perf
    "refactor" -> R.string.update_group_refactor
    "style" -> R.string.update_group_style
    "docs" -> R.string.update_group_docs
    "build" -> R.string.update_group_build
    "ci" -> R.string.update_group_ci
    "chore" -> R.string.update_group_chore
    else -> R.string.update_group_other
}

/**
 * Parses the deliberately small notes dialect emitted by `.github/release-notes/<tag>.md`:
 * `### heading` opens a group and `- item` adds an entry. Anything else is treated as body text so
 * an unstructured note still renders instead of disappearing.
 */
private fun parseCuratedNotes(markdown: String): List<Pair<String?, List<String>>> {
    val groups = mutableListOf<Pair<String?, MutableList<String>>>()
    markdown.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("###") -> groups += line.removePrefix("###").trim() to mutableListOf()
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                if (groups.isEmpty()) groups += null to mutableListOf()
                groups.last().second += line.drop(2).trim()
            }
            else -> {
                if (groups.isEmpty()) groups += null to mutableListOf()
                groups.last().second += line
            }
        }
    }
    return groups.map { it.first to it.second.toList() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatEpochMillis(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Accepts both ISO-8601 shapes this feature produces: an instant from GitHub
 * (`2026-09-12T02:35:00Z`) and an offset from `git log --format=%cI`
 * (`2026-09-12T10:35:00+08:00`). An unparseable value is shown as-is rather than dropped.
 */
private fun formatIsoTimestamp(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val instant = runCatching { java.time.Instant.parse(value) }
        .getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: return value
    return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}

private const val COLLAPSED_ITEMS = 3
private const val DIALOG_GROUPS = 3
private const val DIALOG_ITEMS = 3

/** Keeps a long changelog preview from pushing the dialog's actions off-screen. */
private val DIALOG_PREVIEW_MAX_HEIGHT = 240.dp

/** Groups hidden on the CI channel unless the user asks for them (plan §4.3). */
private val SECONDARY_TYPES = setOf("chore", "build", "ci", "docs", "refactor", "style")
