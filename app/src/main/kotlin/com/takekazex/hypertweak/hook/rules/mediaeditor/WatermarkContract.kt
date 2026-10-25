package com.takekazex.hypertweak.hook.rules.mediaeditor

import java.lang.reflect.Constructor
import java.lang.reflect.Method

// Stable (non-obfuscated) business classes of the editor.
internal const val CLOUD_WM_DATA = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkData"
internal const val CLOUD_WM_CONFIG = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkConfigData"
internal const val WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkItem"
internal const val WM_CATEGORY = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkCategory"
internal const val CLOUD_WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.CloudWatermarkItem"
internal const val CLOUD_WM_CATEGORY =
    "com.miui.mediaeditor.photo.watermark.model.menu.CloudWatermarkCategory"

internal const val WN_GATE_CACHE_KEY = "wmDeviceGateOwner"
internal const val ZN_GATE_CACHE_KEY = "wmThemeGateOwner"
internal const val CLOUD_FILTER_CACHE_KEY = "wmCloudFilterOwner"
internal const val CLOUD_LOADER_CACHE_KEY = "wmCloudLoaderOwner"
internal const val FETCHER_CACHE_KEY = "wmResourceFetcherOwner"
internal const val DEBUG_GATE_CACHE_KEY = "wmDebugGateOwner"

internal val DEVICE_GROUP_MARKERS = listOf(
    "leica_devices",
    "xiaomi_devices",
    "redmi_devices",
    "poco_devices",
    "victoria_devices",
    "west_coast_3_devices",
    "lcc_devices",
    "west_coast_1_devices",
    "west_coast_2_devices"
)

internal val LCC_DEVICE_TAGS = setOf(
    "lcc_devices",
    "lcc_support_devices",
    "lcc_cn_devices",
    "lcc_global_devices"
)

/**
 * Cloud fields are nullable: media editor 2.10.43 replaced `CloudWatermarkData` /
 * `CloudWatermarkConfigData` with the obfuscated `c70.e` model parsed from
 * `watermark_config_v3`. The old cloud paths remain for older editors; [ModernCloud]
 * describes the resolved replacement model on current builds.
 */
internal class ResolvedClasses(
    val deviceGateMethods: Map<String, Method>,
    val vyI0Filter: Method?,
    val cloudWmData: Class<*>?,
    val tb0VBInvoke: Method?,
    val vyILoad: Method?,
    val yyMDispatcher: Method?,
    val yyM: Class<*>?,
    val listenerIface: Class<*>?,
    val cloudWmItem: Class<*>?,
    val modernCloud: ModernCloud?
)

/**
 * The 2.10.43+ cloud model and its surrounding graph, resolved from the menu builder rather
 * than stored obfuscated names. [modelConstructor] is the 22-parameter data-class
 * constructor whose fields are read by [filterMethod]; [menuBuilder] turns the parsed
 * config into the cloud menu map; the fetcher is the object that downloads one resource.
 */
internal class ModernCloud(
    val modelClass: Class<*>,
    val modelConstructor: Constructor<*>,
    val filterMethod: Method,
    val menuBuilder: Method,
    val fetcherClass: Class<*>?,
    val fetcherConstructor: Constructor<*>?,
    val fetcherMethod: Method?,
    val listenerIface: Class<*>?
)

internal class ModernFetcher(
    val type: Class<*>,
    val constructor: Constructor<*>,
    val method: Method,
    val listenerIface: Class<*>
)
