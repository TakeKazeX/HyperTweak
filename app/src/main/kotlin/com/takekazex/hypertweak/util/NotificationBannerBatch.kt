package com.takekazex.hypertweak.util

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class BannerBatchResult(val apps: Int, val channels: Int, val skipped: Int, val failed: Int, val changed: Int,
    val failureNotes: List<String> = emptyList())

internal data class BannerBatchState(val busy: Boolean = false, val applied: Boolean = false,
    val result: BannerBatchResult? = null, val error: String? = null)

internal object NotificationBannerBatch {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(BannerBatchState())
    val state: kotlinx.coroutines.flow.StateFlow<BannerBatchState> = mutableState

    @Synchronized fun start(context: Context, apply: Boolean) {
        if (mutableState.value.busy) return
        mutableState.value = BannerBatchState(busy = true, applied = apply)
        val app = context.applicationContext
        scope.launch {
            mutableState.value = try { BannerBatchState(applied = apply, result = run(app, apply)) }
            catch (failure: Exception) { BannerBatchState(applied = apply, error = failure.message ?: failure.javaClass.simpleName) }
        }
    }

    /** Dismissing a terminal dialog consumes its feedback without interrupting an active job. */
    @Synchronized fun dismissFeedback() {
        if (!mutableState.value.busy) mutableState.value = BannerBatchState()
    }

    suspend fun run(context: Context, apply: Boolean): BannerBatchResult = withContext(Dispatchers.IO) {
        val apk = NativeRootCommand.quote(context.applicationInfo.sourceDir)
        val user = Process.myUid() / 100000
        val action = if (apply) "apply" else "scan"
        val bytes = try {
            NativeRootCommand.run(
                "CLASSPATH=$apk app_process /system/bin com.takekazex.hypertweak.root.NotificationBannerBatch $user $action",
                timeoutMs = 120000,
            )
        } catch (failure: java.util.concurrent.ExecutionException) {
            val command = failure.cause as? NativeRootCommand.CommandFailure ?: throw failure
            val output = command.output.toString(Charsets.UTF_8)
            val record = output.lineSequence().lastOrNull { it.startsWith("HYPERTWEAK_BANNERS_ERROR ") }
            val detail = record?.let { runCatching { JSONObject(it.removePrefix("HYPERTWEAK_BANNERS_ERROR ")).getString("detail") }.getOrNull() }
            throw IllegalStateException(detail ?: "Root helper exited with status ${command.exitCode}:\n${output.takeLast(8192)}", command)
        }
        val output = bytes.toString(Charsets.UTF_8)
        val record = output.lineSequence().lastOrNull { it.startsWith("HYPERTWEAK_BANNERS ") }
            ?: error("Notification service did not return a result")
        val json = JSONObject(record.removePrefix("HYPERTWEAK_BANNERS "))
        check(json.getBoolean("applied") == apply)
        val notes = json.optJSONArray("failure_notes")
        BannerBatchResult(
            json.getInt("apps"), json.getInt("channels"), json.getInt("skipped"), json.getInt("failed"), json.getInt("changed"),
            if (notes == null) emptyList() else List(notes.length().coerceAtMost(20)) { notes.optString(it) }.filter { it.isNotBlank() },
        )
    }
}
