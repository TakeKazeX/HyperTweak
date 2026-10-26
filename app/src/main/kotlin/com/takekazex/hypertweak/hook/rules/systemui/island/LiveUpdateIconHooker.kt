package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fixes native promoted-notification masks at their existing real/fake icon binding boundary. */
class LiveUpdateIconHooker : DynamicHooker() {
    private data class Tint(val original: ColorStateList?, val mode: PorterDuff.Mode?, val applied: ColorStateList)
    private val main = Handler(Looper.getMainLooper())
    private val holders = WeakHashMap<Any, Boolean>()
    private val tints = WeakHashMap<ImageView, Tint>()
    private val masks = WeakHashMap<Drawable, Boolean>()
    @Volatile private var retiring = false
    private var update: ((Any) -> Unit)? = null

    fun snapshotHolders(): List<Any> = synchronized(holders) { holders.keys.toList() }

    fun restoreHolders(values: List<Any>) {
        synchronized(holders) { values.forEach { holders[it] = true } }
        refreshSettings()
    }

    private val refresh = Runnable {
        if (!retiring) snapshotHolders().forEach { holder ->
            HookFailurePolicy.open(TAG, "refresh existing icon", Unit) { update?.invoke(holder) }
        }
    }

    fun refreshSettings() {
        main.removeCallbacks(refresh)
        if (!retiring) main.post(refresh)
    }

    override fun onHook() {
        val owner = "miui.systemui.dynamicisland.module.IslandIconViewHolder".toClassOrNull() ?: return
        val data = classLoader.loadClass("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData")
        val setIcon = HostAccess.requireDeclaredMethod(owner, "setIcon", data, accessible = true)
        check(setIcon.returnType == Void.TYPE)
        val iconField = HostAccess.requireDeclaredField(owner, "icon", accessible = true)
        check(iconField.type == ImageView::class.java)
        val picInfo = HostAccess.requirePublicMethod(owner, "getPicInfo", accessible = false)
        val pic = HostAccess.requirePublicMethod(picInfo.returnType, "getPic", accessible = false)
        val type = HostAccess.requirePublicMethod(picInfo.returnType, "getType", accessible = false)
        val effect = HostAccess.requirePublicMethod(picInfo.returnType, "getEffectSrc", accessible = false)
        val currentData = HostAccess.requirePublicMethod(owner, "getDynamicIslandData", accessible = false)
        check(currentData.returnType == data)
        val extras = HostAccess.requirePublicMethod(data, "getExtras", accessible = false)
        check(extras.returnType == Bundle::class.java)
        listOf("bind", "showShaderIcon", "updatePartial").forEach { name ->
            owner.declaredMethods.filter { it.name == name }.forEach(::deoptimize)
        }

        fun apply(holder: Any) {
            if (retiring || !owner.isInstance(holder)) return
            val image = iconField.get(holder) as? ImageView ?: return
            release(image)
            if (!Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, false)) return
            val info = picInfo.invoke(holder) ?: return
            val bundle = currentData.invoke(holder)?.let { extras.invoke(it) as? Bundle }
            if (!LiveUpdateIconPolicy.eligible(bundle?.getBoolean("miui.focus.isPromoted", false) == true,
                    pic.invoke(info) as? String, type.invoke(info) as? Int, effect.invoke(info) as? String)) return
            // Respect native/other owners' color treatment. Do not mutate the supplied Icon,
            // shared drawable constant state, notification extras or expanded RemoteViews.
            if (image.imageTintList != null || image.colorFilter != null) return
            val drawable = image.drawable ?: return
            if (drawable !is VectorDrawable && drawable !is BitmapDrawable) return
            val monochrome = masks.getOrPut(drawable) { sample(drawable) }
            if (!monochrome) return
            // Native BaseIslandModuleViewHolder uses white for an unhighlighted title.
            // A distinct object also identifies our lease; valueOf may return a shared cache.
            val tint = ColorStateList(arrayOf(intArrayOf()), intArrayOf(Color.WHITE))
            tints[image] = Tint(image.imageTintList, image.imageTintMode, tint)
            image.imageTintMode = PorterDuff.Mode.SRC_IN
            image.imageTintList = tint
        }
        update = ::apply
        deoptimize(setIcon)
        setIcon.hook {
            before { param ->
                if (retiring || Looper.myLooper() != main.looper) return@before
                HookFailurePolicy.open(TAG, "release previous icon tint", Unit) {
                    (iconField.get(param.thisObject) as? ImageView)?.let { image ->
                        release(image)
                        image.drawable?.let(masks::remove)
                    }
                }
            }
            after { param ->
                if (retiring) return@after
                val holder = param.thisObject
                synchronized(holders) { holders[holder] = true }
                if (Looper.myLooper() == main.looper) {
                    HookFailurePolicy.open(TAG, "bind live-update icon", Unit) { apply(holder) }
                } else main.post {
                    HookFailurePolicy.open(TAG, "bind live-update icon on main", Unit) { apply(holder) }
                }
            }
        }
        DebugLog.hookRegistered(TAG, "native promoted-notification monochrome icon foreground")
    }

    private fun sample(drawable: Drawable): Boolean {
        val bitmap = createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bounds = Rect(drawable.bounds)
        return try {
            drawable.setBounds(0, 0, 32, 32)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(32 * 32)
            bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            LiveUpdateIconPolicy.darkMonochrome(pixels)
        } finally {
            drawable.bounds = bounds
            bitmap.recycle()
        }
    }

    private fun release(image: ImageView) {
        val tint = tints.remove(image) ?: return
        if (image.imageTintList === tint.applied && image.imageTintMode == PorterDuff.Mode.SRC_IN) {
            image.imageTintList = tint.original
            image.imageTintMode = tint.mode
        }
    }

    override fun saveHotReloadState(): Any = snapshotHolders()
    override fun restoreHotReloadState(state: Any?) = restoreHolders((state as? List<*>)?.filterNotNull().orEmpty())

    override fun onPrepareHotReload() {
        retiring = true
        main.removeCallbacksAndMessages(null)
        val cleanup = Runnable {
            tints.keys.toList().forEach(::release)
            masks.clear()
            synchronized(holders) { holders.clear() }
            update = null
        }
        if (Looper.myLooper() == main.looper) cleanup.run() else {
            val done = CountDownLatch(1)
            main.post { try { cleanup.run() } finally { done.countDown() } }
            check(done.await(5, TimeUnit.SECONDS)) { "live-update icon restoration timed out" }
        }
    }

    private companion object { const val TAG = "LiveUpdateIcon" }
}
