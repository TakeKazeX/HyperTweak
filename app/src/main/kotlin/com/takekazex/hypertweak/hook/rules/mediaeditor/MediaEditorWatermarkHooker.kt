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
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
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
 *    tapped. With [Preferences.KEY_WM_DOWNLOAD_ALL] on, the menu-builder after-hook walks the
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

    // Stable (non-obfuscated) business classes of the editor.
    private const val CLOUD_WM_DATA = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkData"
    private const val CLOUD_WM_CONFIG = "com.miui.mediaeditor.photo.watermark.model.cloudwatermark.CloudWatermarkConfigData"
    private const val WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkItem"
    private const val WM_CATEGORY = "com.miui.mediaeditor.photo.watermark.model.menu.WatermarkCategory"
    private const val CLOUD_WM_ITEM = "com.miui.mediaeditor.photo.watermark.model.menu.CloudWatermarkItem"
    private const val CLOUD_WM_CATEGORY =
        "com.miui.mediaeditor.photo.watermark.model.menu.CloudWatermarkCategory"

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

    private val LCC_DEVICE_TAGS = setOf(
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
    private class ResolvedClasses(
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
    private class ModernCloud(
        val modelClass: Class<*>,
        val modelConstructor: Constructor<*>,
        val filterMethod: Method,
        val menuBuilder: Method,
        val fetcherClass: Class<*>?,
        val fetcherConstructor: Constructor<*>?,
        val fetcherMethod: Method?,
        val listenerIface: Class<*>?
    )

    private class ModernFetcher(
        val type: Class<*>,
        val constructor: Constructor<*>,
        val method: Method,
        val listenerIface: Class<*>
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

        // 2.10.43 removed the stable cloud model classes. Keep their DexKit keys optional and
        // resolve the replacement model from the menu builder instead.
        val cloudPipelineAvailable =
            CLOUD_WM_DATA.toClassOrNull() != null && CLOUD_WM_CONFIG.toClassOrNull() != null
        val queries = buildMap<String, (DexKitBridge) -> String?> {
            put(WN_GATE_CACHE_KEY) { bridge -> resolveWnGateOwner(bridge) }
            put(ZN_GATE_CACHE_KEY) { bridge -> resolveZnGateOwner(bridge) }
            put(DEBUG_GATE_CACHE_KEY) { bridge -> resolveDebugGateOwner(bridge) }
            if (cloudPipelineAvailable) {
                put(CLOUD_FILTER_CACHE_KEY) { bridge -> resolveCloudFilterOwner(bridge) }
                put(CLOUD_LOADER_CACHE_KEY) { bridge -> resolveCloudLoaderOwner(bridge) }
                put(FETCHER_CACHE_KEY) { bridge -> resolveResourceFetcherOwner(bridge) }
            }
        }
        val resolved = DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = queries,
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

        val gateAndModern = DexKitManager.withBridge(apkPath) { bridge ->
            val gates = resolveDeviceGateMethods(bridge, wnA, znA) ?: return@withBridge null
            val modern = if (!cloudPipelineAvailable) {
                runCatching { resolveModernCloud(bridge) }
                    .onFailure { DebugLog.w(TAG, "modern cloud model resolution failed", it) }
                    .getOrNull()
            } else {
                null
            }
            gates to modern
        } ?: run {
            DebugLog.e(TAG, "DexKit device/theme gate methods could not be resolved")
            return null
        }
        val deviceGateMethods = gateAndModern.first
        val modernCloud = gateAndModern.second

        // Cloud model classes disappeared in media editor 2.10.43; keep them optional.
        val cloudWmData = CLOUD_WM_DATA.toClassOrNull()
        val cloudWmConfig = CLOUD_WM_CONFIG.toClassOrNull()
        val wmItem = WM_ITEM.toClassOrNull()
        val wmCategory = WM_CATEGORY.toClassOrNull()
        val cloudWmItem = CLOUD_WM_ITEM.toClassOrNull()

        val cloudFilterClass = resolved[CLOUD_FILTER_CACHE_KEY]
        val vyI0Filter = if (cloudFilterClass != null && cloudWmConfig != null) {
            cloudFilterClass.declaredMethods.singleOrNull { isCloudFilterMethod(it, cloudWmConfig) }
        } else null

        val vyILoad = resolved[CLOUD_LOADER_CACHE_KEY]
            ?.declaredMethods?.singleOrNull(::isCloudLoaderMethod)

        val yyM = resolved[FETCHER_CACHE_KEY]
        val yyMDispatcher = if (yyM != null && wmItem != null && wmCategory != null) {
            yyM.declaredMethods.singleOrNull { isResourceDispatcher(it, wmItem, wmCategory) }
        } else null
        val listenerIface = yyMDispatcher?.parameterTypes?.getOrNull(2)

        val tb0VBInvoke = resolved[DEBUG_GATE_CACHE_KEY]
            ?.declaredMethods?.singleOrNull(::isDebugGateMethod)

        if (cloudWmData == null || cloudWmConfig == null) {
            if (modernCloud == null) {
                DebugLog.w(
                    TAG,
                    "cloud watermark pipeline is neither the legacy nor the v3 model; " +
                        "installing device/theme gates only"
                )
            } else {
                DebugLog.i(
                    TAG,
                    "v3 cloud watermark model resolved: ${modernCloud.modelClass.name}"
                )
            }
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
            cloudWmItem = cloudWmItem,
            modernCloud = modernCloud
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
                addUsingString("camera.cloud.watermark.debug", StringMatchType.Equals)
            }
        }.toList().filter { data ->
            data.paramCount == 0 && data.returnTypeName in setOf(
                Boolean::class.javaObjectType.name,
                Any::class.java.name
            )
        }.map { it.className }.distinct().singleOrNull()

    /**
     * Resolve the 2.10.43+ cloud graph without storing obfuscated names:
     * the menu builder is the only method constructing both stable menu classes, its parameter
     * type is the config root, and the item model is one of the field owners it reads. The
     * filter is the static `(config, List) -> config` method in that builder class.
     */
    private fun resolveModernCloud(bridge: DexKitBridge): ModernCloud? {
        val menuBuilderData = bridge.findMethod {
            matcher {
                paramCount(1)
                returnType(LinkedHashMap::class.java)
            }
        }.toList().singleOrNull { data ->
            data.invokes.any { it.className == CLOUD_WM_ITEM && it.methodName == "<init>" } &&
                data.invokes.any { it.className == CLOUD_WM_CATEGORY && it.methodName == "<init>" }
        } ?: run {
            DebugLog.w(TAG, "v3 cloud menu builder not found")
            return null
        }

        val configName = menuBuilderData.paramTypeNames.singleOrNull() ?: return null
        val configClass = runCatching { classLoader.loadClass(configName) }.getOrNull() ?: return null
        val menuBuilderClass = runCatching { classLoader.loadClass(menuBuilderData.className) }
            .getOrNull() ?: return null
        val filterMethod = menuBuilderClass.declaredMethods.singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(configClass, List::class.java)) &&
                method.returnType == configClass
        } ?: run {
            DebugLog.w(TAG, "v3 cloud filter method not found")
            return null
        }

        val modelClass = findModernModelClass(bridge, menuBuilderData, configClass) ?: run {
            DebugLog.w(TAG, "v3 cloud item model is ambiguous or missing")
            return null
        }
        val modelConstructor = modelClass.declaredConstructors
            .singleOrNull(::isModernCloudConstructor) ?: run {
                DebugLog.w(TAG, "v3 cloud item constructor is ambiguous or missing")
                return null
            }
        val menuBuilder = runCatching { menuBuilderData.getMethodInstance(classLoader) }
            .getOrNull() ?: return null
        val fetcher = resolveModernFetcher(bridge)

        return ModernCloud(
            modelClass = modelClass,
            modelConstructor = modelConstructor,
            filterMethod = filterMethod,
            menuBuilder = menuBuilder,
            fetcherClass = fetcher?.type,
            fetcherConstructor = fetcher?.constructor,
            fetcherMethod = fetcher?.method,
            listenerIface = fetcher?.listenerIface
        )
    }

    private fun findModernModelClass(
        bridge: DexKitBridge,
        menuBuilder: MethodData,
        configClass: Class<*>
    ): Class<*>? {
        val fromFieldOwners = menuBuilder.usingFields.asSequence()
            .map { it.field.declaredClassName }
            .distinct()
            .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
            .filter { isModernCloudModel(it, configClass) }
            .toList()
            .singleOrNull()
        if (fromFieldOwners != null) return fromFieldOwners

        // Fallback for DexKit builds that omit field-owner data from usingFields: the model is
        // the unique class with a 22-parameter void constructor matching the v3 shape.
        return bridge.findClass {
            matcher {
                methods {
                    add {
                        paramCount(22)
                        returnType(Void.TYPE)
                    }
                }
            }
        }.toList().asSequence()
            .mapNotNull { data -> runCatching { data.getInstance(classLoader) }.getOrNull() }
            .filter { isModernCloudModel(it, configClass) }
            .singleOrNull()
    }

    private fun resolveModernFetcher(bridge: DexKitBridge): ModernFetcher? {
        val itemClass = CLOUD_WM_ITEM.toClassOrNull() ?: return null
        val categoryClass = CLOUD_WM_CATEGORY.toClassOrNull() ?: return null
        val data = bridge.findMethod {
            matcher {
                paramCount(3)
                returnType(Void.TYPE)
                addUsingString(
                    "fetch failed, watermark item or watermark category type is invalid",
                    StringMatchType.Equals
                )
            }
        }.toList().singleOrNull() ?: return null
        val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull() ?: return null
        val parameters = method.parameterTypes
        if (parameters.size != 3 || !parameters[0].isAssignableFrom(itemClass) ||
            !parameters[1].isAssignableFrom(categoryClass) || !parameters[2].isInterface
        ) {
            return null
        }
        val constructor = method.declaringClass.declaredConstructors
            .singleOrNull { it.parameterCount == 0 } ?: return null
        return ModernFetcher(method.declaringClass, constructor, method, parameters[2])
    }

    private fun isModernCloudModel(type: Class<*>, configClass: Class<*>): Boolean {
        if (type == configClass || type.isInterface || type.isEnum) return false
        val instanceFields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        return instanceFields.count { it.type == Long::class.javaPrimitiveType } == 2 &&
            instanceFields.count { it.type == Double::class.javaPrimitiveType } == 1 &&
            instanceFields.count { List::class.java.isAssignableFrom(it.type) } >= 7 &&
            instanceFields.size >= 18 &&
            type.declaredConstructors.any(::isModernCloudConstructor)
    }

    private fun isModernCloudConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        return types.size == 22 &&
            types[0] == String::class.java &&
            types[1] == String::class.java &&
            types[2] == List::class.java &&
            types[3] == List::class.java &&
            types[4] == String::class.java &&
            types[5] == String::class.java &&
            types[6] == Long::class.javaPrimitiveType &&
            types[7] == Long::class.javaPrimitiveType &&
            types[8] == String::class.java &&
            types[9] == String::class.java &&
            types[10] == String::class.java &&
            types[11] == Boolean::class.javaPrimitiveType &&
            (12..18).all { types[it] == List::class.java } &&
            types[19] == Double::class.javaPrimitiveType &&
            !types[20].isPrimitive &&
            types[20] != String::class.java &&
            types[21] == Boolean::class.javaPrimitiveType
    }

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
            (method.returnType == Boolean::class.javaObjectType ||
                method.returnType == Any::class.java)

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
                if (Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false)) {
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
                if (!Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false)) return@after
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
                    if (Preferences.getBoolean(Preferences.KEY_WM_LCC, false)) {
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
                if (!Preferences.getBoolean(Preferences.KEY_WM_DOWNLOAD_ALL, false)) return@after
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
                if (!Preferences.getBoolean(Preferences.KEY_WM_DOWNLOAD_ALL, false)) return@after
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
        return Preferences.getBoolean(Preferences.KEY_WM_UNLOCK_MASTER, false) &&
            Preferences.getBoolean(key, false)
    }
}
