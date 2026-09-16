package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList

/**
 * Restores Security Center's app-consumption card when the native list is empty only because its
 * 1% display threshold filtered every app.
 *
 * The card helper is R8-obfuscated outside the stable `legacypowerrank` model package. Resolve it
 * by its static `(Context, int, boolean, boolean) -> List` shape and require one unique result;
 * the old `ih.b`/current `oh.b` names below are compatibility locators only, never active hooks.
 */
object PowerRankingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "PowerRanking"
    private const val PACKAGE = "com.miui.securitycenter"

    /** Versioned locators retained only when DexKit is unavailable. */
    private val CARD_HELPER_LOCATORS = listOf("ih.b", "oh.b")
    private val CARD_ROW_LOCATORS = listOf("ih.a", "oh.a")

    private const val POWER_RANK_HELPER = "com.miui.powercenter.legacypowerrank.f"
    private const val BATTERY_DATA = "com.miui.powercenter.legacypowerrank.BatteryData"
    private const val LABEL_HELPER = "com.miui.powercenter.legacypowerrank.a"
    private val SYSTEM_PACKAGE_HELPER_LOCATORS = listOf("th.a", "nh.a")

    @Volatile
    private var fallbackBuilder: FallbackBuilder? = null

    override fun onPrepareHotReload() {
        fallbackBuilder = null
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, false)) {
            DebugLog.hookSkipped(TAG, "power ranking", "disabled")
            return
        }

        val target = resolveCardTarget() ?: run {
            DebugLog.hookSkipped(TAG, "power ranking", "card method not uniquely resolved")
            return
        }
        fallbackBuilder = runCatching { resolveFallbackBuilder(target) }
            .onFailure {
                DebugLog.w(TAG, "card fallback resolver failed; card hook remains active", it)
            }
            .getOrNull()
        if (fallbackBuilder == null) {
            DebugLog.w(TAG, "card fallback row graph unresolved; card hook installed only")
        }

        runCatching {
            target.method.isAccessible = true
            deoptimize(target.method)
            target.method.hook("power_ranking_card_fallback") {
                after { param ->
                    HookFailurePolicy.open(TAG, "card fallback", Unit) {
                        val current = param.result as? List<*>
                        if (!current.isNullOrEmpty()) return@open
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val limit = (param.args.getOrNull(1) as? Int ?: 3).coerceIn(1, 20)
                        val fallback = fallbackBuilder?.build(context, limit).orEmpty()
                        if (fallback.isNotEmpty()) {
                            param.result = fallback
                            DebugLog.i(TAG, "power-ranking card fallback rows=${fallback.size}")
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, target.method.toGenericString(), it)
        }
    }

    private fun resolveCardTarget(): CardTarget? {
        val apkPath = hookParam.appInfo?.sourceDir
        val dexTarget = apkPath?.let { path ->
            DexKitManager.withBridge(path) { bridge ->
                val candidates = runCatching {
                    bridge.findMethod {
                        matcher {
                            paramTypes(
                                Context::class.java,
                                Int::class.javaPrimitiveType!!,
                                Boolean::class.javaPrimitiveType!!,
                                Boolean::class.javaPrimitiveType!!
                            )
                            paramCount(4)
                            returnType(List::class.java)
                        }
                    }.toList()
                }.onFailure {
                    DebugLog.w(TAG, "card method structural lookup failed", it)
                }.getOrDefault(emptyList())

                val materialized = candidates.mapNotNull { data ->
                    materializeMethod(data)?.takeIf(::isCardMethod)?.let { CardTarget(it, data) }
                }
                when {
                    materialized.size == 1 -> materialized.single()
                    materialized.size > 1 -> {
                        val graphMatches = materialized.filter {
                            it.data?.let(::looksLikePowerRanking) == true
                        }
                        graphMatches.singleOrNull()?.also {
                            DebugLog.d(TAG, "card method disambiguated by power-rank call graph")
                        } ?: run {
                            DebugLog.w(
                                TAG,
                                "card method shape is ambiguous candidates=${materialized.size}"
                            )
                            null
                        }
                    }
                    else -> null
                }
            }
        }
        if (dexTarget != null) {
            DebugLog.i(TAG, "card method resolved structurally target=${dexTarget.method}")
            return dexTarget
        }

        val namedCandidates = CARD_HELPER_LOCATORS.flatMap { className ->
            className.toClassOrNull()?.declaredMethods?.filter(::isCardMethod).orEmpty()
        }
        return namedCandidates.singleOrNull()?.let { method ->
            DebugLog.i(TAG, "card method resolved via compatibility locator target=$method")
            CardTarget(method, null)
        }
    }

    private fun looksLikePowerRanking(data: MethodData): Boolean {
        val invokes = data.invokes
        val rankList = invokes.any {
            normalizeName(it.className) == POWER_RANK_HELPER &&
                isType(it.returnTypeName, "java.util.List")
        }
        val rankTotal = invokes.any {
            normalizeName(it.className) == POWER_RANK_HELPER &&
                isType(it.returnTypeName, "double")
        }
        val batteryData = invokes.any {
            normalizeName(it.className) == BATTERY_DATA &&
                it.methodName in setOf("getValue", "getPackageName", "getUid")
        }
        return rankList && rankTotal && batteryData
    }

    private fun isCardMethod(method: Method): Boolean =
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

    private fun resolveFallbackBuilder(target: CardTarget): FallbackBuilder? {
        val rankClass = POWER_RANK_HELPER.toClassOrNull() ?: return null
        val dataClass = BATTERY_DATA.toClassOrNull() ?: return null
        val rowClass = resolveRowClass(target) ?: return null
        val invokedMethods = target.data?.invokes?.mapNotNull(::materializeMethod).orEmpty()

        val dataMethod = invokedMethods.singleOrNull {
            it.declaringClass == rankClass &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == List::class.java
        } ?: rankClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == List::class.java
        } ?: return null

        val totalMethod = invokedMethods.singleOrNull {
            it.declaringClass == rankClass &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Double::class.javaPrimitiveType
        } ?: rankClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Double::class.javaPrimitiveType
        } ?: return null

        val labelClass = LABEL_HELPER.toClassOrNull()
        val labelMethod = invokedMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, dataClass)) &&
                it.returnType == String::class.java
        } ?: labelClass?.declaredMethods?.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, dataClass)) &&
                it.returnType == String::class.java
        } ?: return null

        val iconMethod = invokedMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(dataClass)) &&
                it.returnType == Int::class.javaPrimitiveType
        } ?: labelClass?.declaredMethods?.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(dataClass)) &&
                it.returnType == Int::class.javaPrimitiveType
        } ?: return null

        val systemPackageMethod = invokedMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: SYSTEM_PACKAGE_HELPER_LOCATORS.asSequence()
            .mapNotNull { it.toClassOrNull() }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }

        val constructor = rowClass.declaredConstructors.singleOrNull { it.parameterCount == 0 }
            ?: return null
        val packageField = dataClass.accessibleField("defaultPackageName") ?: return null
        val uidField = dataClass.accessibleField("uid") ?: return null
        val valueField = dataClass.accessibleField("value") ?: return null
        val rowFields = resolveRowFields(rowClass) ?: return null

        return FallbackBuilder(
            dataMethod = dataMethod.apply { isAccessible = true },
            totalMethod = totalMethod.apply { isAccessible = true },
            labelMethod = labelMethod.apply { isAccessible = true },
            iconMethod = iconMethod.apply { isAccessible = true },
            systemPackageMethod = systemPackageMethod?.apply { isAccessible = true },
            constructor = constructor.apply { isAccessible = true },
            dataClass = dataClass,
            packageField = packageField,
            uidField = uidField,
            valueField = valueField,
            rowFields = rowFields
        )
    }

    private fun resolveRowClass(target: CardTarget): Class<*>? {
        target.data?.invokes?.asSequence()
            ?.filter { it.isConstructor && it.paramCount == 0 }
            ?.mapNotNull { data ->
                runCatching { data.getClassInstance(classLoader) }.getOrNull()
            }
            ?.firstOrNull(::looksLikeCardRow)
            ?.let { return it }

        val derived = target.method.declaringClass.name.substringBeforeLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { "$it.a" }
        return (listOfNotNull(derived) + CARD_ROW_LOCATORS)
            .distinct()
            .asSequence()
            .mapNotNull { it.toClassOrNull() }
            .firstOrNull(::looksLikeCardRow)
    }

    private fun looksLikeCardRow(clazz: Class<*>): Boolean {
        val fields = clazz.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        return fields.count { it.type == String::class.java } >= 2 &&
            fields.count { it.type == Double::class.javaPrimitiveType } >= 1 &&
            fields.count { it.type == Int::class.javaPrimitiveType } >= 2 &&
            fields.any { it.type == Double::class.javaPrimitiveType }
    }

    private fun resolveRowFields(clazz: Class<*>): RowFields? {
        val used = HashSet<Field>()
        val strings = clazz.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }
        val ints = clazz.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
        }

        fun namedOrFirst(names: List<String>, type: Class<*>, fallback: List<Field>): Field? {
            val named = names.asSequence()
                .mapNotNull { name -> runCatching { clazz.getDeclaredField(name) }.getOrNull() }
                .firstOrNull { it.type == type && !Modifier.isStatic(it.modifiers) && it !in used }
            return (named ?: fallback.firstOrNull { it !in used })?.apply {
                isAccessible = true
                used += this
            }
        }

        val packageField = namedOrFirst(listOf("f32138a", "a"), String::class.java, strings)
            ?: return null
        val labelField = namedOrFirst(listOf("f32139b", "b"), String::class.java, strings)
            ?: return null
        val doubleFallback = clazz.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Double::class.javaPrimitiveType
        } ?: return null
        val percentField = namedOrFirst(
            listOf("f32140c", "c"),
            Double::class.javaPrimitiveType!!,
            listOf(doubleFallback)
        ) ?: return null
        val iconField = namedOrFirst(listOf("f32141d", "d"), Int::class.javaPrimitiveType!!, ints)
            ?: return null
        val uidField = namedOrFirst(listOf("f32142e", "e"), Int::class.javaPrimitiveType!!, ints)
            ?: return null
        return RowFields(packageField, labelField, percentField, iconField, uidField)
    }

    private fun materializeMethod(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.d(TAG, "failed to inspect ${data.className}#${data.methodName}")
    }.getOrNull()

    private fun normalizeName(name: String): String =
        name.removePrefix("L").removeSuffix(";").replace('/', '.')

    private fun isType(name: String, expected: String): Boolean =
        name == expected || name == "L${expected.replace('.', '/')};"

    private fun Class<*>.accessibleField(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private data class CardTarget(
        val method: Method,
        val data: MethodData?
    )

    private data class RowFields(
        val packageName: Field,
        val label: Field,
        val percent: Field,
        val icon: Field,
        val uid: Field
    )

    private class FallbackBuilder(
        private val dataMethod: Method,
        private val totalMethod: Method,
        private val labelMethod: Method,
        private val iconMethod: Method,
        private val systemPackageMethod: Method?,
        private val constructor: java.lang.reflect.Constructor<*>,
        private val dataClass: Class<*>,
        private val packageField: Field,
        private val uidField: Field,
        private val valueField: Field,
        private val rowFields: RowFields
    ) {
        fun build(context: Context, limit: Int): List<Any> {
            val data = runCatching {
                (dataMethod.invoke(null) as? List<*>)
                    .orEmpty()
                    .mapNotNull { item ->
                        item?.takeIf { candidate ->
                            dataClass.isInstance(candidate) &&
                                valueField.getDouble(candidate) > 0.0 &&
                                uidField.getInt(candidate) >= 10_000 &&
                                !((packageField.get(candidate) as? String).isNullOrBlank()) &&
                                (systemPackageMethod == null ||
                                    systemPackageMethod.invoke(
                                        null,
                                        context,
                                        packageField.get(candidate)
                                    ) != true)
                        }
                    }
                    .sortedByDescending { valueField.getDouble(it) }
            }.getOrElse {
                DebugLog.w(TAG, "failed to read app power data", it)
                return emptyList()
            }
            if (data.isEmpty()) return emptyList()

            val total = runCatching {
                (totalMethod.invoke(null) as? Number)?.toDouble()
            }.getOrNull()?.takeIf { it > 0.0 } ?: data.sumOf { valueField.getDouble(it) }
            if (total <= 0.0) return emptyList()

            val rows = ArrayList<Any>(minOf(limit, data.size))
            data.take(limit).forEach { batteryData ->
                val label = runCatching {
                    labelMethod.invoke(null, context, batteryData) as? String
                }.getOrNull()?.takeUnless { it.isBlank() } ?: return@forEach
                val row = runCatching {
                    constructor.newInstance().also {
                        rowFields.packageName.set(it, packageField.get(batteryData))
                        rowFields.label.set(it, label)
                        rowFields.percent.set(it, valueField.getDouble(batteryData) / total * 100.0)
                        rowFields.icon.setInt(it, (iconMethod.invoke(null, batteryData) as Number).toInt())
                        rowFields.uid.setInt(it, uidField.getInt(batteryData))
                    }
                }.getOrNull() ?: return@forEach
                rows += row
            }
            return rows
        }
    }
}
