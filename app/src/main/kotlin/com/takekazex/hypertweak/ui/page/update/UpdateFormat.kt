package com.takekazex.hypertweak.ui.page.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.update.DownloadProgress
import com.takekazex.hypertweak.util.update.UpdateCheckInterval
import com.takekazex.hypertweak.util.update.UpdateUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
internal fun intervalLabel(interval: UpdateCheckInterval): String = stringResource(
    when (interval) {
        UpdateCheckInterval.DAILY -> R.string.update_check_interval_daily
        UpdateCheckInterval.WEEKLY -> R.string.update_check_interval_weekly
        UpdateCheckInterval.MONTHLY -> R.string.update_check_interval_monthly
        UpdateCheckInterval.NEVER -> R.string.update_check_interval_never
    }
)

internal fun progressText(progress: DownloadProgress): String {
    val speed = progress.bytesPerSecond?.takeIf { it > 0L }?.let { formatBytes(it) }
    val total = progress.totalBytes?.takeIf { it > 0L }
    return when {
        total != null && speed != null -> "${formatBytes(progress.downloadedBytes)} / ${formatBytes(total)} · $speed/s"
        total != null -> "${formatBytes(progress.downloadedBytes)} / ${formatBytes(total)}"
        else -> formatBytes(progress.downloadedBytes)
    }
}

internal fun formatBytes(bytes: Long): String = when {
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
internal fun formatIsoTimestamp(raw: String?): String? {
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
