package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Restores Security Center's app-consumption card when the native list is empty only because its
 * 1% display threshold filtered every app. The current 13.2.7 path is `ih.b.b(...)`, which reads
 * `legacypowerrank.f.f()` and converts the result to `ih.a` rows. The old version-code hook was
 * unrelated to this path on the current build and also changed non-ranking behaviour.
 */
object PowerRankingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "PowerRanking"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val CARD_HELPER = "ih.b"
    private const val POWER_RANK_HELPER = "com.miui.powercenter.legacypowerrank.f"
    private const val BATTERY_DATA = "com.miui.powercenter.legacypowerrank.BatteryData"
    private const val CARD_ROW = "ih.a"
    private const val LABEL_HELPER = "com.miui.powercenter.legacypowerrank.a"
    private const val SYSTEM_PACKAGE_HELPER = "nh.a"

    private val rowFields = ConcurrentHashMap<String, Field>()
    private val rowFieldMisses = ConcurrentHashMap.newKeySet<String>()

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, false)) {
            DebugLog.hookSkipped(TAG, "power ranking", "disabled")
            return
        }

        val method = resolveCardMethod() ?: run {
            DebugLog.hookSkipped(TAG, "power ranking", "current card helper not found")
            return
        }
        runCatching {
            method.isAccessible = true
            deoptimize(method)
            method.hook("power_ranking_card_fallback") {
                after { param ->
                    HookFailurePolicy.open(TAG, "card fallback", Unit) {
                        val current = param.result as? List<*>
                        if (!current.isNullOrEmpty()) return@open
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val limit = (param.args.getOrNull(1) as? Int ?: 3).coerceIn(1, 20)
                        val fallback = buildFallbackRows(context, limit)
                        if (fallback.isNotEmpty()) {
                            param.result = fallback
                            DebugLog.i(TAG, "power-ranking card fallback rows=${fallback.size}")
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, method.toGenericString(), it)
        }
    }

    private fun resolveCardMethod(): Method? {
        val helper = CARD_HELPER.toClassOrNull() ?: return null
        return helper.declaredMethods.singleOrNull { method ->
            method.name == "b" &&
                Modifier.isStatic(method.modifiers) &&
                method.returnType == List::class.java &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Context::class.java,
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType
                    )
                )
        }
    }

    private fun buildFallbackRows(context: Context, limit: Int): List<Any> {
        val rankClass = POWER_RANK_HELPER.toClassOrNull() ?: return emptyList()
        val rowClass = CARD_ROW.toClassOrNull() ?: return emptyList()
        val dataClass = BATTERY_DATA.toClassOrNull() ?: return emptyList()
        val labelClass = LABEL_HELPER.toClassOrNull() ?: return emptyList()

        val dataMethod = rankClass.declaredMethods.singleOrNull {
            it.name == "f" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        } ?: return emptyList()
        val totalMethod = rankClass.declaredMethods.singleOrNull {
            it.name == "k" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0
        } ?: return emptyList()
        val labelMethod = labelClass.declaredMethods.singleOrNull {
            it.name == "c" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, dataClass))
        } ?: return emptyList()
        val iconMethod = labelClass.declaredMethods.singleOrNull {
            it.name == "d" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(dataClass))
        } ?: return emptyList()
        val isSystemPackage = SYSTEM_PACKAGE_HELPER.toClassOrNull()?.declaredMethods?.singleOrNull {
            it.name == "e" && Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java))
        }
        val constructor = rowClass.declaredConstructors.singleOrNull { it.parameterCount == 0 } ?: return emptyList()
        val packageField = dataClass.getField("defaultPackageName")
        val uidField = dataClass.getField("uid")
        val valueField = dataClass.getField("value")

        val data = runCatching {
            dataMethod.isAccessible = true
            (dataMethod.invoke(null) as? List<*>)
                .orEmpty()
                .mapNotNull { item ->
                    item?.takeIf { candidate ->
                        dataClass.isInstance(candidate) &&
                            valueField.getDouble(candidate) > 0.0 &&
                            uidField.getInt(candidate) >= 10_000 &&
                            (packageField.get(candidate) as? String).isNullOrBlank().not() &&
                            (isSystemPackage == null ||
                                !((isSystemPackage.invoke(
                                    null,
                                    context,
                                    packageField.get(candidate)
                                ) as? Boolean) == true))
                    }
                }
                .sortedByDescending { valueField.getDouble(it) }
        }.getOrElse {
            DebugLog.w(TAG, "failed to read app power data", it)
            return emptyList()
        }
        if (data.isEmpty()) return emptyList()

        val total = runCatching {
            totalMethod.isAccessible = true
            (totalMethod.invoke(null) as? Number)?.toDouble()
        }.getOrNull()?.takeIf { it > 0.0 } ?: data.sumOf { valueField.getDouble(it) }
        if (total <= 0.0) return emptyList()

        val rows = ArrayList<Any>(minOf(limit, data.size))
        data.take(limit).forEach { batteryData ->
            val label = runCatching { labelMethod.invoke(null, context, batteryData) as? String }
                .getOrNull()
                ?.takeUnless { it.isBlank() }
                ?: return@forEach
            val row = runCatching {
                constructor.isAccessible = true
                constructor.newInstance()
            }.getOrNull() ?: return@forEach
            rowField(rowClass, "f32138a")?.set(row, packageField.get(batteryData))
            rowField(rowClass, "f32139b")?.set(row, label)
            rowField(rowClass, "f32140c")?.set(row, valueField.getDouble(batteryData) / total * 100.0)
            rowField(rowClass, "f32141d")?.setInt(row, iconMethod.invoke(null, batteryData) as Int)
            rowField(rowClass, "f32142e")?.setInt(row, uidField.getInt(batteryData))
            rows += row
        }
        return rows
    }

    /**
     * Cached `getField` lookup for the card row's obfuscated members.
     *
     * The names are resolved once per (row class, field) instead of six reflective lookups per row
     * on every refresh; a miss is cached too, so an OTA-changed row class fails fast and visibly.
     */
    private fun rowField(rowClass: Class<*>, name: String): Field? {
        val key = "${rowClass.name}#$name"
        rowFields[key]?.let { return it }
        if (key in rowFieldMisses) return null
        val field = runCatching { rowClass.getField(name) }.getOrNull()
        if (field == null) rowFieldMisses.add(key) else rowFields[key] = field
        return field
    }
}
