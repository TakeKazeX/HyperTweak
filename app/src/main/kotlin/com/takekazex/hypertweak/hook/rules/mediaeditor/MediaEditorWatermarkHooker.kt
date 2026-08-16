package com.takekazex.hypertweak.hook.rules.mediaeditor

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unlocks watermark categories in the Xiaomi media editor (`com.miui.mediaeditor`,
 * 相册编辑). Verified against 2.10.37.9 on OS4.0.0.15.XPMCNXM (REDMI K90 Pro Max, `myron`);
 * reverse-engineering notes and the full gating map live in the reverse workspace at
 * `cache/mediaeditor-292ff5db343e5f13/WATERMARK_UNLOCK_PLAN.md`.
 *
 * Watermark visibility is gated in three layers, each with its own hooks:
 *
 * 1. **Device checks** — R8-obfuscated static helpers `wn.a` / `zn.a` (classes2.dex) decide
 *    which brand/theme groups this device may see. Every check is a parameterless `boolean`
 *    that inspects `Build.DEVICE` / `Build.BRAND` / `ro.boot.product.theme_customize`. They are
 *    shared by the local template menu (`vy.i.d`) and the cloud config filter (`vy.i0.a`), so a
 *    single hook per method unlocks both sides of one category. Each category has its own
 *    preference switch:
 *    - `wn.a.b()` = leica_devices (徕卡)         → [Preferences.KEY_WM_LEICA]
 *    - `wn.a.i()` = xiaomi_devices (小米)        → [Preferences.KEY_WM_XIAOMI]
 *    - `wn.a.e()` = redmi_devices (红米)         → [Preferences.KEY_WM_REDMI]
 *    - `wn.a.c()` = poco_devices (POCO)          → [Preferences.KEY_WM_POCO]
 *    - `wn.a.g()` = victoria_devices (维多利亚)  → [Preferences.KEY_WM_VICTORIA]
 *    - `wn.a.h()` = west_coast_3_devices (迪士尼3) → [Preferences.KEY_WM_DISNEY3]
 *    - `zn.a.g()` = lcc_devices (LCC)            → [Preferences.KEY_WM_LCC]
 *    - `zn.a.h()` = west_coast_1_devices (迪士尼1) → [Preferences.KEY_WM_DISNEY1]
 *    - `zn.a.i()` = west_coast_2_devices (迪士尼2) → [Preferences.KEY_WM_DISNEY2]
 *    `zn.a.b()` must **never** be hooked: the local watermark menu is wrapped in
 *    `if (!zn.a.b() || zn.a$q.b(null))`, so forcing it true would hide the whole menu.
 *
 * 2. **Cloud config fields** — `CloudWatermarkData` (Kotlin data class, business name kept)
 *    carries per-watermark restriction fields parsed from the `watermark_config_v2` cloud
 *    config: `supportDeviceList`/`unSupportDeviceList` (device-group tags), `supportRegions`/
 *    `unSupportRegions` (country codes), `validFrom`/`validTo` (festival time windows),
 *    `name_length_limitation`, `minWmVer` and `supportDisplayApp`. The filter itself lives in
 *    the obfuscated `vy.i0.a(CloudWatermarkConfigData, List)`. The constructor after-hook
 *    rewrites the restriction fields while the master switch is on, before the filter runs:
 *    validFrom = 0 / validTo = Long.MAX_VALUE (smali-verified check is
 *    `now <= validTo && validFrom <= now`), supportRegions = ["*"] / unSupportRegions = [],
 *    name_length_limitation = [], minWmVer = 0.0 and supportDisplayApp gains "ALL". These
 *    "integrity" limits follow the master switch rather than per-category switches, because a
 *    category such as leica mixes entries with different restrictions (festival editions,
 *    camera-only display apps, higher min versions) and unlocking the category must show them
 *    all. The LCC tag remap below stays behind [Preferences.KEY_WM_LCC]: with it on,
 *    `lcc_global_devices` / `lcc_cn_devices` tags in the support list become `*` and are
 *    dropped from the unsupported list, so both the CN and the global LCC watermark sets pass
 *    regardless of `ro.product.mod_device`.
 *
 * 3. **Downloaded-resource filter** — after a cloud watermark zip lands in
 *    `files/watermarks/`, `tb0.o0` (PhotoWmManager) re-scans the folder and applies a second
 *    filter chain (id whitelist / validity / device_type / region / theme / system properties /
 *    name length). The whole chain is skipped when the system property
 *    `camera.cloud.watermark.debug` is true, which is read by the obfuscated `tb0.v$b.invoke()`.
 *    Hooking that to true while the master switch is on keeps every downloaded resource usable.
 *
 * 4. **Bulk download** — cloud watermark zips are normally fetched on demand when a menu item
 *    is tapped (`yy.h` CloudWatermarkResourceFetcher, dispatched by `yy.m`). With
 *    [Preferences.KEY_WM_DOWNLOAD_ALL] on, the `vy.i.b(List, boolean)` after-hook walks the
 *    freshly built cloud menu and dispatches every `CloudWatermarkItem` through the same
 *    `yy.m.a(WatermarkItem, WatermarkCategory, ee.e.a)` path (listener supplied via a dynamic
 *    proxy), so the resources land locally without tapping each entry.
 *
 * All preference switches are read live inside the callbacks (with the 100 ms Preferences
 * memo), so toggling a category takes effect the next time the watermark menu is built without
 * restarting the editor. Only the initial enable of the master switch needs the editor process
 * to be restarted so the hooks are installed.
 */
object MediaEditorWatermarkHooker : StaticHooker() {
    private const val TAG = "WmUnlock"
    private const val PACKAGE = "com.miui.mediaeditor"

    // Stable (non-obfuscated) business classes of the editor.
    private const val CLOUD_WM_DATA = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkData"
    private const val CLOUD_WM_CONFIG = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkConfigData"
    private const val WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkItem"
    private const val WM_CATEGORY = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkCategory"
    private const val CLOUD_WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.CloudWatermarkItem"

    private class ResolvedClasses(
        val wnA: Class<*>,
        val znA: Class<*>,
        val vyI0Filter: Method,
        val cloudWmData: Class<*>,
        val tb0VBInvoke: Method,
        val vyILoad: Method,
        val yyMDispatcher: Method,
        val yyM: Class<*>,
        val listenerIface: Class<*>,
        val cloudWmItem: Class<*>
    )

    private val downloaded = AtomicBoolean(false)
    private var resolved: ResolvedClasses? = null

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        val classes = runCatching { resolveClasses() }
            .onFailure { DebugLog.e(TAG, "class resolution failed", it) }
            .getOrNull() ?: return
        resolved = classes
        installHooks(classes)
    }

    override fun onPrepareHotReload() {
        downloaded.set(false)
        resolved = null
    }

    // ─── Class resolution ──────────────────────────────────────────────────────

    private fun resolveClasses(): ResolvedClasses? {
        val appInfo = hookParam.appInfo ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val cacheDir = File(baseDir, "cache")

        // Literal R8 class names of the verified baseline take precedence; DexKit string
        // signatures are the fallback for a build where the names changed. The queries are
        // still registered up front so the DexKit scan (and its properties cache) covers them
        // in the same bridge pass.
        val wnA = "wn.a".toClassOrNull() ?: DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                "wnA" to { bridge ->
                    bridge.findClass { matcher { usingStrings("ro.boot.product.theme_customize") } }
                        .firstOrNull { it.name.endsWith(".a") }?.name
                }
            )
        )["wnA"] ?: run {
            DebugLog.e(TAG, "wn.a not resolved")
            return null
        }
        val znA = "zn.a".toClassOrNull() ?: DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                "znA" to { bridge ->
                    bridge.findClass { matcher { usingStrings("ro.theme_customize") } }
                        .firstOrNull { it.name.endsWith(".a") }?.name
                }
            )
        )["znA"] ?: run {
            DebugLog.e(TAG, "zn.a not resolved")
            return null
        }

        val cloudWmData = CLOUD_WM_DATA.toClass() ?: return null
        val cloudWmConfig = CLOUD_WM_CONFIG.toClass() ?: return null
        val wmItem = WM_ITEM.toClass() ?: return null
        val wmCategory = WM_CATEGORY.toClass() ?: return null
        val cloudWmItem = CLOUD_WM_ITEM.toClass() ?: return null

        // vy.i0.a(CloudWatermarkConfigData, List): the cloud filter. Resolved by signature.
        val vyI0Filter = "vy.i0".toClassOrNull()
            ?.declaredMethods
            ?.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == cloudWmConfig &&
                    it.parameterTypes[1] == List::class.java
            } ?: run {
            DebugLog.e(TAG, "vy.i0.a filter not resolved")
            return null
        }

        // vy.i.b(List, boolean): cloud menu loader (after-hook drives bulk download).
        val vyILoad = "vy.i".toClassOrNull()
            ?.declaredMethods
            ?.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == List::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            } ?: run {
            DebugLog.e(TAG, "vy.i.b loader not resolved")
            return null
        }

        // yy.m.a(WatermarkItem, WatermarkCategory, ee.e.a): resource-fetch dispatcher.
        val yyM = "yy.m".toClassOrNull() ?: run {
            DebugLog.e(TAG, "yy.m dispatcher not resolved")
            return null
        }
        val yyMDispatcher = yyM.declaredMethods.firstOrNull {
            it.parameterTypes.size == 3 && it.parameterTypes[0] == wmItem &&
                it.parameterTypes[1] == wmCategory && it.parameterTypes[2].isInterface
        } ?: run {
            DebugLog.e(TAG, "yy.m.a dispatcher method not resolved")
            return null
        }
        val listenerIface = yyMDispatcher.parameterTypes[2]

        // tb0.v$b.invoke(): reads `camera.cloud.watermark.debug`; true skips the whole
        // downloaded-resource filter chain.
        val tb0VBInvoke = "tb0.v\$b".toClassOrNull()
            ?.declaredMethods
            ?.firstOrNull { it.parameterTypes.isEmpty() } ?: run {
            DebugLog.e(TAG, "tb0.v\$b debug flag not resolved")
            return null
        }

        return ResolvedClasses(
            wnA = wnA,
            znA = znA,
            vyI0Filter = vyI0Filter,
            cloudWmData = cloudWmData,
            tb0VBInvoke = tb0VBInvoke,
            vyILoad = vyILoad,
            yyMDispatcher = yyMDispatcher,
            yyM = yyM,
            listenerIface = listenerIface,
            cloudWmItem = cloudWmItem
        )
    }

    // ─── Hook installation ─────────────────────────────────────────────────────

    private fun installHooks(c: ResolvedClasses) {
        // 1. Per-category device checks (shared by local menu + cloud filter).
        hookDeviceCheck(c.wnA, "b", Preferences.KEY_WM_LEICA)
        hookDeviceCheck(c.wnA, "i", Preferences.KEY_WM_XIAOMI)
        hookDeviceCheck(c.wnA, "e", Preferences.KEY_WM_REDMI)
        hookDeviceCheck(c.wnA, "c", Preferences.KEY_WM_POCO)
        hookDeviceCheck(c.wnA, "g", Preferences.KEY_WM_VICTORIA)
        hookDeviceCheck(c.wnA, "h", Preferences.KEY_WM_DISNEY3)
        hookDeviceCheck(c.znA, "g", Preferences.KEY_WM_LCC)
        hookDeviceCheck(c.znA, "h", Preferences.KEY_WM_DISNEY1)
        hookDeviceCheck(c.znA, "i", Preferences.KEY_WM_DISNEY2)

        // 2. Cloud restriction fields: rewrite in the CloudWatermarkData constructor.
        hookCloudDataConstructor(c)

        // 3. Downloaded-resource filter: force the debug property read to true.
        c.tb0VBInvoke.hook("wm_debug_filter") {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false)) {
                    param.result = true
                }
            }
        }

        // 4. Bulk download of every cloud watermark resource.
        hookBulkDownload(c)
    }

    private fun hookDeviceCheck(clazz: Class<*>, methodName: String, prefKey: String) {
        val method = runCatching { clazz.getDeclaredMethod(methodName).apply { isAccessible = true } }
            .getOrNull() ?: run {
            DebugLog.w(TAG, "device check $methodName not found on ${clazz.name}")
            return
        }
        deoptimize(method)
        method.hook("wm_dev_$methodName") {
            after { param ->
                if (masterAnd(prefKey)) param.result = true
            }
        }
        DebugLog.d(TAG, "device check hooked ${clazz.name}#$methodName -> $prefKey")
    }

    /**
     * Rewrites the restriction fields of every `CloudWatermarkData` right after construction.
     * List fields are mutated through the constructor arguments (shared instances); the long /
     * double fields are written on `thisObject` by type order (first `long` = validFrom, second
     * `long` = validTo, the only `double` = minWmVer — the same declaration order R8 preserves).
     */
    private fun hookCloudDataConstructor(c: ResolvedClasses) {
        val clazz = c.cloudWmData
        val ctor = runCatching {
            clazz.declaredConstructors.first { it.parameterTypes.size == 21 }
        }.getOrNull() ?: run {
            DebugLog.e(TAG, "CloudWatermarkData constructor not found")
            return
        }
        // Constructor parameter indices (1-based in the smali signature):
        // 14 supportDeviceList(List), 15 unSupportDeviceList, 16 supportRegions,
        // 17 unSupportRegions, 18 name_length_limitation(List<Integer>), 19 supportDisplayApp.
        // validFrom/validTo/minWmVer are written on `thisObject` by type order below.
        val supportDeviceListIdx = 13
        val unSupportDeviceListIdx = 14
        val supportRegionsIdx = 15
        val unSupportRegionsIdx = 16
        val nameLengthIdx = 17
        val displayAppIdx = 18

        val longFields = clazz.declaredFields.filter { it.type == Long::class.javaPrimitiveType }
        val doubleFields = clazz.declaredFields.filter { it.type == Double::class.javaPrimitiveType }

        ctor.hook {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false)) return@after
                val args = param.args
                try {
                    @Suppress("UNCHECKED_CAST")
                    val supportDevices = args[supportDeviceListIdx] as? MutableList<String>
                    @Suppress("UNCHECKED_CAST")
                    val unSupportDevices = args[unSupportDeviceListIdx] as? MutableList<String>
                    @Suppress("UNCHECKED_CAST")
                    val supportRegions = args[supportRegionsIdx] as? MutableList<String>
                    @Suppress("UNCHECKED_CAST")
                    val unSupportRegions = args[unSupportRegionsIdx] as? MutableList<String>
                    @Suppress("UNCHECKED_CAST")
                    val nameLength = args[nameLengthIdx] as? MutableList<*>
                    @Suppress("UNCHECKED_CAST")
                    val displayApps = args[displayAppIdx] as? MutableList<String>

                    if (Preferences.getBoolean(Preferences.KEY_WM_LCC, false)) {
                        // Both LCC watermark sets (CN and global) regardless of mod_device.
                        supportDevices?.let { list ->
                            for (i in list.indices) {
                                val tag = list[i]
                                if (tag == "lcc_global_devices" || tag == "lcc_cn_devices") {
                                    list[i] = "*"
                                }
                            }
                        }
                        unSupportDevices?.removeAll { it == "lcc_global_devices" || it == "lcc_cn_devices" }
                    }

                    // The remaining "integrity" limits (region / time window / name length /
                    // min version / display-app allow-list) follow the master switch: unlocking
                    // watermarks must show every entry of an unlocked category, not only the
                    // ones that happen to pass these secondary gates. They cannot be left
                    // behind per-category switches because a category like leica mixes entries
                    // with different restrictions (festival editions, camera-only apps, ...).
                    supportRegions?.clear()
                    supportRegions?.add("*")
                    unSupportRegions?.clear()

                    nameLength?.clear()
                    displayApps?.let { if (!it.contains("ALL")) it.add("ALL") }

                    val thisObj = param.thisObject ?: return@after
                    val validFrom = longFields.getOrNull(0)
                    val validTo = longFields.getOrNull(1)
                    if (validFrom != null) {
                        validFrom.isAccessible = true
                        validFrom.setLong(thisObj, 0L)
                    }
                    if (validTo != null) {
                        validTo.isAccessible = true
                        validTo.setLong(thisObj, Long.MAX_VALUE)
                    }
                    doubleFields.firstOrNull()?.let { minWmVer ->
                        minWmVer.isAccessible = true
                        minWmVer.setDouble(thisObj, 0.0)
                    }
                } catch (t: Throwable) {
                    DebugLog.w(TAG, "cloud watermark field rewrite failed", t)
                }
            }
        }
        DebugLog.d(TAG, "cloud watermark fields hook installed on ${clazz.name}")
    }

    private fun hookBulkDownload(c: ResolvedClasses) {
        // yy.m is an object (singleton); its instance is the static field of its own type.
        val dispatcherInstance = runCatching {
            val field = c.yyM.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == c.yyM
            } ?: return
            field.isAccessible = true
            field.get(null)
        }.onFailure { DebugLog.e(TAG, "yy.m singleton not found", it) }.getOrNull() ?: return

        c.vyILoad.hook("wm_bulk_download") {
            after { param ->
                if (!Preferences.getBoolean(Preferences.KEY_WM_DOWNLOAD_ALL, false)) return@after
                if (!downloaded.compareAndSet(false, true)) return@after
                val map = param.result as? Map<*, *> ?: return@after
                val dispatcher = c.yyMDispatcher
                val listener = try {
                    Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(c.listenerIface)
                    ) { _, method, _ ->
                        when (method.name) {
                            "onStart" -> DebugLog.d(TAG, "bulk download start")
                            "onSuccess" -> DebugLog.d(TAG, "bulk download success")
                            "onFail" -> DebugLog.d(TAG, "bulk download fail")
                        }
                        null
                    }
                } catch (t: Throwable) {
                    DebugLog.w(TAG, "failed to create download listener", t)
                    return@after
                }
                var count = 0
                for ((category, items) in map) {
                    if (items !is List<*>) continue
                    for (item in items) {
                        if (item == null || !c.cloudWmItem.isInstance(item)) continue
                        try {
                            dispatcher.invoke(dispatcherInstance, item, category, listener)
                            count++
                        } catch (t: Throwable) {
                            DebugLog.w(TAG, "bulk download dispatch failed for ${item.javaClass.name}", t)
                        }
                    }
                }
                DebugLog.d(TAG, "bulk download dispatched $count cloud watermarks")
            }
        }
        DebugLog.d(TAG, "bulk download hook installed")
    }

    // ─── Preference helpers ────────────────────────────────────────────────────

    private fun masterAnd(key: String): Boolean {
        return Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false) &&
            Preferences.getBoolean(key, false)
    }
}
