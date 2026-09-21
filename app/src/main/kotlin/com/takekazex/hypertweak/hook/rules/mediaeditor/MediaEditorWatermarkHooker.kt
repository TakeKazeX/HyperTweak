package com.takekazex.hypertweak.hook.rules.mediaeditor

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
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
 * Watermark visibility and resource availability are handled by four hook paths:
 *
 * 1. **Device checks** — DexKit locates the two gate owners by class shape, then resolves their
 *    parameterless Boolean methods by field-use profiles. Theme gates are mapped by decoding
 *    embedded values through the string decoder they invoke. These checks are shared by the local
 *    template menu and cloud config filter, so each preference controls both paths. Ambiguous or
 *    changed shapes fail closed. The separate global menu-suppression gate is excluded.
 *
 * 2. **Cloud config fields** — `CloudWatermarkData` (Kotlin data class, business name kept)
 *    carries per-watermark restriction fields parsed from the `watermark_config_v2` cloud
 *    config: `supportDeviceList`/`unSupportDeviceList` (device-group tags), `supportRegions`/
 *    `unSupportRegions` (country codes), `validFrom`/`validTo` (festival time windows),
 *    `name_length_limitation`, `minWmVer` and `supportDisplayApp`. DexKit resolves the cloud
 *    filter by its configuration/list signature and device-group markers. The constructor after-hook
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
 *    `files/watermarks/`, the resource manager re-scans the folder and applies a second filter
 *    chain (id whitelist / validity / device_type / region / theme / system properties / name
 *    length). The chain is skipped when `camera.cloud.watermark.debug` is true. DexKit resolves
 *    the Boolean property reader by its marker and return shape.
 *
 * 4. **Bulk download** — cloud watermark zips are normally fetched on demand when a menu item is
 *    tapped. With [Preferences.KEY_WM_DOWNLOAD_ALL] on, the DexKit-resolved cloud menu loader
 *    after-hook walks the freshly built menu and dispatches every `CloudWatermarkItem` through the
 *    resolved resource dispatcher (listener supplied via a dynamic proxy).
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

    private const val WN_GATE_CACHE_KEY = "wmDeviceGateOwner"
    private const val ZN_GATE_CACHE_KEY = "wmThemeGateOwner"
    private const val CLOUD_FILTER_CACHE_KEY = "wmCloudFilterOwner"
    private const val CLOUD_LOADER_CACHE_KEY = "wmCloudLoaderOwner"
    private const val FETCHER_CACHE_KEY = "wmResourceFetcherOwner"
    private const val DEBUG_GATE_CACHE_KEY = "wmDebugGateOwner"

    private val DEVICE_GROUP_MARKERS = listOf(
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

    private class ResolvedClasses(
        val deviceGateMethods: Map<String, Method>,
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

        val resolved = DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                WN_GATE_CACHE_KEY to { bridge -> resolveWnGateOwner(bridge) },
                ZN_GATE_CACHE_KEY to { bridge -> resolveZnGateOwner(bridge) },
                CLOUD_FILTER_CACHE_KEY to { bridge -> resolveCloudFilterOwner(bridge) },
                CLOUD_LOADER_CACHE_KEY to { bridge -> resolveCloudLoaderOwner(bridge) },
                FETCHER_CACHE_KEY to { bridge -> resolveResourceFetcherOwner(bridge) },
                DEBUG_GATE_CACHE_KEY to { bridge -> resolveDebugGateOwner(bridge) }
            ),
            validators = mapOf(
                WN_GATE_CACHE_KEY to ::isWnGateClass,
                ZN_GATE_CACHE_KEY to ::isZnGateClass,
                CLOUD_FILTER_CACHE_KEY to ::isCloudFilterClass,
                CLOUD_LOADER_CACHE_KEY to ::isCloudLoaderClass,
                FETCHER_CACHE_KEY to ::isResourceFetcherClass,
                DEBUG_GATE_CACHE_KEY to ::isDebugGateClass
            )
        )
        val wnA = resolved[WN_GATE_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit device-gate class is ambiguous or missing")
            return null
        }
        val znA = resolved[ZN_GATE_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit theme-gate class is ambiguous or missing")
            return null
        }

        val deviceGateMethods = DexKitManager.withBridge(apkPath) { bridge ->
            resolveDeviceGateMethods(bridge, wnA, znA)
        } ?: run {
            DebugLog.e(TAG, "DexKit device/theme gate methods could not be resolved")
            return null
        }

        val cloudWmData = CLOUD_WM_DATA.toClass()
        val cloudWmConfig = CLOUD_WM_CONFIG.toClass()
        val wmItem = WM_ITEM.toClass()
        val wmCategory = WM_CATEGORY.toClass()
        val cloudWmItem = CLOUD_WM_ITEM.toClass()

        val cloudFilterClass = resolved[CLOUD_FILTER_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit cloud-filter class is ambiguous or missing")
            return null
        }
        val vyI0Filter = cloudFilterClass.declaredMethods.singleOrNull {
            isCloudFilterMethod(it, cloudWmConfig)
        } ?: run {
            DebugLog.e(TAG, "DexKit cloud-filter method shape changed or is ambiguous")
            return null
        }

        val cloudLoaderClass = resolved[CLOUD_LOADER_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit cloud-loader class is ambiguous or missing")
            return null
        }
        val vyILoad = cloudLoaderClass.declaredMethods.singleOrNull(::isCloudLoaderMethod) ?: run {
            DebugLog.e(TAG, "DexKit cloud-loader method shape changed or is ambiguous")
            return null
        }

        val yyM = resolved[FETCHER_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit resource-fetcher class is ambiguous or missing")
            return null
        }
        val yyMDispatcher = yyM.declaredMethods.singleOrNull {
            isResourceDispatcher(it, wmItem, wmCategory)
        } ?: run {
            DebugLog.e(TAG, "DexKit resource dispatcher method shape changed or is ambiguous")
            return null
        }
        val listenerIface = yyMDispatcher.parameterTypes[2]

        val debugGateClass = resolved[DEBUG_GATE_CACHE_KEY] ?: run {
            DebugLog.e(TAG, "DexKit debug-gate class is ambiguous or missing")
            return null
        }
        val tb0VBInvoke = debugGateClass.declaredMethods.singleOrNull(::isDebugGateMethod) ?: run {
            DebugLog.e(TAG, "DexKit debug-gate method shape changed or is ambiguous")
            return null
        }

        return ResolvedClasses(
            deviceGateMethods = deviceGateMethods,
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

    /** Resolve category gates from their DEX usage and decoded theme markers, never method names. */
    private fun resolveDeviceGateMethods(
        bridge: DexKitBridge,
        wnOwner: Class<*>,
        znOwner: Class<*>
    ): Map<String, Method>? {
        val wnMethods = findStaticBooleanNoArgMethods(bridge, wnOwner)
        val znMethods = findStaticBooleanNoArgMethods(bridge, znOwner)
        val result = linkedMapOf<String, Method>()

        fun resolveUnique(
            ownerName: String,
            candidates: List<MethodData>,
            preference: String,
            predicate: (MethodData) -> Boolean
        ): Boolean {
            val method = candidates.asSequence()
                .filter(predicate)
                .mapNotNull(::materializeGateMethod)
                .singleOrNull()
            if (method == null) {
                DebugLog.e(TAG, "DexKit $ownerName gate for $preference is ambiguous or missing")
                return false
            }
            result[preference] = method
            return true
        }

        fun ownFields(data: MethodData, owner: Class<*>): List<org.luckypray.dexkit.result.FieldData> =
            data.usingFields.asSequence()
                .map { it.field }
                .filter { it.declaredClassName == owner.name && Modifier.isStatic(it.modifiers) }
                .distinctBy { it.descriptor }
                .toList()

        fun listFieldCount(data: MethodData, owner: Class<*>): Int =
            ownFields(data, owner).count { it.typeName == List::class.java.name }

        fun invokesOwner(data: MethodData, owner: Class<*>): Boolean =
            data.invokes.any { it.className == owner.name }

        fun referencesOwner(data: MethodData, owner: Class<*>): Boolean =
            data.usingFields.any { it.field.declaredClassName == owner.name }

        // The six device gates have distinct DEX profiles: 3 and 4 list fields, one list with
        // and without a same-owner call, one lazy value, and one reference to the theme-gate owner.
        val wnResolved = listOf(
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_LEICA) {
                listFieldCount(it, wnOwner) == 3
            },
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_XIAOMI) {
                listFieldCount(it, wnOwner) == 1 && invokesOwner(it, wnOwner)
            },
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_REDMI) {
                listFieldCount(it, wnOwner) == 4
            },
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_POCO) {
                listFieldCount(it, wnOwner) == 1 && !invokesOwner(it, wnOwner)
            },
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_VICTORIA) {
                ownFields(it, wnOwner).size == 1 && listFieldCount(it, wnOwner) == 0
            },
            resolveUnique(wnOwner.name, wnMethods, Preferences.KEY_WM_DISNEY3) {
                listFieldCount(it, wnOwner) == 0 && referencesOwner(it, znOwner)
            }
        ).all { it }
        if (!wnResolved) return null

        val markerPreferences = mutableMapOf<String, Method>()
        znMethods.forEach { data ->
            val referencedOwnerFields = ownFields(data, znOwner)
            if (referencedOwnerFields.size != 1) return@forEach
            val helperType = runCatching {
                classLoader.loadClass(referencedOwnerFields.single().typeName)
            }.getOrNull() ?: return@forEach
            val helperBody = bridge.findMethod {
                matcher {
                    declaredClass(helperType)
                    paramCount(1)
                    returnType(Any::class.java)
                }
            }.toList().singleOrNull { helperData ->
                helperData.className == helperType.name &&
                    helperData.paramTypeNames == listOf(Any::class.java.name) &&
                    helperData.returnTypeName == Any::class.java.name
            } ?: return@forEach

            val decoded = decodeGateStrings(bridge, helperBody)
            val preference = when {
                decoded.any { it.equals("LCC", ignoreCase = true) } -> Preferences.KEY_WM_LCC
                decoded.any { it.equals("WestCoast-II", ignoreCase = true) } -> Preferences.KEY_WM_DISNEY2
                decoded.any { it.equals("WestCoast", ignoreCase = true) } -> Preferences.KEY_WM_DISNEY1
                else -> null
            } ?: return@forEach
            val method = materializeGateMethod(data) ?: return@forEach
            if (markerPreferences.putIfAbsent(preference, method) != null) {
                DebugLog.e(TAG, "multiple DexKit theme gates match $preference")
                return null
            }
        }

        listOf(
            Preferences.KEY_WM_LCC,
            Preferences.KEY_WM_DISNEY1,
            Preferences.KEY_WM_DISNEY2
        ).forEach { preference ->
            val method = markerPreferences[preference]
            if (method == null) {
                DebugLog.e(TAG, "DexKit decoded theme gate for $preference is missing")
                return null
            }
            result[preference] = method
        }

        return result.takeIf { it.size == 9 }
    }

    private fun findStaticBooleanNoArgMethods(
        bridge: DexKitBridge,
        owner: Class<*>
    ): List<MethodData> = bridge.findMethod {
        matcher {
            declaredClass(owner)
            modifiers(Modifier.STATIC, MatchType.Contains)
            paramCount(0)
            returnType(Boolean::class.javaPrimitiveType!!)
        }
    }.toList().filter { data ->
        data.className == owner.name &&
            data.paramCount == 0 &&
            data.returnTypeName == Boolean::class.javaPrimitiveType!!.name &&
            Modifier.isStatic(data.modifiers)
    }

    private fun decodeGateStrings(bridge: DexKitBridge, method: MethodData): Set<String> {
        val encodedStrings = method.usingStrings
        if (encodedStrings.isEmpty()) return emptySet()
        val decoders = method.invokes.toList().filter { data ->
            Modifier.isStatic(data.modifiers) &&
                data.paramTypeNames == listOf(String::class.java.name) &&
                data.returnTypeName == String::class.java.name
        }.mapNotNull(::materializeGateMethod)
        if (decoders.isEmpty()) return emptySet()
        return decoders.flatMap { decoder ->
            encodedStrings.mapNotNull { encoded ->
                runCatching { decoder.invoke(null, encoded) as? String }.getOrNull()
            }
        }.toSet()
    }

    private fun materializeGateMethod(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader).apply { isAccessible = true }
    }.onFailure {
        DebugLog.w(TAG, "failed to materialize DexKit gate ${data.className}#${data.methodName}", it)
    }.getOrNull()

    private fun resolveWnGateOwner(bridge: DexKitBridge): String? =
        bridge.findClass {
            matcher {
                fields {
                    matchType(MatchType.Contains)
                    countMin(6)
                    addForType(List::class.java)
                }
                methods {
                    matchType(MatchType.Contains)
                    countMin(6)
                    add {
                        modifiers(Modifier.STATIC, MatchType.Contains)
                        paramCount(0)
                        returnType(Boolean::class.javaPrimitiveType!!)
                    }
                }
            }
        }.toList().asSequence()
            .mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
            .filter(::isWnGateClass)
            .map { it.name }
            .singleOrNull()

    private fun resolveZnGateOwner(bridge: DexKitBridge): String? =
        bridge.findClass {
            matcher {
                fields {
                    matchType(MatchType.Contains)
                    countMin(5)
                    addForType("java.util.HashSet")
                }
                methods {
                    matchType(MatchType.Contains)
                    countMin(8)
                    add {
                        modifiers(Modifier.STATIC, MatchType.Contains)
                        paramCount(0)
                        returnType(Boolean::class.javaPrimitiveType!!)
                    }
                }
            }
        }.toList().asSequence()
            .mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
            .filter(::isZnGateClass)
            .map { it.name }
            .singleOrNull()

    private fun resolveCloudFilterOwner(bridge: DexKitBridge): String? {
        val cloudConfig = CLOUD_WM_CONFIG.toClassOrNull() ?: return null
        return bridge.findMethod {
            matcher {
                modifiers(Modifier.STATIC, MatchType.Contains)
                paramTypes(cloudConfig, List::class.java)
                returnType(cloudConfig)
                DEVICE_GROUP_MARKERS.forEach { marker ->
                    addUsingString(marker, StringMatchType.Equals)
                }
            }
        }.toList().filter { data ->
            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
            method != null && isCloudFilterMethod(method, cloudConfig)
        }.map { it.className }.distinct().singleOrNull()
    }

    private fun resolveCloudLoaderOwner(bridge: DexKitBridge): String? =
        bridge.findClass {
            matcher { usingStrings("leica_1") }
        }.toList().asSequence()
            .mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
            .filter(::isCloudLoaderClass)
            .map { it.name }
            .distinct()
            .singleOrNull()

    private fun resolveResourceFetcherOwner(bridge: DexKitBridge): String? {
        val itemName = WM_ITEM
        val categoryName = WM_CATEGORY
        return bridge.findMethod {
            matcher {
                paramCount(3)
                returnType(Void.TYPE)
                addParamType(itemName)
                addParamType(categoryName)
            }
        }.toList().filter { data ->
            val types = data.paramTypeNames
            types.size == 3 && types[0] == itemName && types[1] == categoryName &&
                runCatching { classLoader.loadClass(types[2]).isInterface }.getOrDefault(false)
        }.map { it.className }.distinct().singleOrNull()
    }

    private fun resolveDebugGateOwner(bridge: DexKitBridge): String? =
        bridge.findMethod {
            matcher {
                paramCount(0)
                returnType(Boolean::class.javaObjectType)
                addUsingString("camera.cloud.watermark.debug", StringMatchType.Equals)
            }
        }.toList().filter { data ->
            data.paramCount == 0 && data.returnTypeName == Boolean::class.javaObjectType.name
        }.map { it.className }.distinct().singleOrNull()

    private fun isWnGateClass(type: Class<*>): Boolean =
        type.declaredFields.count {
            Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
        } >= 6 && type.declaredMethods.count {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        } >= 6

    private fun isZnGateClass(type: Class<*>): Boolean =
        type.declaredFields.count {
            Modifier.isStatic(it.modifiers) && it.type.name == "java.util.HashSet"
        } >= 5 && type.declaredMethods.count {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                it.returnType == Boolean::class.javaPrimitiveType
        } >= 8

    private fun isCloudFilterClass(type: Class<*>): Boolean {
        val cloudConfig = CLOUD_WM_CONFIG.toClassOrNull() ?: return false
        return type.declaredMethods.count { isCloudFilterMethod(it, cloudConfig) } == 1
    }

    private fun isCloudFilterMethod(method: Method, cloudConfig: Class<*>?): Boolean =
        cloudConfig != null && Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(cloudConfig, List::class.java)) &&
            method.returnType == cloudConfig

    private fun isCloudLoaderClass(type: Class<*>): Boolean =
        type.declaredMethods.count(::isCloudLoaderMethod) == 1

    private fun isCloudLoaderMethod(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.contentEquals(arrayOf(List::class.java, Boolean::class.javaPrimitiveType)) &&
            method.returnType == java.util.LinkedHashMap::class.java

    private fun isResourceFetcherClass(type: Class<*>): Boolean =
        type.declaredMethods.count { method ->
            val parameters = method.parameterTypes
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                parameters.size == 3 && parameters[0].name == WM_ITEM &&
                parameters[1].name == WM_CATEGORY && parameters[2].isInterface
        } == 1

    private fun isResourceDispatcher(method: Method, item: Class<*>, category: Class<*>): Boolean {
        val parameters = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
            parameters.size == 3 && parameters[0] == item && parameters[1] == category &&
            parameters[2].isInterface
    }

    private fun isDebugGateClass(type: Class<*>): Boolean =
        type.declaredMethods.count(::isDebugGateMethod) == 1

    private fun isDebugGateMethod(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) && method.parameterCount == 0 &&
            method.returnType == Boolean::class.javaObjectType

    // ─── Hook installation ─────────────────────────────────────────────────────

    private fun installHooks(c: ResolvedClasses) {
        // 1. Per-category device checks (shared by local menu + cloud filter), resolved by DEX.
        c.deviceGateMethods.forEach { (prefKey, method) -> hookDeviceCheck(method, prefKey) }

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

    private fun hookBulkDownload(c: ResolvedClasses) {
        // The resolved dispatcher is a singleton; its instance is the static field of its own type.
        val dispatcherInstance = runCatching {
            val field = c.yyM.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == c.yyM
            } ?: return
            field.isAccessible = true
            field.get(null)
        }.onFailure { DebugLog.e(TAG, "resource-dispatcher singleton not found", it) }.getOrNull() ?: return

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
