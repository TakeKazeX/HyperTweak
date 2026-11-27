package com.takekazex.hypertweak.hook.rules.systemui.freeform

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.WindowTouchableInsets
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.util.WeakHashMap

object MotionCuesFreeformHooker : DynamicHooker() {
    private const val TAG = "MotionCuesFreeform"
    private const val TYPE_VOLUME_OVERLAY = 2020
    private val enabled by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_MOTION_CUES_FREEFORM_FIX, false) }
    private var windows: OverlayWindows? by generationState { null }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) { refreshSettings() }
    }

    override fun onHook() {
        if (!isMainProcess || !PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = MotionCuesResolver.id, resolve = {
            DexRuntime.resolve(
                feature = MotionCuesResolver, apkPath = apk, cacheDir = dexCacheDirectory,
                compute = { MotionCuesResolver.resolve(DexRuntime.operands(apk)) },
                decode = { values, _ ->
                    val methods = listOf("create", "start", "reconfigure").map { key ->
                        requireNotNull(MethodDescriptorCodec.method(values.getValue(key), classLoader))
                    }
                    val overlay = requireNotNull(MethodDescriptorCodec.field(values.getValue("overlay"), classLoader))
                    check(overlay.declaringClass == methods.first().declaringClass && View::class.java.isAssignableFrom(overlay.type))
                    methods to overlay
                },
            )
        }, install = { (methods, overlay) ->
            val resolved = OverlayWindows(overlay)
            methods.forEach { check(deoptimize(it)) { "Could not deoptimize ${it.name}" } }
            methods.first().hook {
                after { param ->
                    if (enabled) guarded("overlay creation") {
                        HostAccess.onMain { windows?.adopt(overlay.get(param.thisObject) as? View) }
                    }
                }
            }
            windows = resolved
            refreshSettings()
            DebugLog.hookRegistered(TAG, "Motion Cues overlay touchable insets")
        })
    }

    fun refreshSettings(): Boolean = guarded("refresh existing overlays") {
        HostAccess.onMain { windows?.refresh(enabled) }
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            val previous = windows
            windows = null
            previous?.refresh(false)
        }
    }

    private inline fun guarded(action: String, block: () -> Unit): Boolean =
        runCatching(block).onFailure { DebugLog.w(TAG, "$action failed", it) }.isSuccess

    /** All access is on the SystemUI main looper. */
    private class OverlayWindows(overlay: Field) : View.OnAttachStateChangeListener {
        private val viewType = overlay.type
        private val global = Class.forName("android.view.WindowManagerGlobal")
        private val getGlobal = HostAccess.requireDeclaredMethod(global, "getInstance", accessible = true)
        private val getViews = HostAccess.requireDeclaredMethod(global, "getWindowViews", accessible = true)
        private val insets = WindowTouchableInsets()
        private var active = false
        private val callback = insets.listener(TAG) { info ->
            if (active && enabled) guarded("compute overlay insets") { insets.empty(info) }
        }
        private val observers = WeakHashMap<View, ViewTreeObserver?>()

        fun refresh(apply: Boolean) {
            active = apply
            if (apply) {
                val roots = getViews.invoke(getGlobal.invoke(null)) as? List<*>
                    ?: error("WindowManagerGlobal did not return a window list")
                roots.filterIsInstance<View>().forEach(::adopt)
            } else {
                val results = observers.keys.toList().map { view ->
                    guarded("restore overlay insets") { release(view, restore = true) }
                }
                check(results.all { it }) { "could not restore every Motion Cues overlay" }
            }
        }

        fun adopt(view: View?) {
            if (!active || view == null || view.javaClass != viewType) return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            if (params.type != TYPE_VOLUME_OVERLAY || params.width != WindowManager.LayoutParams.MATCH_PARENT ||
                params.height != WindowManager.LayoutParams.MATCH_PARENT ||
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
            ) return
            if (!observers.containsKey(view)) {
                view.addOnAttachStateChangeListener(this)
                observers[view] = null
            }
            if (view.isAttachedToWindow) bind(view)
        }

        override fun onViewAttachedToWindow(view: View) {
            if (active) guarded("attach overlay insets") { bind(view) }
        }

        override fun onViewDetachedFromWindow(view: View) {
            guarded("detach overlay insets") { release(view, restore = false) }
        }

        private fun bind(view: View) {
            if (view.rootView !== view || observers[view] != null) return
            val observer = view.viewTreeObserver
            insets.add(observer, callback)
            observers[view] = observer
            view.requestLayout()
            DebugLog.d(TAG, "registered empty touchable region for Motion Cues overlay")
        }

        private fun release(view: View, restore: Boolean) {
            val observer = observers[view]
            if (observer?.isAlive == true) insets.remove(observer, callback)
            observers.remove(view)
            view.removeOnAttachStateChangeListener(this)
            if (restore && view.isAttachedToWindow) view.requestLayout()
        }
    }
}
