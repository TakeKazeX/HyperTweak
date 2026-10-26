package com.takekazex.hypertweak.hook.rules.mediaeditor

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unlocks watermark categories in the Xiaomi media editor (`com.miui.mediaeditor`,
 * 相册编辑). The legacy cloud model was verified against 2.10.37.9
 * (OS4.0.0.15.XPMCNXM); the replacement `watermark_config_v3` model was derived with DexSQL
 * from 2.10.43.0.6 (device APK SHA-256
 * `ea1cd5c1dd7a8fcbefe8512c1cc4bf8d214ce8ae8900798e5c3ebf1c7488e34c`).
 *
 * Watermark visibility and resource availability are handled by four hook paths:
 *
 * 1. **Device checks** — DexKit locates the two gate owners by class shape, then resolves their
 *    parameterless Boolean methods by field-use profiles. Theme gates are mapped by decoding
 *    embedded values through the string decoder they invoke. These checks are shared by the local
 *    template menu and cloud config filter, so each preference controls both paths. Ambiguous or
 *    changed shapes fail closed. The separate global menu-suppression gate is excluded.
 *
 * 2. **Cloud config fields** — the legacy `CloudWatermarkData` (`watermark_config_v2`) and the
 *    current obfuscated 22-field model (`watermark_config_v3`) carry the same per-watermark
 *    restriction slots: support/unSupport device lists, region lists, validFrom/validTo,
 *    name-length range, minimum manager version and display-app allow-list. The v3 class and its
 *    constructor are derived from the menu builder's field owners, never from an obfuscated name.
 *    A constructor after-hook rewrites those slots while the master switch is on:
 *    supportRegions = ["*"] / unSupportRegions = [], name range = [], minWmVer = 0.0,
 *    validFrom = 0 / validTo = Long.MAX_VALUE and the display-app list gains
 *    "ALL"/"Photo"/"Editor". Device lists stay native so per-category gates still decide which
 *    device-group tags are visible; the explicit LCC switch remaps `lcc_*` tags to "*" and drops
 *    them from the unsupported list, matching the legacy build. These "integrity" limits follow
 *    the master switch rather than per-category switches, because a category such as leica mixes
 *    festival editions, camera-only display apps and higher minimum versions; unlocking the
 *    category must show all of them. Category visibility remains controlled by the
 *    per-category gate hooks.
 *
 * 3. **Downloaded-resource filter** — the legacy build re-scans `files/watermarks/` and skips the
 *    chain when `camera.cloud.watermark.debug` is true; DexKit resolves that property reader by
 *    its marker and return shape. The 2.10.43 reader remains resolvable but has no callers.
 *
 * 4. **Bulk download** — cloud watermark zips are normally fetched on demand when a menu item is
 *    tapped. With [PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL] on, the menu-builder after-hook walks the
 *    freshly built menu and calls the resolved legacy dispatcher or v3 fetcher for every
 *    `CloudWatermarkItem` (listener supplied via a dynamic proxy).
 *
 * All preference switches are read live inside the callbacks (with the 100 ms Preferences
 * memo), so toggling a category takes effect the next time the watermark menu is built without
 * restarting the editor. Only the initial enable of the master switch needs the editor process
 * to be restarted so the hooks are installed.
 */
object MediaEditorWatermarkHooker : StaticHooker() {
    private const val TAG = "WmUnlock"
    private const val PACKAGE = "com.miui.mediaeditor"

    /** Every switch this hooker serves; any one of them being on justifies installing it. */
    private val WATERMARK_KEYS = listOf(
        PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER,
        PlatformSettingsKeys.KEY_WM_LEICA,
        PlatformSettingsKeys.KEY_WM_XIAOMI,
        PlatformSettingsKeys.KEY_WM_REDMI,
        PlatformSettingsKeys.KEY_WM_POCO,
        PlatformSettingsKeys.KEY_WM_DISNEY1,
        PlatformSettingsKeys.KEY_WM_DISNEY2,
        PlatformSettingsKeys.KEY_WM_DISNEY3,
        PlatformSettingsKeys.KEY_WM_VICTORIA,
        PlatformSettingsKeys.KEY_WM_LCC,
        PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL,
        PlatformSettingsKeys.KEY_WM_CAMERA,
    )

    private val downloaded = AtomicBoolean(false)
    private var resolved: ResolvedClasses? by generationState { null }

    /** Prepare optional watermark targets only while at least one category is enabled. */
    override val installPolicy: InstallPolicy = InstallPolicy.WHEN_ENABLED

    override fun isFeatureEnabled(): Boolean = WATERMARK_KEYS.any {
        Preferences.getBoolean(it, false)
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!isFeatureEnabled()) {
            DebugLog.hookSkippedDebug(TAG, "watermark categories", "no watermark switch enabled")
            return
        }
        // Materialize the graph in the background and publish hooks on the owning main thread.
        prepareDexTargets(
            name = "mediaeditor.watermark",
            resolve = {
                runCatching { MediaEditorWatermarkResolver(this).resolveClasses() }
                    .onFailure { DebugLog.e(TAG, "class resolution failed", it) }
                    .getOrNull()
            },
            install = ::installResolved,
        )
    }

    private fun installResolved(classes: ResolvedClasses) {
        resolved = classes
        installHooks(classes)
    }

    override fun onPrepareHotReload() {
        downloaded.set(false)
    }

    // ─── Class resolution ──────────────────────────────────────────────────────

    // ─── Hook installation ─────────────────────────────────────────────────────

    private fun installHooks(c: ResolvedClasses) {
        // 1. Per-category device checks (shared by local menu + cloud filter), resolved by DEX.
        c.deviceGateMethods.forEach { (prefKey, method) -> hookDeviceCheck(method, prefKey) }

        // 2. Cloud restriction fields: legacy `CloudWatermarkData` before 2.10.43, or the
        //    obfuscated 22-field v3 model on current builds.
        when {
            c.cloudWmData != null -> hookCloudDataConstructor(c)
            c.modernCloud != null -> hookModernCloudData(c.modernCloud)
            else -> DebugLog.d(TAG, "cloud watermark field rewrite unavailable on this build")
        }

        // 3. Downloaded-resource filter: force the debug property read to true when resolvable.
        c.tb0VBInvoke?.hook("wm_debug_filter") {
            after { param ->
                if (Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, false)) {
                    param.result = true
                }
            }
        } ?: DebugLog.d(TAG, "cloud watermark debug gate unavailable on this build")

        // 4. Bulk download of every cloud watermark resource when the dispatcher graph resolved.
        val modern = c.modernCloud
        when {
            modern?.fetcherMethod != null && modern.fetcherClass != null &&
                modern.fetcherConstructor != null && modern.listenerIface != null ->
                hookModernBulkDownload(modern)
            c.vyILoad != null && c.yyM != null && c.yyMDispatcher != null &&
                c.listenerIface != null && c.cloudWmItem != null ->
                hookBulkDownload(c)
            else -> DebugLog.d(TAG, "cloud watermark bulk download unavailable on this build")
        }
    }

    private fun hookDeviceCheck(method: Method, prefKey: String) {
        deoptimize(method)
        method.hook("wm_dev_$prefKey") {
            after { param ->
                if (masterAnd(prefKey)) param.result = true
            }
        }
        DebugLog.d(TAG, "device check hooked ${method.declaringClass.name}#${method.name} -> $prefKey")
    }

    /**
     * Rewrites the restriction fields of every `CloudWatermarkData` right after construction.
     * List fields are mutated through the constructor arguments (shared instances); the long /
     * double fields are written on `thisObject` by type order (first `long` = validFrom, second
     * `long` = validTo, the only `double` = minWmVer — the same declaration order R8 preserves).
     */
    private fun hookCloudDataConstructor(c: ResolvedClasses) {
        val clazz = c.cloudWmData ?: return
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
                if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, false)) return@after
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

                    if (Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_LCC, false)) {
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

                    val thisObj = param.thisObject
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

    /**
     * Rewrites the 22-parameter v3 model after construction. The fields that the v3 cloud
     * filter reads are the same restriction slots as the legacy model:
     * support/unSupport device lists, region lists, name-length range, display apps,
     * validFrom/validTo and the minimum manager version. Resolve each list field by the
     * constructor argument identity so R8 field naming/order changes cannot mis-target it.
     */
    private fun hookModernCloudData(modern: ModernCloud) {
        val constructor = modern.modelConstructor
        deoptimize(constructor)
        constructor.hook("wm_cloud_data_v3") {
            after { param ->
                if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, false)) return@after
                try {
                    val target = param.thisObject
                    val args = param.args
                    val instanceFields = modern.modelClass.declaredFields
                        .filter { !Modifier.isStatic(it.modifiers) }
                    val listFields = instanceFields.filter {
                        List::class.java.isAssignableFrom(it.type)
                    }
                    if (listFields.size < 9) {
                        DebugLog.w(
                            TAG,
                            "v3 cloud model list layout changed (${listFields.size} list fields)"
                        )
                        return@after
                    }

                    fun fieldFor(argumentIndex: Int): Field? =
                        listFieldForArg(listFields, target, args.getOrNull(argumentIndex))
                            ?: listFields.getOrNull(argumentIndex - 10)

                    // Keep the native device lists; per-category gate hooks decide whether a
                    // device-group tag is visible. Only the LCC tags are remapped, matching the
                    // legacy build's explicit LCC switch.
                    if (Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_LCC, false)) {
                        rewriteStringList(fieldFor(13), target) { tag ->
                            if (tag in LCC_DEVICE_TAGS) "*" else tag
                        }
                        rewriteStringList(fieldFor(14), target) { tag ->
                            if (tag in LCC_DEVICE_TAGS) null else tag
                        }
                    }
                    setListField(fieldFor(15), target, listOf("*")) // supportRegions
                    setListField(fieldFor(16), target, emptyList<String>()) // unSupportRegions
                    setListField(fieldFor(17), target, emptyList<Any>()) // name length range
                    val displayAppField = fieldFor(18)
                    val displayApps = (displayAppField?.get(target) as? List<*>)
                        ?.filterIsInstance<String>().orEmpty()
                    setListField(
                        displayAppField,
                        target,
                        (displayApps + listOf("ALL", "Photo", "Editor")).distinct()
                    )

                    val longFields = instanceFields.filter {
                        it.type == Long::class.javaPrimitiveType
                    }
                    longFields.getOrNull(0)?.let { field ->
                        field.isAccessible = true
                        field.setLong(target, 0L)
                    }
                    longFields.getOrNull(1)?.let { field ->
                        field.isAccessible = true
                        field.setLong(target, Long.MAX_VALUE)
                    }
                    instanceFields.firstOrNull {
                        it.type == Double::class.javaPrimitiveType
                    }?.let { field ->
                        field.isAccessible = true
                        field.setDouble(target, 0.0)
                    }
                } catch (t: Throwable) {
                    DebugLog.w(TAG, "v3 cloud watermark field rewrite failed", t)
                }
            }
        }
        DebugLog.i(TAG, "v3 cloud watermark fields hooked on ${modern.modelClass.name}")
    }

    private fun listFieldForArg(fields: List<Field>, target: Any, value: Any?): Field? {
        if (value == null) return null
        return fields.firstOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(target) === value
            }.getOrDefault(false)
        }
    }

    private fun setListField(field: Field?, target: Any, values: List<*>) {
        if (field == null) return
        field.isAccessible = true
        field.set(target, ArrayList(values))
    }

    private fun rewriteStringList(
        field: Field?,
        target: Any,
        transform: (String) -> String?
    ) {
        if (field == null) return
        field.isAccessible = true
        val values = (field.get(target) as? List<*>)
            ?.filterIsInstance<String>()
            ?.mapNotNull(transform)
            ?: return
        field.set(target, ArrayList(values))
    }

    /**
     * The v3 menu builder returns `CloudWatermarkCategory -> [CloudWatermarkItem]`; the fetcher
     * is the instance whose 3-argument method downloads one item. Reuse it for the existing
     * download-all preference, once per editor process.
     */
    private fun hookModernBulkDownload(modern: ModernCloud) {
        val constructor = modern.fetcherConstructor ?: return
        val fetcherMethod = modern.fetcherMethod ?: return
        val listener = modern.listenerIface ?: return
        val itemClass = CLOUD_WM_ITEM.toClassOrNull() ?: return
        val categoryClass = CLOUD_WM_CATEGORY.toClassOrNull() ?: return
        val fetcher = runCatching { constructor.newInstance() }
            .onFailure { DebugLog.w(TAG, "v3 cloud fetcher construction failed", it) }
            .getOrNull() ?: return

        deoptimize(modern.menuBuilder)
        modern.menuBuilder.hook("wm_cloud_menu_download") {
            after { param ->
                if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL, false)) return@after
                if (!downloaded.compareAndSet(false, true)) return@after
                val map = param.result as? Map<*, *> ?: return@after
                val proxy = runCatching {
                    Proxy.newProxyInstance(classLoader, arrayOf(listener)) { _, method, _ ->
                        when (method.name) {
                            "onSuccess" -> DebugLog.d(TAG, "v3 cloud watermark download success")
                            "onFail" -> DebugLog.w(TAG, "v3 cloud watermark download failed")
                        }
                        null
                    }
                }.onFailure {
                    DebugLog.w(TAG, "v3 cloud download listener creation failed", it)
                }.getOrNull() ?: return@after

                var count = 0
                for ((category, items) in map) {
                    if (!categoryClass.isInstance(category) || items !is List<*>) continue
                    for (item in items) {
                        if (!itemClass.isInstance(item)) continue
                        runCatching { fetcherMethod.invoke(fetcher, item, category, proxy) }
                            .onSuccess { count++ }
                            .onFailure {
                                DebugLog.w(TAG, "v3 cloud download dispatch failed", it)
                            }
                    }
                }
                DebugLog.d(TAG, "v3 cloud download dispatched $count watermarks")
            }
        }
        DebugLog.d(
            TAG,
            "v3 cloud bulk download hooked on ${modern.menuBuilder.declaringClass.name}"
        )
    }

    private fun hookBulkDownload(c: ResolvedClasses) {
        val yyM = c.yyM ?: return
        val vyILoad = c.vyILoad ?: return
        val dispatcherMethod = c.yyMDispatcher ?: return
        val listener = c.listenerIface ?: return
        val cloudWmItem = c.cloudWmItem ?: return
        // The resolved dispatcher is a singleton; its instance is the static field of its own type.
        val dispatcherInstance = runCatching {
            val field = yyM.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == yyM
            } ?: return
            field.isAccessible = true
            field.get(null)
        }.onFailure { DebugLog.e(TAG, "resource-dispatcher singleton not found", it) }.getOrNull() ?: return

        vyILoad.hook("wm_bulk_download") {
            after { param ->
                if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL, false)) return@after
                if (!downloaded.compareAndSet(false, true)) return@after
                val map = param.result as? Map<*, *> ?: return@after
                val proxy = try {
                    Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(listener)
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
                        if (item == null || !cloudWmItem.isInstance(item)) continue
                        try {
                            dispatcherMethod.invoke(dispatcherInstance, item, category, proxy)
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
        return Preferences.getBoolean(PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, false) &&
            Preferences.getBoolean(key, false)
    }
}
