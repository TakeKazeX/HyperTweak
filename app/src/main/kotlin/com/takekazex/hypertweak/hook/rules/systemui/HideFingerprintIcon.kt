package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.ResourceLookup
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field

object HideFingerprintIcon : StaticHooker() {
    private var normal: Int? = null
    private var light: Int? = null
    private var aod: Int? = null
    private var cachedField: Field? = null

    private val shouldHideCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    override fun onHook() {
        DebugLog.d("HideFingerprint", "onHook called")
        val clzAnimation = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation",
            mapOf("MiuiGxzwFrameAnimation" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwFrameAnimation", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        val clzIconView = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView",
            mapOf("MiuiGxzwIconView" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwIconView", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        if (clzAnimation == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwFrameAnimation", "class not found")
        } else {
            DebugLog.d("HideFingerprint", "resolved animation class=${clzAnimation.name}")
        }
        if (clzIconView == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwIconView", "class not found")
        } else {
            DebugLog.d("HideFingerprint", "resolved icon view class=${clzIconView.name}")
        }

        // 1. Hook the FrameAnimation draw method to substitute fingerprint circle frame drawables with a transparent drawable
        val drawMethod = clzAnimation?.findMethodOrNull {
            name("draw")
            parameterTypes(Int::class.javaPrimitiveType!!)
        }
        if (drawMethod == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwFrameAnimation#draw(Int)", "method not found")
        }
        drawMethod?.hook {
            before { param ->
                val resID = param.args[0] as Int
                if (!Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) return@before

                val cached = shouldHideCache[resID]
                if (cached != null) {
                    if (cached) {
                        param.args[0] = android.R.color.transparent
                    }
                    return@before
                }

                val anim = param.thisObject
                try {
                    val field = cachedField ?: anim.javaClass.getDeclaredField("mContext").apply {
                        isAccessible = true
                        cachedField = this
                    }
                    val context = field.get(anim) as? Context ?: return@before
                    Preferences.initLocalCache(context)
                    val resources = context.resources
                    val pkgName = context.packageName

                    val resName = try { resources.getResourceEntryName(resID) } catch (t: Throwable) { null }

                    if (normal == null) normal = ResourceLookup.identifier(resources, "finger_circle_image_normal", "drawable", pkgName)
                    if (light == null)  light  = ResourceLookup.identifier(resources, "finger_circle_image_light",  "drawable", pkgName)
                    if (aod == null)    aod    = ResourceLookup.identifier(resources, "finger_circle_image_aod",    "drawable", pkgName)

                    val shouldHide = if (resName != null) {
                        resName.startsWith("finger_circle") || resName.contains("fingerprint_circle")
                    } else {
                        resID == normal || resID == light || resID == aod
                    }

                    shouldHideCache[resID] = shouldHide

                    if (shouldHide) {
                        DebugLog.d("HideFingerprint", "hide fingerprint frame resId=$resID resName=$resName")
                        param.args[0] = android.R.color.transparent
                    }
                } catch (t: Throwable) {
                    DebugLog.w("HideFingerprint", "failed while checking fingerprint frame resId=$resID", t)
                }
            }
        }

        // 2. Hook IconView constructors to set the static icon's alpha to 0 (completely transparent)
        clzIconView?.declaredConstructors?.forEach { constructor ->
            try {
                constructor.isAccessible = true
                constructor.hook {
                    after { param ->
                        val view = param.thisObject as? View
                        if (view != null && Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) {
                            try {
                                view.alpha = 0f
                                DebugLog.d("HideFingerprint", "set icon view alpha to 0")
                            } catch (t: Throwable) {
                                DebugLog.w("HideFingerprint", "failed to hide icon view", t)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                DebugLog.hookFailed("HideFingerprint", "${constructor.declaringClass.name}#<init>", t)
            }
        }
    }
}
