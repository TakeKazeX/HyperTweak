package com.takekazex.hypertweak.hook.rules.system

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import java.util.concurrent.CompletableFuture
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.rules.bluetooth.AirPodsScope
import org.luckypray.dexkit.DexKitBridge

/** Hooks the concrete AirPods and MiLink paths found in the shipped APKs. */
object SpatialAudioBlockerHooker : StaticHooker() {
    private const val TAG = "HyperTweak"

    override fun onHook() {
        runCatching {
            if (hookParam.packageName == "com.xiaomi.bluetooth") hookBluetooth()
            if (hookParam.packageName == "com.milink.service") hookMiLink()
        }.onFailure { Log.e(TAG, "spatial/anc hook setup failed", it) }
    }

    private fun hookBluetooth() {
        // C5604b.m17767r(BluetoothDevice, String, String), logged as AirCoreManager.setCommand.
        val airCore = findFirstClass("p145l1.C5604b", "l1.C1554b")
            ?: resolveClass("p145l1.C5604b", "AirCoreManager", "setCommand")
        airCore?.declaredMethods?.filter { it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == android.bluetooth.BluetoothDevice::class.java &&
            it.parameterTypes[1] == String::class.java && it.parameterTypes[2] == String::class.java
        }?.forEach { method -> method.hook {
            before { param -> runCatching {
                // The transport class is AirPods-oriented, but its device argument is still the
                // authority: do not rewrite a common/non-AirPods call just because it has a device.
                if (!isAirPodsTransport(param.thisObject, param.args)) return@runCatching
                normalizeValue(param.args, 1, 2)
            }.onFailure { Log.e(TAG, "AirCore command conversion failed", it) } }
        } }

        // C6409a is the AirpodsModel persistence boundary used by get/set/notify Bundle calls.
        // OS4.0.0.24 moved it to a static utility (AirLocalStorage -> f2.a); the dex names stay
        // stable but DexKit's broad string markers now collide with unrelated classes, so resolve
        // with a unique device/VidPid marker and a semantic (method-shape) validator.
        val storage = findFirstClass("p156n1.C6409a", "n1.C1582a")
            ?: resolveAirPodsClass(
                "p156n1.C6409a",
                listOf(
                    arrayOf("not bonded device clear: ", "isFeatureSupport"),
                    arrayOf("AirLocalStorage", "AirpodsModel"),
                ),
            ) { it.declaredMethods.any { m -> m.parameterTypes.any { p -> p == android.bluetooth.BluetoothDevice::class.java } } }
        storage?.declaredMethods
            ?.filter { it.parameterTypes.any { parameter -> parameter == android.bluetooth.BluetoothDevice::class.java } }
            ?.forEach { method -> method.hook {
                before { param -> runCatching {
                    if (!isAirPodsTransport(param.thisObject, param.args)) return@runCatching
                    findKeyValueIndexes(param.args)?.let { (keyIndex, valueIndex) ->
                        normalizeValue(param.args, keyIndex, valueIndex)
                    }
                } }
                after { param -> runCatching {
                    if (!isAirPodsTransport(param.thisObject, param.args)) return@runCatching
                    param.result = normalizeResult(param.result, keyFrom(param.args), *param.args)
                } }
            } }

        // Repository provider path carries the same key/value in a Bundle. On OS4.0.0.24 the
        // "airpodsRepository"/"send_command" strings moved into the content-provider dispatch
        // helper (h1.a) and the old class name now DexKit-resolves to an empty shell, so use the
        // unique AirRepository_BT tag + a Bundle-method validator.
        (findFirstClass("p169q0.C6614a", "q0.C1602a") ?: resolveAirPodsClass(
            "p169q0.C6614a",
            listOf(
                arrayOf("AirRepository_BT", "send_command"),
                arrayOf("airpodsRepository", "send_command"),
            ),
        ) { it.declaredMethods.any { m -> m.parameterTypes.any { p -> p == Bundle::class.java } } })
            ?.declaredMethods?.filter { it.parameterTypes.any { p -> p == Bundle::class.java } }
            ?.forEach { method -> method.hook { before { param -> runCatching {
                val bundle = param.args.lastOrNull { it is Bundle } as? Bundle ?: return@runCatching
                normalizeBundle(bundle, *param.args)
            } }
            after { param -> runCatching {
                val bundle = param.result as? Bundle ?: return@runCatching
                normalizeBundle(bundle, *param.args)
            } } } }
    }

    private fun hookMiLink() {
        findClass("com.miui.headset.runtime.AncBatteryController")?.let { type ->
            type.declaredMethods.filter { it.name == "setHeadTracking" }.forEach { method -> method.hook {
                before { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        param.result = 201
                    }
                } }
            } }
            type.declaredMethods.filter { it.name == "setMiAudioEffect" }.forEach { method -> method.hook {
                before { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args) && param.args.size > 1) {
                        param.args[1] = 0
                    }
                } }
            } }
            type.declaredMethods.filter { it.name == "getMiAudioEffect" }.forEach { method -> method.hook {
                after { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        param.result = 0
                    }
                } }
            } }
        }
        findClass("com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager")?.let { type ->
            type.declaredMethods.filter { it.name == "setSpatialMode" }.forEach { method -> method.hook {
                before { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args) && param.args.size > 1) {
                        param.args[1] = 0
                    }
                } }
            } }
            type.declaredMethods.filter { it.name == "getSpatialMode" }.forEach { method -> method.hook {
                after { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        param.result = disabledValue(method.returnType)
                    }
                } }
            } }
        }
        // Both MiLink spatial cards call this service method. Complete the request
        // locally instead of allowing the async client call to change headset state.
        findClass("com.miui.circulate.api.protocol.headset.HeadsetServiceController")?.let { type ->
            type.declaredMethods.filter { it.name == "setAudioEffect" && it.parameterTypes.size == 2 }
                .forEach { method -> method.hook {
                    before { param -> runCatching {
                        if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                            param.result = CompletableFuture.completedFuture(100)
                        }
                    } }
                } }
        }
        findClass("com.miui.headset.runtime.ProfileImpl")?.let { type ->
            type.declaredMethods.filter {
                it.name == "updateHeadsetAudioEffect" && it.parameterTypes.size == 4 &&
                    it.parameterTypes.last() == Int::class.javaPrimitiveType
            }.forEach { method -> method.hook {
                before { param -> runCatching {
                    if (disableSpatialEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        param.args[param.args.lastIndex] = 0
                    }
                } }
            } }
        }
        // The UI is a custom View section, not a PreferenceScreen.
        findRuntimeClass("w0", "C6439w0", "updateMiAudioEffectStatus: ")?.declaredConstructors?.forEach { ctor ->
            ctor.hook { after { param -> runCatching {
                hideSpatialCard(param.thisObject)
            } } }
        }
        findRuntimeClass("w0", "C6439w0", "updateMiAudioEffectStatus: ")?.let { type ->
            type.declaredMethods.filter { (it.name == "n" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) ||
                (it.name == "o" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))) }.forEach { method ->
                method.hook { after { param -> runCatching { hideSpatialCard(param.thisObject) } } }
            }
        }
        // C6440x is the separate one-toggle card labelled "开启空间音频".
        findRuntimeClass("x", "C6440x", "updateAudioEffect: ")?.declaredConstructors?.forEach { ctor ->
            ctor.hook { after { param -> runCatching { hideAudioEffectCard(param.thisObject) } } }
        }
        findRuntimeClass("x", "C6440x", "updateAudioEffect: ")?.let { type ->
            type.declaredMethods.filter { (it.name == "m" && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))) ||
                (it.name == "o" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))) }.forEach { method ->
                method.hook { after { param -> runCatching { hideAudioEffectCard(param.thisObject) } } }
            }
        }
        findRuntimeClass("j", "C6412j", "updateMode: ")?.let { type ->
            type.declaredConstructors.forEach { ctor -> ctor.hook { after { param -> runCatching {
                if (!forceAdaptiveEnabled() || !isAirPodsCall(param.thisObject, emptyArray())) return@runCatching
                val off = (field(param.thisObject, "h") ?: field(param.thisObject, "f21223h")) as? View
                val titleId = off?.let { ancTitleId(it, param.thisObject.javaClass.classLoader) } ?: 0
                val title: TextView? = if (titleId != 0) off?.findViewById(titleId) else null
                title?.post {
                    val current = title.text?.toString()
                    AirPodsScope.ancTitle(current, true, AirPodsScope.AIRPODS_TYPE)
                        ?.takeIf { it != current }
                        ?.let { title.text = it }
                }
            } } } }
            type.declaredMethods.filter { (it.name == "z" || it.name == "m25135z") && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType }.forEach { method -> method.hook {
                before { param -> runCatching {
                    // The converted AirPods adaptive state is rendered by the original OFF item.
                    if (forceAdaptiveEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        val mode = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@runCatching
                        param.args[0] = AirPodsScope.ancMode(mode, true, AirPodsScope.AIRPODS_TYPE)
                    }
                } }
                after { param -> runCatching {
                    if (forceAdaptiveEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                        setAncAdaptiveText(param.thisObject)
                    }
                } }
            } }
        }
        findClass("com.miui.circulate.api.protocol.headset.HeadsetServiceController")?.let { type ->
            type.declaredMethods.filter { it.name == "getBluetoothDeviceMode" }.forEach { method ->
                method.hook { after { param -> runCatching {
                    if (forceAdaptiveEnabled() && isAirPodsCall(param.thisObject, param.args) && param.result == 4) {
                        param.result = 2
                    }
                } } }
            }
        }
        findClass("com.miui.circulateplus.world.headset.HeadSetsDetail")?.let { type ->
            type.declaredMethods.filter { it.name == "E" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                .forEach { method -> method.hook {
                    before { param -> runCatching {
                        if (forceAdaptiveEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                            val mode = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@runCatching
                            param.args[0] = AirPodsScope.ancMode(mode, true, AirPodsScope.AIRPODS_TYPE)
                        }
                    } }
                } }
            type.declaredClasses.flatMap { outer -> listOf(outer) + outer.declaredClasses.toList() }
                .flatMap { it.declaredMethods.toList() }
                .filter { it.name == "onBluetoothModeChanged" && it.parameterTypes.size == 2 }
                .forEach { method -> method.hook {
                    before { param -> runCatching {
                        if (forceAdaptiveEnabled() && isAirPodsCall(param.thisObject, param.args)) {
                            val mode = (param.args.getOrNull(1) as? Number)?.toInt() ?: return@runCatching
                            param.args[1] = AirPodsScope.ancMode(mode, true, AirPodsScope.AIRPODS_TYPE)
                        }
                    } }
                } }
        }
    }

    private fun isAirPodsCall(receiver: Any?, args: Array<out Any?>): Boolean =
        runCatching { AirPodsScope.isAirPodsScope(receiver, args, classLoader) }.getOrDefault(false)

    /**
     * Bluetooth-process gate. AirCore/AirLocalStorage/airpodsRepository are AirPods-only
     * transports, so a BluetoothDevice in the call graph is enough. On OS4.0.0.24 this process no
     * longer bundles HeadsetDeviceManager/HeadsetDeviceInfo, so the strict [isAirPodsScope] type
     * check can never resolve and would keep these rewrites from firing.
     */
    private fun isAirPodsTransport(receiver: Any?, args: Array<out Any?>): Boolean =
        runCatching { AirPodsScope.hasBluetoothDevice(receiver, *args) }.getOrDefault(false)

    private fun normalizeValue(args: Array<Any?>, keyIndex: Int, valueIndex: Int) {
        val key = args.getOrNull(keyIndex)?.toString() ?: return
        val original = args.getOrNull(valueIndex)?.toString() ?: return
        val replacement = when {
            key == "air_anc" -> AirPodsScope.ancValue(
                original,
                adaptiveEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            isSpatialKey(key) -> AirPodsScope.spatialValue(
                original,
                disableSpatialEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            else -> original
        }
        if (replacement != original) args[valueIndex] = replacement
    }

    private fun findKeyValueIndexes(args: Array<Any?>): Pair<Int, Int>? {
        val stringIndexes = args.indices.filter { args[it] is String }
        val keyIndex = stringIndexes.getOrNull(0) ?: return null
        val valueIndex = stringIndexes.getOrNull(1) ?: return null
        return keyIndex to valueIndex
    }

    private fun keyFrom(args: Array<Any?>): String? =
        args.firstOrNull { it is String }?.toString()

    private fun normalizeResult(result: Any?, key: String?, vararg scopeRoots: Any?): Any? = when (result) {
        is Bundle -> result.also { normalizeBundle(it, *scopeRoots) }
        is String -> when {
            key == "air_anc" -> AirPodsScope.ancValue(
                result,
                adaptiveEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            isSpatialKey(key) -> AirPodsScope.spatialValue(
                result,
                disableSpatialEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            else -> result
        }
        else -> result
    }

    private fun normalizeBundle(bundle: Bundle, vararg scopeRoots: Any?) {
        if (!isAirPodsTransport(null, arrayOf(bundle, *scopeRoots))) return
        val key = bundle.getString("extra_key") ?: return
        val original = bundle.getString("extra_value") ?: return
        val replacement = when {
            key == "air_anc" -> AirPodsScope.ancValue(
                original,
                adaptiveEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            isSpatialKey(key) -> AirPodsScope.spatialValue(
                original,
                disableSpatialEnabled(),
                AirPodsScope.AIRPODS_TYPE
            )
            else -> original
        }
        if (replacement != original) bundle.putString("extra_value", replacement)
    }

    private fun adaptiveEnabled() = Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)

    private fun forceAdaptiveEnabled() = adaptiveEnabled()

    private fun disableSpatialEnabled() =
        Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)

    private fun isSpatialKey(key: String?): Boolean = key?.contains("spatial", true) == true ||
        key?.contains("head_tracking", true) == true || key?.contains("headtracking", true) == true

    private fun hideField(target: Any, name: String) { (field(target, name) as? View)?.visibility = View.GONE }

    private fun hideSpatialCard(target: Any) {
        if (!disableSpatialEnabled() || !isAirPodsCall(target, emptyArray())) return
        hideField(target, "e")
        hideField(target, "f")
        hideField(target, "f21266e")
        hideField(target, "f21267f")
        updateOwnerVisibility(target, "setMiAudioEffectVisible")
    }

    private fun hideAudioEffectCard(target: Any) {
        if (!disableSpatialEnabled() || !isAirPodsCall(target, emptyArray())) return
        hideField(target, "c")
        hideField(target, "b")
        hideField(target, "f21277c")
        hideField(target, "f21276b")
        updateOwnerVisibility(target, "setAudioEffectVisible")
    }

    private fun disabledValue(returnType: Class<*>): Any? = when (returnType) {
        Boolean::class.javaPrimitiveType, Boolean::class.java -> false
        Int::class.javaPrimitiveType, Int::class.java -> 0
        Long::class.javaPrimitiveType, Long::class.java -> 0L
        String::class.java -> "0"
        else -> null
    }

    private fun setAncAdaptiveText(target: Any) {
        val off = (field(target, "h") ?: field(target, "f21223h")) as? View ?: return
        val id = ancTitleId(off, target.javaClass.classLoader)
        val title = (if (id != 0) off.findViewById(id) as? TextView else null)
            ?: findTextView(off)
        off.post {
            val current = title?.text?.toString()
            AirPodsScope.ancTitle(current, true, AirPodsScope.AIRPODS_TYPE)
                ?.takeIf { it != current }
                ?.let { title?.text = it }
        }
    }

    private fun findTextView(view: View): TextView? {
        if (view is TextView) return view
        val group = view as? android.view.ViewGroup ?: return null
        for (index in 0 until group.childCount) findTextView(group.getChildAt(index))?.let { return it }
        return null
    }

    private fun updateOwnerVisibility(target: Any, setter: String) {
        val owner = field(target, "a") ?: field(target, "f21262a") ?: field(target, "f21275a") ?: return
        runCatching {
            owner.javaClass.methods.firstOrNull {
                it.name == setter && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }?.invoke(owner, false)
            (owner as? View)?.requestLayout()
        }
    }

    private fun ancTitleId(view: View, loader: ClassLoader?): Int = runCatching {
        view.resources.getIdentifier("anc_title", "id", "com.miui.circulate.world").takeIf { it != 0 }
            ?: Class.forName("com.miui.circulate.world.R\$id", false, loader ?: view.context.classLoader)
            .getDeclaredField("anc_title").getInt(null)
    }.getOrDefault(0)

    private fun field(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun findRuntimeClass(runtimeName: String, jadxName: String, vararg strings: String): Class<*>? {
        val prefix = "com.miui.circulateplus.world.headset."
        return findClass(prefix + runtimeName) ?: resolveClass(prefix + jadxName, *strings)
    }

    private fun findFirstClass(vararg names: String): Class<*>? = names.firstNotNullOfOrNull(::findClass)

    private fun resolveClass(name: String, vararg strings: String): Class<*>? {
        findClass(name)?.let {
            Log.d(TAG, "resolved $name directly in ${classLoader.javaClass.name}")
            return it
        }
        val info = hookParam.appInfo ?: return null
        val base = info.deviceProtectedDataDir ?: info.dataDir ?: return null
        val apk = info.sourceDir ?: return null
        val query: (DexKitBridge) -> String? = { bridge ->
            bridge.findClass { matcher { usingStrings(*strings) } }.singleOrNull()?.name
        }
        val resolved = DexKitManager.resolveClasses(java.io.File(base, "cache"), apk, classLoader,
            mapOf(name to query), logMissingQueries = false)[name]
        if (resolved == null) Log.w(TAG, "unable to resolve $name using ${strings.toList()}")
        else Log.d(TAG, "resolved $name as ${resolved.name} using DexKit")
        return resolved
    }

    /**
     * Resolves an AirPods data-path class that DexKit's broad string markers would otherwise
     * match to an unrelated class. Tries each marker set in order and rejects any resolution that
     * fails the semantic [validator] (method shape), so a reused obfuscated name never silently
     * disables the sub-hook.
     */
    private fun resolveAirPodsClass(
        jadxName: String,
        markerSets: List<Array<String>>,
        validator: (Class<*>) -> Boolean
    ): Class<*>? {
        findClass(jadxName)?.let { return it }
        val info = hookParam.appInfo ?: return null
        val base = info.deviceProtectedDataDir ?: info.dataDir ?: return null
        val apk = info.sourceDir ?: return null
        for (markers in markerSets) {
            val key = "apod_" + markers.joinToString("|")
            val query: (DexKitBridge) -> String? = { bridge ->
                bridge.findClass { matcher { usingStrings(*markers) } }.singleOrNull()?.name
            }
            val resolved = DexKitManager.resolveClasses(
                java.io.File(base, "cache"), apk, classLoader,
                mapOf(key to query), logMissingQueries = false, validators = mapOf(key to validator)
            )[key]
            if (resolved != null) {
                Log.d(TAG, "resolved $jadxName as ${resolved.name} using DexKit(${markers.toList()})")
                return resolved
            }
        }
        Log.w(TAG, "unable to resolve $jadxName using ${markerSets.map { it.toList() }}")
        return null
    }

    private fun findClass(name: String): Class<*>? = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
}
