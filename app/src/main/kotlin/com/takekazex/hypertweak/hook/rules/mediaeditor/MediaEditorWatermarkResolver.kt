package com.takekazex.hypertweak.hook.rules.mediaeditor

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData

/** Structural discovery and cached-contract validation; performs no hook installation. */
internal class MediaEditorWatermarkResolver(owner: BaseHooker) : HookComponent(owner) {
    private companion object { const val TAG = "WmUnlock" }
    fun resolveClasses(): ResolvedClasses? {
        val apk = hookParam.appInfo?.sourceDir ?: return null
        return resolveDexContract("mediaeditor.watermark_graph", apk, compute = ::scanClasses,
            encode = { target ->
                val modern = target.modernCloud
                DexContractMembers.encode(
                    "filter" to target.vyI0Filter, "data" to target.cloudWmData, "debug" to target.tb0VBInvoke,
                    "load" to target.vyILoad, "dispatch" to target.yyMDispatcher, "fetcher" to target.yyM,
                    "listener" to target.listenerIface, "item" to target.cloudWmItem,
                    "modern.model" to modern?.modelClass, "modern.constructor" to modern?.modelConstructor,
                    "modern.filter" to modern?.filterMethod, "modern.menu" to modern?.menuBuilder,
                    "modern.fetcher" to modern?.fetcherClass, "modern.fetchConstructor" to modern?.fetcherConstructor,
                    "modern.fetch" to modern?.fetcherMethod, "modern.listener" to modern?.listenerIface,
                ) + target.deviceGateMethods.mapKeys { "gate." + it.key }.mapValues { MethodDescriptorCodec.descriptor(it.value) }
            },
            decode = { members ->
                val keys = listOf(PlatformSettingsKeys.KEY_WM_LEICA, PlatformSettingsKeys.KEY_WM_XIAOMI, PlatformSettingsKeys.KEY_WM_REDMI,
                    PlatformSettingsKeys.KEY_WM_POCO, PlatformSettingsKeys.KEY_WM_VICTORIA, PlatformSettingsKeys.KEY_WM_DISNEY3,
                    PlatformSettingsKeys.KEY_WM_LCC, PlatformSettingsKeys.KEY_WM_DISNEY1, PlatformSettingsKeys.KEY_WM_DISNEY2)
                val gates = keys.associateWith { members.method("gate.$it").also { method ->
                    require(Modifier.isStatic(method.modifiers) && method.parameterCount == 0 && method.returnType == Boolean::class.javaPrimitiveType)
                } }
                val data = members.optionalType("data")
                val filter = members.optionalMethod("filter")?.also {
                    val config = requireNotNull(CLOUD_WM_CONFIG.toClassOrNull()); require(isCloudFilterMethod(it, config))
                }
                val debug = members.optionalMethod("debug")?.also { require(isDebugGateMethod(it)) }
                val load = members.optionalMethod("load")?.also { require(isCloudLoaderMethod(it)) }
                val dispatch = members.optionalMethod("dispatch")?.also {
                    require(isResourceDispatcher(it, requireNotNull(WM_ITEM.toClassOrNull()), requireNotNull(WM_CATEGORY.toClassOrNull())))
                }
                val model = members.optionalType("modern.model")
                val modern = model?.let {
                    val constructor = members.constructor("modern.constructor"); require(constructor.declaringClass == model && isModernCloudConstructor(constructor))
                    val menu = members.method("modern.menu"); require(menu.parameterCount == 1 && menu.returnType == LinkedHashMap::class.java)
                    val modernFilter = members.method("modern.filter")
                    require(Modifier.isStatic(modernFilter.modifiers) && modernFilter.parameterTypes.contentEquals(arrayOf(menu.parameterTypes[0], List::class.java)) && modernFilter.returnType == menu.parameterTypes[0])
                    val fetcher = members.optionalType("modern.fetcher")
                    val fetchConstructor = members.optionalConstructor("modern.fetchConstructor")
                    val fetch = members.optionalMethod("modern.fetch")
                    val listener = members.optionalType("modern.listener")
                    require((fetcher == null && fetchConstructor == null && fetch == null && listener == null) ||
                        (fetcher != null && fetchConstructor?.declaringClass == fetcher && fetch?.declaringClass == fetcher && listener?.isInterface == true))
                    ModernCloud(model, constructor, modernFilter, menu, fetcher, fetchConstructor, fetch, listener)
                }
                ResolvedClasses(gates, filter, data, debug, load, dispatch, members.optionalType("fetcher"),
                    members.optionalType("listener"), members.optionalType("item"), modern)
            },
        )
    }

    private fun scanClasses(bridge: DexKitBridge): ResolvedClasses? {
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
        val resolved = DexRuntime.resolveClasses(
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

        val gateAndModern = run graph@ {
            val gates = resolveDeviceGateMethods(bridge, wnA, znA) ?: return@graph null
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
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_LEICA) {
                listFieldCount(it, wnOwner) == 3
            },
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_XIAOMI) {
                listFieldCount(it, wnOwner) == 1 && invokesOwner(it, wnOwner)
            },
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_REDMI) {
                listFieldCount(it, wnOwner) == 4
            },
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_POCO) {
                listFieldCount(it, wnOwner) == 1 && !invokesOwner(it, wnOwner)
            },
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_VICTORIA) {
                ownFields(it, wnOwner).size == 1 && listFieldCount(it, wnOwner) == 0
            },
            resolveUnique(wnOwner.name, wnMethods, PlatformSettingsKeys.KEY_WM_DISNEY3) {
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
                decoded.any { it.equals("LCC", ignoreCase = true) } -> PlatformSettingsKeys.KEY_WM_LCC
                decoded.any { it.equals("WestCoast-II", ignoreCase = true) } -> PlatformSettingsKeys.KEY_WM_DISNEY2
                decoded.any { it.equals("WestCoast", ignoreCase = true) } -> PlatformSettingsKeys.KEY_WM_DISNEY1
                else -> null
            } ?: return@forEach
            val method = materializeGateMethod(data) ?: return@forEach
            if (markerPreferences.putIfAbsent(preference, method) != null) {
                DebugLog.e(TAG, "multiple DexKit theme gates match $preference")
                return null
            }
        }

        listOf(
            PlatformSettingsKeys.KEY_WM_LCC,
            PlatformSettingsKeys.KEY_WM_DISNEY1,
            PlatformSettingsKeys.KEY_WM_DISNEY2
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

}
