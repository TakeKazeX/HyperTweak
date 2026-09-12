package com.takekazex.hypertweak.util.update

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Owns only update-session state. It deliberately does not depend on hook/Preferences: timestamps,
 * caches, skipped builds, download sidecars, and recovery arms must never enter the module settings
 * backup or the LSPosed daemon's cross-process preference channel.
 */
class UpdateStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun lastAttemptAt(channel: UpdateChannel): Long = prefs.getLong(key(channel, SUFFIX_LAST_ATTEMPT), 0L)

    fun lastSuccessAt(channel: UpdateChannel): Long = prefs.getLong(key(channel, SUFFIX_LAST_SUCCESS), 0L)

    fun recordAttempt(channel: UpdateChannel, now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(key(channel, SUFFIX_LAST_ATTEMPT), now) }
    }

    fun shouldCheck(
        channel: UpdateChannel,
        interval: UpdateCheckInterval,
        now: Long = System.currentTimeMillis()
    ): Boolean = isCheckDue(lastAttemptAt(channel), interval, now)

    fun saveCached(info: UpdateInfo, checkedAt: Long = System.currentTimeMillis()) {
        prefs.edit {
            putString(cacheKey(info.channel), UpdateJsonCodec.encode(info))
            putLong(key(info.channel, SUFFIX_LAST_SUCCESS), checkedAt)
        }
    }

    fun cachedInfo(channel: UpdateChannel): UpdateInfo? =
        prefs.getString(cacheKey(channel), null)?.let(UpdateJsonCodec::decode)

    /**
     * Finds a completed APK for exactly this release.
     *
     * The sidecar prevents a rolling CI asset with the same filename from being mistaken for the
     * current build. The file size is checked here because this method is also used by the UI to
     * decide whether the primary action should say "Install"; the install path can additionally
     * request a full checksum pass before the archive and signing checks hand the file to Android.
     */
    fun cachedDownload(info: UpdateInfo, verifyContents: Boolean = false): DownloadedUpdate? {
        val assetName = safeAssetName(info.apkAsset) ?: return null
        val file = File(updatesDirectory(), assetName)
        if (!file.isFile || file.length() <= 0L) return null
        if (info.apkSize != null && file.length() != info.apkSize) return null

        val sidecar = File(file.parentFile, "$assetName.json")
        val metadata = if (sidecar.isFile) {
            runCatching { JSONObject(sidecar.readText()) }.getOrNull() ?: return null
        } else {
            null
        }
        if (metadata != null) {
            if (metadata.optString("channel", "") != info.channel.wireValue ||
                metadata.optionalLong("versionCode") != info.versionCode ||
                metadata.optionalString("commit") != info.commit
            ) {
                return null
            }
            val sha256 = metadata.optionalString("sha256") ?: return null
            if (!sha256.matches(HEX_SHA256) ||
                (info.apkSha256 != null && !sha256.equals(info.apkSha256, ignoreCase = true))
            ) {
                return null
            }
            if (verifyContents && info.apkSha256 != null &&
                !file.sha256().equals(info.apkSha256, ignoreCase = true)
            ) {
                return null
            }
            return DownloadedUpdate(file, file.length(), sha256.lowercase())
        }

        // A sidecar write is best-effort for older downloads. The package archive and, when
        // available, the metadata checksum are still validated on the install path.
        if (verifyContents && info.apkSha256 != null &&
            !file.sha256().equals(info.apkSha256, ignoreCase = true)
        ) {
            return null
        }
        return DownloadedUpdate(file, file.length(), info.apkSha256.orEmpty())
    }

    fun deleteCachedDownload(info: UpdateInfo) {
        val assetName = safeAssetName(info.apkAsset) ?: return
        val directory = updatesDirectory()
        runCatching { File(directory, assetName).delete() }
        runCatching { File(directory, "$assetName.json").delete() }
    }

    fun skip(info: UpdateInfo) {
        val next = skipped().toMutableSet().apply { add(info.cacheKey) }
        prefs.edit(commit = true) { putStringSet(KEY_SKIPPED, next) }
    }

    fun unskip(info: UpdateInfo) {
        val next = skipped().toMutableSet().apply { remove(info.cacheKey) }
        prefs.edit(commit = true) { putStringSet(KEY_SKIPPED, next) }
    }

    fun isSkipped(info: UpdateInfo): Boolean = info.cacheKey in skipped()

    fun arm(record: ArmRecord) {
        val json = JSONObject()
            .put("armedAtWallClock", record.armedAtWallClock)
            .put("armedAtElapsed", record.armedAtElapsed)
            .put("bootSessionKey", record.bootSessionKey)
            .put("previousLastUpdateTime", record.previousLastUpdateTime)
            .put("targetVersionName", record.targetVersionName)
            .put("apkPath", record.apkPath)
            .put("expectedSignerDigests", record.expectedSignerDigests.toList())
        record.targetVersionCode?.let { json.put("targetVersionCode", it) }
        record.targetCommit?.let { json.put("targetCommit", it) }
        record.targetApkSha256?.let { json.put("targetApkSha256", it) }
        prefs.edit(commit = true) { putString(KEY_ARM, json.toString()) }
    }

    fun arm(): ArmRecord? = runCatching {
        val json = prefs.getString(KEY_ARM, null)?.let(::JSONObject) ?: return null
        val signerArray = json.optJSONArray("expectedSignerDigests")
        val signers = buildSet {
            if (signerArray != null) {
                for (index in 0 until signerArray.length()) add(signerArray.optString(index))
            }
        }
        ArmRecord(
            armedAtWallClock = json.optLong("armedAtWallClock"),
            armedAtElapsed = json.optLong("armedAtElapsed"),
            bootSessionKey = json.optLong("bootSessionKey"),
            previousLastUpdateTime = json.optLong("previousLastUpdateTime"),
            targetVersionCode = json.longOrNull("targetVersionCode"),
            targetVersionName = json.optString("targetVersionName", ""),
            targetCommit = json.optString("targetCommit", "").takeIf(String::isNotBlank),
            targetApkSha256 = json.optString("targetApkSha256", "").takeIf(String::isNotBlank),
            apkPath = json.optString("apkPath", ""),
            expectedSignerDigests = signers
        )
    }.getOrNull()

    fun clearArm() {
        prefs.edit(commit = true) { remove(KEY_ARM) }
    }

    fun markCompletionNotice(notice: CompletionNotice) {
        prefs.edit(commit = true) {
            putString(
                KEY_COMPLETION,
                JSONObject().put("versionName", notice.versionName).apply {
                    notice.versionCode?.let { put("versionCode", it) }
                }.toString()
            )
        }
    }

    fun consumeCompletionNotice(): CompletionNotice? {
        val value = runCatching {
            val json = prefs.getString(KEY_COMPLETION, null)?.let(::JSONObject) ?: return null
            CompletionNotice(
                versionName = json.optString("versionName", ""),
                versionCode = json.longOrNull("versionCode")
            )
        }.getOrNull()
        if (value != null) prefs.edit(commit = true) { remove(KEY_COMPLETION) }
        return value
    }

    fun updatesDirectory(): File = File(appContext.filesDir, UPDATES_DIRECTORY).also { it.mkdirs() }

    fun cleanupExpiredFiles(
        now: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ) {
        val protectedPath = arm()?.apkPath
        updatesDirectory().listFiles().orEmpty().forEach { file ->
            if (file.absolutePath == protectedPath) return@forEach
            if (now - file.lastModified() >= ttlMillis) runCatching { file.delete() }
        }
    }

    /**
     * Deletes every downloaded package, and reports how many bytes that reclaimed.
     *
     * Unlike the automatic 24-hour sweep this does **not** spare the package the recovery arm points
     * at. The sweep spares it because a hand-off may still be reading it, but an explicit "delete the
     * downloaded updates" means exactly that — sparing it left the file on disk, so `cachedDownload`
     * kept finding it and the page kept offering an install the user had just deleted.
     *
     * The arm record goes with the files: reconciliation would otherwise keep pointing at a package
     * that no longer exists, and it cannot succeed for a build the user has discarded anyway.
     */
    fun clearDownloadedUpdates(): Long {
        var removedBytes = 0L
        updatesDirectory().listFiles().orEmpty().forEach { file ->
            removedBytes += file.length()
            runCatching { file.delete() }
        }
        clearArm()
        return removedBytes
    }

    fun downloadedBytes(): Long = updatesDirectory().listFiles().orEmpty().sumOf(File::length)

    private fun skipped(): Set<String> = prefs.getStringSet(KEY_SKIPPED, emptySet()).orEmpty()

    private fun cacheKey(channel: UpdateChannel): String = key(channel, SUFFIX_CACHED_INFO)

    private fun key(channel: UpdateChannel, suffix: String): String = "${channel.wireValue}_$suffix"

    private fun safeAssetName(name: String): String? =
        name.takeIf {
            it.isNotBlank() && it.length <= 180 && it != "." && it != ".." &&
                !it.contains('/') && !it.contains('\\') && !it.contains("..")
        }

    private fun File.sha256(): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrDefault("")

    internal companion object {
        private val HEX_SHA256 = Regex("[0-9a-fA-F]{64}")
        const val NAME = "hypertweak_update"
        const val UPDATES_DIRECTORY = "updates"
        const val KEY_SKIPPED = "skipped_versions"
        const val KEY_ARM = "recovery_arm"
        const val KEY_COMPLETION = "completion_notice"
        const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1000L

        const val SUFFIX_LAST_ATTEMPT = "last_attempt"
        const val SUFFIX_LAST_SUCCESS = "last_success"
        const val SUFFIX_CACHED_INFO = "cached_info"

        /**
         * Every key this store owns, counted per channel where the key is channel-scoped. Nothing in
         * here may ever also be written through `hook/Preferences`: that store is blacklist-filtered
         * into the user's settings backup, so leaking a timestamp or a completion flag into it would
         * pollute every exported backup. `UpdateStateIsolationTest` asserts the two sets stay apart.
         */
        val OWNED_KEYS: Set<String> = buildSet {
            add(KEY_SKIPPED)
            add(KEY_ARM)
            add(KEY_COMPLETION)
            addAll(UpdateChannel.entries.map { channel ->
                listOf(SUFFIX_LAST_ATTEMPT, SUFFIX_LAST_SUCCESS, SUFFIX_CACHED_INFO).map {
                    "${channel.wireValue}_$it"
                }
            }.flatten())
        }

        /**
         * Whether a scheduled check is due.
         *
         * Deliberately driven by the *attempt* timestamp, not the success one: a failed check must
         * still consume the interval, otherwise an offline device would retry on every launch and
         * burn through the anonymous GitHub rate limit. A last-attempt stamp in the future means the
         * clock moved backwards, in which case checking immediately is the safer answer.
         */
        fun isCheckDue(lastAttemptAt: Long, interval: UpdateCheckInterval, now: Long): Boolean {
            val window = interval.intervalMillis ?: return false
            return lastAttemptAt <= 0L || now < lastAttemptAt || now - lastAttemptAt >= window
        }

        fun JSONObject.longOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

        private fun JSONObject.optionalLong(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

        private fun JSONObject.optionalString(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    }
}
