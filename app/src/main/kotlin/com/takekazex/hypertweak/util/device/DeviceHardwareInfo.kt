package com.takekazex.hypertweak.util.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Display
import android.view.WindowManager
import com.takekazex.hypertweak.R
import java.io.File
import java.util.Locale

/** One foreground snapshot. No polling, root commands, device tables or mutable global cache. */
object DeviceHardwareInfo {
    private fun line(path: String): String? = runCatching {
        File(path).bufferedReader().use { it.readLine()?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrNull()

    private fun property(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }
    }.getOrNull()

    internal fun fileSystem(mounts: Sequence<String>, path: String): String? = mounts.mapNotNull {
        val parts = it.split(Regex("\\s+"))
        if (parts.size >= 3 && (path == parts[1] || path.startsWith(parts[1].trimEnd('/') + "/")))
            parts[1] to parts[2] else null
    }.maxByOrNull { it.first.length }?.second

    private fun khz(raw: String?): String? = raw?.toLongOrNull()?.takeIf { it > 0 }
        ?.let { String.format(Locale.US, "%.2f GHz", it / 1_000_000.0) }

    internal fun withIdentity(context: Context, sections: List<BatteryInfoReader.Section>, identity: HardwareIdentityReader.Reading): List<BatteryInfoReader.Section> {
        val labels = mapOf(
            R.string.device_lbl_panel to "panel", R.string.device_lbl_panel_vendor to "panel_vendor",
            R.string.device_lbl_flash_vendor to "flash_vendor", R.string.device_lbl_flash_model to "flash_model",
            R.string.device_lbl_flash_life to "flash_life", R.string.device_lbl_flash_spec to "flash_spec",
            R.string.device_lbl_memory_vendor to "dram_vendor", R.string.device_lbl_memory_type to "dram_type",
            R.string.device_lbl_memory_revision to "dram_revision",
            R.string.device_lbl_touch_vendor to "touch_vendor", R.string.device_lbl_touch_model to "touch_model",
            R.string.device_lbl_fp_vendor to "fp_vendor", R.string.device_lbl_fp_module to "fp_module"
        ).mapKeys { context.getString(it.key) }
        return sections.map { section -> section.copy(rows = section.rows.map { row ->
            labels[row.label]?.let { key -> identity.values[key]?.let { value ->
                val shown = if (key == "panel_vendor" && value.endsWith(" (P11U)"))
                    context.getString(R.string.device_vendor_model_match_fmt, value.removeSuffix(" (P11U)")) else value
                row.copy(value = shown)
            } } ?: row
        }) }
    }

    fun read(context: Context): List<BatteryInfoReader.Section> {
        val unavailable = context.getString(R.string.battery_unavailable)
        fun row(label: Int, read: () -> String?) = BatteryInfoReader.Row(
            context.getString(label), runCatching(read).getOrNull()?.takeIf(String::isNotBlank) ?: unavailable
        )
        fun bytes(value: Long) = value.takeIf { it > 0 }?.let { Formatter.formatFileSize(context, it) }
        fun section(title: Int, vararg rows: BatteryInfoReader.Row) =
            BatteryInfoReader.Section(context.getString(title), rows.toList())
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val memory = runCatching {
            ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it)
            }
        }.getOrNull()
        val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        return listOf(
            section(R.string.device_sec_display,
                row(R.string.device_lbl_resolution) { display?.mode?.let { "${it.physicalWidth} × ${it.physicalHeight}" } },
                row(R.string.device_lbl_window) {
                    context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.let { "${it.width()} × ${it.height()} px" }
                },
                row(R.string.device_lbl_density) { "${context.resources.displayMetrics.densityDpi} dpi" },
                row(R.string.device_lbl_logical_width) { "${context.resources.configuration.screenWidthDp} dp" },
                row(R.string.device_lbl_refresh) { display?.refreshRate?.let { String.format(Locale.US, "%.1f Hz", it) } },
                row(R.string.device_lbl_modes) { display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted()
                    ?.takeIf { it.isNotEmpty() }?.joinToString(" / ") { String.format(Locale.US, "%.1f Hz", it) } },
                row(R.string.device_lbl_hdr) {
                    @Suppress("DEPRECATION")
                    val types = display?.hdrCapabilities?.supportedHdrTypes
                    if (types != null && types.isEmpty()) context.getString(R.string.device_hdr_none)
                    else types?.joinToString(" / ") { when (it) {
                        1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> it.toString()
                    } }
                },
                row(R.string.device_lbl_panel_vendor) { null },
                row(R.string.device_lbl_panel) { null },
                row(R.string.device_lbl_orientation) { property("ro.surface_flinger.primary_display_orientation") },
                row(R.string.device_lbl_brightness) {
                    Settings.System.getString(context.contentResolver, "screen_brightness_float")
                        ?: Settings.System.getString(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }),
            section(R.string.device_sec_storage,
                row(R.string.device_lbl_total) { storage?.totalBytes?.let(::bytes) },
                row(R.string.device_lbl_available) { storage?.availableBytes?.let { Formatter.formatFileSize(context, it) } },
                row(R.string.device_lbl_used) { storage?.let { Formatter.formatFileSize(context, it.totalBytes - it.freeBytes) } },
                row(R.string.device_lbl_encryption) { property("ro.crypto.state") },
                row(R.string.device_lbl_encryption_type) { property("ro.crypto.type") },
                row(R.string.device_lbl_filesystem) {
                    File("/proc/mounts").bufferedReader().use { fileSystem(it.lineSequence(), "/data") }
                },
                row(R.string.device_lbl_flash_vendor) { null },
                row(R.string.device_lbl_flash_model) { null },
                row(R.string.device_lbl_flash_spec) { null },
                row(R.string.device_lbl_flash_life) { null }),
            section(R.string.device_sec_memory,
                row(R.string.device_lbl_total) { memory?.totalMem?.let(::bytes) },
                row(R.string.device_lbl_available) { memory?.availMem?.let(::bytes) },
                row(R.string.device_lbl_memory_vendor) { null },
                row(R.string.device_lbl_memory_type) { null },
                row(R.string.device_lbl_memory_revision) { null },
                row(R.string.device_lbl_memory_freq) {
                    File("/sys/class/devfreq").listFiles()?.filter {
                        it.name.contains("ddr", true)
                    }?.mapNotNull { node -> line("${node.path}/cur_freq")?.let { "${node.name}: $it Hz" } }
                        ?.takeIf { it.isNotEmpty() }?.joinToString("\n")
                }),
            section(R.string.device_sec_input,
                row(R.string.device_lbl_touch_vendor) { null },
                row(R.string.device_lbl_touch_model) { null },
                row(R.string.device_lbl_fp_vendor) { null },
                row(R.string.device_lbl_fp_module) { null }),
            section(R.string.device_sec_soc,
                row(R.string.device_lbl_soc) { Build.SOC_MODEL.takeUnless { it.isBlank() || it == Build.UNKNOWN } },
                row(R.string.device_lbl_soc_vendor) { Build.SOC_MANUFACTURER.takeUnless { it.isBlank() || it == Build.UNKNOWN } },
                row(R.string.device_lbl_platform) { property("ro.board.platform") },
                row(R.string.device_lbl_cpu) {
                    File("/sys/devices/system/cpu").listFiles()?.filter { it.name.matches(Regex("cpu[0-9]+")) }
                        ?.mapNotNull { node -> khz(line("${node.path}/cpufreq/cpuinfo_max_freq")) }
                        ?.groupingBy { it }?.eachCount()?.toSortedMap()?.takeIf { it.isNotEmpty() }
                        ?.entries?.joinToString("\n") { "${it.value} × ${it.key}" }
                },
                row(R.string.device_lbl_gpu) { line("/sys/class/kgsl/kgsl-3d0/gpu_model") },
                row(R.string.device_lbl_gpu_freq) { line("/sys/class/kgsl/kgsl-3d0/gpuclk")?.toLongOrNull()
                    ?.takeIf { it > 0 }?.let { "${it / 1_000_000} MHz" } })
        )
    }
}
