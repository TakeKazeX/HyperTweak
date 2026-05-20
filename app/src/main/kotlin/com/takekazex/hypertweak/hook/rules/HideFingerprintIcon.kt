package com.takekazex.hypertweak.hook.rules

import android.content.Context
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.lang.reflect.Field

object HideFingerprintIcon : StaticHooker() {
    private var normal: Int? = null
    private var light: Int? = null
    private var aod: Int? = null
    private var cachedField: Field? = null

    override fun onHook() {
        val clzAnimation = "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation".toClassOrNull()
        val clzIconView = "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView".toClassOrNull()

        // 1. Hook the FrameAnimation draw method to substitute fingerprint circle frame drawables with a transparent drawable
        clzAnimation?.resolve()?.firstMethodOrNull {
            name = "draw"
            parameters(Int::class)
        }?.hook {
            val resID = args[0] as Int

            if (Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) {
                val anim = thisObject
                if (anim != null) {
                    try {
                        val field = cachedField ?: anim.javaClass.getDeclaredField("mContext").apply {
                            isAccessible = true
                            cachedField = this
                        }
                        val context = field.get(anim) as? Context
                        if (context != null) {
                            val resources = context.resources
                            val pkgName = context.packageName

                            // Check resource entry name dynamically for robust matching
                            val resName = try {
                                resources.getResourceEntryName(resID)
                            } catch (t: Throwable) {
                                null
                            }

                            // Cache standard resource IDs as fallback
                            if (normal == null) {
                                normal = resources.getIdentifier("finger_circle_image_normal", "drawable", pkgName)
                            }
                            if (light == null) {
                                light = resources.getIdentifier("finger_circle_image_light", "drawable", pkgName)
                            }
                            if (aod == null) {
                                aod = resources.getIdentifier("finger_circle_image_aod", "drawable", pkgName)
                            }

                            val shouldHide = if (resName != null) {
                                resName.startsWith("finger_circle") || resName.contains("fingerprint_circle")
                            } else {
                                resID == normal || resID == light || resID == aod
                            }

                            if (shouldHide) {
                                // Draw a transparent drawable instead of drawing the circle
                                return@hook result(proceed(arrayOf(android.R.color.transparent)))
                            }
                        }
                    } catch (t: Throwable) {
                        // Ignore
                    }
                }
            }

            result(proceed())
        }

        // 2. Hook IconView constructors to set the static icon's alpha to 0 (completely transparent)
        clzIconView?.declaredConstructors?.forEach { constructor ->
            try {
                constructor.isAccessible = true
                constructor.hook {
                    val view = thisObject as? View
                    if (view != null && Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) {
                        try {
                            view.alpha = 0f
                        } catch (t: Throwable) {
                            // Ignore
                        }
                    }
                    result(proceed())
                }
            } catch (t: Throwable) {
                // Ignore
            }
        }
    }
}
