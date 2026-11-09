package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Picture
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/** Replays an icon group drawn by the native binder, even when its original slot is masked. */
internal class CarrierNativeGlyph(val source: View) : Drawable() {
    private val picture = Picture()
    private var appearance: List<Any?>? = null
    private var opacity = 255

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
                for (index in 0 until group.childCount) {
                    val child = group.getChildAt(index)
                    add(child)
                    add(child.visibility)
                    add(child.alpha)
                    add(child.left)
                    add(child.top)
                    add(child.translationX)
                    add(child.translationY)
                    add(child.width)
                    add(child.height)
                    addArtwork(child)
                }
            }
        }
        if (next == appearance) return true
        val canvas = picture.beginRecording(source.width, source.height)
        try {
            // Direct draw preserves the children even when the original slot is masked.
            source.draw(canvas)
        } finally {
            picture.endRecording()
        }
        appearance = next
        invalidateSelf()
        return true
    }

    private fun MutableList<Any?>.addArtwork(view: View) {
        when (view) {
            is ImageView -> {
                add(view.drawable)
                add(view.drawable?.level)
                add(view.imageTintList)
                add(view.imageMatrix)
                add(view.imageAlpha)
                add(view.colorFilter)
                add(view.drawable?.colorFilter)
            }
            is TextView -> {
                add(view.text.toString())
                add(view.currentTextColor)
                add(view.textSize)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (picture.width <= 0 || picture.height <= 0 || opacity == 0) return
        val save = canvas.saveLayerAlpha(null, opacity)
        try {
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width().toFloat() / picture.width, bounds.height().toFloat() / picture.height)
            canvas.drawPicture(picture)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {
        if (opacity != alpha) {
            opacity = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
