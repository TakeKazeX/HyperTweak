package com.takekazex.hypertweak.util.device

import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.NativeRootCommand

/** Static, read-only hardware identity probes. No host process, boot work or hardware polling. */
internal object HardwareIdentityReader {
    data class Reading(val values: Map<String, String>, val rootFailed: Boolean = false)

    fun read(isActive: () -> Boolean = { true }): Reading {
        val local = runCatching {
            parse(NativeRootCommand.run(SCRIPT, maxBytes = 16_384, timeoutMs = 3_000,
                start = {
                    ProcessBuilder("sh", "-c", "timeout 2 sh -c " + NativeRootCommand.quote(SCRIPT))
                        .redirectErrorStream(true).start()
                }).toString(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
        if (!isActive() || REQUIRED.all { it in local }) return Reading(format(local))
        return runCatching {
            val response = NativeRootCommand.run(SCRIPT, maxBytes = 16_384, timeoutMs = 8_000)
                .toString(Charsets.UTF_8)
            check(complete(response)) { "invalid hardware identity response" }
            Reading(format(local + parse(response)))
        }.getOrElse {
            DebugLog.w("DeviceInfo", "hardware identity fallback failed", it)
            Reading(format(local), rootFailed = true)
        }
    }

    private val REQUIRED = setOf("panel", "dram_vendor", "flash_vendor", "flash_model", "touch", "fp_vendor", "fp_module")
    private val KEYS = REQUIRED + setOf("dram_type", "dram_mr6", "dram_mr7", "dram_mr8", "flash_rev",
        "flash_spec", "flash_id", "flash_life_a", "flash_life_b", "flash_eol")

    private fun complete(raw: String): Boolean = raw.length <= 16_384 &&
        raw.lineSequence().any { it == "schema\t1" } && raw.lineSequence().any { it == "complete\t1" }

    internal fun parse(raw: String): Map<String, String> {
        if (!complete(raw)) return emptyMap()
        val lines = raw.lineSequence().map { it.split('\t', limit = 2) }.toList()
        val result = linkedMapOf<String, String>()
        for (parts in lines) {
            if (parts.size != 2 || parts[0] !in KEYS) continue
            val value = parts[1].trim()
            if (value.length !in 1..1024 || value.any { it.code < 32 } ||
                value.lowercase() in setOf("unknown", "null", "error", "unsupported")) continue
            // Reject conflicting sources rather than identifying a random component.
            if (parts[0] in result && result[parts[0]] != value) return emptyMap()
            result[parts[0]] = value
        }
        return result
    }

    internal fun format(raw: Map<String, String>): Map<String, String> = buildMap {
        raw["panel"]?.removePrefix("panel_name=")?.trim()
            ?.takeUnless { it.isEmpty() || it.lowercase() in setOf("unknown", "null", "error") }?.let { panel ->
            put("panel", panel)
            panelVendor(panel)?.let { put("panel_vendor", it) }
        }
        raw["dram_vendor"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.toLongOrNull(16)?.let { id ->
            val vendor = when (id) { 1L -> "Samsung"; 6L -> "SK hynix"; 255L -> "Micron"; else -> null }
            put("dram_vendor", listOfNotNull(vendor, "ID 0x${id.toString(16).padStart(2, '0')}").joinToString(" · "))
        }
        raw["dram_type"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.let { put("dram_type", "0x$it") }
        listOf("dram_mr6", "dram_mr7", "dram_mr8").mapNotNull { key ->
            raw[key]?.takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.let { "${key.removePrefix("dram_").uppercase()}=0x$it" }
        }.takeIf { it.isNotEmpty() }?.let { put("dram_revision", it.joinToString(" / ")) }
        for (key in listOf("flash_vendor", "flash_model", "flash_rev", "flash_id", "flash_spec", "fp_module"))
            raw[key]?.let { put(key, it) }
        listOf("flash_life_a", "flash_life_b", "flash_eol").mapNotNull { key ->
            raw[key]?.takeIf { it.matches(Regex("0[xX][0-9a-fA-F]{1,2}")) }?.let {
                "${key.removePrefix("flash_").uppercase()}=$it"
            }
        }.takeIf { it.isNotEmpty() }?.let { put("flash_life", it.joinToString(" / ")) }
        raw["touch"]?.let { info ->
            val vendor = when {
                info.contains("focaltech", true) -> "FocalTech"
                info.contains("goodix", true) -> "Goodix"
                info.contains("synaptics", true) -> "Synaptics"
                info.contains("novatek", true) -> "Novatek"
                info.contains("ilitek", true) -> "ILITEK"
                info.contains("himax", true) -> "Himax"
                else -> null
            }
            vendor?.let { put("touch_vendor", it) }
            val chip = if (vendor == "FocalTech") Regex("IC ID:0x([0-9a-fA-F]{4})(?![0-9a-fA-F])")
                .find(info)?.groupValues?.get(1)?.uppercase()?.let { "FT$it" } else null
            put("touch_model", chip ?: info)
        }
        raw["fp_vendor"]?.let { name ->
            val vendor = when {
                name.startsWith("goodix", true) -> "Goodix"
                name.startsWith("fpc", true) -> "FPC"
                name.startsWith("silead", true) -> "Silead"
                name.startsWith("egis", true) -> "Egis"
                else -> null
            }
            put("fp_vendor", vendor?.let { "$it · $name" } ?: name)
        }
    }

    internal fun panelVendor(panel: String): String? {
        val text = panel.lowercase()
        val tokens = text.split(Regex("[_\\s-]+"))
        return when {
            "csot" in tokens -> "TCL CSOT"
            "boe" in tokens -> "BOE"
            "tianma" in tokens -> "Tianma"
            "samsung" in tokens -> "Samsung Display"
            "visionox" in tokens -> "Visionox"
            // Identified panel family + supplier announcement. Not a global 0x42 vendor map.
            // Evidence: docs/DEVICE_HARDWARE_IDENTITY_2026-10-24.md; UI marks model matching.
            text == "mdss_dsi_p11u_42_0d_0a_dsc_cmd" -> "TCL CSOT (P11U)"
            else -> null
        }
    }

    internal val SCRIPT = """
        emit() {
            if [ -r "${'$'}2" ]; then
                value=${'$'}(head -c 1024 "${'$'}2" 2>/dev/null | tr '\t\r\n' '   ')
                [ -n "${'$'}value" ] && printf '%s\t%s\n' "${'$'}1" "${'$'}value"
            fi
        }
        binary() {
            if [ -r "${'$'}2" ]; then
                value=${'$'}(head -c 4 "${'$'}2" 2>/dev/null | od -An -tx1 | tr -d ' \n')
                [ -n "${'$'}value" ] && printf '%s\t%s\n' "${'$'}1" "${'$'}value"
            fi
        }
        prop() {
            value=${'$'}(getprop "${'$'}2" 2>/dev/null)
            [ -n "${'$'}value" ] && printf '%s\t%s\n' "${'$'}1" "${'$'}value"
        }
        printf 'schema\t1\n'
        for p in /sys/class/mi_display/disp-DSI-0/panel_info /sys/class/drm/card0-DSI-1/panel_info /sys/class/drm/card0-DSI-1/panel_name /sys/class/graphics/fb0/msm_fb_panel_info; do
            if [ -r "${'$'}p" ]; then emit panel "${'$'}p"; break; fi
        done
        for p in /sys/firmware/devicetree/base/memory /proc/device-tree/memory; do
            if [ -r "${'$'}p/ddr_manufacturer_id" ]; then
                binary dram_vendor "${'$'}p/ddr_manufacturer_id"
                binary dram_type "${'$'}p/ddr_device_type"
                binary dram_mr6 "${'$'}p/ddr_mr6"
                binary dram_mr7 "${'$'}p/ddr_mr7"
                binary dram_mr8 "${'$'}p/ddr_mr8"
                break
            fi
        done
        count=0
        for p in /sys/block/sd*/device; do
            count=${'$'}((count + 1)); [ "${'$'}count" -gt 16 ] && break
            resolved=${'$'}(readlink -f "${'$'}p" 2>/dev/null)
            case "${'$'}resolved" in
                *ufs*)
                    emit flash_vendor "${'$'}p/vendor"
                    emit flash_model "${'$'}p/model"
                    emit flash_rev "${'$'}p/rev"
                    host=${'$'}(readlink -f "${'$'}p/../../.." 2>/dev/null)
                    emit flash_spec "${'$'}host/device_descriptor/specification_version"
                    emit flash_id "${'$'}host/device_descriptor/manufacturer_id"
                    emit flash_life_a "${'$'}host/health_descriptor/life_time_estimation_a"
                    emit flash_life_b "${'$'}host/health_descriptor/life_time_estimation_b"
                    emit flash_eol "${'$'}host/health_descriptor/eol_info"
                    break
                    ;;
            esac
        done
        count=0
        found=0
        for p in /sys/bus/spi/devices/* /sys/bus/i2c/devices/*; do
            count=${'$'}((count + 1)); [ "${'$'}count" -gt 64 ] && break
            if [ -r "${'$'}p/fts_driver_info" ]; then
                emit touch "${'$'}p/fts_driver_info"
                found=1
                break
            fi
        done
        if [ "${'$'}found" = 0 ]; then
            value=${'$'}(head -c 16384 /proc/bus/input/devices 2>/dev/null | grep -iE '^N:.*(focaltech|goodix.*(touch|ts)|synaptics|novatek|ilitek|himax|atmel.*touch|elan.*touch)' | head -c 1024 | tr '\t\r\n' '   ')
            [ -n "${'$'}value" ] && printf 'touch\t%s\n' "${'$'}value"
        fi
        prop fp_vendor persist.vendor.sys.fp.vendor
        prop fp_module persist.vendor.sys.fp.module
        printf 'complete\t1\n'
    """.trimIndent()
}
