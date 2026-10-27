package com.takekazex.hypertweak.app.debug

import android.content.Context
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.settings.contract.*
import com.takekazex.hypertweak.settings.contract.debug.*
import com.takekazex.hypertweak.util.diagnostics.DebugAccessSession
import com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents
import com.takekazex.hypertweak.util.logging.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** App adapter; the provider exposes this narrow protocol, never the storage engine. */
internal object DebugSettingsService {
    private val inventory get() = DebugSettingsCatalog.entries.filterKeys(Preferences::isDebugUserSetting)
    private val powerKeys = setOf(PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE, PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE)

    fun status(context: Context) = JSONObject().put("protocol", 1).put("version", BuildConfig.VERSION_NAME)
        .put("remaining_ms", DebugAccessSession.remaining(context))
        .put("backend_connected", XposedServiceManager.currentService != null)
        .put("host_acknowledgements", false)

    fun execute(context: Context, uid: Int, method: String, arg: String?): JSONObject {
        check(uid == 0) { "ROOT_REQUIRED" }
        if (method == "status") return status(context)
        fun authorize() = DebugAccessSession.requireRoot(uid, DebugAccessSession.remaining(context))
        authorize()
        if (method == "list" || method == "describe") {
            val query = arg.orEmpty()
            require(query.length <= 256) { "REQUEST_TOO_LARGE" }
            val selected = inventory.values.filter { it.key.contains(query) }
            return JSONObject().put("protocol", 1).put("settings", JSONArray(selected.map { descriptor ->
                descriptor.describe().put("runtime_groups", JSONArray(RuntimeSettingsGroups.owners(descriptor.key).toList()))
                    .put("effect", if (RuntimeSettingsGroups.owns(descriptor.key)) "snapshot_published; host effect requires verification" else "next_read_or_restart; use feature documentation")
            }))
        }
        if (method in setOf("collect", "collection_status", "collection_cancel")) {
            val collector = com.takekazex.hypertweak.app.DefaultAppServices.diagnostics
            if (method == "collect") {
                require(arg in setOf("module", "system")) { "COLLECTION_SCOPE_REQUIRED" }
                collector.start(context, arg == "system")
            }
            if (method == "collection_cancel") collector.cancel()
            val state = collector.state.value
            return JSONObject().put("running", state.running).put("system", state.system)
                .put("path", state.file?.absolutePath ?: JSONObject.NULL)
                .put("error", state.error ?: JSONObject.NULL)
        }
        if (method == "get") {
            val key = requireNotNull(arg) { "KEY_REQUIRED" }
            val descriptor = inventory[key] ?: error("UNKNOWN_SETTING")
            val receipt = Preferences.inspectDebugSettings()
            authorize()
            return JSONObject().put("protocol", 1).put("revision", receipt.revision)
                .put("setting", entry(descriptor, receipt.values, redact = false))
        }
        require(method in setOf("set", "patch", "reset")) { "UNKNOWN_METHOD" }
        require(arg != null && arg.length <= 65_536) { "REQUEST_TOO_LARGE" }
        val request = JSONObject(arg)
        require(request.optInt("protocol", -1) == 1) { "UNSUPPORTED_PROTOCOL" }
        val expected = if (request.has("expected_revision")) {
            val raw = request.get("expected_revision")
            require(raw is Long || raw is Int) { "INVALID_REVISION" }
            (raw as Number).toLong().also { require(it > 0) { "INVALID_REVISION" } }
        } else null
        val put = request.optJSONObject("values") ?: JSONObject()
        val removeArray = request.optJSONArray("remove") ?: JSONArray()
        val changes = put.keys().asSequence().associateWith { key ->
            (inventory[key] ?: error("UNKNOWN_SETTING: $key")).decode(put.get(key))
        }.toMutableMap()
        val remove = (0 until removeArray.length()).map {
            (removeArray.get(it) as? String) ?: error("INVALID_REMOVE")
        }.toMutableSet()
        require(changes.size + remove.size in 1..64 && changes.keys.intersect(remove).isEmpty()) { "INVALID_PATCH" }
        require(method != "set" || (changes.size == 1 && remove.isEmpty())) { "SET_REQUIRES_ONE_VALUE" }
        require(method != "reset" || (changes.isEmpty() && remove.isNotEmpty())) { "RESET_REQUIRES_REMOVE" }
        remove.forEach { require(inventory[it]?.writable == true) { "READ_ONLY_SETTING: $it" } }
        val requested = (changes.keys + remove).toSet()
        // Migration-aware UI setters remove obsolete selectors; independent power switches flatten
        // the effective old selection before opening the legacy gate.
        if (PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION in changes) remove += PlatformSettingsKeys.KEY_POWER_BUTTON_CTS
        if (CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE in changes) remove += CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT
        // Power migration needs an ordered authoritative baseline. Require CAS so another edit
        // cannot be overwritten between inspection and the serialized transaction.
        var revision = expected
        if (changes.keys.any { it in powerKeys }) {
            val baseline = Preferences.inspectDebugSettings()
            check(expected == null || expected == baseline.revision) { "REVISION_CONFLICT" }
            revision = baseline.revision
            val master = baseline.values[PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER] == true
            powerKeys.forEach { key -> changes.putIfAbsent(key, master && baseline.values[key] == true) }
            changes[PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER] = true
        }
        val receipt = try {
            Preferences.patchDebugSettings(changes, remove, revision, ::authorize)
        } catch (e: Exception) {
            audit(context, uid, method, requested, "failed")
            throw e
        }
        val effects = JSONObject()
        if (AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON in requested) {
            effects.put("launcher_alias", runCatching {
                context.packageManager.setComponentEnabledSetting(
                    android.content.ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias"),
                    if (receipt.values[AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON] == true)
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP)
                "applied"
            }.getOrElse { "failed: ${it.javaClass.simpleName}" })
        }
        if (requested.any { it in setOf(AppSettingsKeys.KEY_APP_SHORTCUTS, AppSettingsKeys.KEY_APP_SHORTCUTS_ORDER) }) {
            effects.put("shortcuts", runCatching {
                com.takekazex.hypertweak.app.DefaultAppServices.shortcuts.updateShortcuts(context)
                "applied"
            }.getOrElse { "failed: ${it.javaClass.simpleName}" })
        }
        DebugSettingsEvents.refresh()
        audit(context, uid, method, requested, "committed revision=${receipt.revision}")
        return JSONObject().put("protocol", 1).put("state", "committed")
            .put("revision", receipt.revision).put("local_mirror", receipt.localMirror)
            .put("runtime_snapshot", "acknowledged_by_settings_backend")
            .put("host_applied", JSONObject.NULL).put("app_side_effects", effects)
            .put("settings", JSONArray(requested.map { entry(requireNotNull(inventory[it]), receipt.values, false) }))
    }

    /** Local diagnostics are available independently of remote-control authorization. */
    fun snapshot(redact: Boolean = true): JSONObject {
        val receipt = runCatching { Preferences.inspectDebugSettings() }
        val values = receipt.getOrNull()?.values ?: com.takekazex.hypertweak.hook.preferences.SettingsBackupCodec.decode(Preferences.exportSettings())
        return JSONObject().put("protocol", 1).put("source", if (receipt.isSuccess) "acknowledged_runtime_and_backend_values" else "local_fallback")
            .put("revision", receipt.getOrNull()?.revision ?: JSONObject.NULL)
            .put("settings", JSONArray(inventory.values.map { entry(it, values, redact) }))
    }

    private fun entry(d: DebugSetting, values: Map<String, *>, redact: Boolean): JSONObject {
        val stored = values[d.key]
        val effective = effective(d, values)
        val hidden = redact && d.sensitive
        return d.describe().put("present", values.containsKey(d.key))
            .put("stored", if (hidden) "[redacted]" else DebugSetting.jsonValue(stored))
            .put("effective", if (hidden) "[redacted]" else DebugSetting.jsonValue(effective))
            .put("source", if (values.containsKey(d.key)) "explicit" else if (effective != d.default) "migration_or_dependency" else "default")
            .put("runtime_groups", JSONArray(RuntimeSettingsGroups.owners(d.key).toList()))
    }

    private fun effective(d: DebugSetting, values: Map<String, *>): Any? {
        fun bool(key: String) = values[key] == true
        val explicit = values.containsKey(d.key)
        return when (d.key) {
            PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER, PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER ->
                if (explicit) values[d.key] else bool(PlatformSettingsKeys.KEY_NATIVE_PICKER)
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN,
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH -> if (explicit) values[d.key] else bool(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT)
            CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT_MODE -> CameraLegendaryMomentMode.resolve(
                values[d.key] as? String, values[CameraSettingsKeys.KEY_CAMERA_LEGENDARY_MOMENT] as? Boolean)
            PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION -> (values[d.key] as? Int)?.takeIf { it in 0..3 }
                ?: if (bool(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS)) 1 else 0
            in powerKeys -> bool(PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER) && bool(d.key)
            IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI -> if (explicit) values[d.key]
                else if (values.containsKey(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI)) !bool(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI) else false
            IconTunerContract.KEY_LEFT_MODE -> values[d.key] ?: if (bool(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED)) 1 else 0
            IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE -> values[d.key] ?: if (values["icon_tuner_stacked_icon_svg_stacked"] == 3) 1 else 0
            else -> storedOrDefault(d, values)
        }
    }
    private fun storedOrDefault(d: DebugSetting, values: Map<String, *>) = values[d.key] ?: d.default

    @Synchronized private fun audit(context: Context, uid: Int, method: String, keys: Set<String>, result: String) {
        // Keys and receipts only; never log user values or request bodies.
        runCatching {
            val file = File(context.filesDir, "logs/debug-settings-audit.txt")
            file.parentFile?.mkdirs()
            val previous = if (file.isFile) file.readText().takeLast(48_000) else ""
            file.writeText(previous + "${System.currentTimeMillis()} uid=$uid method=$method keys=${keys.sorted().joinToString(",")} $result\n")
        }.onFailure { DebugLog.w("DebugSettings", "audit write failed", it) }
        DebugLog.i("DebugSettings", "$method keys=${keys.sorted().joinToString(",")} $result")
    }
}
