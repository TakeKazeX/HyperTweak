package com.takekazex.hypertweak.hook.rules.phone

import android.app.Activity
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.ThreadActivation
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object NetworkTypeMenuHooker : StaticHooker() {
    private const val TAG = "NetworkTypeMenu"
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ENABLE_NETWORK_TYPE_MENU, false)
    }

    private val pages = generationMainResource(HostActivityRefresh()) { it.clear() }
    private var appliedSetting by generationState<Boolean?> { null }

    private data class Targets(
        val bindPage: Method,
        val search: Method,
        val gate: Method,
        val enterpriseActive: Field,
    )

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(TAG, resolve = {
            val index = DexRuntime.operands(apk)
            val contract = NetworkTypeMenuContract.resolve(index) ?: return@prepareDexTargets null
            Targets(
                bindPage = index.method(contract.bindPage, classLoader) ?: return@prepareDexTargets null,
                search = index.method(contract.search, classLoader) ?: return@prepareDexTargets null,
                gate = index.method(contract.gate, classLoader) ?: return@prepareDexTargets null,
                enterpriseActive = index.field(contract.enterpriseActive, classLoader) ?: return@prepareDexTargets null,
            )
        }) { (bindPage, search, gate, enterpriseActive) ->
            val resume = HostAccess.requireDeclaredMethod(Activity::class.java, "onPostResume", accessible = true)
            val uiQuery = ThreadActivation()

            mapOf(bindPage to "page", search to "search").forEach { (consumer, role) ->
                check(deoptimize(consumer)) { "Could not deoptimize network type menu consumer ${consumer.name}" }
                consumer.hook("phone_network_type_menu_${role}") {
                    intercept { chain ->
                        if (enabled && !generationRetired) uiQuery.within { chain.proceed() }
                        else chain.proceed()
                    }
                }
            }
            gate.hook("phone_network_type_menu_visibility") {
                after { param ->
                    if (generationRetired || !enabled || !uiQuery.active || param.result != true) return@after
                    HookFailurePolicy.open(TAG, "show preferred network type", Unit) {
                        if (!enterpriseActive.getBoolean(null)) param.result = false
                    }
                }
            }
            resume.hook("phone_network_type_menu_resume") {
                after { param ->
                    val activity = param.thisObject as? Activity ?: return@after
                    if (!generationRetired && ownsPage(activity)) {
                        HookFailurePolicy.open(TAG, "resume SIM settings", Unit) { pages.resume(activity) }
                    }
                }
            }
            // Rebuild pre-existing pages even if the retiring APK saved no feature state.
            refreshSettings(force = true)
        }
    }

    fun refreshSettings(force: Boolean = false): Boolean = HostAccess.onMain {
        if (generationRetired || (!force && (appliedSetting == null || appliedSetting == enabled))) {
            return@onMain true
        }
        HookFailurePolicy.open(TAG, "refresh SIM settings", false) {
            val setting = enabled
            pages.request(::ownsPage)
            HostActivityRefresh.activities().filter { ownsPage(it) && it.hasWindowFocus() }.forEach(pages::resume)
            appliedSetting = setting
            true
        }
    }

    private fun ownsPage(activity: Activity) = when (activity.javaClass.name) {
        "com.android.phone.settings.MultiSimInfoEditorActivity",
        "com.android.phone.settings.MobileNetworkSettings" -> true
        else -> false
    }
}
