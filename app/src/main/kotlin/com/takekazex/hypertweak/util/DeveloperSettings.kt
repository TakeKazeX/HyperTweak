package com.takekazex.hypertweak.util

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small bridge for the developer settings exposed by the Debug page.
 *
 * The module UI is not a privileged Settings provider client, so writes first try the public
 * provider API and then fall back to the same `settings` command used by the system tools. The
 * latter is intentionally limited to fixed, framework-owned keys below.
 */
object DeveloperSettings {
    private const val SURFACE_FLINGER = "SurfaceFlinger"
    private const val SURFACE_COMPOSER_INTERFACE = "android.ui.ISurfaceComposer"
    const val SHOW_REFRESH_RATE_TRANSACTION = 1034
    const val SHOW_HDR_SDR_RATIO_TRANSACTION = 1043
    private const val SURFACE_FLINGER_QUERY = 2

    const val SHOW_REFRESH_RATE = "show_refresh_rate"
    const val SHOW_HDR_SDR_RATIO = "show_hdr_sdr_ratio"
    const val SHOW_TOUCHES = "show_touches"
    const val POINTER_LOCATION = "pointer_location"

    fun readSystemBoolean(context: Context, key: String): Boolean =
        readInt { Settings.System.getInt(context.contentResolver, key, 0) }

    fun readGlobalBoolean(context: Context, key: String): Boolean =
        readInt { Settings.Global.getInt(context.contentResolver, key, 0) }

    fun readSurfaceFlingerBoolean(transaction: Int): Boolean? = runCatching {
        val binder = surfaceFlingerBinder() ?: return null
        Parcel.obtain().use { data ->
            Parcel.obtain().use { reply ->
                data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE)
                data.writeInt(SURFACE_FLINGER_QUERY)
                // SurfaceFlinger handles this transaction but may return false because it is
                // one-way on some ROMs. The reply parcel is the actual query result.
                binder.transact(transaction, data, reply, 0)
                reply.readBoolean()
            }
        }
    }.getOrNull() ?: runServiceCall("service call $SURFACE_FLINGER $transaction i32 $SURFACE_FLINGER_QUERY")

    suspend fun writeSystemBoolean(context: Context, key: String, enabled: Boolean): Boolean =
        writeProviderBoolean(
            context = context,
            namespace = Namespace.SYSTEM,
            key = key,
            enabled = enabled,
            providerWrite = { Settings.System.putInt(context.contentResolver, key, enabled.intValue) }
        )

    suspend fun writeGlobalBoolean(context: Context, key: String, enabled: Boolean): Boolean =
        writeProviderBoolean(
            context = context,
            namespace = Namespace.GLOBAL,
            key = key,
            enabled = enabled,
            providerWrite = { Settings.Global.putInt(context.contentResolver, key, enabled.intValue) }
        )

    suspend fun writeSurfaceFlingerBoolean(transaction: Int, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val direct = runCatching {
                val binder = surfaceFlingerBinder() ?: return@runCatching false
                Parcel.obtain().use { data ->
                    data.writeInterfaceToken(SURFACE_COMPOSER_INTERFACE)
                    data.writeInt(enabled.intValue)
                    binder.transact(transaction, data, null, 0)
                    // A successful call has no useful boolean return value here. Some ROMs
                    // return false even though SurfaceFlinger applied the setting.
                    true
                }
            }.getOrDefault(false)
            val command = if (direct) {
                false
            } else {
                runSettingsCommand(
                    "service call $SURFACE_FLINGER $transaction i32 ${enabled.intValue}"
                )
            }
            // Some MIUI su wrappers return a non-zero exit code after applying the setting.
            // Trust the state reported by SurfaceFlinger before showing a failure message.
            val verified = readSurfaceFlingerBoolean(transaction) == enabled
            direct || command || verified
        }

    fun refreshRateEnabled(): Boolean? = readSurfaceFlingerBoolean(SHOW_REFRESH_RATE_TRANSACTION)

    fun hdrSdrRatioEnabled(): Boolean? = readSurfaceFlingerBoolean(SHOW_HDR_SDR_RATIO_TRANSACTION)

    private suspend fun writeProviderBoolean(
        context: Context,
        namespace: Namespace,
        key: String,
        enabled: Boolean,
        providerWrite: () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val direct = runCatching { providerWrite() }.getOrDefault(false)
        direct || runSettingsCommand("settings put ${namespace.value} $key ${enabled.intValue}")
    }

    private fun surfaceFlingerBinder(): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        return getService.invoke(null, SURFACE_FLINGER) as? IBinder
    }

    private fun runSettingsCommand(command: String): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.inputStream.close()
        process.errorStream.close()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun runServiceCall(command: String): Boolean? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.close()
        process.waitFor()
        Regex("\\b[0-9a-fA-F]{8}\\b")
            .findAll(output.substringAfter("Parcel(", ""))
            .lastOrNull()
            ?.value
            ?.toLongOrNull(16)
            ?.let { it != 0L }
    }.getOrNull()

    private inline fun readInt(read: () -> Int): Boolean = runCatching { read() != 0 }.getOrDefault(false)

    private enum class Namespace(val value: String) {
        SYSTEM("system"),
        GLOBAL("global")
    }

    private val Boolean.intValue: Int
        get() = if (this) 1 else 0

    private fun <T> Parcel.use(block: (Parcel) -> T): T {
        return try {
            block(this)
        } finally {
            recycle()
        }
    }
}
