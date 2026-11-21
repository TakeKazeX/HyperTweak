package com.takekazex.hypertweak.hook.rules.analytics

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.Method

internal class AnalyticsTelemetryResolver(private val dex: DexOperandIndex) {
    fun resolve(): Map<String, String> {
        val submissions = dex.strings("线程池已关闭，重新初始化后提交任务").filter {
            it.definingClass == TRACK_WORKER && params(it).size == 1 && it.returnType == "V" &&
                calls(it, EXECUTE)
        }
        check(submissions.size == 2 && submissions.any { params(it) == listOf(RUNNABLE) }) {
            "Analytics event-worker submissions missing or ambiguous"
        }
        val replay = unique("cached event replay", dex.strings("dispenseCacheRunnable start")) {
            it.definingClass == TRACK_WORKER && params(it).isEmpty() && it.returnType == "V" && calls(it, EXECUTE)
        }
        val usage = dex.strings("Don't collect usage below Android KK 4.4").filter {
            it.returnType == JSON && params(it) in setOf(listOf("J", "J"), listOf("J", "J", "Z")) &&
                dex.code(it).calls.any { site ->
                    DexOperandIndex.descriptor(site.method) == USAGE_EVENTS ||
                        (site.method.definingClass == it.definingClass &&
                            dex.definition(site.method)?.let { callee -> calls(callee, USAGE_EVENTS) } == true)
                }
        }
        check(usage.size == 2 && usage.map(::params).distinct().size == 2) {
            "Analytics usage collectors missing or ambiguous"
        }
        val installed = dex.strings("apps", "installer", "fit", "lut").filter {
            it.returnType == JSON && params(it).isEmpty() &&
                calls(it, "Landroid/content/pm/PackageManager;->getInstalledPackages(I)Ljava/util/List;")
        }
        check(installed.size == 2) { "Analytics installed-app collectors missing or ambiguous" }
        val radio = unique("location and radio collector", dex.strings("first_active_upload_position", "wl", "wlt")) {
            it.returnType == JSON && params(it) == listOf(it.definingClass)
        }
        val online = unique("OneTrack network check", dex.strings("NetworkUtil")) {
            it.returnType == "Z" && params(it).isEmpty() &&
                calls(it, "Landroid/net/ConnectivityManager;->getActiveNetworkInfo()Landroid/net/NetworkInfo;")
        }
        val legacy = unique("legacy collection policy", dex.strings("device_provisioned")) {
            it.returnType == "Z" && params(it) == listOf("Landroid/content/Context;", "Ljava/lang/String;") &&
                calls(it, "Landroid/provider/Settings\$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I")
        }
        val oneTrackWrite = unique("OneTrack event store", dex.strings("events", "event_name", "timestamp", "data")) {
            it.returnType == "Z" && params(it).size == 1 &&
                calls(it, "Landroid/database/sqlite/SQLiteDatabase;->insert(Ljava/lang/String;Ljava/lang/String;Landroid/content/ContentValues;)J")
        }
        return mapOf(
            "blockedTasks" to (submissions + replay).descriptors(),
            "emptyCollectors" to (usage + installed).descriptors(),
            "radio" to DexOperandIndex.descriptor(radio),
            "offline" to listOf(online, oneTrackWrite).descriptors(),
            "legacy" to DexOperandIndex.descriptor(legacy),
        )
    }

    private fun calls(method: Method, callee: String) =
        dex.code(method).calls.any { DexOperandIndex.descriptor(it.method) == callee }

    private fun unique(role: String, candidates: List<Method>, matches: (Method) -> Boolean): Method =
        candidates.singleOrNull(matches) ?: error("Analytics $role missing or ambiguous")

    private fun params(method: Method) = method.parameterTypes.map(CharSequence::toString)
    private fun List<Method>.descriptors() = map(DexOperandIndex::descriptor).sorted().joinToString("\n")

    private companion object {
        const val TRACK_WORKER = "Lcom/miui/analytics/onetrack/runnable/TrackThreadManager;"
        const val JSON = "Lorg/json/JSONObject;"
        const val RUNNABLE = "Ljava/lang/Runnable;"
        const val EXECUTE = "Ljava/util/concurrent/ThreadPoolExecutor;->execute(Ljava/lang/Runnable;)V"
        const val USAGE_EVENTS = "Landroid/app/usage/UsageStatsManager;->queryEvents(JJ)Landroid/app/usage/UsageEvents;"
    }
}
