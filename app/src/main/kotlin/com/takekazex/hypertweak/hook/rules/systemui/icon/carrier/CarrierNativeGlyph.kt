package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.takekazex.hypertweak.hook.base.HostAccess

/** Replays native binder artwork even when its original slot is masked. */
internal class CarrierNativeGlyph(
    val source: View,
    val children: List<View>? = null
) : Drawable() {
    var picture = Picture()
        private set
    private val paint = Paint()
    private var appearance: List<Any?>? = null

    override fun getIntrinsicWidth() = picture.width
    override fun getIntrinsicHeight() = picture.height

    fun update(): Boolean {
        if (source.width <= 0 || source.height <= 0) return picture.width > 0
        val next = buildList {
            add(source.width)
            add(source.height)
            val group = source as? ViewGroup
            if (group == null) {
                addArtwork(source)
            } else {
                if (children != null) {
                    add(source.scrollX)
                    add(source.scrollY)
                }
                for (index in 0 until (children?.size ?: group.childCount)) {
                    val child = children?.get(index) ?: group.getChildAt(index)
                    add(child)
                    add(child.visibility)
                    if (children == null) add(child.alpha)
                    add(child.left)
                    add(child.top)
                    add(child.translationX)
                    add(child.translationY)
                    if (children != null) {
                        add(child.scaleX)
                        add(child.scaleY)
                        add(child.pivotX)
                        add(child.pivotY)
                        add(child.rotation)
                    }
                    add(child.width)
                    add(child.height)
                    addArtwork(child)
                }
            }
        }
        if (next == appearance) return true
        val nextPicture = Picture()
        val canvas = nextPicture.beginRecording(source.width, source.height)
        try {
            if (children == null) {
                source.draw(canvas)
            } else for (child in children) {
                // The drag may own these views' alphas. Capture their artwork in parent coordinates.
                val save = canvas.save()
                try {
                    canvas.translate((child.left - source.scrollX).toFloat(), (child.top - source.scrollY).toFloat())
                    canvas.concat(child.matrix)
                    child.draw(canvas)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        } finally {
            nextPicture.endRecording()
        }
        picture = nextPicture
        appearance = next
        invalidateSelf()
        return true
    }

    private fun MutableList<Any?>.addArtwork(view: View) {
        when (view) {
            is ImageView -> {
                val drawable = view.drawable
                add(drawable)
                add(drawable?.level)
                add(drawable?.alpha)
                add((drawable as? BitmapDrawable)?.bitmap?.generationId)
                // The native binder changes the label in place, including same-width changes.
                if (drawable?.javaClass?.name == "com.miui.systemui.statusbar.views.MobileTypeDrawable") {
                    add(runCatching { HostAccess.read(drawable, "mMobileType") as? String }.getOrNull())
                }
                add(view.imageTintList)
                add(view.imageMatrix)
                add(view.imageAlpha)
                add(view.colorFilter)
                add(drawable?.colorFilter)
            }
            is TextView -> {
                add(view.text.toString())
                add(view.currentTextColor)
                add(view.textSize)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (picture.width <= 0 || picture.height <= 0 || paint.alpha == 0) return
        val save = canvas.saveLayer(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint
        )
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / picture.width, bounds.height().toFloat() / picture.height)
            canvas.drawPicture(picture)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha() = paint.alpha

    override fun getColorFilter(): ColorFilter? = paint.colorFilter

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paint.colorFilter != colorFilter) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
