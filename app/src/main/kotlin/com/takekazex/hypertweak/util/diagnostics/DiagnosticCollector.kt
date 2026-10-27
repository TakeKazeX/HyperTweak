package com.takekazex.hypertweak.util.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.FileProvider
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.util.logging.LogRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One retained app-process job. Leaving/recreating the page does not duplicate or cancel it. */
internal class DiagnosticCollector(private val settingsSnapshot: () -> JSONObject) {
    data class State(val running: Boolean = false, val system: Boolean = false, val file: File? = null,
        val error: String? = null, val collectedSources: Int = 0, val unavailableSources: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    @Synchronized fun start(context: Context, system: Boolean) {
        if (job?.isCompleted == false) return
        val app = context.applicationContext
        mutable.value = State(running = true, system = system)
        job = scope.launch {
            var work: File? = null
            try {
                val root = File(app.filesDir, "diagnostics").apply { mkdirs() }
                // App-generated names only; no shared-storage destinations or caller paths.
                root.listFiles()?.filter { it.name.startsWith("job-") && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
                    ?.forEach { it.deleteRecursively() }
                val directory = File(root, "job-${UUID.randomUUID()}").apply { check(mkdir()) }
                work = directory
                val payload = File(directory, "payload").apply { check(mkdir()) }
                val statuses = JSONObject()
                fun capture(name: String, read: () -> String) {
                    try {
                        val text = read()
                        File(payload, name).writeText(text.take(4 * 1024 * 1024))
                        statuses.put(name, if (text.length > 4 * 1024 * 1024) "truncated" else "collected")
                    } catch (e: Exception) {
                        statuses.put(name, "unavailable: ${e.javaClass.simpleName}")
                    }
                }
                capture("settings.json") { settingsSnapshot().toString(2) }
                capture("module-logs.txt") { LogRepository.export(LogRepository.read(includeLsposed = false)) }
                capture("debug-settings-audit.txt") { File(app.filesDir, "logs/debug-settings-audit.txt").takeIf(File::isFile)?.readText().orEmpty() }
                capture("scope.txt") { java.util.zip.ZipFile(app.applicationInfo.sourceDir).use { apk ->
                    apk.getInputStream(requireNotNull(apk.getEntry("META-INF/xposed/scope.list"))).bufferedReader().use { it.readText() }
                } }
                val manifest = JSONObject().put("schema", 1).put("collector", 3)
                    .put("module_version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                    .put("created_at", System.currentTimeMillis()).put("android_user", Process.myUid() / 100000)
                    .put("scope", if (system) "system" else "module").put("sources", statuses)
                    .put("lsposed_in_module_logs", "not_requested; system mode collects framework logs separately")
                    .put("settings_redacted", true).put("automatic_upload", false)
                File(payload, "module-manifest.json").writeText(manifest.toString(2))
                File(payload, "PRIVACY.txt").writeText("Local collection only. Logs may contain private data. Review before sharing. No settings changed.\n")
                currentCoroutineContext().ensureActive()
                val report = if (system) {
                    val script = File(directory, "collector.sh")
                    app.assets.open("diagnostics/collector.sh").use { input -> script.outputStream().use(input::copyTo) }
                    val output = File(directory, "runner.txt")
                    val command = "exec /system/bin/sh ${quote(script.absolutePath)} --app ${quote(directory.absolutePath)} ${Process.myUid()}"
                    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).redirectOutput(output).start()
                    try {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 195_000
                        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                            currentCoroutineContext().ensureActive()
                            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Collector timed out (including root authorization)" }
                        }
                        check(process.exitValue() == 0) { "System collector failed (${process.exitValue()}); root may be unavailable" }
                    } finally { process.destroyForcibly() }
                    File(directory, "report.tar.gz").also { check(it.isFile && it.length() > 0) { "Archive missing" } }
                } else {
                    File(directory, "report.zip").also { archive ->
                        File(payload, "manifest.json").writeText(manifest.toString(2))
                        ZipOutputStream(archive.outputStream()).use { zip ->
                            payload.listFiles().orEmpty().sortedBy(File::getName).forEach { file ->
                                currentCoroutineContext().ensureActive()
                                zip.putNextEntry(ZipEntry(file.name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        payload.deleteRecursively()
                    }
                }
                val sourceValues = statuses.keys().asSequence().map { statuses.getString(it) }.toList()
                mutable.value = State(system = system, file = report,
                    collectedSources = sourceValues.count { !it.startsWith("unavailable") },
                    unavailableSources = sourceValues.count { it.startsWith("unavailable") })
            } catch (e: CancellationException) {
                mutable.value = State(error = "Cancelled")
                throw e
            } catch (e: Exception) {
                mutable.value = State(system = system, error = e.message ?: e.javaClass.simpleName)
            } finally {
                // Root collector has a hard 180s deadline. Preserve failed jobs for TTL cleanup;
                // deleting a live root work directory can race its final archive write.
                if (!system && mutable.value.file == null) work?.deleteRecursively()
            }
        }
    }
    @Synchronized fun cancel() {
        if (job?.isCompleted == false) {
            job?.cancel()
            // A coroutine cancelled before its body starts never enters its catch/finally.
            // Publish the terminal UI state here as well so Generate cannot remain disabled.
            mutable.value = State(error = "Cancelled")
        }
    }

    fun share(context: Context) {
        val file = mutable.value.file ?: return
        check(file.isFile)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "zip") "application/zip" else "application/gzip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, null))
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
