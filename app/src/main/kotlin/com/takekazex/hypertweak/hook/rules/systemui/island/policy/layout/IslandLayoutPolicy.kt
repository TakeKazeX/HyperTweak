package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasureContext
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementRender
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest

/**
 * Reproduces the host's branch order without touching Android objects:
 * row admission -> stable order -> full row -> portrait -> compact -> native two-slot.
 */
internal object IslandLayoutPolicy {
    fun measure(context: MeasureContext): MeasurementOutcome {
        val scene = context.scene
        val native = listOfNotNull(context.big, context.primary)
        val more = context.flags.landscapeMore
        val rowMode = context.flags.rowMode

        fun size(item: Any): IslandContentSize? =
            if (item === context.big || context.flags.landscapeFull) context.contentSizes[item]?.let {
                if (context.removePlaceholder) it
                else maxOf(it.left, it.right).let { side -> IslandContentSize(side, side) }
            }
            else context.nativeSmallWidth(item).takeIf { it > 0 }?.let { IslandContentSize(it, 0) }

        val selected = if (rowMode) {
            FullIslandRow.admit(native, context.stagedExtras) { items ->
                val sizes = items.mapNotNull(::size)
                sizes.size == items.size && FullIslandRow.calculate(
                    scene.screenWidth, scene.screenHeight, scene.clockWidth, scene.batteryWidth,
                    sizes, scene.gap, scene.safe.left, scene.safe.right,
                ) != null
            }
        } else {
            native
        }
        var extras = if (more) selected.filter { it !in native } else context.stagedExtras
        val all = (native + extras).distinct()
        val initial = all.sortedBy { item ->
            if (item === context.big) 1 else if (context.seatLeft(item) == true) 0 else 2
        }
        val order = (context.displayOrder + initial.mapNotNull(context.keyOf)).distinct()
        val ordered = all.sortedBy { item ->
            order.indexOf(context.keyOf(item)).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }
        val contents = ordered.mapNotNull(::size)
        val row = if (rowMode && contents.size == ordered.size) {
            FullIslandRow.calculate(
                scene.screenWidth, scene.screenHeight, scene.clockWidth, scene.batteryWidth,
                contents, scene.gap, scene.safe.left, scene.safe.right,
            )
        } else {
            null
        }

        val frames = LinkedHashMap<Any, FullIslandFrame>()
        var displayOrder = context.displayOrder
        var portraitContent: IslandContentSize? = null
        var layout: ThreeIslandLayout? = null
        val sideSources = listOfNotNull(context.primary) + extras
        val portrait = if (context.flags.portraitCompact) {
            context.contentSizes[context.big]?.let { content ->
                PortraitIslandLayout.calculate(
                    scene.screenWidth, scene.screenHeight, scene.clockWidth, scene.batteryWidth,
                    content, scene.cutoutWidth,
                    sideSources.maxOfOrNull(context.nativeSmallWidth) ?: 0, scene.gap,
                    sideSources.any { context.seatLeft(it) == true },
                    sideSources.any { context.seatLeft(it) != true },
                    scene.safe.left, scene.safe.right,
                )?.also { portraitContent = content }
            }
        } else {
            null
        }

        when {
            portrait != null -> layout = portrait
            row != null -> {
                ordered.zip(row).forEach { (item, frame) ->
                    frames[item] = frame.copy(full = item === context.big || context.flags.landscapeFull)
                }
                displayOrder = ordered.mapNotNull(context.keyOf)
                val centre = frames.getValue(context.big)
                val sides = ordered.filter { it !== context.big }.map { frames.getValue(it) }
                layout = ThreeIslandLayout(
                    centre.x, centre.width,
                    sides.firstOrNull { it.x < centre.x }?.x ?: centre.x,
                    sides.firstOrNull { it.x > centre.x }?.x ?: centre.x,
                    true, scene.screenWidth,
                )
            }
            context.flags.portraitCompact -> {
                extras = emptyList()
                layout = null
            }
            extras.firstOrNull() != null && context.primary != null -> {
                extras = extras.take(1)
                val extra = extras.first()
                layout = ThreeIslandLayout.calculate(
                    scene.screenWidth, scene.screenHeight, scene.clockWidth, scene.batteryWidth,
                    minOf(context.nativeResult.bigWidth, scene.maxWidth), context.minBigWidth,
                    maxOf(context.nativeSmallWidth(context.primary), context.nativeSmallWidth(extra)),
                    scene.gap, safeLeft = scene.safe.left, safeRight = scene.safe.right,
                )
            }
            context.primary != null && context.nativeMusicRight -> {
                val x = context.nativeResult.bigXWithSmall
                val width = context.nativeResult.bigWidthWithSmall
                val small = context.nativeSmallWidth(context.primary)
                layout = ThreeIslandLayout(
                    x, width, x - scene.gap - small, x + width + scene.gap,
                    false, scene.screenWidth,
                )
            }
            else -> layout = null
        }

        val outcome = MeasurementOutcome(
            layout = layout,
            fullFrames = frames,
            displayOrder = displayOrder,
            stagedExtras = extras,
            musicSmall = null,
            portraitContent = portraitContent,
            layoutIntent = context.layoutIntent,
            templateRequests = emptyList(),
        )
        return outcome.copy(templateRequests = requests(outcome, context.flags.rowMode, context.big, scene.cutoutWidth))
    }

    /** Recompute lease requests after a spectrum seat has been attached to the outcome. */
    fun requests(
        outcome: MeasurementOutcome,
        rowMode: Boolean,
        big: Any?,
        cutoutWidth: Int,
    ): List<TemplateWidthRequest> {
        val requests = ArrayList<TemplateWidthRequest>(outcome.fullFrames.size + 1)
        outcome.fullFrames.forEach { (view, frame) ->
            if (!frame.full) return@forEach
            requests += TemplateWidthRequest(
                view, 0,
                TemplateSeatPolicy.modeFor(outcome.musicSmall === view, rowMode, frame),
            )
        }
        if (outcome.portraitContent != null && big != null) {
            requests += TemplateWidthRequest(big, cutoutWidth, TemplateSeatMode.NORMAL)
        }
        return requests
    }

    /** Project the shared outcome onto the host's nine-int result for one measured view. */
    fun render(
        outcome: MeasurementOutcome,
        view: Any,
        big: Any?,
        isSummary: Boolean,
        nativeMarginWidth: Int,
        nativeCutoutWidth: Int,
    ): MeasurementRender {
        val layout = outcome.layout ?: return MeasurementRender.Skip
        if (outcome.stagedExtras.isEmpty() && outcome.portraitContent == null && view !in outcome.fullFrames) {
            return MeasurementRender.Skip
        }
        if (!isSummary) return MeasurementRender.Skip
        val frame = outcome.fullFrames[view]
        if (outcome.portraitContent != null && view !== big && frame?.full != true) {
            return MeasurementRender.Skip
        }
        if (frame != null && !frame.full) return MeasurementRender.Skip
        val cutout = if (frame != null) 0 else nativeCutoutWidth
        val width = frame?.width ?: layout.bigWidth
        val x = frame?.x ?: layout.bigX
        if (width < cutout) return MeasurementRender.Invalidate
        val portraitContent = outcome.portraitContent.takeIf { view === big }
        val left = frame?.content?.left ?: portraitContent?.left ?: ((width - cutout) / 2)
        val right = frame?.content?.right ?: portraitContent?.right ?: (width - cutout - left)
        return MeasurementRender.Apply(IslandResultGeometry(
            width = width,
            left = left,
            right = right,
            x = x,
            margin = if (frame != null) 0 else nativeMarginWidth,
            viewWidth = width,
            viewLeft = left,
            viewRight = right,
            viewX = x,
        ))
    }
}
