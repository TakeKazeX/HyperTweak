package com.takekazex.hypertweak.util.update

import android.content.Context
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class GitHubUpdateRepository(
    context: Context,
    private val stateStore: UpdateStateStore
) {
    private val appContext = context.applicationContext

    suspend fun checkUpdate(
        channel: UpdateChannel,
        force: Boolean,
        now: Long = System.currentTimeMillis()
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val interval = UpdateCheckInterval.fromIndex(
            Preferences.getInt(Preferences.KEY_UPDATE_CHECK_INTERVAL, UpdateCheckInterval.DAILY.index)
        )
        if (!force && !stateStore.shouldCheck(channel, interval, now)) {
            val cached = stateStore.cachedInfo(channel)
            if (cached != null) {
                return@withContext classify(cached, stateStore.lastSuccessAt(channel), fromCache = true)
            }
        }

        stateStore.recordAttempt(channel, now)
        val configuredMode = UpdateProxyMode.fromValue(
            Preferences.getString(Preferences.KEY_UPDATE_PROXY_MODE, UpdateProxyMode.OFFICIAL.wireValue)
        )
        val configuredProxy = Preferences.getString(Preferences.KEY_UPDATE_PROXY_URL, "")
        val releaseEndpoint = "${UpdateNetworkPolicy.API_BASE}${channel.releaseApiPath}"

        val releaseCall = callWithProxyFallback(configuredMode, configuredProxy) { mode ->
            requestJson(
                originalUrl = releaseEndpoint,
                mode = mode,
                proxy = configuredProxy,
                endpoint = Endpoint.API,
                maxBytes = MAX_JSON_BYTES
            )
        }

        val releaseJson = if (
            releaseCall.response.status == HttpURLConnection.HTTP_NOT_FOUND && channel == UpdateChannel.CI
        ) {
            // A fresh checkout may be used before the first rolling CI release exists. Probe
            // the stable endpoint so the user still gets a useful answer instead of a dead page.
            val stableEndpoint = "${UpdateNetworkPolicy.API_BASE}${UpdateChannel.STABLE.releaseApiPath}"
            val stableCall = callWithProxyFallback(configuredMode, configuredProxy) { mode ->
                requestJson(
                    originalUrl = stableEndpoint,
                    mode = mode,
                    proxy = configuredProxy,
                    endpoint = Endpoint.API,
                    maxBytes = MAX_JSON_BYTES
                )
            }
            if (stableCall.response.status !in 200..299) throw httpException(stableCall.response.status)
            stableCall.response.body.toJsonObject()
        } else if (releaseCall.response.status in 200..299) {
            releaseCall.response.body.toJsonObject()
        } else {
            throw httpException(releaseCall.response.status)
        }

        val releaseTag = releaseJson.optString("tag_name", channel.defaultTag)
        val releaseUrl = releaseJson.optString(
            "html_url",
            "${UpdateNetworkPolicy.WEB_BASE}/releases/tag/$releaseTag"
        )
        val publishedAt = releaseJson.optString("published_at", "").takeIf(String::isNotBlank)
        val assets = releaseJson.optJSONArray("assets") ?: JSONArray()
        val metadataAsset = findAsset(assets, "build-info.json")
        val apkAssetFromRelease = findFirstApkAsset(assets)
            ?: throw UpdateException.InvalidMetadata("Release has no APK asset")

        val selectedMode = releaseCall.modeUsed
        val metadata = if (metadataAsset != null) {
            if (!UpdateNetworkPolicy.isAllowedGithubAssetUrl(metadataAsset.url, metadataAsset.name)) {
                throw UpdateException.InvalidMetadata("Metadata asset URL is outside the repository")
            }
            val metadataCall = callWithProxyFallback(selectedMode, configuredProxy) { mode ->
                requestJson(
                    originalUrl = metadataAsset.url,
                    mode = mode,
                    proxy = configuredProxy,
                    endpoint = Endpoint.ASSET,
                    maxBytes = MAX_METADATA_BYTES
                )
            }
            metadataCall.response.body.toJsonObject()
        } else {
            null
        }

        val apkAssetName = metadata?.optString("apkAsset", "").orEmpty().ifBlank { apkAssetFromRelease.name }
        val apkAsset = findAsset(assets, apkAssetName)
            ?: throw UpdateException.InvalidMetadata("Metadata points to a missing APK asset")
        if (!UpdateNetworkPolicy.isAllowedGithubAssetUrl(apkAsset.url, apkAsset.name)) {
            throw UpdateException.InvalidMetadata("APK asset URL is outside the repository")
        }

        val info = if (metadata != null) {
            parseBuildInfo(
                metadata = metadata,
                channel = channel,
                tagName = releaseTag,
                releaseUrl = releaseUrl,
                publishedAt = publishedAt,
                apkAsset = apkAsset
            )
        } else {
            parseLegacyRelease(
                channel = channel,
                tagName = releaseTag,
                releaseUrl = releaseUrl,
                publishedAt = publishedAt,
                apkAsset = apkAsset
            )
        }

        val compare = if (isPotentiallyNewer(info)) {
            fetchCompare(info.commit, selectedMode, configuredProxy)
        } else {
            null
        }
        val finalInfo = info.copy(
            distance = compare?.distance ?: info.distance ?: fallbackDistance(info),
            commits = compare?.commits ?: info.commits
        )
        stateStore.saveCached(finalInfo, now)
        val classified = classify(finalInfo, now)
        UpdateCheckResult(
            state = classified.state,
            usedProxyFallback = releaseCall.usedFallback,
            fromCache = false
        )
    }

    /**
     * The answer from the last successful check, re-classified against the *currently* installed
     * build so a cached "new version" cannot go stale after the user actually updates.
     *
     * This exists so opening the app never has to hit the network to show something useful: the
     * stored metadata is enough to render the version, size and changelog, and a network query
     * only ever refreshes it.
     */
    fun cachedState(channel: UpdateChannel): UpdateUiState? {
        val cached = stateStore.cachedInfo(channel) ?: return null
        return classify(cached, stateStore.lastSuccessAt(channel), fromCache = true).state
    }

    suspend fun downloadUpdate(
        info: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadedUpdate = withContext(Dispatchers.IO) {
        if (!UpdateNetworkPolicy.isAllowedGithubAssetUrl(info.downloadUrl, info.apkAsset)) {
            throw UpdateException.InvalidMetadata("APK download URL is not a repository asset")
        }
        val configuredMode = UpdateProxyMode.fromValue(
            Preferences.getString(Preferences.KEY_UPDATE_PROXY_MODE, UpdateProxyMode.OFFICIAL.wireValue)
        )
        val configuredProxy = Preferences.getString(Preferences.KEY_UPDATE_PROXY_URL, "")
        val firstAttempt = runCatching {
            downloadOnce(info, configuredMode, configuredProxy, onProgress)
        }
        if (firstAttempt.isSuccess) return@withContext firstAttempt.getOrThrow()
        val failure = firstAttempt.exceptionOrNull() ?: UpdateException.Network("Download failed")
        if (configuredMode == UpdateProxyMode.THIRD_PARTY && isFallbackable(failure)) {
            return@withContext downloadOnce(info, UpdateProxyMode.OFFICIAL, configuredProxy, onProgress)
        }
        throw failure
    }

    suspend fun testProxy(): ProxyTestState = withContext(Dispatchers.IO) {
        val proxy = Preferences.getString(Preferences.KEY_UPDATE_PROXY_URL, "")
        val normalized = UpdateNetworkPolicy.normalizeProxy(proxy)
        if (normalized == null && proxy.isNotBlank()) {
            return@withContext ProxyTestState.Failure(apiReachable = false, assetReachable = false)
        }
        val mode = UpdateProxyMode.THIRD_PARTY
        val apiOk = runCatching {
            requestJson(
                originalUrl = "${UpdateNetworkPolicy.API_BASE}/releases/latest",
                mode = mode,
                proxy = proxy,
                endpoint = Endpoint.API,
                maxBytes = MAX_JSON_BYTES
            ).status in 200..299
        }.getOrDefault(false)

        val assetUrl = "${UpdateNetworkPolicy.WEB_BASE}/releases/download/ci-latest/build-info.json"
        val assetOk = runCatching {
            requestBytes(
                originalUrl = assetUrl,
                mode = mode,
                proxy = proxy,
                endpoint = Endpoint.ASSET,
                maxBytes = MAX_METADATA_BYTES,
                extraHeaders = mapOf("Range" to "bytes=0-1000")
            ).status in 200..299
        }.getOrDefault(false)
        if (apiOk && assetOk) ProxyTestState.Success(usedProxy = true)
        else ProxyTestState.Failure(apiReachable = apiOk, assetReachable = assetOk)
    }

    fun discardPartial(info: UpdateInfo) {
        val safeName = safeAssetName(info.apkAsset) ?: return
        val directory = stateStore.updatesDirectory()
        runCatching { File(directory, "$safeName.part").delete() }
    }

    private fun classify(info: UpdateInfo, checkedAt: Long, fromCache: Boolean = false): UpdateCheckResult {
        val state = if (isPotentiallyNewer(info)) {
            UpdateUiState.Available(info)
        } else {
            UpdateUiState.UpToDate(info, checkedAt)
        }
        return UpdateCheckResult(state = state, fromCache = fromCache)
    }

    private fun isPotentiallyNewer(info: UpdateInfo): Boolean = UpdateVersioning.isUpdateAvailable(
        localVersionCode = BuildConfig.VERSION_CODE.toLong(),
        localVersionName = BuildConfig.VERSION_NAME,
        remoteVersionCode = info.versionCode,
        remoteVersionName = info.versionName
    )

    private fun fallbackDistance(info: UpdateInfo): Int? =
        info.versionCode?.let { UpdateVersioning.distance(BuildConfig.VERSION_CODE.toLong(), it) }

    private fun parseBuildInfo(
        metadata: JSONObject,
        channel: UpdateChannel,
        tagName: String,
        releaseUrl: String,
        publishedAt: String?,
        apkAsset: Asset
    ): UpdateInfo {
        val schema = metadata.optInt("schema", -1)
        if (schema != 1) throw UpdateException.InvalidMetadata("Unsupported build-info schema")
        val versionName = metadata.optString("versionName", "").takeIf(String::isNotBlank)
            ?: throw UpdateException.InvalidMetadata("Missing versionName")
        val versionCode = metadata.longOrNull("versionCode")
            ?: throw UpdateException.InvalidMetadata("Missing versionCode")
        val apkName = metadata.optString("apkAsset", "")
        if (apkName != apkAsset.name) throw UpdateException.InvalidMetadata("APK asset mismatch")
        val apkSize = metadata.longOrNull("apkSize")
            ?: throw UpdateException.InvalidMetadata("Missing APK size")
        val apkSha256 = metadata.optString("apkSha256", "")
        if (apkSize <= 0L || apkSize > MAX_APK_BYTES || !apkSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw UpdateException.InvalidMetadata("Invalid APK integrity metadata")
        }
        val commit = metadata.optString("commit", "").takeIf(String::isNotBlank)
        val notes = metadata.optJSONObject("notes")?.let {
            UpdateNotes(
                zh = it.optString("zh", "").takeIf(String::isNotBlank),
                en = it.optString("en", "").takeIf(String::isNotBlank)
            )
        }
        return UpdateInfo(
            channel = channel,
            tagName = tagName,
            versionName = versionName,
            versionCode = versionCode,
            commit = commit,
            shortCommit = metadata.optString("shortCommit", commit.orEmpty().take(7)),
            builtAt = metadata.optString("builtAt", "").takeIf(String::isNotBlank),
            compareBase = metadata.optString("compareBase", "").takeIf(String::isNotBlank),
            apkAsset = apkAsset.name,
            apkSize = apkSize,
            apkSha256 = apkSha256.lowercase(),
            minSdk = metadata.intOrNull("minSdk"),
            targetSdk = metadata.intOrNull("targetSdk"),
            notes = notes,
            releaseUrl = releaseUrl,
            downloadUrl = apkAsset.url,
            publishedAt = publishedAt,
            distance = null,
            metadataPresent = true
        )
    }

    private fun parseLegacyRelease(
        channel: UpdateChannel,
        tagName: String,
        releaseUrl: String,
        publishedAt: String?,
        apkAsset: Asset
    ): UpdateInfo {
        val versionName = LEGACY_VERSION_PATTERNS.asSequence()
            .mapNotNull { it.find(apkAsset.name)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?: tagName.removePrefix("v").takeIf { it.isNotBlank() }
            ?: throw UpdateException.InvalidMetadata("Cannot determine legacy release version")
        return UpdateInfo(
            channel = channel,
            tagName = tagName,
            versionName = versionName,
            versionCode = null,
            commit = null,
            shortCommit = "",
            builtAt = null,
            compareBase = null,
            apkAsset = apkAsset.name,
            apkSize = null,
            apkSha256 = null,
            minSdk = null,
            targetSdk = null,
            notes = null,
            releaseUrl = releaseUrl,
            downloadUrl = apkAsset.url,
            publishedAt = publishedAt,
            distance = null,
            metadataPresent = false
        )
    }

    private fun fetchCompare(
        remoteCommit: String?,
        mode: UpdateProxyMode,
        proxy: String
    ): CompareResult? {
        val localCommit = BuildConfig.GIT_COMMIT_SHA.trim()
        val remote = remoteCommit?.trim().orEmpty()
        if (localCommit.isEmpty() || localCommit == "unknown" || remote.isEmpty() || localCommit == remote) {
            return CompareResult(distance = 0, commits = emptyList())
        }
        val encodedLocal = encodePathSegment(localCommit)
        val encodedRemote = encodePathSegment(remote)
        val endpoint = "${UpdateNetworkPolicy.API_BASE}/compare/$encodedLocal...$encodedRemote"
        return runCatching {
            val response = requestJson(
                originalUrl = endpoint,
                mode = mode,
                proxy = proxy,
                endpoint = Endpoint.API,
                maxBytes = MAX_COMPARE_BYTES
            )
            if (response.status !in 200..299) return@runCatching null
            val json = response.body.toJsonObject()
            val commits = json.optJSONArray("commits")?.let(::parseCommits).orEmpty()
            CompareResult(
                distance = json.optInt("ahead_by", commits.size).coerceAtLeast(0),
                commits = commits
            )
        }.getOrNull()
    }

    private fun parseCommits(array: JSONArray): List<CommitChange> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val commit = item.optJSONObject("commit") ?: continue
            val subject = commit.optString("message", "").lineSequence().firstOrNull().orEmpty()
            if (subject.isBlank()) continue
            val hash = item.optString("sha", "")
            add(UpdateChangeLog.parseCommit(hash, subject))
        }
    }

    private fun <T> callWithProxyFallback(
        configuredMode: UpdateProxyMode,
        proxy: String,
        block: (UpdateProxyMode) -> T
    ): ProxyCall<T> {
        if (configuredMode == UpdateProxyMode.OFFICIAL) return ProxyCall(block(UpdateProxyMode.OFFICIAL), UpdateProxyMode.OFFICIAL, false)
        return try {
            ProxyCall(block(UpdateProxyMode.THIRD_PARTY), UpdateProxyMode.THIRD_PARTY, false)
        } catch (failure: Throwable) {
            if (!isFallbackable(failure)) throw failure
            ProxyCall(block(UpdateProxyMode.OFFICIAL), UpdateProxyMode.OFFICIAL, true)
        }
    }

    private fun isFallbackable(failure: Throwable): Boolean = when (failure) {
        is UpdateException.Integrity,
        is UpdateException.InvalidMetadata,
        is UpdateException.Signature -> false
        is UpdateException.Http -> failure.status != HttpURLConnection.HTTP_NOT_FOUND
        else -> true
    }

    private fun requestJson(
        originalUrl: String,
        mode: UpdateProxyMode,
        proxy: String,
        endpoint: Endpoint,
        maxBytes: Long
    ): HttpResponse {
        val response = requestBytes(
            originalUrl = originalUrl,
            mode = mode,
            proxy = proxy,
            endpoint = endpoint,
            maxBytes = maxBytes,
            extraHeaders = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28"
            )
        )
        if (response.status !in 200..299 && response.status != HttpURLConnection.HTTP_NOT_FOUND) {
            throw httpException(response.status)
        }
        return response
    }

    private fun requestBytes(
        originalUrl: String,
        mode: UpdateProxyMode,
        proxy: String,
        endpoint: Endpoint,
        maxBytes: Long,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponse {
        when (endpoint) {
            Endpoint.API -> if (!UpdateNetworkPolicy.isAllowedApiUrl(originalUrl)) {
                throw UpdateException.Redirect("API endpoint is not trusted")
            }

            Endpoint.ASSET -> {
                val assetName = originalUrl.substringAfterLast('/').substringBefore('?')
                if (!UpdateNetworkPolicy.isAllowedGithubAssetUrl(originalUrl, assetName)) {
                    throw UpdateException.Redirect("Asset endpoint is not trusted")
                }
            }
        }
        var current = runCatching {
            URL(UpdateNetworkPolicy.requestUrl(mode, proxy, originalUrl))
        }.getOrElse { throw UpdateException.Redirect("Invalid update URL") }
        var redirects = 0
        while (true) {
            if (!UpdateNetworkPolicy.isAllowedRequestHost(current, mode, proxy)) {
                throw UpdateException.Redirect("Update host is not trusted")
            }
            val connection = runCatching { current.openConnection() as HttpURLConnection }
                .getOrElse { throw UpdateException.Network("Unable to open update connection", it) }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "HyperTweak-Update/${BuildConfig.VERSION_NAME}")
                extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = try {
                    connection.responseCode
                } catch (failure: Throwable) {
                    throw UpdateException.Network("Update server did not respond", failure)
                }
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateException.Redirect("Redirect has no location")
                    if (redirects >= MAX_REDIRECTS) throw UpdateException.Redirect("Too many update redirects")
                    val next = runCatching { current.toURI().resolve(location).toURL() }
                        .getOrElse { throw UpdateException.Redirect("Invalid update redirect") }
                    if (!UpdateNetworkPolicy.isHttps(next) ||
                        !UpdateNetworkPolicy.isAllowedRequestHost(next, mode, proxy)
                    ) {
                        throw UpdateException.Redirect("Update redirect is not trusted")
                    }
                    redirects++
                    current = next
                    continue
                }
                if (status !in 200..299) {
                    // Keep the status available to the release/compare callers. This is needed to
                    // distinguish a not-yet-created ci-latest release from an outage or rate limit.
                    return HttpResponse(
                        status = status,
                        body = ByteArray(0),
                        contentLength = connection.contentLengthLong,
                        finalUrl = current
                    )
                }
                val body = connection.inputStream.use { input -> readLimited(input, maxBytes) }
                return HttpResponse(
                    status = status,
                    body = body,
                    contentLength = connection.contentLengthLong,
                    finalUrl = current
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun downloadOnce(
        info: UpdateInfo,
        mode: UpdateProxyMode,
        proxy: String,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadedUpdate {
        var current = URL(UpdateNetworkPolicy.requestUrl(mode, proxy, info.downloadUrl))
        var redirects = 0
        var connection: HttpURLConnection? = null
        var successfulConnection: HttpURLConnection? = null
        try {
            while (true) {
                if (!UpdateNetworkPolicy.isAllowedRequestHost(current, mode, proxy)) {
                    throw UpdateException.Redirect("APK redirect is not trusted")
                }
                val openedConnection = runCatching { current.openConnection() as HttpURLConnection }
                    .getOrElse { throw UpdateException.Network("Unable to open APK connection", it) }
                connection = openedConnection
                val currentConnection = openedConnection
                currentConnection.instanceFollowRedirects = false
                currentConnection.connectTimeout = CONNECT_TIMEOUT_MS
                currentConnection.readTimeout = READ_TIMEOUT_MS
                currentConnection.requestMethod = "GET"
                currentConnection.setRequestProperty("User-Agent", "HyperTweak-Update/${BuildConfig.VERSION_NAME}")
                val status = try {
                    currentConnection.responseCode
                } catch (failure: Throwable) {
                    throw UpdateException.Network("APK server did not respond", failure)
                }
                if (status in 300..399) {
                    val location = currentConnection.getHeaderField("Location")
                        ?: throw UpdateException.Redirect("APK redirect has no location")
                    if (redirects >= MAX_REDIRECTS) throw UpdateException.Redirect("Too many APK redirects")
                    val next = runCatching { current.toURI().resolve(location).toURL() }
                        .getOrElse { throw UpdateException.Redirect("Invalid APK redirect") }
                    if (!UpdateNetworkPolicy.isHttps(next) ||
                        !UpdateNetworkPolicy.isAllowedRequestHost(next, mode, proxy)
                    ) {
                        throw UpdateException.Redirect("APK redirect is not trusted")
                    }
                    currentConnection.disconnect()
                    connection = null
                    current = next
                    redirects++
                    continue
                }
                if (status == HttpURLConnection.HTTP_NOT_FOUND) throw UpdateException.StaleAsset()
                if (status !in 200..299) throw httpException(status)
                successfulConnection = currentConnection
                break
            }

            val response = requireNotNull(successfulConnection) {
                "APK connection was not created"
            }
            val contentLength = response.contentLengthLong.takeIf { it >= 0L }
            val expectedSize = info.apkSize
            if (contentLength != null && contentLength > MAX_APK_BYTES) {
                throw UpdateException.Integrity("APK is larger than the safety limit")
            }
            if (expectedSize != null && (expectedSize <= 0L || expectedSize > MAX_APK_BYTES)) {
                throw UpdateException.Integrity("APK metadata size is outside the safety limit")
            }
            val directory = stateStore.updatesDirectory()
            val safeName = safeAssetName(info.apkAsset)
                ?: throw UpdateException.InvalidMetadata("Unsafe APK asset name")
            val part = File(directory, "$safeName.part")
            val target = File(directory, safeName)
            runCatching { part.delete() }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastProgressAt = System.nanoTime()
            var lastProgressBytes = 0L
            BufferedInputStream(response.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = try {
                            input.read(buffer)
                        } catch (failure: Throwable) {
                            throw UpdateException.Network("APK download interrupted", failure)
                        }
                        if (read < 0) break
                        if (read == 0) continue
                        downloaded += read
                        if (downloaded > MAX_APK_BYTES || (expectedSize != null && downloaded > expectedSize)) {
                            throw UpdateException.Integrity("APK exceeds its declared size")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        val now = System.nanoTime()
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_NANOS || downloaded == read.toLong()) {
                            val elapsedSeconds = (now - lastProgressAt).coerceAtLeast(1L).toDouble() / 1_000_000_000.0
                            val speed = ((downloaded - lastProgressBytes) / elapsedSeconds).toLong().coerceAtLeast(0L)
                            onProgress(DownloadProgress(downloaded, expectedSize ?: contentLength, speed))
                            lastProgressAt = now
                            lastProgressBytes = downloaded
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            val actualSha = digest.digest().toHexString()
            if (expectedSize != null && downloaded != expectedSize) {
                throw UpdateException.Integrity("APK size does not match metadata")
            }
            if (info.apkSha256 != null && !actualSha.equals(info.apkSha256, ignoreCase = true)) {
                throw UpdateException.Integrity("APK checksum does not match metadata")
            }
            // A rolling CI release can reuse the same asset name. Replace an older completed file
            // only after the new stream has passed every size and checksum check.
            if (target.exists() && !target.delete()) {
                throw UpdateException.Install("Unable to replace the previous downloaded APK")
            }
            if (!part.renameTo(target)) throw UpdateException.Install("Unable to finalize downloaded APK")
            val finalProgress = DownloadProgress(downloaded, expectedSize ?: contentLength, null)
            onProgress(finalProgress)
            val sidecar = File(directory, "$safeName.json")
            runCatching {
                sidecar.writeText(
                    JSONObject()
                        .put("channel", info.channel.wireValue)
                        .put("versionCode", info.versionCode ?: JSONObject.NULL)
                        .put("commit", info.commit ?: JSONObject.NULL)
                        .put("sha256", actualSha)
                        .put("sourceUrl", info.downloadUrl)
                        .toString(),
                    StandardCharsets.UTF_8
                )
            }
            return DownloadedUpdate(target, downloaded, actualSha, mode == UpdateProxyMode.OFFICIAL && proxy.isNotBlank())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            val safeName = safeAssetName(info.apkAsset)
            if (safeName != null) runCatching { File(stateStore.updatesDirectory(), "$safeName.part").delete() }
            throw cancelled
        } catch (failure: UpdateException) {
            val safeName = safeAssetName(info.apkAsset)
            if (safeName != null) runCatching { File(stateStore.updatesDirectory(), "$safeName.part").delete() }
            throw failure
        } catch (failure: Throwable) {
            val safeName = safeAssetName(info.apkAsset)
            if (safeName != null) runCatching { File(stateStore.updatesDirectory(), "$safeName.part").delete() }
            throw UpdateException.Network("APK download failed", failure)
        } finally {
            connection?.disconnect()
        }
    }

    private fun findAsset(assets: JSONArray, name: String): Asset? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name", "") == name) {
                val url = asset.optString("browser_download_url", "")
                if (url.isNotBlank()) return Asset(name, url)
            }
        }
        return null
    }

    private fun findFirstApkAsset(assets: JSONArray): Asset? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url", "")
            if (url.isNotBlank()) return Asset(name, url)
        }
        return null
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw UpdateException.InvalidMetadata("Update response is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ByteArray.toJsonObject(): JSONObject = runCatching {
        JSONObject(toString(StandardCharsets.UTF_8))
    }.getOrElse { throw UpdateException.InvalidMetadata("Update response is not JSON") }

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun safeAssetName(name: String): String? =
        name.takeIf { it.isNotBlank() && it.length <= 180 && it != "." && it != ".." &&
            !it.contains('/') && !it.contains('\\') && !it.contains("..") }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun httpException(status: Int): UpdateException {
        return if (status == HttpURLConnection.HTTP_FORBIDDEN) {
            UpdateException.Http(status, "GitHub rejected the update request")
        } else {
            UpdateException.Http(status, "Update server returned HTTP $status")
        }
    }

    private data class Asset(val name: String, val url: String)

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentLength: Long,
        val finalUrl: URL
    )

    private data class ProxyCall<T>(
        val response: T,
        val modeUsed: UpdateProxyMode,
        val usedFallback: Boolean
    )

    private data class CompareResult(val distance: Int, val commits: List<CommitChange>)

    private enum class Endpoint { API, ASSET }

    private companion object {
        const val MAX_REDIRECTS = 3
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_JSON_BYTES = 2L * 1024L * 1024L
        const val MAX_METADATA_BYTES = 512L * 1024L
        const val MAX_COMPARE_BYTES = 8L * 1024L * 1024L
        const val MAX_APK_BYTES = 64L * 1024L * 1024L
        const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        val LEGACY_VERSION_PATTERNS = listOf(
            Regex("-v([0-9]+(?:\\.[0-9]+){2})(?:-[0-9]+)?-release(?:-debug-signed)?\\.apk$", RegexOption.IGNORE_CASE),
            Regex("-v([0-9]+(?:\\.[0-9]+){2})-release(?:-debug-signed)?\\.apk$", RegexOption.IGNORE_CASE)
        )

        fun JSONObject.longOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

        fun JSONObject.intOrNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key).takeIf { it > 0 } else null
    }
}
