package com.takekazex.hypertweak.hook.rules.systemui.icon.carrier

import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.TwoRowIconLayout
import com.takekazex.hypertweak.presentation.icon.IconSvgSnapshot

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Captured carrier layout baselines and per-row presentation state. */
internal class CarrierArtwork(val single: IconSvgSnapshot, val wifi: IconSvgSnapshot)

internal class CarrierViewState(view: View) {
    val params = (view.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
    val visibility = view.visibility
    fun restore(view: View) {
        params?.let { view.layoutParams = LinearLayout.LayoutParams(it) }
        view.visibility = visibility
    }
}

internal class CarrierRowParts(
    val slot: Int,
    val row: LinearLayout,
    val carrierText: TextView,
    val signal: ImageView,
    val badge: TextView,
    val networkSlot: FrameLayout,
    val wifi: ImageView,
    val type: ImageView
) {
    val airplaneText = TextView(row.context).apply {
        val id = resources.getIdentifier("airplane_mode", "string", "com.android.systemui")
        text = if (id != 0) context.getString(id) else carrierText.text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        includeFontPadding = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    var nativeCarrierText: CharSequence = carrierText.text
    val networkFade = CarrierNetworkCrossfade(type, wifi) { visible ->
        networkSlot.visibility = if (visible) View.VISIBLE else View.GONE
    }
    val rowState = CarrierViewState(row)
    val textState = CarrierViewState(carrierText)
    val gravity = row.gravity
    val baselineAligned = row.isBaselineAligned
    val contentDescription = row.contentDescription
    val textSize = carrierText.textSize
    val maxWidth = carrierText.maxWidth
    val ellipsize = carrierText.ellipsize
    val fontPadding = carrierText.includeFontPadding
    var showHd: Boolean? = null
    var hd: CarrierViewState? = null
    var plus: CarrierViewState? = null
    var wifiBitmap: Bitmap? = null
    var typeBitmap: Bitmap? = null
    var model: CarrierRowModel? = null
    var cellularReady = false
    var wifiReady = false
    var typeSuppressed = false
    var networkWidth = 0
    val badgeDrawable = GradientDrawable()
    var badgeTint: Int? = null
    var badgeDensity = 0f
    var rendered: CarrierRowRenderState? = null
}

internal data class CarrierRowRenderState(
    val model: CarrierRowModel, val art: CarrierArtwork, val tint: Int,
    val densityDpi: Int, val fontScale: Float, val rtl: Boolean,
    val iconHeight: Int, val carrier: String, val badgeText: String,
    val single: Boolean, val roaming: Boolean, val keepType: Boolean,
    val config: MobileTypeConfig, val badge: Boolean
)

/** Reused primitive comparison storage; local captured counters otherwise allocate on R8. */
internal class CarrierLayoutInputs(size: Int) {
    private val values = IntArray(size)
    private var cursor = 0
    var changed = false
        private set

    fun begin() { cursor = 0; changed = false }
    fun add(value: Int) {
        if (values[cursor] != value) { values[cursor] = value; changed = true }
        cursor++
    }
    operator fun get(index: Int): Int = values[index]
}

internal class CarrierBlock(
    val layout: LinearLayout,
    val rows: List<CarrierRowParts>,
    val separator: View?,
    val keyguardSeparator: View?
) {
    val originalChildren = (0 until layout.childCount).map(layout::getChildAt)
    val orientation = layout.orientation
    val minimumHeight = layout.minimumHeight
    val gravity = layout.gravity
    val separatorState = separator?.let(::CarrierViewState)
    val keyguardSeparatorState = keyguardSeparator?.let(::CarrierViewState)
    var compact = false
    var geometry: TwoRowIconLayout.Geometry? = null
    val geometryInputs = CarrierLayoutInputs(5 + rows.size * 3)
    val presentationInputs = CarrierLayoutInputs(6 + rows.size * 9)
    var presentationValid = false
    var observer: ViewTreeObserver? = null
    var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    var attachListener: View.OnAttachStateChangeListener? = null
    var maskContainer: Any? = null
}

/** Idempotent host environment binding, shared by package readiness and recovered views. */
