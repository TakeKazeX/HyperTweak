package com.takekazex.hypertweak.hook.rules.camera

import android.util.SparseArray
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Resolves the camera device-config surface from its behavior and object graph.
 *
 * Xiaomi changes the obfuscated facade, factory, and config base independently. The old
 * implementation treated those names and the concrete base type as one version tuple, so a
 * factory move from `Gu.b` to `p905ze.b` made every dependent hook disappear. This resolver
 * anchors on the facade's semantic `LCC` branch, validates its exposed API, finds the config
 * object field by the capability surface it implements, and reads the facade singleton without
 * knowing the factory class or config-field name.
 */
internal object CameraHostProfile {
    enum class Family { CAMERA_68, CAMERA_66 }

    data class Binding(
        val family: Family,
        val facade: Class<*>,
        val configField: Field,
        val configType: Class<*>,
        val mismatchGate: String?,
        val modelArrayGetter: String,
        val brandGetter: String,
        val lccGate: String,
        val streetGate: String,
        val masterLiveGate: String,
        val modeOrder: String,
        val effectTable: String,
        val focalStops: String,
        val leicaStyleGate: String?,
        private val singletonField: Field,
    ) {
        fun configMethod(receiver: Class<*>, name: String, returnType: Class<*>): Method? =
            runCatching {
                receiver.getMethod(name).takeIf {
                    !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                        returnType.isAssignableFrom(it.returnType)
                }?.apply { isAccessible = true }
            }.getOrNull()

        /** A selected target must implement the same full camera-config ABI as the live host. */
        fun acceptsConfig(candidate: Any): Boolean {
            val type = candidate.javaClass
            return configType.isInstance(candidate) &&
                configMethod(type, streetGate, java.lang.Boolean.TYPE) != null &&
                configMethod(type, masterLiveGate, java.lang.Boolean.TYPE) != null &&
                configMethod(type, modeOrder, IntArray::class.java) != null &&
                configMethod(type, effectTable, Map::class.java) != null &&
                configMethod(type, focalStops, SparseArray::class.java) != null
        }

        /** Read the config instance through the host's facade singleton, not its factory path. */
        fun configInstance(): Any? = runCatching {
            val owner = singletonField.get(null) ?: return@runCatching null
            configField.get(owner)?.takeIf(configType::isInstance)
        }.getOrNull()

        /** Replace the facade's active config only after a complete target ABI check. */
        fun replaceConfigInstance(target: Any): Boolean = runCatching {
            if (!acceptsConfig(target) || !configField.type.isInstance(target)) return@runCatching false
            val owner = singletonField.get(null) ?: return@runCatching false
            configField.set(owner, target)
            configField.get(owner) === target
        }.getOrDefault(false)
    }

    private data class Surface(
        val family: Family,
        val streetGate: String,
        val masterLiveGate: String,
        val modeOrder: String,
        val effectTable: String,
        val focalStops: String,
        val leicaStyleGate: String?,
    )

    fun resolve(ctx: CameraResolver.Ctx, scope: String): Binding? {
        val facade = CameraResolver.resolveClass(
            scope = scope,
            key = "camera_device_config_facade",
            ctx = ctx,
            candidates = emptyList(),
            // LCC is a semantic branch in the device-config facade, not a class/package name.
            // The method and class shapes below reject unrelated uses of the same literal.
            probe = { bridge -> findFacade(bridge, ctx.classLoader)?.name },
            validate = ::isFacade,
        ) ?: return null

        val configField = facade.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && isConfigType(it.type) }
            .singleOrNull()
            ?.apply { isAccessible = true }
            ?: run {
                DebugLog.w(scope, "config field not uniquely identified by capability surface on ${facade.name}")
                return null
            }
        val singletonField = findSingletonField(facade) ?: run {
            DebugLog.w(scope, "facade singleton not uniquely identified on ${facade.name}")
            return null
        }
        val surface = resolveConfigSurface(configField.type) ?: run {
            DebugLog.w(scope, "config ABI not recognized on ${configField.type.name}")
            return null
        }
        val modelGetter = facade.methods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                it.returnType == Array<String>::class.java
        } ?: run {
            DebugLog.w(scope, "device model-array getter is not unique on ${facade.name}")
            return null
        }
        val brandGetter = facade.methods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                it.returnType == String::class.java && it.name in setOf("z", "x")
        } ?: run {
            DebugLog.w(scope, "device-brand getter is not unique on ${facade.name}")
            return null
        }
        val lccGate = facade.methods.singleOrNull {
            Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                it.returnType == java.lang.Boolean.TYPE && it.name in setOf("Y", "V")
        } ?: run {
            DebugLog.w(scope, "LCC capability gate is not unique on ${facade.name}")
            return null
        }
        val mismatchGates = facade.methods.filter {
            Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                it.returnType == java.lang.Boolean.TYPE && it.name in setOf("O", "K")
        }
        val mismatchGate = mismatchGates.singleOrNull()?.name
        if (mismatchGate == null) {
            // Model-mismatch bypass is optional. New releases may reuse the old O/K signatures
            // for unrelated flags; that ambiguity must not disable the entire camera profile.
            DebugLog.w(scope, "model-mismatch gate is ambiguous on ${facade.name}; profile resolution continues")
        }
        if (configField.type.getMethod(surface.streetGate).returnType != java.lang.Boolean.TYPE ||
            configField.type.getMethod(surface.masterLiveGate).returnType != java.lang.Boolean.TYPE ||
            configField.type.getMethod(surface.modeOrder).returnType != IntArray::class.java ||
            !Map::class.java.isAssignableFrom(configField.type.getMethod(surface.effectTable).returnType) ||
            !SparseArray::class.java.isAssignableFrom(configField.type.getMethod(surface.focalStops).returnType)
        ) {
            DebugLog.w(scope, "config ABI failed final signature validation on ${configField.type.name}")
            return null
        }

        val binding = Binding(
            surface.family, facade, configField, configField.type, mismatchGate, modelGetter.name,
            brandGetter.name, lccGate.name, surface.streetGate, surface.masterLiveGate,
            surface.modeOrder, surface.effectTable, surface.focalStops, surface.leicaStyleGate,
            singletonField,
        )
        // Do not read the static singleton while resolving metadata. On Camera 6.8 the nested
        // holder initializes Pe.b, whose initializer depends on CameraAppImpl state and crashes
        // if touched from Xposed's package-loaded callback. Callers that need the live object
        // must wait until the host's config Provider has completed initialization.
        DebugLog.i(
            scope,
            "camera config surface resolved structurally: facade=${facade.name}, " +
                "config=${configField.type.name}, gates=${surface.streetGate}/${surface.masterLiveGate}",
        )
        return binding
    }

    private fun findFacade(bridge: DexKitBridge, loader: ClassLoader): Class<*>? {
        val candidates = runCatching {
            bridge.findClass { matcher { usingStrings("LCC") } }
                .mapNotNull { data -> runCatching { data.getInstance(loader) }.getOrNull() }
                .filter(::isFacade)
                .distinctBy { it.name }
        }.getOrNull().orEmpty()
        return candidates.singleOrNull()
    }

    private fun isFacade(type: Class<*>): Boolean {
        if (type.isInterface || type.isEnum || type.isArray) return false
        val modelGetterCount = type.methods.count {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                it.returnType == Array<String>::class.java
        }
        if (modelGetterCount != 1) return false
        if (type.methods.none {
                Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE && it.name in setOf("Y", "V")
            }
        ) return false
        if (type.methods.none {
                Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE && it.name in setOf("O", "K")
            }
        ) return false
        return type.declaredFields.count { !Modifier.isStatic(it.modifiers) && isConfigType(it.type) } == 1 &&
            findSingletonField(type) != null
    }

    private fun findSingletonField(facade: Class<*>): Field? {
        val fields = buildList {
            facade.declaredFields.forEach(::add)
            facade.declaredClasses.forEach { nested -> nested.declaredFields.forEach(::add) }
        }.filter {
            Modifier.isStatic(it.modifiers) && facade.isAssignableFrom(it.type)
        }
        return fields.singleOrNull()?.apply { isAccessible = true }
    }

    private fun isConfigType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.isInterface || type.isEnum) return false
        val methods = type.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 }
        val hasModeArray = methods.any { it.returnType == IntArray::class.java }
        val hasEffectMap = methods.any { Map::class.java.isAssignableFrom(it.returnType) }
        val hasZoomTable = methods.any { SparseArray::class.java.isAssignableFrom(it.returnType) }
        val hasCapabilityGates = methods.count { it.returnType == java.lang.Boolean.TYPE } >= 4
        return hasModeArray && hasEffectMap && hasZoomTable && hasCapabilityGates
    }

    private fun resolveConfigSurface(configType: Class<*>): Surface? {
        val candidates = listOf(
            Surface(Family.CAMERA_68, "c2", "l3", "G", "c0", "X0", "D2"),
            Surface(Family.CAMERA_66, "a3", "y4", "M", "q0", "v1", null),
        )
        return candidates.filter { matchesConfigSurface(configType, it) }.singleOrNull()
    }

    /** Method names can be reused across config families; classify by the full getter ABI. */
    private fun matchesConfigSurface(configType: Class<*>, surface: Surface): Boolean {
        fun getter(name: String): Method? = configType.methods.singleOrNull {
            it.name == name && !Modifier.isStatic(it.modifiers) &&
                !it.isSynthetic && it.parameterCount == 0
        }

        fun returns(name: String, expected: Class<*>): Boolean =
            getter(name)?.returnType == expected

        val effectTable = getter(surface.effectTable)?.returnType
        val focalStops = getter(surface.focalStops)?.returnType
        return returns(surface.streetGate, java.lang.Boolean.TYPE) &&
            returns(surface.masterLiveGate, java.lang.Boolean.TYPE) &&
            returns(surface.modeOrder, IntArray::class.java) &&
            effectTable != null && Map::class.java.isAssignableFrom(effectTable) &&
            focalStops != null && SparseArray::class.java.isAssignableFrom(focalStops) &&
            (surface.leicaStyleGate == null || returns(surface.leicaStyleGate, java.lang.Boolean.TYPE))
    }
}
