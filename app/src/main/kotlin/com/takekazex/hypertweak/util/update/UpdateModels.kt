package com.takekazex.hypertweak.util.update

import java.io.File

enum class UpdateChannel(
    val wireValue: String,
    val releaseApiPath: String,
    val defaultTag: String
) {
    CI("ci", "/releases/tags/ci-latest", "ci-latest"),
    STABLE("stable", "/releases/latest", "latest");

    companion object {
        fun fromValue(value: String?): UpdateChannel =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: STABLE
    }
}

enum class UpdateProxyMode(val wireValue: String) {
    OFFICIAL("official"),
    THIRD_PARTY("third_party");

    companion object {
        fun fromValue(value: String?): UpdateProxyMode =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: OFFICIAL
    }
}

enum class UpdateCheckInterval(val index: Int, val intervalMillis: Long?) {
    DAILY(0, 24L * 60L * 60L * 1000L),
    WEEKLY(1, 7L * 24L * 60L * 60L * 1000L),
    MONTHLY(2, 30L * 24L * 60L * 60L * 1000L),
    NEVER(3, null);

    companion object {
        fun fromIndex(index: Int): UpdateCheckInterval =
            entries.firstOrNull { it.index == index } ?: DAILY
    }
}

data class CommitChange(
    val hash: String,
    val subject: String,
    val type: String
)

data class UpdateNotes(
    val zh: String?,
    val en: String?
) {
    fun preferred(isChinese: Boolean): String? =
        if (isChinese) zh?.takeIf { it.isNotBlank() } ?: en else en?.takeIf { it.isNotBlank() } ?: zh
}

data class UpdateInfo(
    val channel: UpdateChannel,
    val tagName: String,
    val versionName: String,
    val versionCode: Long?,
    val commit: String?,
    val shortCommit: String,
    val builtAt: String?,
    val compareBase: String?,
    val apkAsset: String,
    val apkSize: Long?,
    val apkSha256: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val notes: UpdateNotes?,
    val releaseUrl: String,
    val downloadUrl: String,
    val publishedAt: String?,
    val distance: Int?,
    val commits: List<CommitChange> = emptyList(),
    val metadataPresent: Boolean = true
) {
    val cacheKey: String
        get() = "${channel.wireValue}|${versionCode ?: -1L}|${commit.orEmpty()}"

    val hasIntegrityMetadata: Boolean
        get() = apkSize != null && apkSize > 0L && apkSha256?.matches(HEX_SHA256) == true

    companion object {
        private val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

enum class UpdateErrorKind {
    NETWORK,
    RATE_LIMITED,
    HTTP,
    INVALID_METADATA,
    INTEGRITY,
    SIGNATURE,
    INSTALL,
    UNKNOWN_SOURCES,
    STALE_ASSET,
    UNSUPPORTED,
    UNKNOWN
}

data class UpdateError(
    val kind: UpdateErrorKind,
    val message: String,
    val retryable: Boolean = true
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data class Checking(val previous: UpdateUiState? = null) : UpdateUiState

    data class Available(
        val info: UpdateInfo,
        val usedProxyFallback: Boolean = false,
        val staleAssetNotice: Boolean = false
    ) : UpdateUiState

    data class Skipped(val info: UpdateInfo) : UpdateUiState

    data class UpToDate(
        val info: UpdateInfo?,
        val checkedAt: Long,
        val usedProxyFallback: Boolean = false
    ) : UpdateUiState

    data class Downloading(
        val info: UpdateInfo,
        val progress: DownloadProgress
    ) : UpdateUiState

    data class AwaitingUnknownSources(
        val info: UpdateInfo,
        val file: File
    ) : UpdateUiState

    data class Error(
        val error: UpdateError,
        val info: UpdateInfo? = null
    ) : UpdateUiState
}

data class DownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let {
            (downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f)
        }
}

data class DownloadedUpdate(
    val file: File,
    val size: Long,
    val sha256: String,
    val usedProxyFallback: Boolean = false
)

data class UpdateCheckResult(
    val state: UpdateUiState,
    val usedProxyFallback: Boolean = false,
    val fromCache: Boolean = false
)

data class ArmRecord(
    val armedAtWallClock: Long,
    val armedAtElapsed: Long,
    val bootSessionKey: Long,
    val previousLastUpdateTime: Long,
    val targetVersionCode: Long?,
    val targetVersionName: String,
    val targetCommit: String?,
    val targetApkSha256: String?,
    val apkPath: String,
    val expectedSignerDigests: Set<String>
)

data class CompletionNotice(
    val versionName: String,
    val versionCode: Long?
)

sealed interface ProxyTestState {
    data object Idle : ProxyTestState
    data object Testing : ProxyTestState
    data class Success(val usedProxy: Boolean) : ProxyTestState
    data class Failure(val apiReachable: Boolean, val assetReachable: Boolean) : ProxyTestState
}

sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    class Http(val status: Int, message: String) : UpdateException(message)

    class InvalidMetadata(message: String) : UpdateException(message)

    class Integrity(message: String) : UpdateException(message)

    class Signature(message: String) : UpdateException(message)

    class Install(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    class StaleAsset : UpdateException("The rolling CI asset is no longer available")

    class Redirect(message: String) : UpdateException(message)
}
