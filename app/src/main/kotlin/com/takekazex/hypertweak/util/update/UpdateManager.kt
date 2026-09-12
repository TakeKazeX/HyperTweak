package com.takekazex.hypertweak.util.update

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-owned coordinator; no hook process ever constructs this class. */
class UpdateManager(private val activity: ComponentActivity) {
    private val appContext = activity.applicationContext
    private val stateStore = UpdateStateStore(appContext)
    private val repository = GitHubUpdateRepository(appContext, stateStore)
    private val installer = UpdateInstaller(activity, stateStore)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var autoCheckStarted = false

    /**
     * Bumped whenever a download is cancelled or superseded. A running download captures the value
     * it started with and refuses to publish state once it no longer matches.
     */
    private var downloadGeneration = 0

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private val _proxyTestState = MutableStateFlow<ProxyTestState>(ProxyTestState.Idle)
    val proxyTestState: StateFlow<ProxyTestState> = _proxyTestState.asStateFlow()

    fun channel(): UpdateChannel = UpdateChannel.fromValue(
        Preferences.getString(Preferences.KEY_UPDATE_CHANNEL, UpdateChannel.STABLE.wireValue)
    )

    fun interval(): UpdateCheckInterval = UpdateCheckInterval.fromIndex(
        Preferences.getInt(Preferences.KEY_UPDATE_CHECK_INTERVAL, UpdateCheckInterval.DAILY.index)
    )

    fun proxyMode(): UpdateProxyMode = UpdateProxyMode.fromValue(
        Preferences.getString(Preferences.KEY_UPDATE_PROXY_MODE, UpdateProxyMode.OFFICIAL.wireValue)
    )

    fun proxyAddress(): String = Preferences.getString(Preferences.KEY_UPDATE_PROXY_URL, "")

    fun setChannel(channel: UpdateChannel) {
        val previous = this.channel()
        Preferences.putString(Preferences.KEY_UPDATE_CHANNEL, channel.wireValue)
        if (previous == channel) return

        // Switching channels must not leave the previous channel's work in place:
        //  * an in-flight download would keep writing progress into the new channel's state and
        //    would install a build from the channel the user just left;
        //  * a package already downloaded and waiting on the installer would still be offered (and
        //    installable) even though it belongs to the other channel.
        cancelDownload()
        discardPackageFromOtherChannel(channel)
        // Start from a clean slate: priming may find nothing for the new channel, and leaving the
        // old state behind would show a download that is no longer running.
        _state.value = UpdateUiState.Idle
        primeFromCache(replaceCurrent = true)
        checkUpdate(force = true)
    }

    /**
     * Drops a downloaded package that belongs to a channel the user has left.
     *
     * The file is only deleted while it is still ours. Once the package has been handed to the
     * installer that process may be reading it, so an in-flight hand-off is merely unlinked from the
     * page state and left to the 24-hour sweep.
     */
    private fun discardPackageFromOtherChannel(channel: UpdateChannel) {
        val pending = when (val state = _state.value) {
            is UpdateUiState.AwaitingUnknownSources -> state.info
            else -> null
        } ?: return
        if (pending.channel == channel) return
        if (_state.value is UpdateUiState.AwaitingUnknownSources) {
            stateStore.deleteCachedDownload(pending)
        }
        _state.value = UpdateUiState.Idle
    }

    /**
     * Restores the last known result from the per-channel cache.
     *
     * A check is throttled to at most once per interval, but the *answer* is worth far more than
     * that: reopening the app should not show an empty page just because today's query is not due
     * yet. The cached metadata already carries everything the page renders, so it is restored as
     * the current state and any later check simply replaces it.
     */
    fun primeFromCache(replaceCurrent: Boolean = false) {
        if (!replaceCurrent && _state.value !is UpdateUiState.Idle) return
        val cached = runCatching { repository.cachedState(channel()) }.getOrNull() ?: return
        _state.value = applySkipped(cached)
    }

    fun setInterval(interval: UpdateCheckInterval) {
        Preferences.putInt(Preferences.KEY_UPDATE_CHECK_INTERVAL, interval.index)
    }

    fun setProxyMode(mode: UpdateProxyMode) {
        Preferences.putString(Preferences.KEY_UPDATE_PROXY_MODE, mode.wireValue)
        if (mode == UpdateProxyMode.THIRD_PARTY) testProxy()
    }

    fun setProxyAddress(address: String) {
        Preferences.putString(Preferences.KEY_UPDATE_PROXY_URL, address.trim())
    }

    fun startAutoCheck() {
        if (autoCheckStarted) return
        autoCheckStarted = true
        if (!isOfficialPackage() || interval() == UpdateCheckInterval.NEVER || !isOnline()) return
        if (!stateStore.shouldCheck(channel(), interval())) return
        checkUpdate(force = false)
    }

    fun checkUpdate(force: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        if (!isOfficialPackage()) return
        if (!isOnline()) {
            if (force) {
                _state.value = UpdateUiState.Error(
                    UpdateError(UpdateErrorKind.NETWORK, activity.getString(R.string.update_error_offline))
                )
            }
            return
        }
        val previous = _state.value
        _state.value = UpdateUiState.Checking(previous)
        checkJob = scope.launch {
            try {
                val result = repository.checkUpdate(channel(), force)
                _state.value = applySkipped(result.state)
                if (result.usedProxyFallback && _state.value is UpdateUiState.Available) {
                    val available = _state.value as UpdateUiState.Available
                    _state.value = available.copy(usedProxyFallback = true)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                _state.value = UpdateUiState.Error(toUpdateError(failure))
            } finally {
                checkJob = null
            }
        }
    }

    fun testProxy() {
        if (_proxyTestState.value is ProxyTestState.Testing) return
        _proxyTestState.value = ProxyTestState.Testing
        scope.launch {
            _proxyTestState.value = runCatching { repository.testProxy() }
                .getOrElse { ProxyTestState.Failure(apiReachable = false, assetReachable = false) }
        }
    }

    fun downloadAndInstall() {
        // A package already downloaded and waiting on the system permission must not be fetched a
        // second time; finish that install instead.
        if (_state.value is UpdateUiState.AwaitingUnknownSources) {
            retryPendingInstall()
            return
        }
        val info = currentInfo() ?: return
        if (downloadJob?.isActive == true) return
        // Every state write below is gated on this generation. Cancelling (or starting a new
        // download) bumps it, so a superseded coroutine can no longer overwrite the state — which is
        // what used to let a download for the old channel install itself after a channel switch.
        val generation = ++downloadGeneration
        downloadJob = scope.launch {
            var usingCachedApk = false
            try {
                val cached = withContext(Dispatchers.IO) {
                    stateStore.cachedDownload(info, verifyContents = true)
                }
                if (cached != null) {
                    usingCachedApk = true
                    if (!isCurrentDownload(generation)) return@launch
                    handleInstallResult(info, installer.install(info, cached.file))
                    return@launch
                }
                val downloaded = repository.downloadUpdate(info) { progress ->
                    if (isCurrentDownload(generation)) {
                        _state.value = UpdateUiState.Downloading(info, progress)
                    }
                }
                if (!isCurrentDownload(generation)) return@launch
                handleInstallResult(info, installer.install(info, downloaded.file))
            } catch (failure: CancellationException) {
                if (!usingCachedApk) repository.discardPartial(info)
                if (isCurrentDownload(generation)) _state.value = UpdateUiState.Available(info)
            } catch (failure: Throwable) {
                if (!isCurrentDownload(generation)) return@launch
                if (failure is UpdateException.StaleAsset) {
                    handleStaleAsset(info)
                } else {
                    _state.value = UpdateUiState.Error(toUpdateError(failure), info)
                }
            } finally {
                if (isCurrentDownload(generation)) downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        // Invalidate first: the cancellation below is cooperative, so the job's own state writes
        // could otherwise still land after the caller has moved on.
        downloadGeneration++
        downloadJob?.cancel()
        downloadJob = null
    }

    private fun isCurrentDownload(generation: Int): Boolean = generation == downloadGeneration

    /**
     * Explicit "allow installing from this source" action from the page.
     *
     * When the permission is still missing this opens the system toggle; when it has been granted it
     * completes the pending install.
     */
    fun retryPendingInstall() {
        val pending = _state.value as? UpdateUiState.AwaitingUnknownSources ?: return
        if (!installer.canRequestPackageInstalls()) {
            runCatching { activity.startActivity(installer.unknownSourcesIntent()) }
            return
        }
        startPendingInstall(pending)
    }

    /** Lets the page choose the correct primary action without exposing the state-store itself. */
    fun hasCachedDownload(info: UpdateInfo): Boolean =
        runCatching { stateStore.cachedDownload(info) != null }.getOrDefault(false)

    /**
     * Lifecycle hook.
     *
     * It must **not** open the system permission toggle: the toggle is a different Activity, so
     * returning from it re-enters `onResume`, and opening it again there produced an endless loop of
     * the "install unknown apps" screen. Only a permission that has since been granted proceeds.
     *
     * It deliberately does **not** try to work out whether a handed-off install was cancelled. The
     * hand-off reports nothing back, and a successful replacement kills this process, so the
     * installed version is the only source of truth: if it installed, the app *is* the new version.
     */
    fun onResume() {
        val pending = _state.value as? UpdateUiState.AwaitingUnknownSources ?: return
        if (!installer.canRequestPackageInstalls()) return
        startPendingInstall(pending)
    }

    private fun startPendingInstall(pending: UpdateUiState.AwaitingUnknownSources) {
        scope.launch {
            val stillCached = withContext(Dispatchers.IO) {
                stateStore.cachedDownload(pending.info, verifyContents = true)
                    ?.file
                    ?.let { cached ->
                        runCatching {
                            cached.canonicalFile == pending.file.canonicalFile
                        }.getOrDefault(false)
                    } == true
            }
            if (!stillCached) {
                _state.value = UpdateUiState.Available(pending.info)
                return@launch
            }
            handleInstallResult(
                info = pending.info,
                result = installer.install(pending.info, pending.file)
            )
        }
    }

    private fun handleInstallResult(
        info: UpdateInfo,
        result: UpdateInstaller.InstallResult
    ) {
        when (result) {
            // "Handed off" is not a state of its own: the installer reports nothing back, and if it
            // succeeds this process is replaced. Leaving the update available is therefore both
            // accurate and self-healing — retrying installs the cached package, and if the user did
            // install it they are already running the new build.
            is UpdateInstaller.InstallResult.Started -> _state.value = UpdateUiState.Available(info)
            is UpdateInstaller.InstallResult.RequiresUnknownSources -> {
                _state.value = UpdateUiState.AwaitingUnknownSources(info, result.file)
                runCatching { activity.startActivity(installer.unknownSourcesIntent()) }
            }
            is UpdateInstaller.InstallResult.Failure -> {
                // An APK that fails archive/integrity/signature validation must not leave the page
                // stuck on an "Install" action that will fail forever. Keep it only for a transient
                // installer-launch failure.
                if (result.error.kind in setOf(
                        UpdateErrorKind.INTEGRITY,
                        UpdateErrorKind.SIGNATURE,
                        UpdateErrorKind.UNSUPPORTED
                    )
                ) {
                    stateStore.deleteCachedDownload(info)
                }
                _state.value = UpdateUiState.Error(result.error, info)
            }
        }
    }

    fun skipCurrent() {
        val info = currentInfo() ?: return
        stateStore.skip(info)
        _state.value = UpdateUiState.Skipped(info)
    }

    fun unskip(info: UpdateInfo) {
        stateStore.unskip(info)
        _state.value = UpdateUiState.Available(info)
    }

    fun shareDirect() {
        val info = currentInfo() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            // InsX-re accepts a bare URL only; do not add a label or explanatory text.
            putExtra(Intent.EXTRA_TEXT, info.downloadUrl)
        }
        startChooser(send)
    }

    fun shareDownloaded() {
        val state = _state.value
        val file = when (state) {
            is UpdateUiState.AwaitingUnknownSources -> state.file
            else -> currentInfo()?.let { info -> stateStore.cachedDownload(info)?.file } ?: return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(appContext, FILE_PROVIDER_AUTHORITY, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = APK_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("HyperTweak APK", uri)
            }
            startChooser(send)
        }
    }

    fun openReleasePage() {
        val info = currentInfo() ?: return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, info.releaseUrl.toUri())) }
    }

    fun clearDownloadedUpdates() {
        stateStore.clearDownloadedUpdates()
        // A pending hand-off has just lost the package it was waiting on, so the page must stop
        // presenting it as ready to install; the update itself is still available to re-download.
        val pending = _state.value as? UpdateUiState.AwaitingUnknownSources ?: return
        _state.value = UpdateUiState.Available(pending.info)
    }

    fun downloadedBytes(): Long = stateStore.downloadedBytes()

    /** When the last *successful* check happened, used for the "last checked" line. */
    fun lastCheckedAt(): Long = stateStore.lastSuccessAt(channel())

    fun consumeCompletionNotice(): CompletionNotice? = stateStore.consumeCompletionNotice()

    fun close() {
        scope.cancel()
    }

    private fun currentInfo(): UpdateInfo? = when (val state = _state.value) {
        is UpdateUiState.Available -> state.info
        is UpdateUiState.Skipped -> state.info
        is UpdateUiState.Downloading -> state.info
        is UpdateUiState.AwaitingUnknownSources -> state.info
        is UpdateUiState.Error -> state.info
        is UpdateUiState.Checking -> (state.previous as? UpdateUiState.Available)?.info
        is UpdateUiState.UpToDate, UpdateUiState.Idle -> null
    }

    private fun applySkipped(state: UpdateUiState): UpdateUiState {
        val info = when (state) {
            is UpdateUiState.Available -> state.info
            else -> return state
        }
        return if (stateStore.isSkipped(info)) UpdateUiState.Skipped(info) else state
    }

    private fun handleStaleAsset(info: UpdateInfo) {
        scope.launch {
            try {
                val result = repository.checkUpdate(channel(), force = true)
                val next = result.state
                if (next is UpdateUiState.Available && next.info.cacheKey != info.cacheKey) {
                    _state.value = next.copy(staleAssetNotice = true)
                } else {
                    _state.value = UpdateUiState.Error(
                        UpdateError(UpdateErrorKind.STALE_ASSET, activity.getString(R.string.update_error_stale_asset)),
                        info
                    )
                }
            } catch (failure: Throwable) {
                _state.value = UpdateUiState.Error(toUpdateError(failure), info)
            }
        }
    }

    private fun startChooser(intent: Intent) {
        runCatching { activity.startActivity(Intent.createChooser(intent, null)) }
    }

    private fun isOfficialPackage(): Boolean = appContext.packageName == BuildConfig.APPLICATION_ID

    private fun isOnline(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Turns a transport or verification failure into a user-facing message.
     *
     * Everything the repository throws carries an English diagnostic aimed at logs, so each kind is
     * mapped to a localized string here instead of being surfaced verbatim. `retryable` carries the
     * one distinction the UI acts on: verification failures deleted the file and must not offer a
     * retry, while transport failures may be retried.
     */
    private fun toUpdateError(failure: Throwable): UpdateError = when (failure) {
        is UpdateException.Http -> if (failure.status == 403) {
            UpdateError(UpdateErrorKind.RATE_LIMITED, activity.getString(R.string.update_error_rate_limited))
        } else {
            UpdateError(UpdateErrorKind.HTTP, activity.getString(R.string.update_error_http))
        }
        is UpdateException.InvalidMetadata ->
            UpdateError(UpdateErrorKind.INVALID_METADATA, activity.getString(R.string.update_error_invalid_metadata), retryable = false)
        is UpdateException.Integrity ->
            UpdateError(UpdateErrorKind.INTEGRITY, activity.getString(R.string.update_error_integrity), retryable = false)
        is UpdateException.Signature ->
            UpdateError(UpdateErrorKind.SIGNATURE, activity.getString(R.string.update_error_signature), retryable = false)
        is UpdateException.Install ->
            UpdateError(UpdateErrorKind.INSTALL, activity.getString(R.string.update_error_install))
        is UpdateException.StaleAsset ->
            UpdateError(UpdateErrorKind.STALE_ASSET, activity.getString(R.string.update_error_stale_asset))
        is UpdateException.Redirect ->
            UpdateError(UpdateErrorKind.NETWORK, activity.getString(R.string.update_error_untrusted_redirect), retryable = false)
        is UpdateException.Network ->
            UpdateError(UpdateErrorKind.NETWORK, activity.getString(R.string.update_error_network))
        else -> UpdateError(UpdateErrorKind.UNKNOWN, activity.getString(R.string.update_error_unknown))
    }

    private companion object {
        const val FILE_PROVIDER_AUTHORITY = "com.takekazex.hypertweak.fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
