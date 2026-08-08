package com.takekazex.hypertweak.hook.rules.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, fail-closed AirPods policy for the Bluetooth hooks.
 *
 * The circulate API deliberately keeps the device kind in HeadsetDeviceInfo.type. A Bluetooth
 * transport object by itself is not enough to identify AirPods, so callers that are not an
 * AirPods-only data path must provide that information before changing a value.
 */
internal object AirPodsScope {
    const val AIRPODS_TYPE = 5
    const val AIRPODS_HEADPHONES_TYPE = 6

    private const val DEVICE_EXTRA = "android.bluetooth.device.extra.DEVICE"
    private const val HEADSET_INFO_NAME = "HeadsetDeviceInfo"
    private const val HEADSET_MANAGER = "com.miui.circulate.api.protocol.headset.HeadsetDeviceManager"

    private val fieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val methodsCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val staticMethodsCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val typeByAddress = ConcurrentHashMap<String, Int>()

    fun isAirPodsType(type: Int?): Boolean =
        type == AIRPODS_TYPE || type == AIRPODS_HEADPHONES_TYPE

    /** Pure policy used by both runtime hooks and JVM tests. */
    fun spatialValue(original: String?, disableSpatialAudio: Boolean, type: Int?): String? {
        return if (disableSpatialAudio && isAirPodsType(type) && original == "01") "00" else original
    }

    /** Pure policy used by both runtime hooks and JVM tests. */
    fun ancValue(original: String?, forceAdaptiveAnc: Boolean, type: Int?): String? {
        return if (forceAdaptiveAnc && isAirPodsType(type) && original == "01") "04" else original
    }

    /** Pure policy used by both runtime hooks and JVM tests. */
    fun ancMode(original: Int, forceAdaptiveAnc: Boolean, type: Int?): Int {
        return if (forceAdaptiveAnc && isAirPodsType(type) && original == 4) 2 else original
    }

    /** Pure policy used by both runtime hooks and JVM tests. */
    fun ancTitle(original: String?, forceAdaptiveAnc: Boolean, type: Int?): String? {
        return if (forceAdaptiveAnc && isAirPodsType(type) &&
            original?.contains("关闭") == true
        ) "自适应" else original
    }

    fun headsetType(value: Any?): Int? {
        if (value == null || !isHeadsetDeviceInfo(value)) return null
        return readInt(value, "type")
    }

    fun isAirPodsInfo(value: Any?): Boolean = isAirPodsType(headsetType(value))

    /**
     * Resolves the current AirPods type from a MiLink object graph. Unknown or missing type data
     * intentionally returns false so the original platform behavior is retained.
     */
    fun isAirPodsScope(
        receiver: Any?,
        args: Array<out Any?> = emptyArray(),
        classLoader: ClassLoader? = null
    ): Boolean {
        val roots = arrayOf(receiver, *args)
        val loader = classLoader ?: roots.firstNotNullOfOrNull { it?.javaClass?.classLoader }
        val infos = collectHeadsetInfos(receiver, args, loader)
        // Method arguments carry the current device more reliably than a controller's cached
        // adapter/manager fields, which may contain other bonded devices.
        val device = findBluetoothDevice(*args) ?: findBluetoothDevice(receiver)
        val address = addressCandidate(*args) ?: deviceAddress(device) ?: addressCandidate(receiver)

        if (address != null) {
            val matching = infos.filter { infoAddress(it) == address }
            if (matching.isNotEmpty()) return matching.any(::isAirPodsInfo)
            lookupHeadsetInfo(address, loader)?.let { return isAirPodsInfo(it) }
            typeByAddress[address]?.let { return isAirPodsType(it) }
            // An unrelated or incomplete AirPods object in the same graph must not widen the
            // scope when its address cannot be matched.
            return false
        }
        return infos.any(::isAirPodsInfo)
    }

    /** Settings has to prove both sides of the scope: a BluetoothDevice and its AirPods type. */
    fun isAirPodsPreferenceScope(vararg roots: Any?): Boolean {
        val device = findBluetoothDevice(*roots)
        if (device == null) return false
        return isAirPodsScope(null, roots)
    }

    /** AirCore/AirLocalStorage/airpodsRepository are AirPods-only APIs; keep their device gate. */
    fun hasBluetoothDevice(vararg roots: Any?): Boolean = findBluetoothDevice(*roots) != null

    fun findBluetoothDevice(vararg roots: Any?): BluetoothDevice? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        for (root in roots) findBluetoothDevice(root, 0, visited)?.let { return it }
        return null
    }

    private fun collectHeadsetInfos(
        receiver: Any?,
        args: Array<out Any?>,
        classLoader: ClassLoader?
    ): List<Any> {
        val result = ArrayList<Any>(2)
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        collectHeadsetInfos(receiver, 0, visited, result)
        args.forEach { collectHeadsetInfos(it, 0, visited, result) }

        // HeadsetServiceController receives CirculateServiceInfo, not HeadsetDeviceInfo. Its
        // existing getter is the authoritative mapping for the current service device.
        if (receiver != null && receiver.javaClass.name.contains("HeadsetServiceController")) {
            val infoMethods = methodsFor(receiver.javaClass).filter {
                it.name == "getBluetoothDeviceInfo" && it.parameterTypes.size == 1
            }
            for (method in infoMethods) {
                for (arg in args) {
                    if (arg == null || !method.parameterTypes[0].isAssignableFrom(arg.javaClass)) continue
                    runCatching { method.invoke(receiver, arg) }.getOrNull()?.let { value ->
                        collectHeadsetInfos(value, 0, visited, result)
                    }
                }
            }
        }

        val addresses = args.asSequence()
            .flatMap { addressCandidates(it).asSequence() }
            .plus(receiver?.let { addressCandidates(it).asSequence() } ?: emptySequence())
            .distinct()
            .toList()
        for (address in addresses) {
            lookupHeadsetInfo(address, classLoader ?: receiver?.javaClass?.classLoader)?.let {
                collectHeadsetInfos(it, 0, visited, result)
            }
        }
        result.forEach(::rememberType)
        return result
    }

    @Suppress("DEPRECATION")
    private fun collectHeadsetInfos(
        value: Any?,
        depth: Int,
        visited: MutableSet<Any>,
        result: MutableList<Any>
    ) {
        if (value == null || depth > 4 || !visited.add(value)) return
        if (isHeadsetDeviceInfo(value)) {
            result += value
            return
        }
        if (isTerminal(value)) return

        when (value) {
            is Bundle -> {
                value.keySet().take(16).forEach { key ->
                    collectHeadsetInfos(value.get(key), depth + 1, visited, result)
                }
                return
            }
            is Intent -> {
                collectHeadsetInfos(value.extras, depth + 1, visited, result)
                return
            }
            is Context -> {
                invokeNoArg(value, "getIntent")?.let {
                    collectHeadsetInfos(it, depth + 1, visited, result)
                }
                return
            }
            is Iterable<*> -> {
                value.take(12).forEach { collectHeadsetInfos(it, depth + 1, visited, result) }
                return
            }
            is Array<*> -> {
                value.take(12).forEach { collectHeadsetInfos(it, depth + 1, visited, result) }
                return
            }
        }

        knownNoArgMethods(value).forEach { method ->
            runCatching { method.invoke(value) }.getOrNull()?.let {
                collectHeadsetInfos(it, depth + 1, visited, result)
            }
        }
        fieldsFor(value.javaClass).forEach { field ->
            runCatching { field.get(value) }.getOrNull()?.let {
                if (!shouldSkipGraphValue(it)) collectHeadsetInfos(it, depth + 1, visited, result)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findBluetoothDevice(
        value: Any?,
        depth: Int,
        visited: MutableSet<Any>
    ): BluetoothDevice? {
        if (value == null || depth > 4 || !visited.add(value)) return null
        if (value is BluetoothDevice) return value
        if (isTerminal(value)) return null

        when (value) {
            is Bundle -> {
                runCatching {
                    value.getParcelable(DEVICE_EXTRA, BluetoothDevice::class.java)
                }.getOrNull()?.let { return it }
                value.keySet().take(16).forEach { key ->
                    findBluetoothDevice(value.get(key), depth + 1, visited)?.let { return it }
                }
                return null
            }
            is Intent -> {
                runCatching {
                    value.getParcelableExtra(DEVICE_EXTRA, BluetoothDevice::class.java)
                }.getOrNull()?.let { return it }
                findBluetoothDevice(value.extras, depth + 1, visited)?.let { return it }
                return null
            }
            is Context -> {
                findBluetoothDevice(invokeNoArg(value, "getIntent"), depth + 1, visited)?.let { return it }
                return null
            }
            is Iterable<*> -> {
                value.take(12).forEach {
                    findBluetoothDevice(it, depth + 1, visited)?.let { device -> return device }
                }
                return null
            }
            is Array<*> -> {
                value.take(12).forEach {
                    findBluetoothDevice(it, depth + 1, visited)?.let { device -> return device }
                }
                return null
            }
        }

        knownNoArgMethods(value).forEach { method ->
            findBluetoothDevice(runCatching { method.invoke(value) }.getOrNull(), depth + 1, visited)
                ?.let { return it }
        }
        fieldsFor(value.javaClass).forEach { field ->
            val child = runCatching { field.get(value) }.getOrNull()
            if (!shouldSkipGraphValue(child)) {
                findBluetoothDevice(child, depth + 1, visited)?.let { return it }
            }
        }
        return null
    }

    private fun knownNoArgMethods(type: Any): List<Method> {
        val names = setOf(
            "getHeadsetDeviceInfo", "getDeviceInfo", "getAttachedDeviceInfo", "getBluetoothDevice",
            "getHeadsetInfo", "getIntent", "getExtras", "getContext", "getActivity"
        )
        return methodsFor(type.javaClass).filter { it.parameterTypes.isEmpty() && it.name in names }
    }

    private fun methodsFor(type: Class<*>): List<Method> = methodsCache.getOrPut(type) {
        buildList {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!method.isSynthetic && !Modifier.isStatic(method.modifiers)) {
                        runCatching { method.isAccessible = true }
                        add(method)
                    }
                }
                current = current.superclass
            }
        }
    }

    private fun fieldsFor(type: Class<*>): List<Field> = fieldsCache.getOrPut(type) {
        buildList {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers) &&
                        (!field.isSynthetic || field.name.startsWith("this\$") || field.name.contains("\$this"))
                    ) {
                        runCatching { field.isAccessible = true }
                        add(field)
                    }
                }
                current = current.superclass
            }
        }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        return methodsFor(target.javaClass)
            .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.let { runCatching { it.invoke(target) }.getOrNull() }
    }

    private fun readInt(target: Any, name: String): Int? {
        return (readProperty(target, name) as? Number)?.toInt()
    }

    private fun readProperty(target: Any, name: String): Any? {
        fieldsFor(target.javaClass).firstOrNull { it.name == name }?.let {
            return runCatching { it.get(target) }.getOrNull()
        }
        val getter = "get" + name.replaceFirstChar { it.uppercaseChar() }
        return methodsFor(target.javaClass)
            .firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
            ?.let { runCatching { it.invoke(target) }.getOrNull() }
    }

    private fun isHeadsetDeviceInfo(value: Any): Boolean {
        return value.javaClass.simpleName == HEADSET_INFO_NAME ||
            value.javaClass.name.endsWith(".$HEADSET_INFO_NAME")
    }

    private fun infoAddress(info: Any): String? {
        return sequenceOf("mac", "deviceId", "address")
            .mapNotNull { readProperty(info, it)?.toString()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }

    private fun rememberType(info: Any) {
        val type = headsetType(info) ?: return
        infoAddress(info)?.let { typeByAddress[it] = type }
    }

    private fun lookupHeadsetInfo(address: String, classLoader: ClassLoader?): Any? {
        val managerClass = runCatching {
            Class.forName(HEADSET_MANAGER, false, classLoader ?: AirPodsScope::class.java.classLoader)
        }.getOrNull() ?: return null
        val manager = staticMethodsFor(managerClass)
            .firstOrNull { it.name == "get" && it.parameterTypes.isEmpty() && Modifier.isStatic(it.modifiers) }
            ?.let { runCatching { it.invoke(null) }.getOrNull() } ?: return null
        return methodsFor(manager.javaClass)
            .firstOrNull {
                it.name == "getBluetoothDevice" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
            ?.let { runCatching { it.invoke(manager, address) }.getOrNull() }
    }

    private fun addressCandidate(vararg roots: Any?): String? =
        roots.asSequence().flatMap { addressCandidates(it).asSequence() }.firstOrNull()

    private fun addressCandidates(value: Any?): List<String> {
        if (value == null) return emptyList()
        if (value is String) return listOf(value).filter(::looksLikeBluetoothAddress)
        if (value is BluetoothDevice) return listOfNotNull(deviceAddress(value))
        if (isHeadsetDeviceInfo(value)) {
            return listOfNotNull(infoAddress(value))
        }
        return listOfNotNull(
            readProperty(value, "deviceId")?.toString(),
            readProperty(value, "mac")?.toString(),
            readProperty(value, "address")?.toString()
        ).filter(String::isNotEmpty)
    }

    private fun deviceAddress(device: BluetoothDevice?): String? = runCatching {
        device?.address?.takeIf(String::isNotEmpty)
    }.getOrNull()

    private fun looksLikeBluetoothAddress(value: String): Boolean =
        value.length == 17 && value.count { it == ':' } == 5

    private fun staticMethodsFor(type: Class<*>): List<Method> = staticMethodsCache.getOrPut(type) {
        buildList {
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!method.isSynthetic && Modifier.isStatic(method.modifiers)) {
                        runCatching { method.isAccessible = true }
                        add(method)
                    }
                }
                current = current.superclass
            }
        }
    }

    private fun shouldSkipGraphValue(value: Any?): Boolean {
        if (value == null || isTerminal(value)) return true
        val name = value.javaClass.name
        return name.startsWith("android.view.") || name.startsWith("android.graphics.") ||
            name.startsWith("android.content.res.") || name.startsWith("java.lang.reflect.")
    }

    private fun isTerminal(value: Any): Boolean {
        return value is BluetoothDevice || value is String || value is Number || value is Boolean || value is Char ||
            value is Enum<*> || value is Class<*> || value is ClassLoader || value is Thread
    }
}
