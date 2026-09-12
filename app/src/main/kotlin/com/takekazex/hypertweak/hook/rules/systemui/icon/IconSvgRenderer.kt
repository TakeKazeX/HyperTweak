package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Xml
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.takekazex.hypertweak.util.DebugLog
import java.io.StringReader
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import org.xmlpull.v1.XmlPullParser

/** SVG families supported by the cellular signal renderers. */
enum class SvgKind {
    SINGLE_SIGNAL,
    STACKED_SIGNAL
}

/** Rendering inputs that affect bitmap geometry or the white alpha mask. */
data class IconSvgRenderConfig(
    val iconHeightPx: Int,
    val scale: Float = 1f,
    val alphaFg: Float = 1f,
    val alphaBg: Float = 0.35f,
    val alphaError: Float = 0.7f,
    val paddingStartPx: Int = 0,
    val paddingEndPx: Int = 0,
    val rtl: Boolean = false,
    val densityDpi: Int = 0,
    val fontScale: Float = 1f,
    val configVersion: Int = 1
)

/**
 * AndroidSVG-backed parser, validator and alpha-mask renderer.
 *
 * The source document is parsed once. Level variants are generated only when first requested and
 * then retained in a bounded per-document cache; bitmap output is also bounded and never recycled
 * here because a host StatusBarIcon may still own a returned bitmap.
 */
object IconSvgRenderer {
    private const val TAG = "IconTuner"
    const val MAX_SOURCE_BYTES = 128 * 1024
    private const val MAX_NODES = 512
    private const val MAX_VARIANTS = 32
    private const val MAX_BITMAPS = 48

    private val supportedElements = setOf(
        "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
        "defs", "clipPath", "linearGradient", "radialGradient", "stop", "style", "title", "desc"
    )
    private val graphicElements = setOf(
        "path", "rect", "circle", "ellipse", "line", "polyline", "polygon"
    )
    private val numericAttributes = setOf(
        "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry", "width", "height",
        "stroke-width", "stroke-miterlimit", "stroke-dashoffset", "opacity", "fill-opacity",
        "stroke-opacity", "stop-opacity", "viewbox", "transform", "points", "d"
    )
    private val numberPattern = Regex("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val idPattern = Regex("""\bid\s*=\s*([\"'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val opacityPattern = Regex("""(\sopacity\s*=\s*)([\"'])[^\"']*\2""", RegexOption.IGNORE_CASE)
    private val stylePattern = Regex("""(\sstyle\s*=\s*)([\"'])(.*?)\2""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val styleOpacityPattern = Regex("""(^|;)\s*opacity\s*:[^;]*""", RegexOption.IGNORE_CASE)
    private val externalReferencePattern = Regex(
        """(?i)(?:\b(?:href|src)\s*=\s*[\"']\s*(?:https?|file|content):|\burl\(\s*[\"']?\s*(?:https?|file|content):)"""
    )

    class Document internal constructor(
        val source: String,
        internal val svg: SVG,
        internal val metadata: Metadata,
        val sourceHash: String
    ) {
        internal val variants = object : LinkedHashMap<String, SVG>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SVG>?): Boolean =
                size > MAX_VARIANTS
        }
        internal val bitmaps = object : LinkedHashMap<String, Bitmap>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > MAX_BITMAPS
        }
    }

    internal data class Metadata(
        val viewBox: RectF,
        val ids: Map<String, String>,
        val width: Float,
        val height: Float,
        val typeContainer: RectF?
    )

    fun parse(source: String): Result<Document> = runCatching {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
            "SVG exceeds ${MAX_SOURCE_BYTES} bytes"
        }
        preflight(source)
        val metadata = parseMetadata(source)
        val svg = SVG.getFromString(source)
        Document(source, svg, metadata, sha256(source))
    }

    fun validate(document: Document, kind: SvgKind): Result<Unit> = runCatching {
        val box = document.metadata.viewBox
        require(box.width() > 0f && box.height() > 0f && box.width().isFinite() && box.height().isFinite()) {
            "SVG viewBox must have finite positive dimensions"
        }
        require(document.metadata.width > 0f && document.metadata.height > 0f) {
            "SVG width/height must be finite and positive"
        }
        val ids = document.metadata.ids
        when (kind) {
            SvgKind.SINGLE_SIGNAL -> {
                (1..4).forEach { level -> requireGraphic(ids, "signal_$level") }
            }
            SvgKind.STACKED_SIGNAL -> {
                (1..2).forEach { row ->
                    (1..4).forEach { level -> requireGraphic(ids, "signal_${row}_$level") }
                }
            }
        }
        ids["type_container"]?.let { require(it == "rect") { "type_container must be a rect" } }
    }

    fun renderSingle(document: Document, level: Int, config: IconSvgRenderConfig): Bitmap =
        render(document, SvgKind.SINGLE_SIGNAL, listOf(level), config)

    /** Renders a single-signal mask and centers [badge] on the SVG's type-container anchor. */
    fun renderSingleWithBadge(
        document: Document,
        level: Int,
        config: IconSvgRenderConfig,
        badge: Bitmap
    ): Bitmap = composeBadge(document, renderSingle(document, level, config), config, badge)

    fun renderStacked(
        document: Document,
        upperLevel: Int,
        lowerLevel: Int,
        config: IconSvgRenderConfig
    ): Bitmap = render(document, SvgKind.STACKED_SIGNAL, listOf(upperLevel, lowerLevel), config)

    /** Renders a stacked mask and centers [badge] on the SVG's type-container anchor. */
    fun renderStackedWithBadge(
        document: Document,
        upperLevel: Int,
        lowerLevel: Int,
        config: IconSvgRenderConfig,
        badge: Bitmap
    ): Bitmap = composeBadge(
        document,
        renderStacked(document, upperLevel, lowerLevel, config),
        config,
        badge
    )

    private fun render(
        document: Document,
        kind: SvgKind,
        levels: List<Int>,
        config: IconSvgRenderConfig
    ): Bitmap {
        require(validate(document, kind).isSuccess) { validate(document, kind).exceptionOrNull()?.message.orEmpty() }
        val safe = config.safe()
        val contentHeight = max(1, ceil(safe.iconHeightPx * safe.scale).toInt())
        val aspect = document.metadata.viewBox.width() / document.metadata.viewBox.height()
        val contentWidth = max(1, ceil(contentHeight * aspect).toInt())
        val start = if (safe.rtl) safe.paddingEndPx else safe.paddingStartPx
        val end = if (safe.rtl) safe.paddingStartPx else safe.paddingEndPx
        val outputWidth = (contentWidth + start + end).coerceAtMost(2048)
        val key = buildString {
            append(document.sourceHash).append('|').append(kind).append('|').append(levels.joinToString(","))
            append('|').append(contentHeight).append('|').append(contentWidth).append('|').append(start).append('|').append(end)
            append('|').append(safe.alphaFg).append('|').append(safe.alphaBg).append('|').append(safe.alphaError)
            append('|').append(safe.rtl).append('|').append(safe.densityDpi).append('|').append(safe.fontScale)
            append('|').append(safe.configVersion)
        }
        synchronized(document) { document.bitmaps[key]?.let { return it } }

        val opacities = opacityMap(document, kind, levels, safe)
        val variant = synchronized(document) {
            document.variants[key] ?: SVG.getFromString(withOpacities(document.source, opacities)).also {
                // AndroidSVG otherwise honors the source's absolute root width/height (20 or
                // 24 px) instead of the requested bitmap viewport. Set dimensions on this
                // geometry-specific variant so the viewBox fills the output bitmap rather than
                // remaining pinned to its upper-left corner.
                it.setDocumentWidth(contentWidth.toFloat())
                it.setDocumentHeight(contentHeight.toFloat())
                document.variants[key] = it
            }
        }
        val bitmap = createBitmap(outputWidth, contentHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val options = RenderOptions.create().viewPort(start.toFloat(), 0f, contentWidth.toFloat(), contentHeight.toFloat())
        variant.renderToCanvas(canvas, options)
        synchronized(document) { document.bitmaps[key] = bitmap }
        return bitmap
    }

    private fun composeBadge(
        document: Document,
        signal: Bitmap,
        config: IconSvgRenderConfig,
        badge: Bitmap
    ): Bitmap {
        val anchor = document.metadata.typeContainer ?: return signal
        val safe = config.safe()
        val contentHeight = max(1, ceil(safe.iconHeightPx * safe.scale).toInt())
        val aspect = document.metadata.viewBox.width() / document.metadata.viewBox.height()
        val contentWidth = max(1, ceil(contentHeight * aspect).toInt())
        val start = if (safe.rtl) safe.paddingEndPx else safe.paddingStartPx
        val scaleX = contentWidth / document.metadata.viewBox.width()
        val scaleY = contentHeight / document.metadata.viewBox.height()
        val anchorX = start + (anchor.centerX() - document.metadata.viewBox.left) * scaleX
        val anchorY = (anchor.centerY() - document.metadata.viewBox.top) * scaleY
        val badgeLeft = kotlin.math.round(anchorX - badge.width / 2f).toInt()
        val badgeTop = kotlin.math.round(anchorY - badge.height / 2f).toInt()
        val extraStart = (-badgeLeft).coerceAtLeast(0)
        val extraEnd = (badgeLeft + badge.width - signal.width).coerceAtLeast(0)
        val output = if (extraStart == 0 && extraEnd == 0) signal else {
            createBitmap((signal.width + extraStart + extraEnd).coerceAtMost(2048), signal.height, Bitmap.Config.ARGB_8888)
                .also { bitmap ->
                    Canvas(bitmap).drawBitmap(signal, extraStart.toFloat(), 0f, null)
                }
        }
        val canvas = Canvas(output)
        canvas.drawBitmap(
            badge,
            (badgeLeft + extraStart).toFloat(),
            badgeTop.coerceIn(-badge.height, output.height).toFloat(),
            null
        )
        return output
    }

    private fun opacityMap(
        document: Document,
        kind: SvgKind,
        levels: List<Int>,
        config: IconSvgRenderConfig
    ): Map<String, Float> {
        val result = LinkedHashMap<String, Float>()
        when (kind) {
            SvgKind.SINGLE_SIGNAL -> (1..4).forEach { result["signal_$it"] = segmentOpacity(levels[0], it, config) }
            SvgKind.STACKED_SIGNAL -> (1..2).forEach { row ->
                (1..4).forEach { level ->
                    result["signal_${row}_$level"] = segmentOpacity(levels[row - 1], level, config)
                }
            }
        }
        document.metadata.ids["type_container"]?.let { result["type_container"] = 0f }
        return result
    }

    private fun segmentOpacity(level: Int, segment: Int, config: IconSvgRenderConfig): Float = when {
        level < 0 -> config.alphaError
        level.coerceIn(0, 4) >= segment -> config.alphaFg
        else -> config.alphaBg
    }

    private fun withOpacities(source: String, opacities: Map<String, Float>): String =
        source.replace(Regex("<(?!/|!|\\?)([A-Za-z_][\\w:.-]*)(\\s[^<>]*?)?>")) { match ->
            val elementName = match.groupValues[1]
            val attributes = match.groupValues.getOrNull(2).orEmpty()
            val id = idPattern.find(attributes)?.groupValues?.getOrNull(2) ?: return@replace match.value
            val opacity = opacities[id] ?: return@replace match.value
            val selfClosing = attributes.trimEnd().endsWith('/')
            val body = if (selfClosing) attributes.trimEnd().dropLast(1) else attributes
            val rewritten = setOpacity(body, opacity)
            "<$elementName${rewritten}${if (selfClosing) " />" else ">"}"
        }

    private fun setOpacity(attributes: String, opacity: Float): String {
        val value = "%.5f".format(Locale.US, opacity.coerceIn(0f, 1f))
        opacityPattern.find(attributes)?.let { match ->
            return attributes.replaceRange(match.range, " opacity=\"$value\"")
        }
        stylePattern.find(attributes)?.let { match ->
            val oldStyle = match.groupValues[3]
            val style = if (styleOpacityPattern.containsMatchIn(oldStyle)) {
                oldStyle.replace(styleOpacityPattern, "opacity:$value")
            } else {
                "$oldStyle;opacity:$value"
            }
            return attributes.replaceRange(match.range, " style=\"$style\"")
        }
        return "$attributes opacity=\"$value\""
    }

    private fun requireGraphic(ids: Map<String, String>, id: String) {
        require(ids[id] in graphicElements) { "missing graphic element id=$id" }
    }

    private fun preflight(source: String) {
        val lower = source.lowercase(Locale.US)
        require(lower.trimStart().startsWith("<?xml") || lower.trimStart().startsWith("<svg")) {
            "SVG must start with an XML declaration or svg root"
        }
        require("<!doctype" !in lower && "<!entity" !in lower && "<![cdata[" !in lower) {
            "DOCTYPE/entities/CDATA are not supported"
        }
        require("<script" !in lower && "javascript:" !in lower) { "scripts are not supported" }
        // The standard SVG namespace is an http URL in the root xmlns attribute and is not an
        // external resource. Check actual resource-bearing attributes instead of rejecting the
        // namespace declaration itself; the old check made every built-in SVG unloadable.
        require(!externalReferencePattern.containsMatchIn(source)) {
            "external resource references are not supported"
        }
    }

    private fun parseMetadata(source: String): Metadata {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(source))
        }
        val ids = LinkedHashMap<String, String>()
        var rootSeen = false
        var depth = 0
        var nodes = 0
        var rootWidth = Float.NaN
        var rootHeight = Float.NaN
        var viewBox: RectF? = null
        var typeContainer: RectF? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    nodes++
                    require(nodes <= MAX_NODES) { "SVG has too many elements" }
                    depth++
                    require(depth <= 48) { "SVG nesting is too deep" }
                    val name = parser.name
                    require(name in supportedElements) { "unsupported SVG element: $name" }
                    if (!rootSeen) {
                        require(name == "svg") { "SVG root must be svg" }
                        rootSeen = true
                        rootWidth = numericAttr(parser, "width")
                        rootHeight = numericAttr(parser, "height")
                        viewBox = parseViewBox(parser.getAttributeValue(null, "viewBox"))
                    }
                    for (index in 0 until parser.attributeCount) {
                        val attrName = parser.getAttributeName(index)
                        val attrValue = parser.getAttributeValue(index).orEmpty()
                        require(!attrName.equals("href", true) && !attrName.endsWith(":href", true)) {
                            "external references are not supported"
                        }
                        require(!attrValue.contains("url(", true) || attrValue.contains("url(#")) {
                            "external URL references are not supported"
                        }
                        if (attrName.lowercase(Locale.US) in numericAttributes || attrName.equals("d", true)) {
                            numberPattern.findAll(attrValue).forEach { number ->
                                require(number.value.toFloatOrNull()?.isFinite() == true) {
                                    "SVG contains a non-finite coordinate"
                                }
                            }
                        }
                    if (attrName == "id") {
                        val id = attrValue.trim()
                        if (id.isNotEmpty()) require(ids.put(id, name) == null) { "duplicate SVG id=$id" }
                        if (id == "type_container" && name == "rect") {
                            val x = numericAttr(parser, "x")
                            val y = numericAttr(parser, "y")
                            val width = numericAttr(parser, "width")
                            val height = numericAttr(parser, "height")
                            if (x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
                                typeContainer = RectF(x, y, x + width, y + height)
                            }
                        }
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        require(rootSeen && depth == 0) { "SVG root is missing or malformed" }
        val box = viewBox ?: run {
            require(rootWidth.isFinite() && rootHeight.isFinite()) { "SVG needs viewBox or numeric width/height" }
            RectF(0f, 0f, rootWidth, rootHeight)
        }
        require(box.width() > 0f && box.height() > 0f) { "SVG dimensions must be positive" }
        return Metadata(
            box,
            ids,
            rootWidth.takeIf { it.isFinite() } ?: box.width(),
            rootHeight.takeIf { it.isFinite() } ?: box.height(),
            typeContainer
        )
    }

    private fun numericAttr(parser: XmlPullParser, name: String): Float {
        val raw = parser.getAttributeValue(null, name)?.trim().orEmpty()
        if (raw.isEmpty()) return Float.NaN
        val match = numberPattern.find(raw) ?: return Float.NaN
        return match.value.toFloatOrNull() ?: Float.NaN
    }

    private fun parseViewBox(raw: String?): RectF? {
        val values = raw?.let { numberPattern.findAll(it).mapNotNull { match -> match.value.toFloatOrNull() }.toList() }
        if (values == null || values.size != 4 || values.any { !it.isFinite() }) return null
        return RectF(values[0], values[1], values[0] + values[2], values[1] + values[3])
    }

    private fun IconSvgRenderConfig.safe(): IconSvgRenderConfig = copy(
        iconHeightPx = iconHeightPx.coerceIn(1, 512),
        scale = scale.takeIf { it.isFinite() }?.coerceIn(0.1f, 4f) ?: 1f,
        alphaFg = alphaFg.safeAlpha(1f),
        alphaBg = alphaBg.safeAlpha(0.35f),
        alphaError = alphaError.safeAlpha(0.7f),
        paddingStartPx = paddingStartPx.coerceIn(0, 512),
        paddingEndPx = paddingEndPx.coerceIn(0, 512),
        fontScale = fontScale.takeIf { it.isFinite() }?.coerceIn(0.1f, 4f) ?: 1f
    )

    private fun Float.safeAlpha(default: Float): Float = takeIf { isFinite() }?.coerceIn(0f, 1f) ?: default

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it) }
}
