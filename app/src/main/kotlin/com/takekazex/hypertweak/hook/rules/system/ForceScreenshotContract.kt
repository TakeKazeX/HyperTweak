package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.HostAccess
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Discover the policy operand by controlled, local constructor probes, never by SDK or field name.
 * No capture, Surface allocation, Binder call or live host object participates in discovery.
 */
internal data class ForceScreenshotContract(val policy: Field, val captureValue: Any) {
    /** Restore reusable request objects even when the original native call throws. */
    fun <T> withCapturePolicy(args: Any, fail: (Throwable) -> Unit, capture: () -> T): T {
        val old = try { policy.get(args) } catch (t: Throwable) { fail(t); return capture() }
        try { policy.set(args, captureValue) } catch (t: Throwable) { fail(t); return capture() }
        return try { capture() } finally {
            try { policy.set(args, old) } catch (t: Throwable) { fail(t) }
        }
    }

    companion object {
        fun resolve(capture: Class<*>, builder: Class<*>, layerBuilder: Class<*>,
                    surface: Class<*>, setter: Method, captureValue: Any): ForceScreenshotContract {
            check(setter.declaringClass == builder && !Modifier.isStatic(setter.modifiers))
            val type = setter.parameterTypes.single()
            check(type == Int::class.javaPrimitiveType || type == Boolean::class.javaPrimitiveType)
            check(builder.isAssignableFrom(setter.returnType))
            val makeBuilder = builder.getDeclaredConstructor().apply { isAccessible = true }
            val makeArgs = capture.getDeclaredConstructor(builder).apply { isAccessible = true }
            val copy = layerBuilder.getDeclaredConstructor(surface, capture).apply { isAccessible = true }
            setter.isAccessible = true
            val values: List<Any> = if (type == Boolean::class.javaPrimitiveType) listOf(false, true)
                else listOf(0, 1, 2)
            check(captureValue in values)
            val samples = values.map { value ->
                val b = makeBuilder.newInstance()
                setter.invoke(b, value)
                makeArgs.newInstance(b)
            }
            val fields = capture.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == type }
                .onEach { it.isAccessible = true }
            val field = fields.singleOrNull { f -> samples.map(f::get) == values }
                ?: error("Capture policy operand missing or ambiguous")
            // Prove CaptureArgs -> LayerCaptureArgs.Builder -> CaptureArgs preserves that operand.
            val copied = samples.map { args -> makeArgs.newInstance(copy.newInstance(null, args)) }
            check(copied.map(field::get) == values) { "Layer capture policy copy changed" }
            return ForceScreenshotContract(field, captureValue)
        }
    }
}

/** Resolve the live vendor override; the stub's default false implementation is not evidence
 * that the device has no secure-window gate. get() is a local singleton lookup, done at setup.
 */
internal object ForceScreenshotVendorGate {
    fun resolve(stub: Class<*>, root: Class<*>): Method {
        val factory = stub.declaredMethods.single {
            it.name == "get" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                stub.isAssignableFrom(it.returnType)
        }.apply { isAccessible = true }
        val live = factory.invoke(null)
        check(live != null && stub.isInstance(live)) { "Vendor window-manager singleton unavailable" }
        return HostAccess.requirePublicMethod(live.javaClass, "notAllowCaptureDisplay", root, Int::class.javaPrimitiveType, accessible = true).apply {
            check(returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers) &&
                !Modifier.isAbstract(modifiers)) { "Vendor screenshot gate signature changed" }
        }
    }
}

/** Repair Surface security changed by older global isSecureLocked overrides. The host's own
 * refresh reads its current policy and schedules its native transaction/animation under the WM lock.
 */
internal class ForceScreenshotSurfaceRecovery private constructor(
    private val service: Class<*>, private val root: Field, private val lock: Field, private val refresh: Method
) {
    fun restore(instance: Any) {
        check(service.isInstance(instance)) { "Expected a local WindowManagerService" }
        val monitor = checkNotNull(lock.get(instance))
        synchronized(monitor) { refresh.invoke(checkNotNull(root.get(instance))) }
    }
    companion object {
        fun resolve(service: Class<*>, root: Class<*>, lock: Class<*>): ForceScreenshotSurfaceRecovery {
            fun field(type: Class<*>) = service.declaredFields.single {
                !Modifier.isStatic(it.modifiers) && it.type == type
            }.apply { isAccessible = true }
            val refresh = HostAccess.requireDeclaredMethod(root, "refreshSecureSurfaceState", accessible = true).apply {
                check(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
            }
            return ForceScreenshotSurfaceRecovery(service, field(root), field(lock), refresh)
        }
    }
}
