package com.takekazex.hypertweak.hook.rules.system

import android.content.pm.ApplicationInfo
import android.os.Process
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PackageManagerSettingsKeys
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Method

/** Install-time policy overlays in system_server. PMS remains the owner of persisted package state. */
internal object PackageManagerHooker : StaticHooker() {
    private val activePatches by hookSetting(emptySet<String>()) {
        val selected = PackageManagerSettingsKeys.patchKeys.filterTo(mutableSetOf()) {
            Preferences.getBoolean(it, false)
        }
        // Upgrades from the independent switches inherit any enabled patch until the master is set.
        if (Preferences.getBoolean(PackageManagerSettingsKeys.KEY_ENABLED, selected.isNotEmpty())) {
            selected
        } else {
            emptySet()
        }
    }

    private val downgrade get() = PackageManagerSettingsKeys.KEY_DOWNGRADE in activePatches
    private val lowTarget get() = PackageManagerSettingsKeys.KEY_LOW_TARGET in activePatches
    private val minScheme get() = PackageManagerSettingsKeys.KEY_MIN_SIGNATURE_SCHEME in activePatches
    private val integrity get() = PackageManagerSettingsKeys.KEY_APK_INTEGRITY in activePatches
    private val compressedResources get() = PackageManagerSettingsKeys.KEY_COMPRESSED_RESOURCES in activePatches
    private val updateSignature get() = PackageManagerSettingsKeys.KEY_UPDATE_SIGNATURE in activePatches
    private val splitSignature get() = PackageManagerSettingsKeys.KEY_SPLIT_SIGNATURE in activePatches
    private val sharedUid get() = PackageManagerSettingsKeys.KEY_SHARED_UID in activePatches
    private val verificationAgent get() = PackageManagerSettingsKeys.KEY_VERIFICATION_AGENT in activePatches
    private val persistentUpdate get() = PackageManagerSettingsKeys.KEY_PERSISTENT_UPDATE in activePatches
    private val systemAppUpdate get() = PackageManagerSettingsKeys.KEY_SYSTEM_APP_UPDATE in activePatches
    private val isolation get() = PackageManagerSettingsKeys.KEY_ISOLATION in activePatches

    override fun onHook() {
        val preparing = ThreadActivation()
        val validating = ThreadActivation()
        val reconciling = ThreadActivation()
        val permissionCheck = ThreadActivation()
        val updatePair = ThreadLocal<UpdateSigners>()

        install("prepare_scope") {
            val prepare = pmMethod("InstallPackageHelper", "preparePackage", Void.TYPE, pm("InstallRequest"))
            deoptimize(prepare)
            prepare.hook("pms.prepare") {
                intercept { chain ->
                    if (!lowTarget && !minScheme && !integrity && !compressedResources && !updateSignature &&
                        !sharedUid && !persistentUpdate && !systemAppUpdate && !isolation) return@intercept chain.proceed()
                    val previous = updatePair.get()
                    updatePair.remove()
                    try {
                        preparing.within { chain.proceed() }
                    } finally {
                        if (previous == null) updatePair.remove() else updatePair.set(previous)
                    }
                }
            }
        }
        install("session_scope") {
            pmMethod("PackageInstallerSession", "validateApkInstallLocked",
                host("android.content.pm.parsing.PackageLite"))
                .scope("validate_session", validating, deoptCaller = true) {
                    minScheme || integrity || splitSignature
                }
        }
        install("reconcile_scope") {
            pmMethod("InstallPackageHelper", "reconcileInstallPackages", List::class.java,
                List::class.java, Map::class.java)
                .scope("reconcile_install", reconciling) { updateSignature || sharedUid }
        }

        install("downgrade") {
            pmMethod("PackageManagerServiceUtils", "checkDowngrade", Void.TYPE, Long::class.javaPrimitiveType!!,
                Integer.TYPE, Array<String>::class.java, IntArray::class.java, host("android.content.pm.PackageInfoLite"))
                .overrideWhen("downgrade", null) { downgrade }
        }
        install("low_target") {
            pmMethod("InstallRequest", "getInstallFlags", Integer.TYPE).hook("pms.low_target") {
                after { param ->
                    val flags = param.result as? Int
                    if (lowTarget && preparing.active && flags != null) {
                        param.result = flags or INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK
                    }
                }
            }
        }
        install("apk_signature_scheme") {
            val verifier = host("android.util.apk.ApkSignatureVerifier")
            val floor = method(verifier, "getMinimumSignatureSchemeVersionForTargetSdk", Integer.TYPE, Integer.TYPE)
            val scanning = ThreadActivation()
            val scan = pmMethod("ScanPackageUtils", "assertMinSignatureSchemeIsValid", Void.TYPE,
                pm("pkg.AndroidPackage"), Integer.TYPE)
            pmMethod("InstallPackageHelper", "scanInstallPackages", java.lang.Boolean.TYPE,
                List::class.java, Map::class.java, Map::class.java)
                .scope("scan_install", scanning) { minScheme }
            deoptimize(pmMethod("InstallPackageHelper", "assertPackageIsValid", Void.TYPE,
                pm("pkg.AndroidPackage"), Integer.TYPE, Integer.TYPE))
            floor.overrideWhen("scheme_floor", 1) { minScheme && (preparing.active || validating.active) }
            scan.overrideWhen("scheme_scan", null) { minScheme && scanning.active }
        }
        install("apk_integrity") {
            val verifier = host("android.util.apk.ApkSignatureVerifier")
            val parseInput = host("android.content.pm.parsing.result.ParseInput")
            val parseResult = host("android.content.pm.parsing.result.ParseResult")
            val args = arrayOf(parseInput, String::class.java, Integer.TYPE, java.lang.Boolean.TYPE)
            val verify = method(verifier, "verifySignatures", parseResult, *args)
            val internalVerify = method(verifier, "verifySignaturesInternal", parseResult, *args)
            val apkLite = host("android.content.pm.parsing.ApkLiteParseUtils")
            val parseLite = method(apkLite, "parseApkLiteInner", parseResult, parseInput,
                File::class.java, FileDescriptor::class.java, String::class.java, Integer.TYPE)
            val parsingSessionApk = ThreadActivation()
            parseLite.scope("session_apk_integrity", parsingSessionApk) { integrity && validating.active }
            deoptimize(verify)
            internalVerify.hook("pms.apk_integrity") {
                intercept { chain ->
                    if (integrity && (preparing.active || parsingSessionApk.active) && chain.args[3] == true) {
                        chain.proceed(chain.args.toTypedArray().also { it[3] = false })
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
        install("compressed_resources") {
            val parser = host("com.android.internal.pm.pkg.parsing.ParsingPackageUtils")
            val parseBase = method(parser, "parseBaseApk", host("android.content.pm.parsing.result.ParseResult"),
                host("android.content.pm.parsing.result.ParseInput"), File::class.java,
                String::class.java, host("com.android.internal.pm.split.SplitAssetLoader"),
                Integer.TYPE, java.lang.Boolean.TYPE)
            val contains = method(host("android.content.res.AssetManager"), "containsAllocatedTable", java.lang.Boolean.TYPE)
            deoptimize(parseBase)
            contains.overrideWhen("compressed_resources", false) {
                compressedResources && preparing.active
            }
        }
        install("update_and_shared_uid") { installSignaturePolicy(preparing, reconciling, permissionCheck, updatePair) }
        install("split_signature") {
            val signing = host("android.content.pm.SigningDetails")
            val exactMatch = method(signing, "signaturesMatchExactly", java.lang.Boolean.TYPE, signing)
            val consistency = pmMethod("PackageInstallerSession", "assertApkConsistentLocked", Void.TYPE,
                String::class.java, host("android.content.pm.parsing.ApkLite"))
            val checkingSplit = ThreadActivation()
            consistency.scope("split_consistency", checkingSplit, deoptCaller = true) {
                splitSignature && validating.active
            }
            exactMatch.overrideWhen("split_signature", true) { splitSignature && checkingSplit.active }
        }
        install("verification_agent") {
            val lite = host("android.content.pm.PackageInfoLite")
            val general = pmMethod("VerifyingSession", "isVerificationEnabled", java.lang.Boolean.TYPE,
                lite, Integer.TYPE, List::class.java)
            val adb = pmMethod("VerifyingSession", "isAdbVerificationEnabled", java.lang.Boolean.TYPE,
                lite, Integer.TYPE, java.lang.Boolean.TYPE)
            val caller = pmMethod("VerifyingSession", "sendPackageVerificationRequest", Void.TYPE,
                Integer.TYPE, lite, pm("PackageVerificationState"))
            deoptimize(caller)
            general.overrideWhen("verification_agent", false) { verificationAgent }
            adb.overrideWhen("adb_verification_agent", false) { verificationAgent }
        }
        install("persistent_update") {
            pmMethod("PackageSetting", "isPersistent", java.lang.Boolean.TYPE)
                .overrideWhen("persistent_update", false) { persistentUpdate && preparing.active }
        }
        install("hyperos_system_app_update") {
            pmMethod("PackageManagerServiceImpl", "canBeUpdate", Void.TYPE, String::class.java)
                .overrideWhen("hyperos_system_app_update", null) { systemAppUpdate && preparing.active }
        }
        install("hyperos_isolation") {
            val parsed = host("com.android.internal.pm.parsing.pkg.ParsedPackage")
            val source = pm("InstallSource")
            val gate = pmMethod("PackageManagerServiceImpl", "verifyIsolationViolation", Void.TYPE, parsed, source)
            deoptimize(pmMethod("PackageManagerServiceImpl", "checkInstallRestriction", Void.TYPE, parsed, source))
            gate.overrideWhen("hyperos_isolation", null) { isolation && preparing.active }
        }
    }

    private fun installSignaturePolicy(
        preparing: ThreadActivation,
        reconciling: ThreadActivation,
        permissionCheck: ThreadActivation,
        updatePair: ThreadLocal<UpdateSigners>,
    ) {
        val signing = host("android.content.pm.SigningDetails")
        val setting = pm("PackageSetting")
        val shared = pm("SharedUserSetting")
        val exception = pm("PackageManagerException")
        val error = HostAccess.requireDeclaredField(exception, "error", accessible = true)
        val appId = method(shared, "getAppId", Integer.TYPE, accessible = true)
        val uidFlags = method(pm("SettingBase"), "getFlags", Integer.TYPE, accessible = true)
        val privileged = method(shared, "isPrivileged", java.lang.Boolean.TYPE, accessible = true)
        val isSystem = method(setting, "isSystem", java.lang.Boolean.TYPE, accessible = true)
        val oldSigning = method(setting, "getSigningDetails", signing, accessible = true)
        val capability = method(signing, "checkCapability", java.lang.Boolean.TYPE, signing, Integer.TYPE)
        val hasSharedUser = method(setting, "hasSharedUser", java.lang.Boolean.TYPE, accessible = true)
        val state = pm("pkg.PackageStateInternal")
        val stateSystem = HostAccess.requirePublicMethod(state, "isSystem", accessible = true)
        val stateShared = HostAccess.requirePublicMethod(state, "hasSharedUser", accessible = true)
        val parsed = host("com.android.internal.pm.parsing.pkg.ParsedPackage")
        val verify = pmMethod("PackageManagerServiceUtils", "verifySignatures", java.lang.Boolean.TYPE,
            setting, shared, setting, signing, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        val checkKeySet = pmMethod("KeySetManagerService", "checkUpgradeKeySetLocked",
            java.lang.Boolean.TYPE, state, pm("pkg.AndroidPackage"))
        val matchPermissions = pmMethod("InstallPackageHelper", "doesSignatureMatchForPermissions",
            java.lang.Boolean.TYPE, String::class.java, parsed, Integer.TYPE)

        // Keep this guard active across a live setting change inside the native permission check.
        matchPermissions.scope("permission_signatures", permissionCheck) { preparing.active || reconciling.active }
        capability.hook("pms.update_installed_data_capability") {
            before { param ->
                if (!updateSignature || !preparing.active || permissionCheck.active ||
                    param.args[1] != CAPABILITY_INSTALLED_DATA) return@before
                val pair = updatePair.get() ?: return@before
                if (param.thisObject === pair.incoming && param.args[0] === pair.installed) {
                    param.result = true
                }
            }
        }
        verify.hook("pms.install_signature") {
            after { param ->
                val failure = param.throwable
                if ((!updateSignature && !sharedUid) || (!preparing.active && !reconciling.active) ||
                    !exception.isInstance(failure)) return@after
                val existing = param.args[0] ?: return@after
                val sharedUser = param.args[1]
                if (param.args[2] != null) return@after // Disabled factory package.
                val allow = HookFailurePolicy.open(hookerName, "classify install signature failure", false) {
                    if (isSystem.invoke(existing) != false) return@open false
                    when (error.getInt(failure)) {
                        INSTALL_FAILED_UPDATE_INCOMPATIBLE -> {
                            if (!updateSignature || sharedUser != null || hasSharedUser.invoke(existing) != false) {
                                return@open false
                            }
                            if (preparing.active) {
                                // preparePackage repeats the installed-data check on these exact signers.
                                val installed = oldSigning.invoke(existing) ?: return@open false
                                val incoming = param.args[3] ?: return@open false
                                updatePair.set(UpdateSigners(installed, incoming))
                            }
                            true
                        }
                        INSTALL_FAILED_SHARED_USER_INCOMPATIBLE -> {
                            if (!sharedUid || sharedUser == null || privileged.invoke(sharedUser) != false) {
                                return@open false
                            }
                            val flags = uidFlags.invoke(sharedUser) as? Int ?: return@open false
                            val uid = appId.invoke(sharedUser) as? Int ?: return@open false
                            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                            flags and systemFlags == 0 && uid in Process.FIRST_APPLICATION_UID..Process.LAST_APPLICATION_UID
                        }
                        else -> false
                    }
                }
                // The native Boolean reports a legacy keyset migration, not signature validity.
                if (allow) {
                    param.throwable = null
                    param.result = false
                }
            }
        }
        checkKeySet.hook("pms.upgrade_keyset") {
            after { param ->
                if (!updateSignature || permissionCheck.active || (!preparing.active && !reconciling.active) ||
                    param.result != false) return@after
                val existing = param.args[0] ?: return@after
                if (HookFailurePolicy.open(hookerName, "classify upgrade keyset", false) {
                    stateSystem.invoke(existing) == false && stateShared.invoke(existing) == false
                }) param.result = true
            }
        }
    }

    private fun Method.scope(
        id: String,
        activation: ThreadActivation,
        deoptCaller: Boolean = false,
        enabled: () -> Boolean,
    ) {
        if (deoptCaller) deoptimize(this)
        hook("pms.$id") {
            intercept { chain ->
                if (enabled()) activation.within { chain.proceed() } else chain.proceed()
            }
        }
    }

    private fun Method.overrideWhen(id: String, result: Any?, enabled: () -> Boolean) {
        hook("pms.$id") { before { param -> if (enabled()) param.result = result } }
    }

    private fun install(id: String, block: () -> Unit) = HookFailurePolicy.open(hookerName, id, Unit, block)

    private fun pm(name: String) = host("com.android.server.pm.$name")
    private fun host(name: String) = checkNotNull(name.toClassOrNull()) { "Missing host class $name" }

    private fun pmMethod(owner: String, name: String, result: Class<*>, vararg args: Class<*>) =
        method(pm(owner), name, result, *args)

    private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>, accessible: Boolean = false): Method =
        HostAccess.requireDeclaredMethod(owner, name, *args, accessible = accessible).also {
            check(it.returnType == result) { "Unexpected return type for $it" }
        }

    private class UpdateSigners(val installed: Any, val incoming: Any)

    private const val CAPABILITY_INSTALLED_DATA = 1
    private const val INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK = 0x01000000
    private const val INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7
    private const val INSTALL_FAILED_SHARED_USER_INCOMPATIBLE = -8
}
