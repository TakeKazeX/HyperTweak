package com.takekazex.hypertweak.ui.page.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppIconButton as IconButton
import com.takekazex.hypertweak.util.update.DownloadProgress
import com.takekazex.hypertweak.util.update.UpdateChangeLog
import com.takekazex.hypertweak.util.update.UpdateChannel
import com.takekazex.hypertweak.util.update.UpdateError
import com.takekazex.hypertweak.util.update.UpdateInfo
import com.takekazex.hypertweak.util.update.UpdateUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Each state that has a version worth showing; the rest only have a status line. */
internal val UpdateUiState.hasReleaseSection: Boolean
    get() = when (this) {
        is UpdateUiState.Available,
        is UpdateUiState.Skipped,
        is UpdateUiState.Downloading,
        is UpdateUiState.AwaitingUnknownSources -> true
        is UpdateUiState.Error -> info != null
        else -> false
    }

/** The version being offered, whichever state is currently showing it. */
internal val UpdateUiState.releaseInfo: UpdateInfo?
    get() = when (this) {
        is UpdateUiState.Available -> info
        is UpdateUiState.Skipped -> info
        is UpdateUiState.Downloading -> info
        is UpdateUiState.AwaitingUnknownSources -> info
        is UpdateUiState.Error -> info
        else -> null
    }

@Composable
internal fun CurrentVersionRow() {
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
internal fun ReleaseCard(
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
internal fun ReleaseButtons(
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
internal class ChangeGroupView(val heading: String?, val type: String?, val items: List<String>)

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
internal fun ErrorCard(error: UpdateError, onRetry: () -> Unit) {
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

internal fun groupTitleRes(type: String): Int = when (type.lowercase()) {
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
internal fun parseCuratedNotes(markdown: String): List<Pair<String?, List<String>>> {
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

private const val COLLAPSED_ITEMS = 3

/** Groups hidden on the CI channel unless the user asks for them (plan §4.3). */
private val SECONDARY_TYPES = setOf("chore", "build", "ci", "docs", "refactor", "style")
