package com.takekazex.hypertweak.util.platform

import android.content.Context
import android.provider.Settings
import com.takekazex.hypertweak.settings.contract.SettingsValues
import com.takekazex.hypertweak.settings.contract.SmartDnsCloudKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads and applies MIUI's cloud-controlled SmartDNS settings.
 *
 * The Settings.System keys are the source of truth: MiuiNetworkPolicyManagerService observes
 * them and republishes the resulting persist.device_config.netd_native.* properties. Writing
 * those properties directly would be overwritten on the next cloud refresh.
 */
internal object CloudDnsSettings {
    private val mutex = Mutex()
    const val MODE_DEFAULT = SmartDnsCloudKeys.MODE_DEFAULT
    const val MODE_ON = SmartDnsCloudKeys.MODE_ON
    const val MODE_OFF = SmartDnsCloudKeys.MODE_OFF

    private const val KEY_SMARTDNS_V1 = SmartDnsCloudKeys.SETTING_SMARTDNS_V1
    private const val KEY_SMARTDNS_V2 = SmartDnsCloudKeys.SETTING_SMARTDNS_V2
    private const val KEY_HAPPY_EYEBALLS = SmartDnsCloudKeys.SETTING_HAPPY_EYEBALLS

    private const val KEY_SMARTDNS_MODE = "smartdns_cloud_mode"
    private const val KEY_IPV4_FIRST_MODE = "smartdns_cloud_ipv4_first_mode"
    private const val KEY_HAPPY_EYEBALLS_MODE = "smartdns_cloud_happy_eyeballs_mode"

    private const val KEY_SMARTDNS_BASELINE_CAPTURED = "tweak_baseline_smartdns_cloud_captured"
    private const val KEY_SMARTDNS_BASELINE_V2_PRESENT = "tweak_baseline_smartdns_cloud_v2_present"
    private const val KEY_SMARTDNS_BASELINE_V2 = "tweak_baseline_smartdns_cloud_v2"
    private const val KEY_SMARTDNS_BASELINE_V1_PRESENT = "tweak_baseline_smartdns_cloud_v1_present"
    private const val KEY_SMARTDNS_BASELINE_V1 = "tweak_baseline_smartdns_cloud_v1"
    private const val KEY_HAPPY_BASELINE_CAPTURED = "tweak_baseline_happy_eyeballs_cloud_captured"
    private const val KEY_HAPPY_BASELINE_PRESENT = "tweak_baseline_happy_eyeballs_cloud_present"
    private const val KEY_HAPPY_BASELINE = "tweak_baseline_happy_eyeballs_cloud"

    private const val SMARTDNS_ENABLED_INDEX = 0
    private const val SMARTDNS_IPV4_FIRST_INDEX = 3
    private const val DEFAULT_V2 = "on,1000,2500,on,2500,2,1,1,560,420,128"
    private const val DEFAULT_V1 = "on,1000,2500,on,2500,2"

    data class State(
        val smartDnsMode: Int,
        val ipv4FirstMode: Int,
        val happyEyeballsMode: Int,
        val effectiveSmartDns: Boolean?,
        val effectiveIpv4First: Boolean?,
        val effectiveHappyEyeballs: Boolean?,
        val smartDnsV2: String?,
        val smartDnsV1: String?,
        val ipv4CloudValue: String?,
        val happyEyeballsCloud: String?,
    )

    internal data class SmartDnsConfig(
        val key: String,
        val fields: List<String>,
        val present: Boolean,
    ) {
        val enabled: Boolean
            get() = present && fields.getOrNull(SMARTDNS_ENABLED_INDEX) == "on"
        val ipv4First: Boolean
            get() = present && fields.getOrNull(SMARTDNS_IPV4_FIRST_INDEX) == "on"
    }

    internal data class SmartDnsValue(val key: String, val value: String)

    internal data class SmartDnsBaseline(val v2: String?, val v1: String?)

    fun read(context: Context, store: SettingsValues): State {
        val v2 = readString(context, KEY_SMARTDNS_V2)
        val v1 = readString(context, KEY_SMARTDNS_V1)
        val smartConfig = parseSmartDnsConfig(v2, v1)
        val smartMode = readMode(store, KEY_SMARTDNS_MODE)
        val ipv4Mode = if (smartMode == MODE_OFF) MODE_OFF else readMode(store, KEY_IPV4_FIRST_MODE)
        return State(
            smartDnsMode = smartMode,
            ipv4FirstMode = ipv4Mode,
            happyEyeballsMode = readMode(store, KEY_HAPPY_EYEBALLS_MODE),
            effectiveSmartDns = readPropertyBoolean(SmartDnsCloudKeys.PROPERTY_SMARTDNS_ENABLE)
                ?: smartConfig.takeIf { it.present }?.enabled,
            effectiveIpv4First = readPropertyBoolean(SmartDnsCloudKeys.PROPERTY_SMARTDNS_IPV4_FIRST)
                ?: smartConfig.takeIf { it.present }?.ipv4First,
            effectiveHappyEyeballs = readPropertyBoolean(SmartDnsCloudKeys.PROPERTY_HAPPY_EYEBALLS)
                ?: readString(context, KEY_HAPPY_EYEBALLS)?.trim()?.let { it == "on" },
            smartDnsV2 = v2,
            smartDnsV1 = v1,
            ipv4CloudValue = if (smartConfig.present) {
                smartConfig.fields.getOrNull(SMARTDNS_IPV4_FIRST_INDEX)
            } else null,
            happyEyeballsCloud = readString(context, KEY_HAPPY_EYEBALLS),
        )
    }

    suspend fun setSmartDnsModes(context: Context, store: SettingsValues,
        smartDnsMode: Int, ipv4FirstMode: Int): Boolean = mutex.withLock {
        setSmartDnsModesLocked(context, store, smartDnsMode, ipv4FirstMode)
    }

    suspend fun setHappyEyeballsMode(context: Context, store: SettingsValues, mode: Int): Boolean =
        mutex.withLock { setHappyEyeballsModeLocked(context, store, mode) }

    /** Restore device state before discarding its non-portable baselines. Failures retain them. */
    fun <T> replaceSettingsBlocking(context: Context, store: SettingsValues, replace: () -> T): T =
        runBlocking(Dispatchers.IO) { mutex.withLock {
            // Do not clear captures/modes until the settings replacement is acknowledged.
            val previousSmart = readMode(store, KEY_SMARTDNS_MODE)
            val previousIpv4 = readMode(store, KEY_IPV4_FIRST_MODE)
            val previousHappy = readMode(store, KEY_HAPPY_EYEBALLS_MODE)
            suspend fun replayPrevious() {
                check(setSmartDnsModesLocked(context, store, previousSmart, previousIpv4))
                check(setHappyEyeballsModeLocked(context, store, previousHappy))
                store.flush()
            }
            try {
                readSmartDnsBaseline(store)?.let { baseline ->
                    check(restoreSmartDnsBaseline(context, baseline)) {
                        "Unable to restore SmartDNS before replacing settings"
                    }
                }
                if (store.getBoolean(KEY_HAPPY_BASELINE_CAPTURED, false)) {
                    check(restoreHappyBaseline(context, store)) {
                        "Unable to restore Happy Eyeballs before replacing settings"
                    }
                }
            } catch (error: Exception) {
                runCatching { replayPrevious() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            val result = try { replace() } catch (error: Exception) {
                runCatching { replayPrevious() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            if (result == false) {
                replayPrevious()
                return@withLock result
            }
            // Baselines belong to this device, never to an imported backup. The original
            // values are now restored; recapture those values if the backup requests overrides.
            clearSmartDnsBaseline(store)
            clearHappyBaseline(store)
            check(setSmartDnsModesLocked(context, store, readMode(store, KEY_SMARTDNS_MODE),
                readMode(store, KEY_IPV4_FIRST_MODE))) { "Unable to apply restored SmartDNS settings" }
            check(setHappyEyeballsModeLocked(context, store, readMode(store, KEY_HAPPY_EYEBALLS_MODE))) {
                "Unable to apply restored Happy Eyeballs settings"
            }
            store.flush()
            result
        } }

    private suspend fun setSmartDnsModesLocked(
        context: Context,
        store: SettingsValues,
        smartDnsMode: Int,
        ipv4FirstMode: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val smartMode = smartDnsMode.coerceIn(MODE_DEFAULT, MODE_OFF)
        val ipv4Mode = if (smartMode == MODE_OFF) MODE_OFF else ipv4FirstMode.coerceIn(MODE_DEFAULT, MODE_OFF)

        if (smartMode == MODE_DEFAULT && ipv4Mode == MODE_DEFAULT) {
            if (!store.getBoolean(KEY_SMARTDNS_BASELINE_CAPTURED, false)) {
                saveModes(store, smartMode, ipv4Mode)
                return@withContext true
            }
            val baseline = readSmartDnsBaseline(store) ?: return@withContext false
            val success = restoreSmartDnsBaseline(context, baseline)
            if (success) {
                clearSmartDnsBaseline(store)
                saveModes(store, smartMode, ipv4Mode)
            }
            return@withContext success
        }

        captureSmartDnsBaseline(context, store)
        store.flush()
        val baseline = readSmartDnsBaseline(store) ?: return@withContext false
        val value = buildSmartDnsValue(baseline.v2, baseline.v1, smartMode, ipv4Mode)
        val success = writeSetting(context, value.key, value.value)
        if (success) saveModes(store, smartMode, ipv4Mode)
        success
    }

    private suspend fun setHappyEyeballsModeLocked(
        context: Context,
        store: SettingsValues,
        mode: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = mode.coerceIn(MODE_DEFAULT, MODE_OFF)
        if (normalized == MODE_DEFAULT) {
            if (!store.getBoolean(KEY_HAPPY_BASELINE_CAPTURED, false)) {
                store.putInt(KEY_HAPPY_EYEBALLS_MODE, normalized)
                return@withContext true
            }
            val success = restoreHappyBaseline(context, store)
            if (success) {
                clearHappyBaseline(store)
                store.putInt(KEY_HAPPY_EYEBALLS_MODE, normalized)
            }
            return@withContext success
        }

        captureHappyBaseline(context, store)
        store.flush()
        val success = writeSetting(
            context,
            KEY_HAPPY_EYEBALLS,
            if (normalized == MODE_ON) "on" else "off",
        )
        if (success) store.putInt(KEY_HAPPY_EYEBALLS_MODE, normalized)
        success
    }

    /** Re-applies a non-default SmartDNS mode if MIUI's cloud value changed underneath it. */
    suspend fun reconcileSmartDns(context: Context, store: SettingsValues): Boolean =
        mutex.withLock { withContext(Dispatchers.IO) {
            val smartMode = readMode(store, KEY_SMARTDNS_MODE)
            val ipv4Mode = if (smartMode == MODE_OFF) MODE_OFF else readMode(store, KEY_IPV4_FIRST_MODE)
            if (smartMode == MODE_DEFAULT && ipv4Mode == MODE_DEFAULT) return@withContext true
            val baseline = readSmartDnsBaseline(store) ?: return@withContext true
            val value = buildSmartDnsValue(baseline.v2, baseline.v1, smartMode, ipv4Mode)
            if (readString(context, value.key) == value.value) return@withContext true
            writeSetting(context, value.key, value.value)
        } }

    /** Re-applies a non-default Happy Eyeballs mode after a cloud refresh. */
    suspend fun reconcileHappyEyeballs(context: Context, store: SettingsValues): Boolean =
        mutex.withLock { withContext(Dispatchers.IO) {
            val mode = readMode(store, KEY_HAPPY_EYEBALLS_MODE)
            if (mode == MODE_DEFAULT) return@withContext true
            val desired = if (mode == MODE_ON) "on" else "off"
            if (readString(context, KEY_HAPPY_EYEBALLS) == desired) return@withContext true
            writeSetting(context, KEY_HAPPY_EYEBALLS, desired)
        } }

    internal fun buildSmartDnsValue(
        baselineV2: String?,
        baselineV1: String?,
        smartDnsMode: Int,
        ipv4FirstMode: Int,
    ): SmartDnsValue {
        val config = parseSmartDnsConfig(baselineV2, baselineV1)
        val fields = config.fields.toMutableList()
        val baselineEnabled = config.present && fields[SMARTDNS_ENABLED_INDEX] == "on"
        val enabled = when (smartDnsMode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> baselineEnabled
        }
        var ipv4First = when (ipv4FirstMode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> config.ipv4First
        }
        // MiuiNetworkPolicyManagerService checks the whole CSV for "on" when it publishes
        // smartdns_enable. Keep the IPv4 flag off while the master switch is off so a
        // leftover "on" cannot re-enable SmartDNS.
        if (!enabled) ipv4First = false
        fields[SMARTDNS_ENABLED_INDEX] = if (enabled) "on" else "off"
        fields[SMARTDNS_IPV4_FIRST_INDEX] = if (ipv4First) "on" else "off"
        return SmartDnsValue(config.key, fields.joinToString(","))
    }

    internal fun parseSmartDnsConfig(v2: String?, v1: String?): SmartDnsConfig {
        val useV2 = !v2.isNullOrBlank() || v1.isNullOrBlank()
        val raw = if (useV2) v2 else v1
        val key = if (useV2) KEY_SMARTDNS_V2 else KEY_SMARTDNS_V1
        val defaultFields = (if (useV2) DEFAULT_V2 else DEFAULT_V1).split(',')
        val fields = raw?.split(',')?.map { it.trim() }?.toMutableList() ?: mutableListOf()
        if (fields.all { it.isEmpty() }) fields.clear()
        while (fields.size < defaultFields.size) fields += defaultFields[fields.size]
        return SmartDnsConfig(key = key, fields = fields, present = !raw.isNullOrBlank())
    }

    private fun saveModes(store: SettingsValues, smartMode: Int, ipv4Mode: Int) {
        store.putInt(KEY_SMARTDNS_MODE, smartMode)
        store.putInt(KEY_IPV4_FIRST_MODE, ipv4Mode)
    }

    private fun readMode(store: SettingsValues, key: String): Int =
        store.getInt(key, MODE_DEFAULT).coerceIn(MODE_DEFAULT, MODE_OFF)

    private fun captureSmartDnsBaseline(context: Context, store: SettingsValues) {
        if (store.getBoolean(KEY_SMARTDNS_BASELINE_CAPTURED, false)) return
        val v2 = readString(context, KEY_SMARTDNS_V2)
        val v1 = readString(context, KEY_SMARTDNS_V1)
        store.putBoolean(KEY_SMARTDNS_BASELINE_V2_PRESENT, v2 != null)
        store.putString(KEY_SMARTDNS_BASELINE_V2, v2 ?: "")
        store.putBoolean(KEY_SMARTDNS_BASELINE_V1_PRESENT, v1 != null)
        store.putString(KEY_SMARTDNS_BASELINE_V1, v1 ?: "")
        store.putBoolean(KEY_SMARTDNS_BASELINE_CAPTURED, true)
    }

    private fun readSmartDnsBaseline(store: SettingsValues): SmartDnsBaseline? {
        if (!store.getBoolean(KEY_SMARTDNS_BASELINE_CAPTURED, false)) return null
        return SmartDnsBaseline(
            v2 = if (store.getBoolean(KEY_SMARTDNS_BASELINE_V2_PRESENT, false)) {
                store.getString(KEY_SMARTDNS_BASELINE_V2, "")
            } else null,
            v1 = if (store.getBoolean(KEY_SMARTDNS_BASELINE_V1_PRESENT, false)) {
                store.getString(KEY_SMARTDNS_BASELINE_V1, "")
            } else null,
        )
    }

    private suspend fun restoreSmartDnsBaseline(context: Context, baseline: SmartDnsBaseline): Boolean =
        restoreBaseline(baseline) { key, value ->
            if (value == null) deleteSetting(context, key) else writeSetting(context, key, value)
        }

    internal suspend fun restoreBaseline(baseline: SmartDnsBaseline,
        write: suspend (String, String?) -> Boolean): Boolean {
        // Empty v2 can make v1 the active override; both keys must be restored exactly.
        val v2 = write(KEY_SMARTDNS_V2, baseline.v2)
        val v1 = write(KEY_SMARTDNS_V1, baseline.v1)
        return v2 && v1
    }

    private fun clearSmartDnsBaseline(store: SettingsValues) {
        store.putBoolean(KEY_SMARTDNS_BASELINE_CAPTURED, false)
        store.putBoolean(KEY_SMARTDNS_BASELINE_V2_PRESENT, false)
        store.putString(KEY_SMARTDNS_BASELINE_V2, "")
        store.putBoolean(KEY_SMARTDNS_BASELINE_V1_PRESENT, false)
        store.putString(KEY_SMARTDNS_BASELINE_V1, "")
    }

    private fun captureHappyBaseline(context: Context, store: SettingsValues) {
        if (store.getBoolean(KEY_HAPPY_BASELINE_CAPTURED, false)) return
        val current = readString(context, KEY_HAPPY_EYEBALLS)
        store.putBoolean(KEY_HAPPY_BASELINE_PRESENT, current != null)
        store.putString(KEY_HAPPY_BASELINE, current ?: "")
        store.putBoolean(KEY_HAPPY_BASELINE_CAPTURED, true)
    }

    private suspend fun restoreHappyBaseline(context: Context, store: SettingsValues): Boolean =
        if (store.getBoolean(KEY_HAPPY_BASELINE_PRESENT, false)) {
            writeSetting(context, KEY_HAPPY_EYEBALLS, store.getString(KEY_HAPPY_BASELINE, ""))
        } else deleteSetting(context, KEY_HAPPY_EYEBALLS)

    private fun clearHappyBaseline(store: SettingsValues) {
        store.putBoolean(KEY_HAPPY_BASELINE_CAPTURED, false)
        store.putBoolean(KEY_HAPPY_BASELINE_PRESENT, false)
        store.putString(KEY_HAPPY_BASELINE, "")
    }

    private fun readString(context: Context, key: String): String? =
        Settings.System.getString(context.contentResolver, key)

    private fun readPropertyBoolean(name: String): Boolean? = runCatching {
        val properties = Class.forName("android.os.SystemProperties")
        val get = properties.getMethod("get", String::class.java)
        when ((get.invoke(null, name) as? String)?.trim()?.lowercase()) {
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> null
        }
    }.getOrNull()

    private suspend fun writeSetting(context: Context, key: String, value: String): Boolean =
        withContext(Dispatchers.IO) {
            if (readString(context, key) == value) return@withContext true
            val direct = runCatching {
                Settings.System.putString(context.contentResolver, key, value)
            }.getOrDefault(false)
            if (direct && readString(context, key) == value) return@withContext true
            runCatching {
                NativeRootCommand.run("settings --user ${android.os.Process.myUid() / 100000} put system $key ${NativeRootCommand.quote(value)}")
                check(readString(context, key) == value) { "Settings readback did not match" }
            }.isSuccess
        }

    private suspend fun deleteSetting(context: Context, key: String): Boolean =
        withContext(Dispatchers.IO) {
            if (readString(context, key) == null) return@withContext true
            val direct = runCatching {
                Settings.System.putString(context.contentResolver, key, null)
            }.getOrDefault(false)
            if (direct && readString(context, key) == null) return@withContext true
            runCatching {
                NativeRootCommand.run("settings --user ${android.os.Process.myUid() / 100000} delete system $key")
                check(readString(context, key) == null) { "Settings deletion did not persist" }
            }.isSuccess
        }
}
