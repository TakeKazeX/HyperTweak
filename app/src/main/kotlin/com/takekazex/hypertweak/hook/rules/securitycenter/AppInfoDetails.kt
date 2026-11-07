package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.FileUriExposedException
import android.os.StrictMode
import android.widget.Toast
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Locale

internal data class AppSigningDetails(val schemeVersion: Int, val certificates: List<AppSigningCertificate>)
internal data class AppSigningCertificate(
    val current: Boolean,
    val sha256: String,
    val subject: String?,
    val issuer: String?,
    val serial: String?,
    val validity: String?,
)

private val installedPackageLookup by lazy {
    val activityThread = Class.forName("android.app.ActivityThread")
    val getManager = HostAccess.requirePublicMethod(activityThread, "getPackageManager", accessible = false)
    val getInfo = HostAccess.requirePublicMethod(
        getManager.returnType, "getPackageInfo",
        String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        accessible = false,
    )
    getManager to getInfo
}

/** Queries the selected Android user, including clones, through the same Binder as the host. */
internal fun readInstalledSigningInfo(packageName: String, uid: Int): AppSigningDetails? {
    if (uid < 0) return null
    val (getManager, getInfo) = installedPackageLookup
    val manager = getManager.invoke(null) ?: return null
    val info = getInfo.invoke(manager, packageName, PackageManager.GET_SIGNING_CERTIFICATES.toLong(), uid / 100000)
        as? PackageInfo ?: return null
    if (info.packageName != packageName || info.applicationInfo?.uid != uid) return null
    val signing = info.signingInfo ?: return null
    val current = signing.apkContentsSigners.orEmpty().distinct()
    val history = if (signing.hasMultipleSigners()) emptyList()
        else signing.signingCertificateHistory.orEmpty().distinct().filterNot { it in current }
    val certificates = current.map { describeCertificate(it, true) } +
        history.map { describeCertificate(it, false) }
    return AppSigningDetails(signing.schemeVersion, certificates).takeIf { certificates.isNotEmpty() }
}

private fun describeCertificate(signature: Signature, current: Boolean): AppSigningCertificate {
    val bytes = signature.toByteArray()
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
    val x509 = try {
        CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as? X509Certificate
    } catch (_: CertificateException) {
        null
    }
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return AppSigningCertificate(
        current, sha256, x509?.subjectX500Principal?.name, x509?.issuerX500Principal?.name,
        x509?.serialNumber?.toString(16)?.uppercase(Locale.ROOT),
        x509?.let { "${date.format(it.notBefore)} – ${date.format(it.notAfter)}" },
    )
}

private val permitLegacyPath by lazy {
    HostAccess.requirePublicMethod(StrictMode::class.java, "disableDeathOnFileUriExposure", accessible = false)
}

internal fun openAppInfoDirectory(activity: Activity, path: String) {
    val file = File(path)
    if (!file.isAbsolute) return
    val mtIntent = Intent(Intent.ACTION_VIEW).apply {
        setClassName("bin.mt.plus", "bin.mt.plus.Main")
        data = Uri.fromFile(file)
        putExtra("shortcut", true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val opened = HookFailurePolicy.open("MoreAppInfo", "open in MT Manager", false) {
        val target = mtIntent.resolveActivityInfo(activity.packageManager, 0)
        if (target?.exported != true || target.packageName != "bin.mt.plus") return@open false
        try {
            activity.startActivity(mtIntent)
        } catch (_: FileUriExposedException) {
            // MT's own file shortcut carries only a local path, and the receiving app uses its
            // existing filesystem permissions. Scope the legacy diagnostic exception to this launch.
            val policy = StrictMode.getVmPolicy()
            try {
                permitLegacyPath.invoke(null)
                activity.startActivity(mtIntent)
            } finally {
                StrictMode.setVmPolicy(policy)
            }
        }
        true
    }
    if (opened) return
    val message = if (Locale.getDefault().language == "zh") "无法打开此路径，可长按复制"
        else "Can’t open this path. Touch and hold to copy it."
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
