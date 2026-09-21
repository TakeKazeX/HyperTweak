package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
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
 * by its static `(Context, int, boolean, boolean) -> List` shape and call graph, and require one
 * unique result. If DexKit cannot identify that target, the hook is skipped.
 *
 * **DexKit lifetime rule.** A `MethodData` is only readable while its bridge is open:
 * [DexKitManager.withBridge] wraps `DexKitBridge.create(...).use(block)`, so the bridge is closed
 * the moment the block returns and any later `invokes`/`getClassInstance` call throws
 * `IllegalStateException: DexKitBridge is not valid`. Every `MethodData` read — the call-graph
 * disambiguation and the whole fallback row graph — therefore happens inside the block, and only
 * materialized `Method`/`Class`/`Field` objects leave it ([CardResolution]).
 */
object PowerRankingHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "PowerRanking"
    private const val PACKAGE = "com.miui.securitycenter"

    private const val POWER_RANK_HELPER = "com.miui.powercenter.legacypowerrank.f"
    private const val BATTERY_DATA = "com.miui.powercenter.legacypowerrank.BatteryData"
    private const val LABEL_HELPER = "com.miui.powercenter.legacypowerrank.a"

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

        val resolution = resolveCard() ?: run {
            DebugLog.hookSkipped(TAG, "power ranking", "card method not uniquely resolved")
            return
        }
        fallbackBuilder = runCatching { resolveFallbackBuilder(resolution) }
            .onFailure {
                DebugLog.w(TAG, "card fallback resolver failed; installing the hook without rows", it)
            }
            .getOrNull()
        if (fallbackBuilder == null) {
            DebugLog.w(TAG, "card fallback rows unavailable; card hook installed only")
        } else {
            DebugLog.i(TAG, "card fallback ready ${fallbackBuilder?.describe()}")
        }

        runCatching {
            resolution.method.isAccessible = true
            deoptimize(resolution.method)
            resolution.method.hook("power_ranking_card_fallback") {
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
            DebugLog.hookFailed(TAG, resolution.method.toGenericString(), it)
        }
    }

    /** Resolve only through the structural DexKit query; ambiguity fails closed. */
    private fun resolveCard(): CardResolution? {
        val apkPath = hookParam.appInfo?.sourceDir ?: return null
        val dexResolved = DexKitManager.withBridge(apkPath) { bridge -> resolveCardWithBridge(bridge) }
        if (dexResolved != null) DebugLog.i(TAG, "card method resolved structurally target=${dexResolved.method}")
        return dexResolved
    }

    /** Runs with the bridge open: every `MethodData` read in this function is legal here only. */
    private fun resolveCardWithBridge(bridge: DexKitBridge): CardResolution? {
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
            materializeMethod(data)?.takeIf(::isCardMethod)?.let { it to data }
        }
        val chosen = when {
            materialized.size == 1 -> materialized.single()
            materialized.size > 1 -> materialized
                .filter { looksLikePowerRanking(it.second) }
                .singleOrNull()
                ?.also { DebugLog.d(TAG, "card method disambiguated by power-rank call graph") }
                ?: run {
                    DebugLog.w(
                        TAG,
                        "card method shape is ambiguous candidates=${materialized.size}"
                    )
                    return null
                }
            else -> return null
        }

        // Materialize the call graph now: the row graph cannot be derived from a closed bridge.
        val invokedMethods = chosen.second.invokes
            .filterNot { it.isConstructor }
            .mapNotNull(::materializeMethod)
        val rowClass = chosen.second.invokes.asSequence()
            .filter { it.isConstructor && it.paramCount == 0 }
            .mapNotNull { data ->
                runCatching { data.getClassInstance(classLoader) }.getOrNull()
            }
            .firstOrNull(::looksLikeCardRow)
        return CardResolution(chosen.first, invokedMethods, rowClass)
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

    private fun resolveFallbackBuilder(resolution: CardResolution): FallbackBuilder? {
        val rankClass = POWER_RANK_HELPER.toClassOrNull() ?: return missing("rank helper class")
        val dataClass = BATTERY_DATA.toClassOrNull() ?: return missing("battery data class")
        val rowClass = resolveRowClass(resolution) ?: return missing("card row class")
        val invokedMethods = resolution.invokedMethods

        val dataMethod = invokedMethods.singleOrNull {
            it.declaringClass == rankClass &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == List::class.java
        } ?: rankClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == List::class.java
        } ?: return missing("rank list accessor on $POWER_RANK_HELPER")

        val totalMethod = invokedMethods.singleOrNull {
            it.declaringClass == rankClass &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Double::class.javaPrimitiveType
        } ?: rankClass.declaredMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType == Double::class.javaPrimitiveType
        } ?: return missing("rank total accessor on $POWER_RANK_HELPER")

        val labelClass = LABEL_HELPER.toClassOrNull()
        val labelMethod = invokedMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, dataClass)) &&
                it.returnType == String::class.java
        } ?: uniqueOrPublic(labelClass?.declaredMethods?.toList().orEmpty()) {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, dataClass)) &&
                it.returnType == String::class.java
        } ?: return missing("label method(Context, BatteryData)")

        val iconMethod = invokedMethods.singleOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(dataClass)) &&
                it.returnType == Int::class.javaPrimitiveType
        } ?: uniqueOrPublic(labelClass?.declaredMethods?.toList().orEmpty()) {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(dataClass)) &&
                it.returnType == Int::class.javaPrimitiveType
        } ?: return missing("icon method(BatteryData)")

        val systemPackageMethod = invokedMethods.firstOrNull {
            Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java, String::class.java)) &&
                it.returnType == Boolean::class.javaPrimitiveType
        }

        val constructor = rowClass.declaredConstructors.singleOrNull { it.parameterCount == 0 }
            ?: return missing("no-arg row constructor on ${rowClass.name}")
        val packageField = dataClass.accessibleField("defaultPackageName")
            ?: return missing("BatteryData#defaultPackageName")
        val uidField = dataClass.accessibleField("uid") ?: return missing("BatteryData#uid")
        val valueField = dataClass.accessibleField("value") ?: return missing("BatteryData#value")
        val rowFields = resolveRowFields(rowClass)
            ?: return missing("row fields on ${rowClass.name}")

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

    /**
     * Names the missing link so one capture says which part of the graph an OTA changed, instead of
     * a single "unresolved" line that leaves the whole chain suspect.
     */
    private fun missing(what: String): Nothing? {
        DebugLog.w(TAG, "card fallback row graph incomplete: $what")
        return null
    }

    /**
     * One match wins; several matches are narrowed to the single public one.
     *
     * The label helper carries an internal static `(Context, BatteryData) -> String` beside the
     * public one the card path calls, so a class-level lookup that insisted on a unique match would
     * give up on a buildable graph. Public is the tie-breaker because the card path calls the
     * public accessor.
     */
    private fun uniqueOrPublic(
        candidates: List<Method>,
        predicate: (Method) -> Boolean
    ): Method? {
        val matches = candidates.filter(predicate)
        return matches.singleOrNull() ?: matches.filter { Modifier.isPublic(it.modifiers) }
            .singleOrNull()
    }

    private fun resolveRowClass(resolution: CardResolution): Class<*>? {
        return resolution.rowClass?.takeIf(::looksLikeCardRow)
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

    /**
     * The card method and everything read from its DexKit data while the bridge was open.
     *
     * [invokedMethods] and [rowClass] hold materialized reflection objects on purpose: a
     * `MethodData` would throw `DexKitBridge is not valid` here, because the bridge that produced
     * it is closed by the time this value is used.
     */
    private data class CardResolution(
        val method: Method,
        val invokedMethods: List<Method>,
        val rowClass: Class<*>?
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
        fun describe(): String =
            "rank=${dataMethod.name}/${totalMethod.name} label=${labelMethod.name} " +
                "icon=${iconMethod.name} systemPackage=${systemPackageMethod?.name ?: "none"} " +
                "row=${constructor.declaringClass.simpleName}(" +
                "pkg=${rowFields.packageName.name},label=${rowFields.label.name}," +
                "percent=${rowFields.percent.name},icon=${rowFields.icon.name}," +
                "uid=${rowFields.uid.name})"

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
