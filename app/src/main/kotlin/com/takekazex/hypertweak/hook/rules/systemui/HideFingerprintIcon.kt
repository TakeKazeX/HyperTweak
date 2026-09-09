package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.view.View
import android.view.ViewParent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.ResourceLookup
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object HideFingerprintIcon : StaticHooker() {
    private data class SurfaceHint(
        val surface: Surface,
        val resourceId: Int,
        val capturedAtNanos: Long
    )

    private enum class Surface {
        AOD,
        LOCKSCREEN,
        APP_AUTH
    }

    private enum class ResourceType {
        NOT_FINGERPRINT,
        AOD,
        LOCKSCREEN,
        APP_AUTH,
        OTHER_FINGERPRINT
    }

    private const val SURFACE_HINT_TTL_NANOS = 500_000_000L

    private var normal: Int? = null
    private var light: Int? = null
    private var aod: Int? = null
    private var grey: Int? = null
    private var greyEnroll: Int? = null
    private var cachedField: Field? = null
    @Volatile
    private var hideAodEnabled = false
    @Volatile
    private var hideLockscreenEnabled = false
    @Volatile
    private var hideAppAuthEnabled = false

    private var animationViewField: Field? = null
    private var managerFrameAnimationField: Field? = null
    private var managerKeyguardAuthenField: Field? = null
    private var iconDozingField: Field? = null
    private var iconKeyguardAuthenField: Field? = null
    private var setHighlightTransparentMethod: Method? = null

    /** Resource names distinguish the standard AOD, lockscreen, and app-auth images. */
    private val fingerprintResourceCache = ConcurrentHashMap<Int, ResourceType>()

    /** Associates a just-selected resource with the surface selected by its animation manager. */
    private val surfaceByAnimation =
        Collections.synchronizedMap(WeakHashMap<Any, SurfaceHint>())

    override fun onPrepareHotReload() {
        normal = null
        light = null
        aod = null
        grey = null
        greyEnroll = null
        cachedField = null
        animationViewField = null
        managerFrameAnimationField = null
        managerKeyguardAuthenField = null
        iconDozingField = null
        iconKeyguardAuthenField = null
        setHighlightTransparentMethod = null
        fingerprintResourceCache.clear()
        surfaceByAnimation.clear()
        hideAodEnabled = false
        hideLockscreenEnabled = false
        hideAppAuthEnabled = false
    }

    override fun onHook() {
        // AOD and the interactive lockscreen share one user-facing switch.
        val hideLockscreenFingerprint = Preferences.hideFingerprintLockscreenEnabled()
        hideAodEnabled = hideLockscreenFingerprint
        hideLockscreenEnabled = hideLockscreenFingerprint
        hideAppAuthEnabled = Preferences.hideFingerprintAppAuthEnabled()
        if (!hideAodEnabled && !hideLockscreenEnabled && !hideAppAuthEnabled) {
            DebugLog.hookSkipped("HideFingerprint", "fingerprint icon hooks", "disabled")
            return
        }

        val clzAnimation = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation",
            mapOf("MiuiGxzwFrameAnimation" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwFrameAnimation", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        val clzAnimManager = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimManager",
            mapOf("MiuiGxzwAnimManager" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwAnimManager", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        if (clzAnimation == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwFrameAnimation", "class not found")
        }
        if (clzAnimManager == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwAnimManager", "class not found")
        }

        val clzIconView = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwIconView",
            mapOf("MiuiGxzwIconView" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwIconView", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        if (clzIconView == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwIconView", "class not found")
        }

        // The touch/highlight indicator is a separate WindowManager window ("gxzw_icon"), so
        // hiding the animation bitmap alone cannot keep the halo hidden after a relock or a touch.
        iconDozingField = clzIconView?.let { findField(it, "mDozing") }
        iconKeyguardAuthenField = clzIconView?.let { findField(it, "mKeyguardAuthen") }
        setHighlightTransparentMethod = clzIconView?.findMethodOrNull {
            name("setHightlightTransparen")
            noParams()
        }
        if (setHighlightTransparentMethod == null) {
            DebugLog.hookSkipped(
                "HideFingerprint",
                "MiuiGxzwIconView#setHightlightTransparen()",
                "method not found"
            )
        }
        val iconLifecycleMethods = listOf(
            clzIconView?.findMethodOrNull {
                name("onKeyguardAuthen")
                parameterTypes(Boolean::class.javaPrimitiveType!!)
            } to "MiuiGxzwIconView#onKeyguardAuthen(boolean)",
            clzIconView?.findMethodOrNull {
                name("setGxzwIconOpaque")
                noParams()
            } to "MiuiGxzwIconView#setGxzwIconOpaque()",
            clzIconView?.findMethodOrNull {
                name("onTouchDown")
                noParams()
            } to "MiuiGxzwIconView#onTouchDown()",
            clzIconView?.findMethodOrNull {
                name("show")
                parameterTypes(Boolean::class.javaPrimitiveType!!)
            } to "MiuiGxzwIconView#show(boolean)"
        )
        iconLifecycleMethods.forEach { (method, target) ->
            if (method == null) {
                DebugLog.hookSkipped("HideFingerprint", target, "method not found")
            } else {
                method.hook {
                    after { param -> hideSelectedHighlight(param.thisObject) }
                }
            }
        }

        // Capture the surface before the manager passes a resource to FrameAnimation.draw(). The
        // boolean parameter is the vendor's AOD path; when it is false, mKeyguardAuthen separates
        // the lockscreen from in-app biometric authentication.
        managerFrameAnimationField = clzAnimManager?.let {
            findField(it, "mMiuiGxzwFrameAnimation")
        }
        managerKeyguardAuthenField = clzAnimManager?.let {
            findField(it, "mKeyguardAuthen")
        }
        val getFingerIconResource = clzAnimManager?.findMethodOrNull {
            name("getFingerIconResource")
            parameterTypes(Boolean::class.javaPrimitiveType!!)
        }
        if (getFingerIconResource == null) {
            DebugLog.hookSkipped(
                "HideFingerprint",
                "MiuiGxzwAnimManager#getFingerIconResource(Boolean)",
                "method not found"
            )
        }
        getFingerIconResource?.hook {
            after { param ->
                val manager = param.thisObject
                val isAod = param.args.getOrNull(0) as? Boolean ?: return@after
                val surface = surfaceForManager(manager, isAod)
                val resourceId = param.result as? Int ?: return@after
                managerFrameAnimationField?.let { field ->
                    runCatching { field.get(manager) }.getOrNull()?.let { animation ->
                        surfaceByAnimation[animation] = SurfaceHint(
                            surface = surface,
                            resourceId = resourceId,
                            capturedAtNanos = System.nanoTime()
                        )
                    }
                }
            }
        }

        // Hook FrameAnimation as a fallback for theme/custom callers that pass a fingerprint
        // resource directly instead of going through getFingerIconResource().
        animationViewField = clzAnimation?.let { findField(it, "mMiuiGxzwAnimationView") }
        val drawMethod = clzAnimation?.findMethodOrNull {
            name("draw")
            parameterTypes(Int::class.javaPrimitiveType!!)
        }
        if (drawMethod == null) {
            DebugLog.hookSkipped("HideFingerprint", "MiuiGxzwFrameAnimation#draw(Int)", "method not found")
        }
        drawMethod?.hook {
            before { param ->
                val resID = param.args.getOrNull(0) as? Int ?: return@before
                val resourceType = classifyResource(param.thisObject, resID) ?: return@before
                if (resourceType == ResourceType.NOT_FINGERPRINT) return@before

                // The standard resource name is the strongest evidence: it is the exact image
                // selected for this draw and remains correct even while mKeyguardAuthen is being
                // propagated across the manager/view pair during a relock. Use the short-lived
                // manager hint only for custom fingerprint resources, then the live owner state.
                val surface = resourceType.toSurface()
                    ?: surfaceHintForAnimation(param.thisObject, resID)
                    ?: surfaceForAnimation(param.thisObject)
                    ?: return@before
                if (shouldHide(surface)) {
                    param.args[0] = android.R.color.transparent
                }
            }
        }
    }

    private fun shouldHide(surface: Surface): Boolean = when (surface) {
        Surface.AOD -> hideAodEnabled
        Surface.LOCKSCREEN -> hideLockscreenEnabled
        Surface.APP_AUTH -> hideAppAuthEnabled
    }

    private fun surfaceForIcon(icon: Any): Surface? {
        val dozing = readBoolean(icon, iconDozingField)
        val keyguardAuthen = readBoolean(icon, iconKeyguardAuthenField)
        if (dozing == null && keyguardAuthen == null) return null
        return when {
            dozing == true -> Surface.AOD
            keyguardAuthen == true -> Surface.LOCKSCREEN
            else -> Surface.APP_AUTH
        }
    }

    private fun surfaceHintForAnimation(animation: Any, resourceId: Int): Surface? {
        val hint = surfaceByAnimation[animation] ?: return null
        if (hint.resourceId != resourceId ||
            System.nanoTime() - hint.capturedAtNanos > SURFACE_HINT_TTL_NANOS
        ) {
            surfaceByAnimation.remove(animation)
            return null
        }
        return hint.surface
    }

    private fun ResourceType.toSurface(): Surface? = when (this) {
        ResourceType.AOD -> Surface.AOD
        ResourceType.LOCKSCREEN -> Surface.LOCKSCREEN
        ResourceType.APP_AUTH -> Surface.APP_AUTH
        ResourceType.NOT_FINGERPRINT,
        ResourceType.OTHER_FINGERPRINT -> null
    }

    private fun classifyResource(animation: Any, resourceId: Int): ResourceType? {
        fingerprintResourceCache[resourceId]?.let { return it }
        return try {
            val field = cachedField ?: animation.javaClass.getDeclaredField("mContext").apply {
                isAccessible = true
                cachedField = this
            }
            val context = field.get(animation) as? Context ?: return null
            val resources = context.resources
            val pkgName = context.packageName
            val resName = runCatching { resources.getResourceEntryName(resourceId) }.getOrNull()

            if (normal == null) {
                normal = ResourceLookup.identifier(resources, "finger_circle_image_normal", "drawable", pkgName)
            }
            if (light == null) {
                light = ResourceLookup.identifier(resources, "finger_circle_image_light", "drawable", pkgName)
            }
            if (aod == null) {
                aod = ResourceLookup.identifier(resources, "finger_circle_image_aod", "drawable", pkgName)
            }
            if (grey == null) {
                grey = ResourceLookup.identifier(resources, "finger_circle_image_grey", "drawable", pkgName)
            }
            if (greyEnroll == null) {
                greyEnroll = ResourceLookup.identifier(resources, "finger_circle_image_grey_enroll", "drawable", pkgName)
            }

            val isFingerprint = resName?.let {
                it.startsWith("finger_circle") || it.contains("fingerprint_circle")
            } ?: (resourceId == normal || resourceId == light || resourceId == aod ||
                resourceId == grey || resourceId == greyEnroll)
            if (!isFingerprint) {
                ResourceType.NOT_FINGERPRINT
            } else {
                when {
                    resName?.contains("_aod") == true || resourceId == aod -> ResourceType.AOD
                    resName?.contains("_grey") == true || resourceId == grey || resourceId == greyEnroll -> ResourceType.APP_AUTH
                    resName?.contains("_normal") == true || resName?.contains("_light") == true ||
                        resourceId == normal || resourceId == light -> ResourceType.LOCKSCREEN
                    else -> ResourceType.OTHER_FINGERPRINT
                }
            }.also { fingerprintResourceCache[resourceId] = it }
        } catch (t: Throwable) {
            DebugLog.w("HideFingerprint", "failed while checking fingerprint frame resId=$resourceId", t)
            null
        }
    }

    /** Hides only the visual halo window; the icon view remains present to receive sensor input. */
    private fun hideSelectedHighlight(icon: Any) {
        val surface = surfaceForIcon(icon) ?: return
        if (!shouldHide(surface)) return
        val method = setHighlightTransparentMethod ?: return
        runCatching { method.invoke(icon) }
            .onFailure { DebugLog.w("HideFingerprint", "failed to hide selected fingerprint halo", it) }
    }

    private fun surfaceForManager(manager: Any, isAod: Boolean): Surface {
        if (isAod) return Surface.AOD
        return if (readBoolean(manager, managerKeyguardAuthenField) == true) {
            Surface.LOCKSCREEN
        } else {
            Surface.APP_AUTH
        }
    }

    /**
     * If a direct FrameAnimation.draw() call bypasses the manager, walk back from its animation
     * view to MiuiGxzwAnimViewInternal, whose mDozing/mKeyguardAuthen state is updated by SystemUI.
     */
    private fun surfaceForAnimation(animation: Any): Surface? {
        val animationView = runCatching {
            animationViewField?.get(animation) as? View
        }.getOrNull() ?: return null
        var parent: ViewParent? = animationView.parent
        while (parent != null) {
            val dozing = readBoolean(parent, findField(parent.javaClass, "mDozing"))
            val keyguardAuthen = readBoolean(parent, findField(parent.javaClass, "mKeyguardAuthen"))
            if (dozing != null || keyguardAuthen != null) {
                return when {
                    dozing == true -> Surface.AOD
                    keyguardAuthen == true -> Surface.LOCKSCREEN
                    else -> Surface.APP_AUTH
                }
            }
            parent = parent.parent
        }
        return null
    }

    private fun readBoolean(instance: Any, field: Field?): Boolean? {
        return field?.let { runCatching { it.get(instance) as? Boolean }.getOrNull() }
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return field.apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}
