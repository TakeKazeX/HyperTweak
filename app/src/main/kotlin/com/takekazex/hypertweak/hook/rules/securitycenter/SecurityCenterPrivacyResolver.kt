package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

private const val TAG = "SecurityCenterPrivacyEntries"

/** Registered with [DexRuntime]; bump [schema] when the evidence or queries change. */
internal object SecurityCenterPrivacyFeature : DexFeature {
    override val id = "securitycenter.privacy"
    override val schema = 2
}

/**
 * Resolves the obfuscated Security Center privacy targets from host evidence.
 *
 * The previous implementation parsed the 81 MB target APK with dexlib2 and built two full
 * instruction indexes (string literals and a reverse call graph) on the package-load thread. That
 * cost 7–10.5 s for every cold start of `com.miui.securitycenter`, long enough for ActivityManager
 * to kill `com.miui.securitycenter.remote` for missing its content-provider publish deadline.
 *
 * DexKit answers the same questions in native code. [DexRuntime] caches the descriptors keyed by
 * the target APK build and the feature schema, so only an APK update or a query change triggers
 * another scan; ordinary process starts materialize the cached methods through the host class
 * loader and install their hooks immediately.
 */
internal class SecurityCenterPrivacyResolver private constructor(private val targets: Targets) {

    fun antiPeepingV1(): Method? = targets.antiPeepingV1

    fun antiPeepingV2(): Method? = targets.antiPeepingV2

    fun frontAction(): Method? = targets.frontAction

    fun commonFunctions(): Method? = targets.commonFunctions

    fun legacyCommonCard(): Method? = targets.legacyCommonCard

    fun beautyEntry(): Method? = targets.beautyEntry

    fun beautySupport(): Method? = targets.beautySupport

    /** Callers that must be deoptimized so the capability hook is not bypassed by inlining. */
    fun callers(target: Method): List<Method> = when (target) {
        targets.antiPeepingV1 -> targets.antiPeepingV1Callers
        targets.antiPeepingV2 -> targets.antiPeepingV2Callers
        else -> emptyList()
    }

    private data class Targets(
        val antiPeepingV1: Method?,
        val antiPeepingV2: Method?,
        val frontAction: Method?,
        val commonFunctions: Method?,
        val legacyCommonCard: Method?,
        val beautyEntry: Method?,
        val beautySupport: Method?,
        val antiPeepingV1Callers: List<Method>,
        val antiPeepingV2Callers: List<Method>,
    )

    /**
     * Descriptor snapshot: the only representation that outlives a DexKit bridge.
     *
     * `MethodData` is invalid once the shared DexKit scope closes, so every call-graph read
     * happens inside that block and only these strings and materialized [Method]s leave it.
     */
    private data class Snapshot(
        val antiPeepingV1: String?,
        val antiPeepingV2: String?,
        val frontAction: String?,
        val commonFunctions: String?,
        val legacyCommonCard: String?,
        val beautyEntry: String?,
        val beautySupport: String?,
        val antiPeepingV1Callers: List<String>,
        val antiPeepingV2Callers: List<String>,
    ) {
        /**
         * [strict] is used for cache reads: any descriptor that no longer resolves means the cache
         * belongs to a different build (or was corrupted) and the caller must scan again. A fresh
         * scan materializes tolerantly so one missing optional capability cannot disable the rest.
         */
        fun materialize(loader: ClassLoader, strict: Boolean): Targets? {
            var failed = false
            fun method(role: String, descriptor: String?): Method? {
                if (descriptor == null) return null
                val resolved = MethodDescriptorCodec.method(descriptor, loader)
                if (resolved == null) {
                    failed = true
                    DebugLog.w(TAG, "privacy target $role did not materialize; descriptor=$descriptor")
                }
                return resolved
            }

            fun callers(role: String, descriptors: List<String>): List<Method> = descriptors.mapNotNull {
                val resolved = MethodDescriptorCodec.method(it, loader)
                if (resolved == null) {
                    failed = true
                    DebugLog.w(TAG, "privacy caller of $role did not materialize; descriptor=$it")
                }
                resolved
            }

            val targets = Targets(
                antiPeepingV1 = method("anti-peeping V1", antiPeepingV1),
                antiPeepingV2 = method("anti-peeping V2", antiPeepingV2),
                frontAction = method("front assistant action", frontAction),
                commonFunctions = method("common function list", commonFunctions),
                legacyCommonCard = method("common function card", legacyCommonCard),
                beautyEntry = method("beauty settings initializer", beautyEntry),
                beautySupport = method("beauty UI support", beautySupport),
                antiPeepingV1Callers = callers("anti-peeping V1", antiPeepingV1Callers),
                antiPeepingV2Callers = callers("anti-peeping V2", antiPeepingV2Callers),
            )
            return if (strict && failed) null else targets
        }
    }

    companion object {
        private const val V1_TEXT = "supportAntiPeeping: device supports pixel privacy, use V2 instead"
        private const val V2_TEXT = "supportAntiPeepingV2: not support pixel privacy"
        private const val FRONT_ACTION_URI =
            "#Intent;action=com.miui.gamebooster.action.ACCESS_FRONT_ASSISTANT;end"
        private const val COMMON_USERSET_TEXT = "filter commonly after userset: "
        private const val COMMON_SERVER_TEXT = "filter commonly after server: "
        private const val BEAUTY_UNSUPPORTED_TEXT = "Device not support beauty!!!"
        private const val BEAUTY_SWITCH_KEY = "preference_key_beauty_switch"
        private const val BEAUTY_SUPPORT_PROPERTY = "persist.vendor.vcb.ability"

        /**
         * Resolves the targets for [apkPath] through the shared DEX runtime.
         *
         * [cacheOnly] is used by auxiliary processes: they install the cached gates without
         * opening a backend, and let the caller defer the one scan after an APK update.
         */
        fun resolve(
            apkPath: String,
            loader: ClassLoader,
            cacheDir: File?,
            cacheOnly: Boolean = false,
        ): SecurityCenterPrivacyResolver? = DexRuntime.resolve(
            feature = SecurityCenterPrivacyFeature,
            apkPath = apkPath,
            cacheDir = cacheDir,
            cacheOnly = cacheOnly,
            compute = { session ->
                session.dexKit { bridge -> resolveWithBridge(bridge, loader).toValues() }
                    ?: throw IllegalStateException("DexKit session unavailable")
            },
            decode = { values, fromCache -> decode(values, loader, strict = fromCache) },
        )

        private fun decode(
            values: Map<String, String>,
            loader: ClassLoader,
            strict: Boolean,
        ): SecurityCenterPrivacyResolver? {
            val targets = values.toSnapshot().materialize(loader, strict) ?: return null
            return SecurityCenterPrivacyResolver(targets)
        }

        private fun Snapshot.toValues(): Map<String, String> = buildMap {
            antiPeepingV1?.let { put("v1", it) }
            antiPeepingV2?.let { put("v2", it) }
            frontAction?.let { put("front", it) }
            commonFunctions?.let { put("common", it) }
            legacyCommonCard?.let { put("legacy", it) }
            beautyEntry?.let { put("beauty", it) }
            beautySupport?.let { put("beauty_support", it) }
            if (antiPeepingV1Callers.isNotEmpty()) put("v1_callers", antiPeepingV1Callers.joinToString(","))
            if (antiPeepingV2Callers.isNotEmpty()) put("v2_callers", antiPeepingV2Callers.joinToString(","))
        }

        private fun Map<String, String>.toSnapshot(): Snapshot {
            fun value(key: String): String? = this[key]?.takeIf { it.isNotEmpty() }
            fun descriptors(key: String): List<String> =
                this[key].orEmpty().split(',').filter { it.isNotEmpty() }
            return Snapshot(
                antiPeepingV1 = value("v1"),
                antiPeepingV2 = value("v2"),
                frontAction = value("front"),
                commonFunctions = value("common"),
                legacyCommonCard = value("legacy"),
                beautyEntry = value("beauty"),
                beautySupport = value("beauty_support"),
                antiPeepingV1Callers = descriptors("v1_callers"),
                antiPeepingV2Callers = descriptors("v2_callers"),
            )
        }

        /** Bridge-lifetime block: every `MethodData` read below happens while the bridge is open. */
        private fun resolveWithBridge(bridge: DexKitBridge, loader: ClassLoader): Snapshot {
            val queries = HashMap<String, List<MethodData>>()
            fun using(text: String): List<MethodData> = queries.getOrPut(text) {
                runCatching {
                    bridge.findMethod {
                        matcher { addUsingString(text, StringMatchType.Equals) }
                    }.toList()
                }.onFailure {
                    DebugLog.w(TAG, "privacy string query failed: $text", it)
                }.getOrDefault(emptyList())
            }

            fun intersect(first: List<MethodData>, second: List<MethodData>): List<MethodData> {
                val secondDescriptors = second.mapTo(HashSet()) { it.descriptor }
                return first.filter { it.descriptor in secondDescriptors }
            }

            fun unique(role: String, candidates: List<MethodData>): MethodData? {
                val distinct = candidates.distinctBy { it.descriptor }
                if (distinct.isEmpty()) {
                    DebugLog.d(TAG, "$role semantic candidates=0; preserving native behavior")
                    return null
                }
                if (distinct.size > 1) {
                    DebugLog.w(TAG, "$role semantic candidates=${distinct.size}; skipping this capability")
                    return null
                }
                return distinct.single()
            }

            fun callersGraph(root: MethodData): List<MethodData> {
                val visited = LinkedHashMap<String, MethodData>()
                visited[root.descriptor] = root
                var frontier = listOf(root)
                repeat(4) {
                    val next = ArrayList<MethodData>()
                    frontier.forEach { data ->
                        runCatching { data.callers.toList() }.getOrDefault(emptyList()).forEach { caller ->
                            if (visited.putIfAbsent(caller.descriptor, caller) == null) next += caller
                        }
                    }
                    frontier = next
                }
                return visited.values.toList()
            }

            val v1 = unique(
                "anti-peeping V1",
                using(V1_TEXT).filter { it.zeroBoolean() }.flatMap { root ->
                    if (root.static()) {
                        listOf(root)
                    } else {
                        runCatching { root.callers.toList() }.getOrDefault(emptyList()).filter { caller ->
                            caller.declaredClassName == root.declaredClassName &&
                                caller.static() && caller.zeroBoolean() &&
                                runCatching { caller.invokes.toList() }
                                    .getOrDefault(emptyList())
                                    .any { it.descriptor == root.descriptor }
                        }
                    }
                }
            )
            val v2 = unique("anti-peeping V2", using(V2_TEXT).filter { it.static() && it.zeroBoolean() })
            val front = unique(
                "front assistant action",
                using(FRONT_ACTION_URI).filter {
                    it.static() && it.returnTypeName == "boolean" &&
                        it.typeMatches(listOf("java.lang.String"))
                }
            )
            val common = unique(
                "common function list",
                intersect(using(COMMON_USERSET_TEXT), using(COMMON_SERVER_TEXT)).filter {
                    it.static() &&
                        it.typeMatches(listOf("android.content.Context", "java.util.List")) &&
                        normalizeType(it.returnTypeName) in setOf("java.util.List", "java.util.ArrayList")
                }
            )
            val legacy = unique(
                "common function card",
                using(COMMON_USERSET_TEXT).filter {
                    it.static() &&
                        normalizeType(it.returnTypeName) ==
                        "com.miui.common.card.models.CommonlyUsedFunctionCardModel" &&
                        it.typeMatches(listOf("android.content.Context")) &&
                        runCatching { it.invokes.toList() }.getOrDefault(emptyList())
                            .any { call -> call.methodName == "setCommonlyUsedFuncDataList" }
                }
            )
            val beauty = unique(
                "beauty settings initializer",
                intersect(using(BEAUTY_UNSUPPORTED_TEXT), using(BEAUTY_SWITCH_KEY)).filter {
                    normalizeType(it.returnTypeName) == "void" &&
                        it.typeMatches(listOf("android.os.Bundle", "java.lang.String"))
                }
            )
            val beautySupport = beauty?.let { entry ->
                unique(
                    "beauty UI support",
                    runCatching { entry.invokes.toList() }.getOrDefault(emptyList()).filter {
                        it.static() && it.zeroBoolean() &&
                            it.usingStrings.contains(BEAUTY_SUPPORT_PROPERTY)
                    }
                )
            }

            // Materialize inside the bridge and re-encode through our own codec, so the cache
            // format never depends on DexKit's descriptor spelling.
            fun targetDescriptor(role: String, data: MethodData?): String? {
                if (data == null) return null
                return runCatching {
                    MethodDescriptorCodec.descriptor(data.getMethodInstance(loader))
                }.onFailure {
                    DebugLog.w(TAG, "privacy target $role did not materialize", it)
                }.getOrNull()
            }

            fun callerDescriptors(role: String, root: MethodData?): List<String> {
                if (root == null) return emptyList()
                return callersGraph(root).mapNotNull { data ->
                    runCatching {
                        MethodDescriptorCodec.descriptor(data.getMethodInstance(loader))
                    }.onFailure {
                        DebugLog.w(TAG, "privacy caller of $role did not materialize", it)
                    }.getOrNull()
                }
            }

            return Snapshot(
                antiPeepingV1 = targetDescriptor("anti-peeping V1", v1),
                antiPeepingV2 = targetDescriptor("anti-peeping V2", v2),
                frontAction = targetDescriptor("front assistant action", front),
                commonFunctions = targetDescriptor("common function list", common),
                legacyCommonCard = targetDescriptor("common function card", legacy),
                beautyEntry = targetDescriptor("beauty settings initializer", beauty),
                beautySupport = targetDescriptor("beauty UI support", beautySupport),
                antiPeepingV1Callers = callerDescriptors("anti-peeping V1", v1),
                antiPeepingV2Callers = callerDescriptors("anti-peeping V2", v2),
            )
        }

        private fun MethodData.static(): Boolean = Modifier.isStatic(accessFlags)

        private fun MethodData.zeroBoolean(): Boolean =
            paramCount == 0 && returnTypeName == "boolean"

        private fun MethodData.typeMatches(expected: List<String>): Boolean {
            if (paramTypeNames.size != expected.size) return false
            return expected.indices.all { index -> normalizeType(paramTypeNames[index]) == expected[index] }
        }

        /** DexKit reports source names; old evidence compared JVM descriptors, so normalize both. */
        private fun normalizeType(name: String): String {
            if (name.startsWith("L") && name.endsWith(";")) {
                return name.substring(1, name.length - 1).replace('/', '.')
            }
            return name.replace('/', '.')
        }
    }
}
