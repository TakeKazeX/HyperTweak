package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.MenuItem
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Adjusts the two native menu items while leaving the host's action dispatch in charge. */
object AppDetailsActionsHooker : DynamicHooker() {
    private const val TAG = "AppDetailsActions"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val ACTIVITY = "com.miui.appmanager.ApplicationsDetailsActivity"
    private const val FRAGMENT = "com.miui.appmanager.fragment.ApplicationsDetailsFragment"
    private const val DISABLE_ITEM_ID = 6 // Native menu dispatch case for enable/disable.

    private val hideReport by hookSetting(false) {
        Preferences.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_HIDE_REPORT, false)
    }
    private val allowDisable by hookSetting(false) {
        Preferences.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_ALLOW_DISABLE, false)
    }

    private val protectedPackages = setOf(
        "android", PACKAGE, "com.android.settings", "com.android.systemui",
        "com.lbe.security.miui", "com.miui.packageinstaller", "com.android.packageinstaller",
    )

    private data class ItemState(val visible: Boolean, val enabled: Boolean) {
        fun restore(item: MenuItem) {
            item.isEnabled = enabled
            item.isVisible = visible
        }
    }

    private val modified = generationMainResource(WeakHashMap<MenuItem, ItemState>()) { items ->
        items.entries.toList().forEach { (item, original) -> original.restore(item) }
        items.clear()
    }

    private data class Targets(
        val createMenu: Method,
        val updateDisable: Method,
        val isProtected: Method,
        val reportItem: Field,
        val disableItem: Field,
        val application: Field,
        val activityFragment: Field,
    ) {
        fun encode() = DexContractMembers.encode(
            "createMenu" to createMenu, "updateDisable" to updateDisable, "isProtected" to isProtected,
            "reportItem" to reportItem, "disableItem" to disableItem, "application" to application,
            "activityFragment" to activityFragment,
        )
    }

    private var installed by generationState<Targets?> { null }

    override fun onHook() {
        if (hookParam.processName != PACKAGE) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(
            resolve = {
                resolveDexContract(
                    id = "securitycenter.app_details_actions",
                    apk = apk,
                    compute = ::resolve,
                    encode = Targets::encode,
                    decode = { members ->
                        Targets(
                            members.method("createMenu"), members.method("updateDisable"),
                            members.method("isProtected"), members.field("reportItem"),
                            members.field("disableItem"), members.field("application"),
                            members.field("activityFragment"),
                        )
                    },
                ) ?: run {
                    DebugLog.hookSkipped(TAG, "app details actions", "missing or ambiguous menu contract")
                    null
                }
            },
            install = ::install,
        )
    }

    private fun resolve(bridge: DexKitBridge): Targets? {
        val methods = bridge.findClass { matcher { className(FRAGMENT, StringMatchType.Equals) } }.singleOrNull()
            ?.methods?.distinctBy { it.descriptor } ?: return null
        val createMenu = methods.singleOrNull {
            it.methodName == "onCreateOptionsMenu" && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf("android.view.Menu", "android.view.MenuInflater") &&
                "mimarket://browse" in it.usingStrings
        } ?: return null
        val dispatch = methods.singleOrNull {
            it.methodName == "optionsItemSelected" && it.returnTypeName == "void" &&
                it.paramTypeNames == listOf("android.view.MenuItem") &&
                "application/vnd.android.package-archive" in it.usingStrings
        } ?: return null
        val disableAction = methods.singleOrNull {
            it.paramCount == 0 && "app_disable_description_title" in it.usingStrings &&
                dispatch.invokes.any { call -> call.descriptor == it.descriptor }
        } ?: return null
        if (disableAction.usingFields.none { it.field.descriptor == "Landroid/content/pm/ApplicationInfo;->enabled:Z" }) return null

        val updateDisable = createMenu.invokes.distinctBy { it.descriptor }.singleOrNull { method ->
            method.className == FRAGMENT && method.paramCount == 0 && method.returnTypeName == "void" &&
                method.usingFields.any { it.field.descriptor == "Landroid/content/pm/ApplicationInfo;->enabled:Z" } &&
                method.invokes.any { it.descriptor == "Landroid/view/MenuItem;->setVisible(Z)Landroid/view/MenuItem;" } &&
                method.invokes.any { it.descriptor == "Landroid/view/MenuItem;->setEnabled(Z)Landroid/view/MenuItem;" }
        } ?: return null
        val protection = updateDisable.invokes.distinctBy { it.descriptor }.singleOrNull { method ->
            method.className == FRAGMENT && method.paramCount == 0 && method.returnTypeName == "boolean" &&
                method.invokes.any { it.descriptor == "Ljava/util/List;->contains(Ljava/lang/Object;)Z" }
        } ?: return null

        // The report item is assigned only when the market report is available. Validate its
        // resource ID on each instance before changing visibility.
        val report = createMenu.usingFields.filter {
            it.usingType.isWrite() && it.field.declaredClassName == FRAGMENT &&
                it.field.typeName == "android.view.MenuItem"
        }.map { it.field }.distinctBy { it.descriptor }.singleOrNull { field ->
            methods.none { method ->
                method.usingFields.any { it.usingType.isRead() && it.field.descriptor == field.descriptor }
            }
        } ?: return null
        val disable = fragmentField(updateDisable, "android.view.MenuItem") ?: return null
        val app = fragmentField(updateDisable, "android.content.pm.ApplicationInfo") ?: return null
        val host = ACTIVITY.toClassOrNull() ?: return null
        val owner = FRAGMENT.toClassOrNull() ?: return null
        val activityFragment = host.declaredFields.singleOrNull { it.type == owner } ?: return null

        return Targets(
            MethodDescriptorCodec.method(createMenu.descriptor, classLoader) ?: return null,
            MethodDescriptorCodec.method(updateDisable.descriptor, classLoader) ?: return null,
            MethodDescriptorCodec.method(protection.descriptor, classLoader) ?: return null,
            MethodDescriptorCodec.field(report.descriptor, classLoader) ?: return null,
            disable, app, activityFragment.apply { isAccessible = true },
        )
    }

    private fun fragmentField(method: MethodData, type: String): Field? =
        method.usingFields.map { it.field }.distinctBy { it.descriptor }
            .singleOrNull { it.declaredClassName == FRAGMENT && it.typeName == type }
            ?.let { MethodDescriptorCodec.field(it.descriptor, classLoader) }

    private fun install(targets: Targets) {
        val getActivity = HostAccess.requirePublicMethod(targets.createMenu.declaringClass, "getActivity", accessible = false)
        targets.createMenu.hook {
            after { call -> HookFailurePolicy.open(TAG, "bind report", Unit) {
                val fragment = call.thisObject
                val activity = getActivity.invoke(fragment) as? Activity ?: return@open
                bindReport(targets, fragment, activity)
            } }
        }
        targets.updateDisable.hook {
            before { call -> HookFailurePolicy.open(TAG, "restore native disable item", Unit) {
                val item = targets.disableItem.get(call.thisObject) as? MenuItem ?: return@open
                restore(item)
            } }
            after { call -> HookFailurePolicy.open(TAG, "bind disable item", Unit) {
                val fragment = call.thisObject
                val activity = getActivity.invoke(fragment) as? Activity ?: return@open
                bindDisable(targets, fragment, activity)
            } }
        }
        installed = targets
        refreshSettings()
        DebugLog.hookRegistered(TAG, "app details actions")
    }

    private fun bindReport(targets: Targets, fragment: Any, activity: Activity) {
        val item = targets.reportItem.get(fragment) as? MenuItem ?: return
        val reportId = ResourceLookup.identifier(activity.resources, "app_report", "id", PACKAGE)
        if (reportId == 0 || item.itemId != reportId) return
        if (hideReport && item.isVisible) {
            remember(item)
            item.isVisible = false
        } else if (!hideReport) {
            restore(item)
        }
    }

    private fun bindDisable(targets: Targets, fragment: Any, activity: Activity) {
        val item = targets.disableItem.get(fragment) as? MenuItem ?: return
        if (item.itemId != DISABLE_ITEM_ID) return
        if (!allowDisable) {
            restore(item)
            return
        }
        if (item.isVisible) return
        val info = targets.application.get(fragment) as? ApplicationInfo ?: return
        val currentUserId = Process.myUid() / 100000
        if (!info.enabled || info.packageName in protectedPackages ||
            UserHandle.getUserHandleForUid(info.uid) != Process.myUserHandle() ||
            activity.intent.getIntExtra("miui.intent.extra.USER_ID", currentUserId) != currentUserId ||
            targets.isProtected.invoke(fragment) != false
        ) return
        val users = activity.getSystemService(UserManager::class.java) ?: return
        val policy = activity.getSystemService(DevicePolicyManager::class.java) ?: return
        if (users.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL) ||
            policy.activeAdmins.orEmpty().any { it.packageName == info.packageName }
        ) return
        remember(item)
        item.isEnabled = true
        item.isVisible = true
    }

    private fun remember(item: MenuItem) {
        modified.putIfAbsent(item, ItemState(item.isVisible, item.isEnabled))
    }

    private fun restore(item: MenuItem) {
        modified.remove(item)?.restore(item)
    }

    fun refreshSettings() = HostAccess.onMain {
        val targets = installed ?: return@onMain
        HookFailurePolicy.open(TAG, "refresh existing app details", Unit) {
            val owner = Class.forName("android.app.ActivityThread")
            val thread = HostAccess.requirePublicMethod(owner, "currentActivityThread", accessible = false)
                .invoke(null) ?: return@open
            val records = HostAccess.read(thread, "mActivities") as? Map<*, *> ?: return@open
            records.values.filterNotNull().forEach { record ->
                val activity = HostAccess.read(record, "activity") as? Activity ?: return@forEach
                if (activity.isDestroyed || activity.javaClass != targets.activityFragment.declaringClass) return@forEach
                val fragment = targets.activityFragment.get(activity) ?: return@forEach
                bindReport(targets, fragment, activity)
                bindDisable(targets, fragment, activity)
            }
        }
    }
}
