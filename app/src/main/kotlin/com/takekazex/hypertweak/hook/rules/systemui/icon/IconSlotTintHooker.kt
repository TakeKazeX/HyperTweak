package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collection

/** Applies SystemUI's area tint to module-owned bitmap status-bar slots only. */
object IconSlotTintHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val VIEW_CLASS = "com.android.systemui.statusbar.StatusBarIconView"
    private const val DARK_CLASS = "com.android.systemui.statusbar.DarkIconDispatcherExt"

    private val moduleSlots = IconSlotPolicy.MODULE_SLOTS.toSet()

    private var slotField: Field? = null
    private var iconField: Field? = null
    private var packageField: Field? = null
    private var tintMethod: Method? = null
    private var decorMethod: Method? = null

    override fun onHook() {
        val viewClass = VIEW_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VIEW_CLASS, "class not found")
            return
        }
        viewClass.findMethodOrNull {
            name("updateLightDarkTint")
            paramCount(4)
        }?.hook {
            after { param ->
                runCatching { applyTint(param.thisObject, param.args) }
                    .onFailure { DebugLog.w(TAG, "module icon tint failed", it) }
            }
        } ?: run {
            DebugLog.hookSkipped(TAG, "$VIEW_CLASS#updateLightDarkTint", "method not found")
            return
        }
        DebugLog.hookRegistered(TAG, "$VIEW_CLASS#updateLightDarkTint(module slots)")
    }

    private fun applyTint(target: Any?, args: Array<Any?>) {
        val view = target as? View ?: return
        val slot = readField(view, slotField, "mSlot") as? String ?: return
        if (slot !in moduleSlots) return
        val icon = readField(view, iconField, "mIcon") ?: return
        val pkg = readField(icon, packageField, "pkg") as? String ?: return
        if (pkg != HostIconBridge.MODULE_PACKAGE) return

        val areas = args.getOrNull(0) as? Collection<*> ?: return
        val baseTint = (args.getOrNull(2) as? Number)?.toInt() ?: return
        val tint = resolveTintMethod(view.javaClass.classLoader, view, areas, baseTint) ?: baseTint
        (view as? ImageView)?.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        resolveDecorMethod(view.javaClass)?.invoke(view, tint)
    }

    private fun resolveTintMethod(
        loader: ClassLoader?,
        view: View,
        areas: Collection<*>,
        baseTint: Int
    ): Int? {
        val method = tintMethod ?: runCatching {
            val darkClass = Class.forName(
                IconTunerFlows.hostClassName("com.android.systemui.statusbar", "DarkIconDispatcherExt"),
                false,
                loader ?: classLoader
            )
            darkClass.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == "getTint" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[1].isAssignableFrom(view.javaClass) &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }
        }.onFailure { DebugLog.w(TAG, "DarkIconDispatcherExt#getTint lookup failed", it) }.getOrNull()
            .also { tintMethod = it }
        return runCatching {
            (method ?: return null).invoke(null, areas, view, baseTint) as? Number
        }.onFailure { DebugLog.w(TAG, "DarkIconDispatcherExt#getTint failed", it) }
            .getOrNull()?.toInt()
    }

    private fun resolveDecorMethod(type: Class<*>): Method? {
        if (decorMethod != null) return decorMethod
        decorMethod = findReflectiveMethod(type) {
            it.name == "setDecorColor" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType!!))
        }?.apply { isAccessible = true }
        return decorMethod
    }

    private fun readField(target: Any, cached: Field?, name: String): Any? {
        val field = cached ?: findField(target.javaClass, name)?.apply { isAccessible = true }
            .also {
                when (name) {
                    "mSlot" -> slotField = it
                    "mIcon" -> iconField = it
                    "pkg" -> packageField = it
                }
            } ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun findReflectiveMethod(type: Class<*>, predicate: (Method) -> Boolean): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull(predicate)?.let { return it }
            current = current.superclass
        }
        return type.methods.firstOrNull(predicate)
    }
}
