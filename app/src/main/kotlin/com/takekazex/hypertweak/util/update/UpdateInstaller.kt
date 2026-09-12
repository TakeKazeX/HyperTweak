package com.takekazex.hypertweak.util.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import java.io.File
import java.security.MessageDigest

class UpdateInstaller(
    private val context: Context,
    private val stateStore: UpdateStateStore
) {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    fun install(info: UpdateInfo, apkFile: File): InstallResult {
        if (!apkFile.isFile) {
            return InstallResult.Failure(error(UpdateErrorKind.INTEGRITY, R.string.update_error_integrity, retryable = false))
        }
        val archive = runCatching {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }.getOrNull()
            ?: return InstallResult.Failure(error(UpdateErrorKind.INTEGRITY, R.string.update_error_integrity, retryable = false))

        val installed = runCatching {
            packageManager.getPackageInfo(
                BuildConfig.APPLICATION_ID,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }.getOrNull()
            ?: return InstallResult.Failure(error(UpdateErrorKind.INSTALL, R.string.update_error_install))

        val validationError = validateArchive(info, archive, installed)
        if (validationError != null) return InstallResult.Failure(validationError)

        if (!packageManager.canRequestPackageInstalls()) {
            return InstallResult.RequiresUnknownSources(info, apkFile)
        }

        val expectedSigners = signerDigests(installed)
        if (expectedSigners.isEmpty()) {
            return InstallResult.Failure(
                error(UpdateErrorKind.SIGNATURE, R.string.update_error_signature, retryable = false)
            )
        }
        val arm = ArmRecord(
            armedAtWallClock = System.currentTimeMillis(),
            armedAtElapsed = android.os.SystemClock.elapsedRealtime(),
            bootSessionKey = bootSessionKey(),
            previousLastUpdateTime = installed.lastUpdateTime,
            targetVersionCode = info.versionCode ?: archive.longVersionCode,
            targetVersionName = archive.versionName.orEmpty(),
            targetCommit = info.commit,
            targetApkSha256 = info.apkSha256,
            apkPath = apkFile.absolutePath,
            expectedSignerDigests = expectedSigners
        )
        stateStore.arm(arm)

        return try {
            val uri = FileProvider.getUriForFile(appContext, FILE_PROVIDER_AUTHORITY, apkFile)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("HyperTweak update", uri)
            }
            context.startActivity(intent)
            InstallResult.Started(info)
        } catch (failure: Throwable) {
            stateStore.clearArm()
            InstallResult.Failure(
                error(UpdateErrorKind.INSTALL, R.string.update_error_install, retryable = true)
            )
        }
    }

    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${appContext.packageName}".toUri()
    )

    fun canRequestPackageInstalls(): Boolean = packageManager.canRequestPackageInstalls()

    /** Confirms the package that survived the install is the armed target, not just any update. */
    fun installedMatches(record: ArmRecord): Boolean {
        val installed = runCatching {
            packageManager.getPackageInfo(
                BuildConfig.APPLICATION_ID,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        }.getOrNull() ?: return false
        if (installed.packageName != BuildConfig.APPLICATION_ID) return false
        if (record.targetVersionCode != null && installed.longVersionCode != record.targetVersionCode) return false
        if (record.targetVersionCode == null && installed.versionName != record.targetVersionName) return false
        // A build made without git carries an empty SHA; treating that as a mismatch would make
        // reconciliation impossible for such builds, so the commit is only compared when known.
        val builtCommit = BuildConfig.GIT_COMMIT_SHA.trim()
        if (record.targetCommit != null && builtCommit.isNotEmpty() &&
            !record.targetCommit.equals(builtCommit, ignoreCase = true)
        ) {
            return false
        }
        val signers = signerDigests(installed)
        return signers.isNotEmpty() && signers == record.expectedSignerDigests
    }

    /**
     * Builds a user-facing failure.
     *
     * The message is resolved here rather than left as an English diagnostic, because the UI shows
     * `UpdateError.message` verbatim: an untranslated string would reach the user as-is.
     */
    private fun error(
        kind: UpdateErrorKind,
        messageRes: Int,
        retryable: Boolean = true
    ): UpdateError = UpdateError(kind, appContext.getString(messageRes), retryable)

    private fun validateArchive(
        info: UpdateInfo,
        archive: PackageInfo,
        installed: PackageInfo
    ): UpdateError? {
        if (archive.packageName != BuildConfig.APPLICATION_ID) {
            return error(UpdateErrorKind.INTEGRITY, R.string.update_error_integrity, retryable = false)
        }
        if (info.versionCode != null && archive.longVersionCode != info.versionCode) {
            return error(UpdateErrorKind.INTEGRITY, R.string.update_error_integrity, retryable = false)
        }
        if (info.versionCode != null && archive.longVersionCode <= installed.longVersionCode) {
            return error(UpdateErrorKind.UNSUPPORTED, R.string.update_error_unsupported, retryable = false)
        }
        if (info.versionCode == null && !UpdateVersioning.isRemoteVersionNewer(installed.versionName.orEmpty(), archive.versionName.orEmpty())) {
            return error(UpdateErrorKind.UNSUPPORTED, R.string.update_error_unsupported, retryable = false)
        }
        if (info.versionName.isNotBlank() && archive.versionName != info.versionName) {
            return error(UpdateErrorKind.INTEGRITY, R.string.update_error_integrity, retryable = false)
        }
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            return error(
                UpdateErrorKind.SIGNATURE,
                R.string.update_error_signature,
                retryable = false
            )
        }
        return null
    }

    private fun signerDigests(info: PackageInfo): Set<String> = runCatching {
        info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHexString()
        }.toSet()
    }.getOrDefault(emptySet())

    private fun bootSessionKey(): Long =
        System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    sealed interface InstallResult {
        data class Started(val info: UpdateInfo) : InstallResult
        data class RequiresUnknownSources(val info: UpdateInfo, val file: File) : InstallResult
        data class Failure(val error: UpdateError) : InstallResult
    }

    private companion object {
        const val FILE_PROVIDER_AUTHORITY = "com.takekazex.hypertweak.fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
