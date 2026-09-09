package com.takekazex.hypertweak.hook.rules.systemui

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.view.View
import android.view.ViewParent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Hides only the fingerprint visuals. The GXZW view remains present so the touch window and the
 * fingerprint recognition callbacks continue to work.
 */
object HideFingerprintIcon : StaticHooker() {
    private enum class Surface {
        AOD,
        LOCKSCREEN,
        APP_AUTH
    }

    private data class StateFields(
        val dozing: Field?,
        val keyguardAuthen: Field?
    )

    @Volatile
    private var hideAodEnabled = false
    @Volatile
    private var hideLockscreenEnabled = false
    @Volatile
    private var hideAppAuthEnabled = false

    private var iconDozingField: Field? = null
    private var iconKeyguardAuthenField: Field? = null
    private var setHighlightTransparentMethod: Method? = null

    /** The animation view is drawn on every frame, so cache the parent-state reflection lookup. */
    private val animationStateFields = ConcurrentHashMap<Class<*>, StateFields>()
    private val reportedClears = ConcurrentHashMap.newKeySet<Surface>()

    override fun onPrepareHotReload() {
        iconDozingField = null
        iconKeyguardAuthenField = null
        setHighlightTransparentMethod = null
        animationStateFields.clear()
        reportedClears.clear()
        hideAodEnabled = false
        hideLockscreenEnabled = false
        hideAppAuthEnabled = false
    }

    override fun onHook() {
        hideAodEnabled = Preferences.hideFingerprintAodEnabled()
        hideLockscreenEnabled = Preferences.hideFingerprintLockscreenEnabled()
        hideAppAuthEnabled = Preferences.hideFingerprintAppAuthEnabled()
        if (!hideAodEnabled && !hideLockscreenEnabled && !hideAppAuthEnabled) {
            DebugLog.hookSkipped("HideFingerprint", "fingerprint icon hooks", "disabled")
            return
        }

        val clzAnimationView = resolveAppClass(
            "com.miui.keyguard.biometrics.fod.MiuiGxzwAnimationView",
            mapOf("MiuiGxzwAnimationView" to { bridge ->
                bridge.findClass {
                    searchPackages("com.miui.keyguard.biometrics.fod")
                    matcher { className("MiuiGxzwAnimationView", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        if (clzAnimationView == null) {
            DebugLog.hookSkipped(
                "HideFingerprint",
                "MiuiGxzwAnimationView",
                "class not found"
            )
        }

        val onDraw = clzAnimationView?.findMethodOrNull {
            name("onDraw")
            parameterTypes(Canvas::class.java)
        }
        if (onDraw == null) {
            DebugLog.hookSkipped(
                "HideFingerprint",
                "MiuiGxzwAnimationView#onDraw(Canvas)",
                "method not found"
            )
        }
        onDraw?.hook {
            after { param ->
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@after
                val view = param.thisObject as? View ?: return@after
                val surface = surfaceForAnimationView(view) ?: return@after
                if (!shouldHide(surface)) return@after

                runCatching {
                    // MiuiGxzwAnimationView has already drawn mBitmap/mBackgroundBitmap here.
                    // CLEAR is the same operation used by the vendor when it removes the surface,
                    // and unlike an Android color resource it cannot fail bitmap decoding.
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    if (reportedClears.add(surface)) {
                        DebugLog.i("HideFingerprint", "cleared fingerprint animation surface=$surface")
                    }
                }.onFailure {
                    DebugLog.w("HideFingerprint", "failed to clear fingerprint surface=$surface", it)
                }
            }
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

        // The touch/highlight indicator is a separate WindowManager window ("gxzw_icon").
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

        listOf(
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
        ).forEach { (method, target) ->
            if (method == null) {
                DebugLog.hookSkipped("HideFingerprint", target, "method not found")
            } else {
                method.hook {
                    after { param -> hideSelectedHighlight(param.thisObject) }
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
        if (dozing == null || keyguardAuthen == null) return null
        return surfaceFromState(dozing, keyguardAuthen)
    }

    /** Reads the live owner state so unlock/relock and AOD transitions cannot use stale hints. */
    private fun surfaceForAnimationView(view: View): Surface? {
        var dozing: Boolean? = null
        var keyguardAuthen: Boolean? = null
        var parent: ViewParent? = view.parent
        while (parent != null) {
            val fields = animationStateFields.computeIfAbsent(parent.javaClass) { clazz ->
                StateFields(
                    dozing = findField(clazz, "mDozing"),
                    keyguardAuthen = findField(clazz, "mKeyguardAuthen")
                )
            }
            if (dozing == null) dozing = readBoolean(parent, fields.dozing)
            if (keyguardAuthen == null) keyguardAuthen = readBoolean(parent, fields.keyguardAuthen)
            if (dozing != null && keyguardAuthen != null) break
            parent = parent.parent
        }
        if (dozing == null || keyguardAuthen == null) return null
        return surfaceFromState(dozing, keyguardAuthen)
    }

    private fun surfaceFromState(dozing: Boolean, keyguardAuthen: Boolean): Surface = when {
        dozing -> Surface.AOD
        keyguardAuthen -> Surface.LOCKSCREEN
        else -> Surface.APP_AUTH
    }

    /** Hides only the visual halo window; the icon view remains present to receive sensor input. */
    private fun hideSelectedHighlight(icon: Any) {
        val surface = surfaceForIcon(icon) ?: return
        if (!shouldHide(surface)) return
        val method = setHighlightTransparentMethod ?: return
        runCatching { method.invoke(icon) }
            .onFailure { DebugLog.w("HideFingerprint", "failed to hide selected fingerprint halo", it) }
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
