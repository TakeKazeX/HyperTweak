package com.takekazex.hypertweak.hook.rules.contacts

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Executable
import java.lang.reflect.Method

object BusinessHallAdBlockHooker : DynamicHooker() {
    private const val TAG = "BusinessHallAdBlock"
    private const val FEATURE = "contacts.business_hall_ads"
    private const val DATA = "com.mobile.businesshall.bean.RecommendResponse\$Data"
    private const val PRODUCT = "com.mobile.businesshall.bean.CommonProduct"
    private const val LIST = "java.util.List"

    private data class Getter(val owner: String, val field: String, val type: String) {
        val name = "get" + field.replaceFirstChar(Char::uppercaseChar)
        val fieldDescriptor = "L${owner.replace('.', '/')};->${field}:L${type.replace('.', '/')};"
    }

    private val getters = listOf(
        Getter(DATA, "popUpWindowRec", "$DATA\$PopUpWindowRec"),
        Getter(DATA, "contactRec", "$DATA\$ContactRec"),
        Getter(DATA, "contactDualCard", "$DATA\$ContactDualCard"),
        Getter(DATA, "contactNoCard", "$DATA\$ContactNoCard"),
        Getter(DATA, "contactOneCard", "$DATA\$ContactNoCard"),
        Getter(DATA, "contactIndexTrafficQuery", LIST),
        Getter(DATA, "contactIndexVirtualCard", LIST),
        Getter(DATA, "payResultRec", "$DATA\$PayResultRec"),
        Getter(PRODUCT, "activityData", LIST),
    )

    private data class Targets(val getters: List<Method>, val callers: List<Executable>)

    override fun onHook() {
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(
            FEATURE,
            resolve = {
                resolveDexContract(
                    FEATURE,
                    apk,
                    compute = ::discover,
                    encode = { DexContractMembers.encode("getters" to it.getters, "callers" to it.callers) },
                    decode = { members -> Targets(members.methods("getters"), members.executables("callers")) },
                )
            },
            install = ::install,
        )
    }

    private fun discover(bridge: DexKitBridge): Targets? {
        val classes = getters.map { it.owner }.distinct().associateWith { owner ->
            bridge.findClass { matcher { className(owner, StringMatchType.Equals) } }.singleOrNull()
        }
        val methods = getters.map { getter ->
            classes[getter.owner]?.methods?.singleOrNull { method ->
                method.methodName == getter.name && method.paramCount == 0 &&
                    method.returnTypeName == getter.type &&
                    method.opNames == listOf("iget-object", "return-object") &&
                    method.usingFields.singleOrNull()?.let {
                        it.usingType.isRead() && it.field.descriptor == getter.fieldDescriptor
                    } == true
            } ?: return null
        }
        val callers = methods.flatMap { it.callers }.distinctBy { it.descriptor }.map {
            when {
                it.isMethod -> MethodDescriptorCodec.method(it.descriptor, classLoader)
                it.isConstructor -> MethodDescriptorCodec.constructor(it.descriptor, classLoader)
                else -> null
            } ?: return null
        }
        if (methods.any { it.callers.isEmpty() }) return null
        return Targets(methods.map { it.getMethodInstance(classLoader) }, callers)
    }

    private fun install(targets: Targets) = installDexContract {
        val enabled by hookSetting(false) {
            Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_AD_BLOCK, false)
        }
        targets.callers.forEach { caller ->
            check(deoptimize(caller)) { "Could not deoptimize Business Hall consumer ${caller.name}" }
        }
        targets.getters.forEach { getter ->
            val suppressed = if (getter.returnType == List::class.java) emptyList<Any>() else null
            getter.hook("business_hall_ads_${getter.name}") {
                before { param ->
                    if (enabled) param.result = suppressed
                }
            }
        }
        DebugLog.i(TAG, "installed ${targets.getters.size} recommendation getter hooks")
    }
}
