package com.takekazex.hypertweak.hook.rules.systemui.island.flashlight

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * The full-screen flashlight page's exit, in the plugin's own `:flashlight` process.
 *
 * Back and the page's own bottom swipe both run `MiFlashlightManager.hideFlashlight()`, which
 * emits `MiFlashlightHideEvent` (the activity starts `hideBgAnim`: blur progress 1→0, mirrored
 * into the layout's alpha, then `finish()`) and calls `MiFlashlightLayout.hideView()`. Nothing
 * carries the window anywhere; the page just fades and the island comes back once the window is
 * gone.
 *
 * While the torch is lit (its island returns), the layout instead flies into the island: it
 * scales from the flashlight head (the page centre) to the island pill on the camera cutout,
 * its rounded clip narrowing to the band around the head, staying opaque until it lands and
 * fading there. The blur still fades natively; only the layout alpha the activity mirrors from it
 * is taken over during the flight. As it starts, SystemUI is told to restore the island (see
 * [IslandFlashlightHooker]): the pill grows out of the cutout while the page shrinks into it, and
 * the answer carries the island's bounds, which the flight eases onto.
 */
object FlashlightPageExitHooker : StaticHooker() {
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL, false)
    }

    /** Page → SystemUI: the exit started, restore the island now. */
    const val ACTION_PAGE_EXIT = "com.takekazex.hypertweak.action.FLASHLIGHT_PAGE_EXIT"
    /** The plugin runs as `android.uid.system`; ordinary apps cannot send the signal. */
    const val SENDER_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    /** SystemUI → page, in the signal's result extras: the restored island, screen pixels. */
    const val EXTRA_ISLAND_LEFT = "island_left"
    const val EXTRA_ISLAND_WIDTH = "island_width"
    const val EXTRA_ISLAND_HEIGHT = "island_height"

    private const val TAG = "FlashlightPageExit"
    private const val LAYOUT = "miui.systemui.flashlight.view.MiFlashlightLayout"
    private const val ACTIVITY = "miui.systemui.flashlight.MiFlashlightActivity"
    private const val SYSTEMUI = "com.android.systemui"
    /** Inside the activity's ~420ms blur fade, which hides the decor view when it completes. */
    private const val DURATION_MS = 380L
    private const val FADE_FROM = 0.65f
    /** Share of the remaining gap to SystemUI's island bounds closed per frame. */
    private const val RETARGET = 0.35f
    /** Estimate of the icon-only big island; `island_height` is read from the plugin. */
    private const val ISLAND_LEFT_DP = 36f
    private const val ISLAND_RIGHT_DP = 9f
    private const val ISLAND_WIDTH_DP = 69f
    private const val ISLAND_HEIGHT_DP = 34f
    private val EASE = PathInterpolator(0.3f, 0f, 0.1f, 1f)

    /** Torch state per page; the camera service reports it as soon as the page is attached. */
    private val torchOn = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    /** Main thread only: a page whose alpha the flight owns. */
    private var flying = false

    override fun onHook() {
        val layout = LAYOUT.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, LAYOUT, "not found on host")
            return
        }
        check(View::class.java.isAssignableFrom(layout)) { "$LAYOUT is not a View" }
        val hide = HostAccess.requireDeclaredMethod(layout, "hideView", accessible = false)
            .apply { check(returnType == Void.TYPE) }
        val attached = HostAccess.requireDeclaredMethod(layout, "onAttachedToWindow", accessible = false)
        // `onBgAnimUpdate(float, Collection<UpdateInfo>, Function1)`: the blur step whose callback
        // mirrors the blur progress into the layout's alpha.
        val activity = classLoader.loadClass(ACTIVITY)
        val bgUpdate = activity.declaredMethods.single { m ->
            m.name == "onBgAnimUpdate" && m.returnType == Void.TYPE && m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Float::class.javaPrimitiveType &&
                Collection::class.java.isAssignableFrom(m.parameterTypes[1]) && m.parameterTypes[2].isInterface
        }
        val callbackType = bgUpdate.parameterTypes[2]
        val keepAlpha = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "HyperTweakFlashlightFlight"
                else -> null
            }
        }
        attached.hook {
            after { param ->
                val view = param.thisObject as? View ?: return@after
                HookFailurePolicy.open(TAG, "watch torch for the flashlight page", Unit) { watchTorch(view) }
            }
        }
        bgUpdate.hook {
            before { param -> if (flying) param.args[2] = keepAlpha }
        }
        hide.hook {
            before { param ->
                if (!settingEnabled) return@before
                val view = param.thisObject as? View ?: return@before
                val lit = torchOn[view]
                if (lit != true) {
                    DebugLog.d(TAG, "flashlight page exit keeps its fade torch=$lit")
                    return@before
                }
                HookFailurePolicy.open(TAG, "fly flashlight page into its island", Unit) { flyIntoIsland(view) }
            }
        }
        DebugLog.hookRegistered(TAG, "flashlight page exit into its island")
    }

    /**
     * The island only comes back while the torch is lit. The callback lives as long as the page's
     * attachment, independent of this hooker's generation, so a hot reload cannot leak it.
     */
    private fun watchTorch(view: View) {
        if (torchOn.containsKey(view)) return
        val manager = view.context.getSystemService(CameraManager::class.java) ?: return
        val backFlash = manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return
        torchOn[view] = false
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == backFlash) torchOn[view] = enabled
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == backFlash) torchOn[view] = false
            }
        }
        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                runCatching { manager.unregisterTorchCallback(callback) }
                torchOn.remove(v)
                flying = false
            }
        })
    }

    private fun flyIntoIsland(view: View) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f || flying) return
        // Aimed at an estimate; SystemUI answers the exit signal with the island it restored.
        val target = estimateIsland(view)
        var exact: RectF? = null
        val clip = RectF(0f, 0f, w, h)
        var radius = 0f
        flying = true
        view.alpha = 1f
        view.pivotX = w / 2f
        view.pivotY = h / 2f
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(clip.left.toInt(), clip.top.toInt(), clip.right.toInt(), clip.bottom.toInt(), radius)
            }
        }
        view.clipToOutline = true
        signalIsland(view) { exact = it }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = EASE
            addUpdateListener {
                // A late answer is eased in rather than jumped to.
                exact?.let { e ->
                    target.set(target.left + (e.left - target.left) * RETARGET, target.top + (e.top - target.top) * RETARGET,
                        target.right + (e.right - target.right) * RETARGET, target.bottom + (e.bottom - target.bottom) * RETARGET)
                }
                val t = it.animatedValue as Float
                // The band around the flashlight head (the page centre) with the island's proportions.
                val bandHeight = w * target.height() / target.width()
                val bandTop = h / 2f - bandHeight / 2f
                val scale = 1f + (target.width() / w - 1f) * t
                view.scaleX = scale
                view.scaleY = scale
                view.translationX = (target.centerX() - w / 2f) * t
                view.translationY = (target.centerY() - h / 2f) * t
                clip.set(0f, bandTop * t, w, h + (bandTop + bandHeight - h) * t)
                // In page pixels, so it stays a pill at the island's scale.
                radius = bandHeight / 2f * t
                view.invalidateOutline()
                val fraction = it.animatedFraction
                view.alpha = if (fraction < FADE_FROM) 1f else 1f - (fraction - FADE_FROM) / (1f - FADE_FROM)
            }
            start()
        }
        DebugLog.i(TAG, "flashlight page flying into its island estimate=${target.toShortString()}")
    }

    /**
     * Asks SystemUI to bring the island back now; its answer carries the island's on-screen
     * bounds. The plugin runs as `android.uid.system`, which holds INTERACT_ACROSS_USERS.
     */
    @SuppressLint("MissingPermission")
    private fun signalIsland(view: View, onBounds: (RectF) -> Unit) {
        val reply = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = getResultExtras(false) ?: return
                val width = extras.getInt(EXTRA_ISLAND_WIDTH)
                val height = extras.getInt(EXTRA_ISLAND_HEIGHT)
                if (width <= 0 || height <= 0) return
                val location = IntArray(2).also { view.getLocationOnScreen(it) }
                val left = extras.getInt(EXTRA_ISLAND_LEFT) - location[0].toFloat()
                // SystemUI centres the island on the cutout vertically, as the estimate does.
                val estimate = estimateIsland(view)
                val top = estimate.centerY() - height / 2f
                onBounds(RectF(left, top, left + width, top + height))
                DebugLog.d(TAG, "flashlight island bounds left=$left width=$width height=$height")
            }
        }
        runCatching {
            view.context.sendOrderedBroadcastAsUser(Intent(ACTION_PAGE_EXIT).setPackage(SYSTEMUI), Process.myUserHandle(),
                null, reply, Handler(Looper.getMainLooper()), 0, null, null)
        }.onFailure { DebugLog.w(TAG, "island restore signal failed; it returns when the page closes", it) }
    }

    /**
     * The flashlight's collapsed big island hugs the cutout: its icon area to the left, a narrow
     * end area to the right (36dp and 9dp on the reference build). SystemUI's answer replaces it.
     */
    private fun estimateIsland(view: View): RectF {
        val res = view.resources
        val density = res.displayMetrics.density
        // The page runs in the island's own package, so its island dimens are the live ones.
        fun dimen(name: String, fallbackDp: Float): Float {
            val id = res.getIdentifier(name, "dimen", view.context.packageName)
            return if (id != 0) runCatching { res.getDimension(id) }.getOrNull() ?: (fallbackDp * density) else fallbackDp * density
        }
        val height = dimen("island_height", ISLAND_HEIGHT_DP)
        val location = IntArray(2).also { view.getLocationOnScreen(it) }
        val cutout = view.rootWindowInsets?.displayCutout?.boundingRectTop?.takeIf { !it.isEmpty }
        val left: Float
        val right: Float
        val cy: Float
        if (cutout != null) {
            left = cutout.left - ISLAND_LEFT_DP * density
            right = cutout.right + ISLAND_RIGHT_DP * density
            cy = cutout.exactCenterY()
        } else {
            val cx = res.displayMetrics.widthPixels / 2f
            left = cx - ISLAND_WIDTH_DP * density / 2f
            right = cx + ISLAND_WIDTH_DP * density / 2f
            cy = height / 2f + 8f * density
        }
        return RectF(left - location[0], cy - height / 2f - location[1], right - location[0], cy + height / 2f - location[1])
    }
}
