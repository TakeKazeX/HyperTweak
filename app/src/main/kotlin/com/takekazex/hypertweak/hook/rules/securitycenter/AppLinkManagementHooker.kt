package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Redirects the host's Clear defaults row to the system link-management page. */
object AppLinkManagementHooker : DynamicHooker() {
    private const val TAG = "AppLinkManagement"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val ACTIVITY = "com.miui.appmanager.ApplicationsDetailsActivity"
    private const val FRAGMENT = "com.miui.appmanager.fragment.ApplicationsDetailsFragment"
    private const val PREFERENCE = "androidx.preference.Preference"
    private const val ROW_KEY = "app_default_pref"
    private const val SETTINGS_PACKAGE = "com.android.settings"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(AppSettingsKeys.KEY_APP_LINK_MANAGEMENT, false)
    }

    private data class Targets(
        val load: Method,
        val click: Method,
        val resume: Method,
        val application: Field,
        val activityFragment: Field,
    ) {
        fun encode() = DexContractMembers.encode(
            "load" to load, "click" to click, "resume" to resume,
            "application" to application, "activityFragment" to activityFragment,
        )
    }

    private data class Original(val title: CharSequence?, val summary: CharSequence?)

    private val originals = generationMainResource(WeakHashMap<Any, Original>()) { it.clear() }
    private var installed by generationState<Targets?> { null }

    override fun onHook() {
        if (hookParam.processName != PACKAGE || !PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(
            resolve = {
                resolveDexContract(
                    id = "securitycenter.app_link_management",
                    apk = apk,
                    compute = ::resolve,
                    encode = Targets::encode,
                    decode = { members ->
                        Targets(
                            members.method("load"), members.method("click"), members.method("resume"),
                            members.field("application"), members.field("activityFragment"),
                        )
                    },
                )
            },
            install = ::install,
        )
    }

    private fun resolve(bridge: DexKitBridge): Targets? {
        val methods = bridge.findClass { matcher { className(FRAGMENT, StringMatchType.Equals) } }
            .singleOrNull()?.methods ?: return null
        val bind = methods.singleOrNull { method ->
            method.paramCount == 0 && method.returnTypeName == "void" && ROW_KEY in method.usingStrings &&
                method.invokes.any { it.descriptor.endsWith("->findPreference(Ljava/lang/CharSequence;)Landroidx/preference/Preference;") }
        } ?: return null
        val click = methods.singleOrNull {
            it.methodName == "onPreferenceClick" && it.returnTypeName == "boolean" &&
                it.paramTypeNames == listOf(PREFERENCE) && ROW_KEY in it.usingStrings &&
                it.invokes.any { call -> call.descriptor == "Landroidx/preference/Preference;->getKey()Ljava/lang/String;" }
        } ?: return null
        val load = methods.singleOrNull { callback ->
            callback.methodName == "onLoadFinished" && callback.returnTypeName == "void" &&
                callback.paramCount == 2 && callback.paramTypeNames.last() == "java.lang.Object" &&
                callback.invokes.any { body ->
                    body.className == FRAGMENT && body.paramCount == 2 && body.returnTypeName == "void" &&
                        body.paramTypeNames.first() == callback.paramTypeNames.first() &&
                        "enter_way" in body.usingStrings &&
                        body.invokes.any { it.descriptor == "Landroidx/preference/Preference;->setSummary(I)V" } &&
                        body.usingFields.any { read ->
                            read.usingType.isRead() && read.field.typeName == "miuix.preference.TextPreference" &&
                                bind.usingFields.any { write -> write.usingType.isWrite() && write.field.descriptor == read.field.descriptor }
                        }
                }
        } ?: return null
        val parse = methods.singleOrNull {
            it.paramCount == 0 && it.returnTypeName == "boolean" &&
                it.usingStrings.containsAll(listOf("package_name", "miui.intent.extra.USER_ID")) &&
                it.usingFields.any { field -> field.field.descriptor ==
                    "Landroid/content/pm/PackageInfo;->applicationInfo:Landroid/content/pm/ApplicationInfo;" }
        } ?: return null
        val app = parse.usingFields.filter {
            it.usingType.isWrite() && it.field.declaredClassName == FRAGMENT &&
                it.field.typeName == ApplicationInfo::class.java.name
        }.map { it.field.descriptor }.distinct().singleOrNull() ?: return null
        val host = ACTIVITY.toClassOrNull() ?: return null
        val fragment = FRAGMENT.toClassOrNull() ?: return null
        val activityFragment = host.declaredFields.singleOrNull { it.type == fragment } ?: return null

        return Targets(
            MethodDescriptorCodec.method(load.descriptor, classLoader) ?: return null,
            MethodDescriptorCodec.method(click.descriptor, classLoader) ?: return null,
            HostAccess.requireDeclaredMethod(fragment, "onResume", accessible = false),
            MethodDescriptorCodec.field(app, classLoader) ?: return null,
            activityFragment.apply { isAccessible = true },
        )
    }

    private fun install(targets: Targets) {
        targets.click.hook {
            before { param ->
                val row = param.args[0] ?: return@before
                if (installed !== targets || row !in originals) return@before
                // A row labeled Open by default must never clear defaults, even if launch fails.
                param.result = true
                HookFailurePolicy.open(TAG, "open system link management", Unit) {
                    try {
                        val fragment = param.thisObject
                        val activity = activity(fragment)
                        val intent = if (enabled && activity != null) destination(targets, fragment, activity) else null
                        if (activity != null && intent != null) activity.startActivity(intent)
                        else restore(row)
                    } catch (failure: Exception) {
                        restore(row)
                        throw failure
                    }
                }
            }
        }
        targets.load.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "release row for native loader", Unit) {
                    if (installed === targets) row(param.thisObject)?.let(::restore)
                }
            }
            after { param ->
                HookFailurePolicy.open(TAG, "bind loaded row", Unit) { bind(targets, param.thisObject) }
            }
        }
        targets.resume.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "refresh resumed row", Unit) { bind(targets, param.thisObject) }
            }
        }
        installed = targets
        refreshSettings()
        DebugLog.hookRegistered(TAG, ROW_KEY)
    }

    fun refreshSettings() = HostAccess.onMain {
        val targets = installed ?: return@onMain
        if (!enabled) {
            restoreRows()
            return@onMain
        }
        HookFailurePolicy.open(TAG, "find living app details", Unit) {
            HostActivityRefresh.activities().filter { it.javaClass == targets.activityFragment.declaringClass }
                .forEach { activity ->
                    HookFailurePolicy.open(TAG, "refresh living row", Unit) {
                        bind(targets, targets.activityFragment.get(activity))
                    }
                }
        }
    }

    override fun onPrepareHotReload() = HostAccess.onMain {
        installed = null
        restoreRows()
    }

    private fun restoreRows() {
        originals.keys.toList().forEach { row ->
            HookFailurePolicy.open(TAG, "restore native row", Unit) { restore(row) }
        }
    }

    private fun bind(targets: Targets, fragment: Any?) {
        if (installed !== targets || fragment == null) return
        val row = row(fragment) ?: return
        if (!enabled) {
            restore(row)
            return
        }
        // On any platform or reflection failure, leave the stock action and text together.
        try {
            val activity = activity(fragment)
            val intent = activity?.let { destination(targets, fragment, it) }
            val manager = activity?.getSystemService(DomainVerificationManager::class.java)
            val packageName = intent?.data?.schemeSpecificPart
            if (activity == null || manager == null || packageName == null) {
                restore(row)
                return
            }
            val allowed = manager.getDomainVerificationUserState(packageName)?.isLinkHandlingAllowed == true
            val strings = activity.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            val title = strings.getText(R.string.app_link_management_row_title)
            val summary = strings.getText(
                if (allowed) R.string.app_link_management_allowed else R.string.app_link_management_disallowed
            )
            if (row !in originals) {
                originals[row] = Original(
                    requireNotNull(HostAccess.method(row, "getTitle")).invoke(row) as? CharSequence,
                    requireNotNull(HostAccess.method(row, "getSummary")).invoke(row) as? CharSequence,
                )
            }
            writeText(row, title, summary)
        } catch (failure: Exception) {
            restore(row)
            throw failure
        }
    }

    private fun row(fragment: Any): Any? = HostAccess.requirePublicMethod(
        fragment.javaClass, "findPreference", CharSequence::class.java, accessible = false
    ).invoke(fragment, ROW_KEY)

    private fun activity(fragment: Any): Activity? =
        HostAccess.method(fragment, "getActivity")?.invoke(fragment) as? Activity

    private fun destination(targets: Targets, fragment: Any, activity: Activity): Intent? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val app = targets.application.get(fragment) as? ApplicationInfo ?: return null
        // The public Settings action and DomainVerificationManager both use the calling user.
        if (app.uid < 0 || UserHandle.getUserHandleForUid(app.uid) != Process.myUserHandle()) return null
        val packageName = app.packageName?.takeIf { it.isNotBlank() } ?: return null
        val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.fromParts("package", packageName, null))
            .setPackage(SETTINGS_PACKAGE)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        return intent.takeIf {
            activity.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

    private fun restore(row: Any) {
        val original = originals[row] ?: return
        writeText(row, original.title, original.summary)
        originals.remove(row)
    }

    private fun writeText(row: Any, title: CharSequence?, summary: CharSequence?) {
        val setTitle = HostAccess.requirePublicMethod(row.javaClass, "setTitle", CharSequence::class.java, accessible = false)
        val setSummary = HostAccess.requirePublicMethod(row.javaClass, "setSummary", CharSequence::class.java, accessible = false)
        setSummary.invoke(row, summary)
        setTitle.invoke(row, title)
    }
}
