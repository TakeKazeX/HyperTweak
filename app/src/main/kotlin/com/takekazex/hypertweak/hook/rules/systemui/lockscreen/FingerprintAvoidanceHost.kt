package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import android.content.Context
import android.graphics.Rect
import com.takekazex.hypertweak.hook.base.HostAccess
import java.lang.reflect.Method

/** Stable, shape-checked native boundary. No Kotlin class names belonging to the module's R8. */
internal class FingerprintAvoidanceHost(loader: ClassLoader) {
    val repository = loader.loadClass("com.miui.systemui.keyguard.data.repository.MiuiKeyguardRepositoryImpl")
    val getter = HostAccess.requireDeclaredMethod(repository, "getNotificationBottomOnKeyguard", accessible = true)
    val mutable = loader.loadClass("com.miui.coroutines.syncflow.SynchronousStateFlowImpl")
    val setter = HostAccess.requireDeclaredMethod(mutable, "setValue", Any::class.java, accessible = true)
    private val readonly = loader.loadClass("com.miui.coroutines.syncflow.ReadonlySynchronousStateFlow")
    private val delegate = HostAccess.requireDeclaredField(readonly, "flow", accessible = true)
    val bridgeWriter: Method = HostAccess.requireDeclaredMethod(
        loader.loadClass("com.miui.coroutines.syncflow.BridgeKt\$asSynchronousStateFlow\$1"),
        "invokeSuspend", Any::class.java, accessible = true
    )
    private val gxzw = loader.loadClass("com.miui.keyguard.biometrics.fod.MiuiGxzwUtils")
    private val calculateIcon = HostAccess.requireDeclaredMethod(gxzw, "caculateGxzwIconSize", Context::class.java, accessible = true)
    private val iconY = HostAccess.requireDeclaredField(gxzw, "GXZW_ICON_Y", accessible = true)
    private val iconOffset = HostAccess.requireDeclaredField(gxzw, "mGxzwIconYOffset", accessible = true)
    private val sensor = HostAccess.requireDeclaredField(
        loader.loadClass("com.miui.utils.configs.MiuiConfigs"), "GXZW_SENSOR", accessible = true
    )
    private val lowPosition = HostAccess.requireDeclaredMethod(
        loader.loadClass("com.miui.systemui.util.BaseKeyguardUtils"), "isGxzwLowPosition", accessible = true
    )

    init {
        check(getter.returnType.name == "com.miui.coroutines.syncflow.SynchronousStateFlow")
        check(delegate.type == mutable && setter.returnType == Void.TYPE)
        check(iconY.type == Integer.TYPE && iconOffset.type == Integer.TYPE && sensor.type == java.lang.Boolean.TYPE)
        check(calculateIcon.returnType == Void.TYPE && lowPosition.returnType == java.lang.Boolean.TYPE)
    }

    fun unwrap(flow: Any): Any {
        check(readonly.isInstance(flow)) { "Unexpected notification-bound flow: ${flow.javaClass.name}" }
        return checkNotNull(delegate.get(flow))
    }

    fun inputs(repo: Any): Inputs {
        check(repository.isInstance(repo))
        val panel = lazy(read(repo, "keyguardPanelViewController"))
        check(panel.javaClass.name == "com.android.keyguard.panel.KeyguardPanelViewController")
        val context = read(panel, "context") as? Context ?: error("No keyguard context")
        val configuration = lazy(read(panel, "miuiConfigurationRepository"))
        val repoConfiguration = lazy(read(repo, "miuiConfigurationRepository"))
        fun dimension(owner: Any, name: String): Any {
            val id = context.resources.getIdentifier(name, "dimen", context.packageName)
            check(id != 0) { "Missing native dimension $name" }
            return checkNotNull(HostAccess.requirePublicMethod(owner.javaClass, "getDimension", Integer.TYPE, accessible = true).invoke(owner, id))
        }
        val normal = dimension(configuration, "miui_keyguard_all_in_one_nssl_lock_y_padding")
        val fingerprint = dimension(configuration, "miui_keyguard_all_in_one_nssl_lock_y_padding_above_gxzw")
        val numberPadding = dimension(repoConfiguration, "notif_bottom_on_keyguard_num_view_padding")
        val remote = read(panel, "_remoteViewY")
        val indication = read(read(panel, "keyguardBottomAreaInjector"), "_indicationAreaTop")
        val number = read(lazy(read(repo, "notificationNumStateViewModel")), "bound")
        return Inputs(context, read(repo, "scope"), listOf(normal, fingerprint, remote, indication, number, numberPadding))
    }

    inner class Inputs(val context: Context, val scope: Any, val flows: List<Any>) {
        fun geometry(mode: Int): FingerprintAvoidancePolicy.Geometry {
            val hasSensor = sensor.getBoolean(null)
            if (mode == 2 && hasSensor) calculateIcon.invoke(null, context)
            return FingerprintAvoidancePolicy.Geometry(
                intValue(flows[0]), intValue(flows[1]), intValue(flows[2]), intValue(flows[3]),
                hasSensor, iconY.getInt(null), iconOffset.getInt(null), lowPosition.invoke(null) == true,
                (value(flows[4]) as? Rect ?: error("No native number bounds")).height(), intValue(flows[5])
            )
        }
    }

    companion object {
        fun read(owner: Any, name: String): Any = checkNotNull(HostAccess.field(owner.javaClass, name)?.get(owner)) { "Missing ${owner.javaClass.name}.$name" }
        fun lazy(owner: Any): Any = checkNotNull(HostAccess.requirePublicMethod(owner.javaClass, "get", accessible = true).invoke(owner))
        fun value(flow: Any): Any = checkNotNull(HostAccess.requirePublicMethod(flow.javaClass, "getValue", accessible = true).invoke(flow))
        fun intValue(flow: Any): Int = value(flow) as? Int ?: error("Expected native Int state")
    }
}
