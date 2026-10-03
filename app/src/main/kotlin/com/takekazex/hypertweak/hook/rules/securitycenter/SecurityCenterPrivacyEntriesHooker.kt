package com.takekazex.hypertweak.hook.rules.securitycenter

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.app.job.JobParameters
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.core.callMethodOrNull
import org.json.JSONObject
import java.lang.reflect.Modifier

/** Keeps the native Security Center privacy and camera entries in their original preference pages. */
object SecurityCenterPrivacyEntriesHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "SecurityCenterPrivacyEntries"
    private const val PACKAGE = "com.miui.securitycenter"

    private const val PRIVACY_CALL_KEY = "key_privacy_call"
    private const val ANTI_PEEPING_V1_KEY = "key_anti_peeping"
    private const val ANTI_PEEPING_V2_KEY = "key_peeping_perception"
    private const val ANTI_PEEPING_V2_RECOMMEND_KEY = "key_peep_protection_v2_recommend"
    private const val PRIVACY_PROTECTION_CATEGORY_KEY = "privacy_protection_category"
    private const val PRIVACY_V2_CATEGORY_KEY = "device_unique_feature_category"

    private const val GAME_BOOSTER_CUSTOM_CATEGORY_KEY = "gd_setting_group_custom_settings"
    private const val BEAUTY_MANAGE_KEY = "preference_key_beauty_manage"
    private const val CALL_PRIVACY_CAMERA_KEY = "preference_key_cb_privacy_camera"
    private const val BEAUTY_SWITCH_KEY = "preference_key_beauty_switch"
    private const val BEAUTY_AUTO_LIGHT_KEY = "preference_key_beauty_auto_light"
    private const val BEAUTY_FACE_KEY = "preference_key_beauty_face"
    private const val BEAUTY_PRIVACY_KEY = "preference_key_beauty_privacy"
    private const val BEAUTY_LIGHT_CATEGORY_KEY = "category_key_beauty_light"
    private const val BEAUTY_FACE_CATEGORY_KEY = "category_key_beauty_face"
    private const val BEAUTY_PRIVACY_CATEGORY_KEY = "category_key_beauty_privacy"

    private const val BEAUTY_UTILS_CLASS = "s8.i"
    private const val BEAUTY_MANAGE_FRAGMENT_CLASS =
        "com.miui.gamebooster.beauty.BeautyManageFragment"
    private const val COMMON_FUNCTION_INTENT_GATE = "com.miui.common.utils.v0"
    private const val FUNCTION_CARD_MODEL_CLASS = "com.miui.common.card.models.FunctionCardModel"
    private const val COMMON_FUNCTION_LIST_CLASS = "bk.e"
    private const val GRID_FUNCTION_DATA_CLASS = "com.miui.common.card.GridFunctionData"
    private const val GRID_FUNCTION_DATA_SOURCE_CLASS =
        "com.miui.common.card.GridFunctionData\$DataSource"
    private const val ANTI_PEEPING_SUPPORT_CLASS = "t3.g"
    private const val FRONT_ASSISTANT_ACTION = "com.miui.gamebooster.action.ACCESS_FRONT_ASSISTANT"
    private const val FRONT_ASSISTANT_ACTION_URI =
        "#Intent;action=$FRONT_ASSISTANT_ACTION;end"

    private val beautyManageUiGateActive = ThreadLocal.withInitial { false }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!hasAnyFeatureEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "native privacy and camera entries", "no overrides selected")
            return
        }

        val selectedAntiPeepingMode = antiPeepingMode()
        if (selectedAntiPeepingMode != Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT) {
            hookAntiPeepingCapabilityGates()
        }

        if (!hookParam.isMainProcess) return

        hookPreferenceVisibility()
        hookPreferenceRemoval()
        if (isFrontCameraAssistantEnabled()) {
            hookFrontAssistantAction()
            hookFrontAssistantFunctionCardGates()
            hookBeautyManageEntry()
        }

        DebugLog.i(
            TAG,
            "native entry overrides active; anti-peeping mode=$selectedAntiPeepingMode"
        )
    }

    private fun hasAnyFeatureEnabled(): Boolean =
        isPrivacyCallEnabled() ||
            antiPeepingMode() != Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT ||
            isFrontCameraAssistantEnabled() ||
            isCallPrivacyCameraEnabled()

    private fun isPrivacyCallEnabled(): Boolean = Preferences.getBoolean(
        Preferences.KEY_SECURITY_CENTER_PRIVACY_CALL_ENTRY,
        false
    )

    private fun antiPeepingMode(): Int = Preferences.getInt(
        Preferences.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE,
        Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT
    ).coerceIn(
        Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT,
        Preferences.SECURITY_CENTER_ANTI_PEEPING_V2
    )

    private fun isFrontCameraAssistantEnabled(): Boolean = Preferences.getBoolean(
        Preferences.KEY_SECURITY_CENTER_FRONT_CAMERA_ASSISTANT_ENTRY,
        false
    )

    private fun isCallPrivacyCameraEnabled(): Boolean = Preferences.getBoolean(
        Preferences.KEY_SECURITY_CENTER_CALL_PRIVACY_CAMERA_ENTRY,
        false
    )

    private fun hookPreferenceVisibility() {
        val preferenceClass = "androidx.preference.Preference".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "Preference.setVisible", "Preference class not found")
            return
        }
        val setVisible = CompatibleMethodResolver.find(
            preferenceClass,
            "setVisible",
            parameterTypes = listOf(Boolean::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "Preference.setVisible(boolean)", "method not found")
            return
        }

        runCatching {
            deoptimize(setVisible)
            setVisible.hook("security_center_independent_native_entry_visibility") {
                before { param ->
                    HookFailurePolicy.open(TAG, "Preference.setVisible.before", Unit) {
                        val preference = param.thisObjectOrNull ?: return@open
                        val key = preference.callMethodOrNull("getKey") as? String ?: return@open
                        visibilityOverride(key)?.let { param.args[0] = it }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "Preference.setVisible(boolean)", it)
        }
    }

    private fun hookPreferenceRemoval() {
        val groupClass = "androidx.preference.PreferenceGroup".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference", "class not found")
            return
        }
        val preferenceClass = "androidx.preference.Preference".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference", "Preference class not found")
            return
        }
        val removePreference = CompatibleMethodResolver.find(
            groupClass,
            "removePreference",
            parameterTypes = listOf(preferenceClass)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference(Preference)", "method not found")
            return
        }

        runCatching {
            deoptimize(removePreference)
            removePreference.hook("security_center_keep_native_privacy_entries") {
                before { param ->
                    HookFailurePolicy.open(TAG, "PreferenceGroup.removePreference.before", Unit) {
                        val preference = param.args.getOrNull(0) ?: return@open
                        val key = preference.callMethodOrNull("getKey") as? String ?: return@open
                        if (shouldKeepPreference(key)) param.result = false
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "PreferenceGroup.removePreference(Preference)", it)
        }
    }

    private fun visibilityOverride(key: String): Boolean? {
        val antiPeeping = antiPeepingMode()
        return when (key) {
            PRIVACY_CALL_KEY -> true.takeIf { isPrivacyCallEnabled() }
            PRIVACY_PROTECTION_CATEGORY_KEY ->
                true.takeIf {
                    isPrivacyCallEnabled() ||
                        antiPeeping != Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT
                }

            ANTI_PEEPING_V1_KEY -> when (antiPeeping) {
                Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT -> null
                else -> antiPeeping == Preferences.SECURITY_CENTER_ANTI_PEEPING_V1
            }

            ANTI_PEEPING_V2_KEY,
            ANTI_PEEPING_V2_RECOMMEND_KEY -> when (antiPeeping) {
                Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT -> null
                else -> antiPeeping == Preferences.SECURITY_CENTER_ANTI_PEEPING_V2
            }

            PRIVACY_V2_CATEGORY_KEY -> when (antiPeeping) {
                Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT -> null
                else -> antiPeeping == Preferences.SECURITY_CENTER_ANTI_PEEPING_V2
            }

            GAME_BOOSTER_CUSTOM_CATEGORY_KEY ->
                true.takeIf { isFrontCameraAssistantEnabled() || isCallPrivacyCameraEnabled() }

            BEAUTY_MANAGE_KEY,
            BEAUTY_SWITCH_KEY,
            BEAUTY_AUTO_LIGHT_KEY,
            BEAUTY_FACE_KEY,
            BEAUTY_LIGHT_CATEGORY_KEY,
            BEAUTY_FACE_CATEGORY_KEY -> true.takeIf { isFrontCameraAssistantEnabled() }

            CALL_PRIVACY_CAMERA_KEY,
            BEAUTY_PRIVACY_KEY,
            BEAUTY_PRIVACY_CATEGORY_KEY -> true.takeIf { isCallPrivacyCameraEnabled() }
            else -> null
        }
    }

    private fun shouldKeepPreference(key: String): Boolean = when (key) {
        BEAUTY_MANAGE_KEY,
        BEAUTY_AUTO_LIGHT_KEY,
        BEAUTY_LIGHT_CATEGORY_KEY,
        BEAUTY_FACE_KEY,
        BEAUTY_FACE_CATEGORY_KEY -> isFrontCameraAssistantEnabled()

        BEAUTY_PRIVACY_KEY,
        BEAUTY_PRIVACY_CATEGORY_KEY -> isCallPrivacyCameraEnabled()
        else -> false
    }

    /** Applies the selected version to Security Center's shared V1/V2 support gates. */
    private fun hookAntiPeepingCapabilityGates() {
        val supportClass = ANTI_PEEPING_SUPPORT_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ANTI_PEEPING_SUPPORT_CLASS, "class not found")
            return
        }
        hookAntiPeepingCapabilityGate(
            supportClass = supportClass,
            methodName = "j",
            selectedMode = Preferences.SECURITY_CENTER_ANTI_PEEPING_V1,
            hookKey = "security_center_anti_peeping_v1_capability"
        )
        hookAntiPeepingCapabilityGate(
            supportClass = supportClass,
            methodName = "k",
            selectedMode = Preferences.SECURITY_CENTER_ANTI_PEEPING_V2,
            hookKey = "security_center_anti_peeping_v2_capability"
        )
        deoptimizeAntiPeepingFlowCallers()
    }

    /** Prevents ART from keeping an inlined stock-false capability result in entry/lifecycle code. */
    private fun deoptimizeAntiPeepingFlowCallers() {
        when (antiPeepingMode()) {
            Preferences.SECURITY_CENTER_ANTI_PEEPING_V1 -> {
                deoptimizeTargetMethod("com.miui.antipeeping.common.c", "q")
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v1.ui.activity.AntiPeepingSplashActivity",
                    "onCreate",
                    Bundle::class.java
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v1.ui.activity.AntiPeepingActivity",
                    "onCreate",
                    Bundle::class.java
                )
                "miui.process.ForegroundInfo".toClassOrNull()?.let { foregroundInfo ->
                    deoptimizeTargetMethod(
                        "nf.x\$a",
                        "onForegroundInfoChanged",
                        foregroundInfo
                    )
                }
                deoptimizeTargetMethod("nf.x\$g", "onReceive", Context::class.java, Intent::class.java)
                deoptimizeTargetMethod("nf.x", "H0")
            }

            Preferences.SECURITY_CENTER_ANTI_PEEPING_V2 -> {
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.ui.PeepProtectionIntroActivity",
                    "onCreate",
                    Bundle::class.java
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.ui.PeepProtectionMainActivity",
                    "onCreate",
                    Bundle::class.java
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.ui.PeepProtectionTileService",
                    "onClick"
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.ui.PeepProtectionTileService",
                    "onStartListening"
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.provider.PeepProtectionRemoteProvider",
                    "onCreate"
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.provider.PeepProtectionRemoteProvider",
                    "handleToggle",
                    Context::class.java,
                    Bundle::class.java
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.provider.PeepProtectionPageProvider",
                    "onCreate"
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.provider.PeepProtectionPageProvider",
                    "call",
                    String::class.java,
                    String::class.java,
                    Bundle::class.java
                )
                deoptimizeTargetMethod(
                    "com.miui.antipeeping.v2.provider.PeepProtectionPageProvider",
                    "syncTileComponentsState",
                    Context::class.java
                )
                deoptimizeTargetMethod("z3.p", "i")
                deoptimizeTargetMethod("z3.f", "b", Context::class.java)
                deoptimizeTargetMethod("z3.a", "a", Context::class.java)
                deoptimizeTargetMethod("z3.e", "onReceive", Context::class.java, Intent::class.java)
                if (hookParam.isMainProcess) {
                    deoptimizeTargetMethod("com.miui.securitycenter.Application", "onCreate")
                }
            }
        }

        if (!hookParam.isMainProcess) {
            deoptimizeTargetMethod(
                "com.miui.securitycenter.service.ConnectivityChangeJobService2",
                "onStartJob",
                JobParameters::class.java
            )
            deoptimizeTargetMethod(
                "com.miui.securitycenter.service.ConnectivityChangeJobService2\$e",
                "run"
            )
        }
    }

    private fun deoptimizeTargetMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>
    ) {
        val targetClass = className.toClassOrNull() ?: return
        CompatibleMethodResolver.find(
            targetClass,
            methodName,
            parameterTypes = parameterTypes.toList()
        )?.let(::deoptimize)
    }

    private fun hookAntiPeepingCapabilityGate(
        supportClass: Class<*>,
        methodName: String,
        selectedMode: Int,
        hookKey: String
    ) {
        val method = CompatibleMethodResolver.find(
            supportClass,
            methodName,
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = emptyList()
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(TAG, "$ANTI_PEEPING_SUPPORT_CLASS#$methodName()", "method not uniquely resolved")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook(hookKey) {
                before { param ->
                    HookFailurePolicy.open(TAG, "$ANTI_PEEPING_SUPPORT_CLASS#$methodName", Unit) {
                        val mode = antiPeepingMode()
                        if (mode != Preferences.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT) {
                            param.result = mode == selectedMode
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$ANTI_PEEPING_SUPPORT_CLASS#$methodName()", it)
        }
    }

    /** Lets Security Center's native function-card filter retain the front-camera assistant item. */
    private fun hookFrontAssistantAction() {
        val actionGateClass = COMMON_FUNCTION_INTENT_GATE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COMMON_FUNCTION_INTENT_GATE, "class not found")
            return
        }
        val isSupportedAction = CompatibleMethodResolver.find(
            actionGateClass,
            "i",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(String::class.java)
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$COMMON_FUNCTION_INTENT_GATE#i(String)",
                "intent support predicate not uniquely resolved"
            )
            return
        }

        runCatching {
            deoptimize(isSupportedAction)
            isSupportedAction.hook("security_center_front_assistant_native_entry") {
                before { param ->
                    HookFailurePolicy.open(TAG, "front assistant action visibility", Unit) {
                        val action = param.args.getOrNull(0) as? String ?: return@open
                        if (action == FRONT_ASSISTANT_ACTION_URI && isFrontCameraAssistantEnabled()) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$COMMON_FUNCTION_INTENT_GATE front assistant entry", it)
        }
    }

    /** The native function card applies a second cloud/version filter after local capability. */
    private fun hookFrontAssistantFunctionCardGates() {
        val functionCardModel = FUNCTION_CARD_MODEL_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, FUNCTION_CARD_MODEL_CLASS, "class not found")
            return
        }
        hookFrontAssistantStringGate(
            functionCardModel,
            methodName = "isShowLocalFunction",
            hookKey = "security_center_front_assistant_local_card_gate"
        )
        hookFrontAssistantCommonFunctionFallback()

        val cloudGate = CompatibleMethodResolver.find(
            functionCardModel,
            "isCloudShowFunction",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(JSONObject::class.java)
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$FUNCTION_CARD_MODEL_CLASS#isCloudShowFunction(JSONObject)",
                "method not uniquely resolved"
            )
            return
        }

        runCatching {
            deoptimize(cloudGate)
            cloudGate.hook("security_center_front_assistant_cloud_card_gate") {
                before { param ->
                    HookFailurePolicy.open(TAG, "front assistant cloud card gate", Unit) {
                        if (!isFrontCameraAssistantEnabled()) return@open
                        val item = param.args.getOrNull(0) as? JSONObject ?: return@open
                        val action = item.optString("action")
                        if (isFrontAssistantAction(action)) param.result = true
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$FUNCTION_CARD_MODEL_CLASS#isCloudShowFunction(JSONObject)", it)
        }
    }

    /**
     * Ensures the front-camera assistant occupies a slot in Security Center's native common
     * function card when the cloud list omits it. Replace an unpinned suggestion when the six
     * native slots are full; preserve all user-pinned functions.
     */
    private fun hookFrontAssistantCommonFunctionFallback() {
        val listBuilder = COMMON_FUNCTION_LIST_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, COMMON_FUNCTION_LIST_CLASS, "class not found")
            return
        }
        val buildNewCommonFunctions = CompatibleMethodResolver.find(
            listBuilder,
            "d",
            returnType = List::class.java,
            parameterTypes = listOf(Context::class.java, List::class.java)
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$COMMON_FUNCTION_LIST_CLASS#d(Context,List)",
                "native common-function builder not uniquely resolved"
            )
            null
        }

        if (buildNewCommonFunctions != null) {
            runCatching {
                deoptimize(buildNewCommonFunctions)
                buildNewCommonFunctions.hook("security_center_front_assistant_new_common_card_fallback") {
                    after { param ->
                        HookFailurePolicy.open(TAG, "front assistant new common card fallback", Unit) {
                            val context = param.args.getOrNull(0) as? Context ?: return@open
                            val functions = mutableFunctionList(param.result) ?: return@open
                            ensureFrontAssistantInCommonFunctions(context, functions)
                        }
                    }
                }
            }.onFailure {
                DebugLog.hookFailed(TAG, "$COMMON_FUNCTION_LIST_CLASS#d(Context,List)", it)
            }
        }

        val commonCardClass = "com.miui.common.card.models.CommonlyUsedFunctionCardModel".toClassOrNull()
        if (commonCardClass == null) {
            DebugLog.hookSkipped(TAG, "CommonlyUsedFunctionCardModel", "class not found")
            return
        }
        val buildLegacyCommonFunctions = CompatibleMethodResolver.find(
            listBuilder,
            "b",
            returnType = commonCardClass,
            parameterTypes = listOf(Context::class.java)
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(TAG, "$COMMON_FUNCTION_LIST_CLASS#b(Context)", "native common-function builder not uniquely resolved")
            return
        }

        runCatching {
            deoptimize(buildLegacyCommonFunctions)
            buildLegacyCommonFunctions.hook("security_center_front_assistant_legacy_common_card_fallback") {
                after { param ->
                    HookFailurePolicy.open(TAG, "front assistant legacy common card fallback", Unit) {
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val card = param.result ?: return@open
                        val functions = mutableFunctionList(
                            card.callMethodOrNull("getCommonlyUsedFuncDataList")
                        )
                            ?: return@open
                        ensureFrontAssistantInCommonFunctions(context, functions)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$COMMON_FUNCTION_LIST_CLASS#b(Context)", it)
        }
    }

    private fun ensureFrontAssistantInCommonFunctions(context: Context, functions: MutableList<Any?>) {
        if (!isFrontCameraAssistantEnabled()) return
        if (functions.any { gridFunctionAction(it) == FRONT_ASSISTANT_ACTION_URI }) return
        val assistant = createFrontAssistantGridFunctionData(context) ?: return
        val lastVisibleIndex = minOf(functions.size, 6) - 1

        val replaceIndex = (lastVisibleIndex downTo 0).firstOrNull {
            gridFunctionDataSource(it) == "RANDOM_RECOMMENDATION"
        } ?: (lastVisibleIndex downTo 0).firstOrNull {
            gridFunctionDataSource(it) == "RECENT_USED"
        } ?: (lastVisibleIndex downTo 0).firstOrNull {
            gridFunctionDataSource(it) == "SERVER_CONFIGURATION"
        }
        when {
            functions.size < 6 -> functions.add(assistant)
            replaceIndex != null -> functions[replaceIndex] = assistant
            lastVisibleIndex >= 0 -> {
                // The native card has only six slots; preserve the saved user list and replace
                // only its last in-memory item while this module switch is enabled.
                functions[lastVisibleIndex] = assistant
                DebugLog.d(TAG, "front assistant uses the last temporary common-function slot")
            }
        }
    }

    private fun createFrontAssistantGridFunctionData(context: Context): Any? = runCatching {
        val dataClass = GRID_FUNCTION_DATA_CLASS.toClassOrNull() ?: return null
        val data = dataClass.getDeclaredConstructor().newInstance()
        val dataSourceClass = GRID_FUNCTION_DATA_SOURCE_CLASS.toClassOrNull() ?: return null
        val recommendationSource = dataSourceClass.enumConstants?.firstOrNull {
            (it as? Enum<*>)?.name == "RANDOM_RECOMMENDATION"
        } ?: return null
        val resources = context.resources
        val titleId = resources.getIdentifier("beauty_fc_assistant", "string", PACKAGE)
        val summaryId = resources.getIdentifier("beauty_fc_assistant_summary_common", "string", PACKAGE)
        val iconId = resources.getIdentifier("ic_beauty_settings", "drawable", PACKAGE)
        if (titleId == 0 || summaryId == 0 || iconId == 0) return null

        data.callMethodOrNull("setTitle", resources.getString(titleId))
        data.callMethodOrNull("setSummary", resources.getString(summaryId))
        data.callMethodOrNull("setFunctionId", "10020001")
        data.callMethodOrNull("setAction", FRONT_ASSISTANT_ACTION_URI)
        data.callMethodOrNull("setStatKey", FRONT_ASSISTANT_ACTION_URI)
        data.callMethodOrNull("setDataId", FRONT_ASSISTANT_ACTION_URI)
        data.callMethodOrNull("setUseLocalPic", true)
        data.callMethodOrNull("setLocalPicResoourceId", iconId)
        data.callMethodOrNull("setDataSource", recommendationSource)
        data
    }.onFailure {
        DebugLog.hookFailed(TAG, "create native front assistant card item", it)
    }.getOrNull()

    private fun gridFunctionAction(data: Any?): String? =
        data?.callMethodOrNull("getAction") as? String

    private fun gridFunctionDataSource(data: Any?): String? =
        (data?.callMethodOrNull("getDataSource") as? Enum<*>)?.name

    @Suppress("UNCHECKED_CAST")
    private fun mutableFunctionList(value: Any?): MutableList<Any?>? =
        value as? MutableList<Any?>

    private fun hookFrontAssistantStringGate(functionCardModel: Class<*>, methodName: String, hookKey: String) {
        val method = CompatibleMethodResolver.find(
            functionCardModel,
            methodName,
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = listOf(String::class.java)
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(TAG, "$FUNCTION_CARD_MODEL_CLASS#$methodName(String)", "method not uniquely resolved")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook(hookKey) {
                before { param ->
                    HookFailurePolicy.open(TAG, "front assistant function card gate", Unit) {
                        val action = param.args.getOrNull(0) as? String ?: return@open
                        if (isFrontCameraAssistantEnabled() && isFrontAssistantAction(action)) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$FUNCTION_CARD_MODEL_CLASS#$methodName(String)", it)
        }
    }

    private fun isFrontAssistantAction(value: String): Boolean {
        if (value == FRONT_ASSISTANT_ACTION || value == FRONT_ASSISTANT_ACTION_URI) return true
        return runCatching { Intent.parseUri(value, 0).action == FRONT_ASSISTANT_ACTION }
            .getOrDefault(false)
    }

    /**
     * BeautyManageFragment finishes its Activity when the vendor beauty capability predicate is
     * false. Override that predicate only while the fragment builds this native settings page;
     * calls from BeautyView, service code, and actual camera operation keep the vendor result.
     */
    private fun hookBeautyManageEntry() {
        val beautyUtils = BEAUTY_UTILS_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BEAUTY_UTILS_CLASS, "class not found")
            return
        }
        val supportBeauty = CompatibleMethodResolver.find(
            beautyUtils,
            "M",
            returnType = Boolean::class.javaPrimitiveType,
            parameterTypes = emptyList()
        )?.takeIf { Modifier.isStatic(it.modifiers) } ?: run {
            DebugLog.hookSkipped(TAG, "$BEAUTY_UTILS_CLASS#M()", "support predicate not uniquely resolved")
            return
        }
        val beautyManageFragment = BEAUTY_MANAGE_FRAGMENT_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BEAUTY_MANAGE_FRAGMENT_CLASS, "class not found")
            return
        }
        val onCreatePreferences = CompatibleMethodResolver.find(
            beautyManageFragment,
            "onCreatePreferences",
            parameterTypes = listOf(Bundle::class.java, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$BEAUTY_MANAGE_FRAGMENT_CLASS#onCreatePreferences(Bundle,String)",
                "method not found"
            )
            return
        }

        runCatching {
            // The support call is in this virtual screen initializer. Deoptimize both sides so the
            // UI-only predicate override still sees it if ART inlined the tiny static method.
            deoptimize(supportBeauty)
            deoptimize(onCreatePreferences)
            onCreatePreferences.hook("security_center_beauty_manage_ui_scope") {
                before {
                    if (isFrontCameraAssistantEnabled()) beautyManageUiGateActive.set(true)
                }
                after {
                    beautyManageUiGateActive.remove()
                }
            }
            supportBeauty.hook("security_center_beauty_manage_ui_capability") {
                before { param ->
                    HookFailurePolicy.open(TAG, "beauty manage UI support gate", Unit) {
                        if (isFrontCameraAssistantEnabled() && beautyManageUiGateActive.get() == true) {
                            param.result = true
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$BEAUTY_MANAGE_FRAGMENT_CLASS beauty entry", it)
        }
    }

}
