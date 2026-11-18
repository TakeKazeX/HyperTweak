package com.takekazex.hypertweak.ui.page.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.components.dialog.CenteredDialog
import com.takekazex.hypertweak.util.update.DownloadProgress
import com.takekazex.hypertweak.util.update.UpdateChangeLog
import com.takekazex.hypertweak.util.update.UpdateInfo
import com.takekazex.hypertweak.util.update.UpdateManager
import com.takekazex.hypertweak.util.update.UpdateUiState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val state by manager.state.collectAsStateWithLifecycle()
    val isChinese = LocalConfiguration.current.locales[0].language.equals("zh", ignoreCase = true)

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

/** Keeps a long changelog preview from pushing the dialog's actions off-screen. */
private const val DIALOG_GROUPS = 3
private const val DIALOG_ITEMS = 3

private val DIALOG_PREVIEW_MAX_HEIGHT = 240.dp
