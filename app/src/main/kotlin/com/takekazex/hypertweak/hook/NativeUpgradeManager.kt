package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.pm.PackageManager
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.NativeRootCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** User-triggered native activation follows upstream's exact-spawner replacement, not dlclose. */
object NativeUpgradeManager {
    enum class State { CHECKING, CURRENT, UPDATED, FAILED, ROLLBACK_REQUESTED }
    data class Report(val state: State, val detail: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _report = MutableStateFlow<Report?>(null)
    val report = _report.asStateFlow()
    private const val LIBRARY = "lib/arm64-v8a/libhypertweak_native.so"
    @Volatile private var rollbackFile: File? = null
    private data class Owner(val pid: Int, val start: Long)
    private data class Running(val owner: Owner, val launcher: Owner, val maps: String)
    private fun root(command: String, max: Int = 65536): ByteArray = NativeRootCommand.run(command, max)
    private fun text(command: String) = root(command).toString(Charsets.UTF_8).trim()
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun payload(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry(LIBRARY) ?: error("module native payload missing")
        require(entry.size in 1..(8L * 1024 * 1024)) { "invalid native payload size" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }
        check(bytes.size.toLong() == entry.size) { "native payload length mismatch" }
        digest(bytes)
    }
    private fun owner(pid: Int) = Owner(pid, NativeUpgradeIdentity.procStart(text("cat /proc/$pid/stat")))
    private fun spawner(): Owner {
        val pids = text("""
            for pid in ${'$'}(ps -A -o UID,PID,NAME | awk 'NR > 1 && ${'$'}1 == 0 && ${'$'}3 !~ /^\[/ {print ${'$'}2}'); do
                [ "${'$'}(readlink /proc/${'$'}pid/exe 2>/dev/null)" = /system_ext/bin/hyos_spawner ] || continue
                [ "${'$'}(awk '/^Uid:/{print ${'$'}2}' /proc/${'$'}pid/status)" = 0 ] || continue
                echo "${'$'}pid"
            done
        """.trimIndent()).lines().filter(String::isNotBlank).map(String::toInt)
        return owner(pids.singleOrNull() ?: error("exact root HYOS spawner unavailable or ambiguous"))
    }
    private fun running(): Running {
        val home = text("pidof com.miui.home").split(Regex("\\s+")).mapNotNull(String::toIntOrNull).singleOrNull()
            ?: error("launcher process unavailable or ambiguous")
        val parent = text("awk '/^PPid:/{print ${'$'}2}' /proc/$home/status").toInt()
        check(text("readlink /proc/$parent/exe") == "/system_ext/bin/hyos_spawner" &&
            text("awk '/^Uid:/{print ${'$'}2}' /proc/$parent/status") == "0") { "launcher parent is not the root HYOS spawner" }
        val spawner = owner(parent)
        val uid = text("awk '/^Uid:/{print ${'$'}2}' /proc/$home/status").toInt()
        check(uid / 100000 == android.os.Process.myUid() / 100000 && uid >= 10000) { "launcher belongs to another Android user" }
        return Running(spawner, owner(home), root("cat /proc/$home/maps", 2 * 1024 * 1024).toString(Charsets.UTF_8))
    }
    private fun loadedApk(context: Context, running: Running): File {
        val ranges = running.maps.lineSequence().filter { it.contains("/data/app/") && it.contains("base.apk") }
            .filter { it.split(Regex("\\s+"))[1].contains('x') }
            .sortedByDescending { it.contains(NativeRuleProtocol.MODULE) }
            .map { it.substringBefore(' ') }.distinct().take(12).toList()
        val directory = File(context.filesDir, "updates").apply { mkdirs() }
        val file = File.createTempFile("native-loaded-", ".apk", directory)
        for (range in ranges) {
            if (!range.matches(Regex("[0-9a-f]+-[0-9a-f]+"))) continue
            try {
                file.writeBytes(root("cat /proc/${running.launcher.pid}/map_files/$range", 32 * 1024 * 1024))
                payload(file)
                validateArchive(context, file)
                return file
            } catch (_: Exception) { file.delete() }
        }
        file.delete()
        error("cannot identify the loaded signed module APK; activation cancelled")
    }
    private fun validateArchive(context: Context, file: File) {
        val archive = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("loaded module APK is invalid")
        check(archive.packageName == context.packageName) { "loaded APK belongs to another package" }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = archive.signingInfo?.apkContentsSigners
        check(!signers.isNullOrEmpty() && signers.toSet() == installed.signingInfo?.apkContentsSigners?.toSet()) {
            "loaded APK signer does not match installed module"
        }
    }
    private fun replace(owner: Owner) {
        // The check and signal run in the same root shell; a reused PID is never signalled.
        text("""
            [ "${'$'}(readlink /proc/${owner.pid}/exe)" = /system_ext/bin/hyos_spawner ] || exit 51
            [ "${'$'}(awk '/^Uid:/{print ${'$'}2}' /proc/${owner.pid}/status)" = 0 ] || exit 52
            [ "${'$'}(sed 's/.*) //' /proc/${owner.pid}/stat | awk '{print ${'$'}20}')" = ${owner.start} ] || exit 53
            kill -TERM ${owner.pid}
        """.trimIndent())
    }
    @Synchronized fun activate(context: Context) {
        if (_busy.value) return
        _busy.value = true; _report.value = Report(State.CHECKING)
        val app = context.applicationContext ?: context
        scope.launch {
            var inspected: File? = null
            try { withTimeout(60000) {
                val service = XposedServiceManager.currentService ?: error("module service unavailable")
                check(service.apiVersion >= io.github.libxposed.service.XposedService.API_102) { "module service API 102 required" }
                check(service.runningTargets.any { it.processName == NativeRuleProtocol.SYSTEM_UI &&
                    it.state == io.github.libxposed.service.HookedTarget.State.UP_TO_DATE }) {
                    "update SystemUI Java hooks before activating native code"
                }
                Preferences.flush()
                val installedHash = payload(File(app.applicationInfo.sourceDir))
                val before = running()
                val backup = loadedApk(app, before).also { inspected = it }
                val loadedHash = payload(backup)
                if (!NativeUpgradeIdentity.needsUpdate(installedHash, loadedHash)) {
                    backup.delete()
                    val user = android.os.Process.myUid() / 100000
                    val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                    val identity = root("cat /data/user_de/$user/com.miui.home/hypertweak-native/runtime_identity")
                    check(NativeUpgradeIdentity.ready(identity, version, before.launcher.pid, before.launcher.start)) {
                        "loaded native code has no matching initialization acknowledgement"
                    }
                    _report.value = Report(State.CURRENT); return@withTimeout
                }
                check(running().let { it.owner == before.owner && it.launcher == before.launcher }) { "launcher changed during inspection" }
                check(app.packageManager.getApplicationInfo(app.packageName, 0).sourceDir == app.applicationInfo.sourceDir &&
                    payload(File(app.applicationInfo.sourceDir)) == installedHash) { "installed module changed during inspection" }
                // Keep the complete previous APK for explicit rollback. Never push an isolated .so.
                val retained = File(app.filesDir, "updates/native-rollback.apk")
                check(backup.renameTo(retained)) { "cannot retain previous signed APK" }
                inspected = null
                rollbackFile = retained
                File(app.filesDir, "updates/native-activation.txt").writeText("CHECKING")
                replace(before.owner)
                var after: Running? = null
                for (attempt in 0 until 30) {
                    delay(500)
                    val candidate = runCatching { running() }.getOrNull()
                    if (candidate != null && candidate.owner != before.owner && candidate.launcher != before.launcher) {
                        after = candidate; break
                    }
                }
                if (after == null) {
                    text("am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.miui.home")
                    for (attempt in 0 until 20) {
                        delay(500)
                        val candidate = runCatching { running() }.getOrNull()
                        if (candidate != null && candidate.owner != before.owner && candidate.launcher != before.launcher) {
                            after = candidate; break
                        }
                    }
                }
                val target = after ?: error("replacement launcher did not become available")
                NativeRuleSignal.publishCommitted()
                val apkPath = app.applicationInfo.sourceDir
                val inode = text("stat -c %i ${NativeRootCommand.quote(apkPath)}").toLong()
                check(target.maps.lineSequence().any { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 6)
                    parts.size == 6 && parts[1].contains('x') && parts[4].toLongOrNull() == inode && parts[5] == apkPath
                }) { "replacement launcher still maps an old APK" }
                val uid = android.os.Process.myUid() / 100000
                val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
                var initialized = false
                for (attempt in 0 until 20) {
                    val identity = runCatching { root("cat /data/user_de/$uid/com.miui.home/hypertweak-native/runtime_identity") }.getOrNull()
                    if (identity != null && NativeUpgradeIdentity.ready(identity, version, target.launcher.pid, target.launcher.start)) {
                        initialized = true; break
                    }
                    delay(250)
                }
                check(initialized) { "native initialization identity not acknowledged" }
                _report.value = Report(State.UPDATED)
                DebugLog.i("NativeRules", "native payload activated launcher=${target.launcher.pid}")
            } } catch (error: Exception) {
                DebugLog.e("NativeRules", "native activation failed; signed previous APK retained", error)
                _report.value = Report(State.FAILED, error.message)
            } finally {
                inspected?.delete()
                runCatching { File(app.filesDir, "updates/native-activation.txt").apply { parentFile?.mkdirs() }
                    .writeText((_report.value?.state ?: State.FAILED).name) }
                    .onFailure { DebugLog.w("NativeRules", "native activation journal write failed", it) }
                _busy.value = false
            }
        }
    }
    fun restore(context: Context) {
        val retained = File(context.filesDir, "updates/native-rollback.apk")
        if (retained.isFile) rollbackFile = retained
        val state = runCatching { State.valueOf(File(context.filesDir, "updates/native-activation.txt").readText().trim()) }.getOrNull()
        // Successful checks describe a particular process family, which cannot be established
        // from an app-private journal after a reboot, package update or launcher replacement.
        _report.value = when (state) {
            State.CHECKING, State.FAILED -> Report(State.FAILED)
            State.ROLLBACK_REQUESTED -> Report(State.ROLLBACK_REQUESTED)
            else -> null
        }
    }
    @Synchronized fun rollback(context: Context) {
        if (_busy.value) return
        val backup = rollbackFile ?: return
        val app = context.applicationContext ?: context
        _busy.value = true
        scope.launch {
            try {
                validateArchive(app, backup)
                val owner = spawner()
                // PackageManager may retire this app process. Keep the bounded replacement steps
                // in the same root command so restoration does not depend on its Java callback.
                _report.value = Report(State.ROLLBACK_REQUESTED)
                val journal = File(app.filesDir, "updates/native-activation.txt")
                journal.writeText(State.ROLLBACK_REQUESTED.name)
                val expectedHash = digest(backup.readBytes())
                NativeRootCommand.run("""
                    rollback_apk=${'$'}(mktemp /data/local/tmp/hypertweak-rollback.XXXXXX) || exit 54
                    trap 'rm -f "${'$'}rollback_apk"' EXIT HUP INT TERM
                    cp ${NativeRootCommand.quote(backup.path)} "${'$'}rollback_apk" || exit 54
                    [ "${'$'}(sha256sum "${'$'}rollback_apk" | awk '{print ${'$'}1}')" = $expectedHash ] || exit 54
                    pm install -r -d -t "${'$'}rollback_apk" || exit 54
                    [ "${'$'}(readlink /proc/${owner.pid}/exe)" = /system_ext/bin/hyos_spawner ] || exit 55
                    [ "${'$'}(awk '/^Uid:/{print ${'$'}2}' /proc/${owner.pid}/status)" = 0 ] || exit 55
                    [ "${'$'}(sed 's/.*) //' /proc/${owner.pid}/stat | awk '{print ${'$'}20}')" = ${owner.start} ] || exit 56
                    kill -TERM ${owner.pid} || exit 57
                    am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.miui.home
                """.trimIndent(), timeoutMs = 60000)
            } catch (error: Exception) {
                DebugLog.e("NativeRules", "native rollback failed", error)
                _report.value = Report(State.FAILED, error.message)
            } finally {
                runCatching { File(app.filesDir, "updates/native-activation.txt")
                    .writeText((_report.value?.state ?: State.FAILED).name) }
                    .onFailure { DebugLog.w("NativeRules", "native rollback journal write failed", it) }
                _busy.value = false
            }
        }
    }
    fun canRollback(): Boolean = rollbackFile?.isFile == true
}
