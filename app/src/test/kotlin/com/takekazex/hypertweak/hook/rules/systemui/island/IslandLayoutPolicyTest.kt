package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.LayoutFlags
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasureContext
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementRender
import com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandLayoutPolicy
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLayoutPolicyTest {
    private val big = Any()
    private val primary = Any()
    private val extra = Any()
    private val keys = mapOf<Any, String>(big to "big", primary to "small", extra to "extra")
    private val widths = mapOf<Any, Int>(primary to 80, extra to 70)
    private val seats = mapOf<Any, Boolean>(primary to false, extra to true)
    private val nativeResult = NativeMeasureResult(
        bigWidth = 280, bigWidthWithSmall = 350, bigXWithSmall = 400, marginWidth = 7,
    )

    private fun scene(
        width: Int = 1200,
        height: Int = 900,
        orientation: IslandOrientation = IslandOrientation.PORTRAIT,
        cutout: Int = 100,
        maxWidth: Int = 600,
    ) = IslandScene(width, height, clockWidth = 100, batteryWidth = 100, gap = 10,
        cutoutWidth = cutout, maxWidth = maxWidth, safe = SafeInsets.NONE, orientation = orientation)

    private fun context(
        scene: IslandScene,
        flags: LayoutFlags,
        contentSizes: Map<Any, IslandContentSize> = mapOf(big to IslandContentSize(100, 100)),
        extras: List<Any> = emptyList(),
        displayOrder: List<String> = emptyList(),
        nativeMusicRight: Boolean = false,
    ) = MeasureContext(
        scene = scene,
        big = big,
        primary = primary,
        stagedExtras = extras,
        contentSizes = contentSizes,
        nativeSmallWidth = { view -> widths[view] ?: 0 },
        keyOf = keys::get,
        seatLeft = seats::get,
        displayOrder = displayOrder,
        flags = flags,
        minBigWidth = 200,
        nativeResult = nativeResult,
        nativeMusicRight = nativeMusicRight,
    )

    private fun flags(
        full: Boolean = false,
        more: Boolean = false,
        portrait: Boolean = false,
    ) = LayoutFlags(landscapeFull = full, landscapeMore = more, portraitCompact = portrait,
        rowMode = full || more, summarySwipe = true)

    @Test fun `opposite media width never becomes the left timer spacing`() {
        val input = context(scene(cutout = 72), flags(portrait = true),
            contentSizes = mapOf(big to IslandContentSize(93, 131)), extras = listOf(extra))
        for (mediaWidth in listOf(102, 180, 240)) {
            val outcome = IslandLayoutPolicy.measure(input.copy(nativeSmallWidth = {
                if (it === primary) mediaWidth else 102
            }))
            val layout = checkNotNull(outcome.layout)
            assertEquals(471, layout.bigX)
            assertEquals(359, layout.leftX)
            assertEquals(777, layout.rightX)
            assertEquals(10, layout.bigX - layout.leftX - 102)
        }
        val mediaExpanded = IslandLayoutPolicy.measure(input.copy(primary = null,
            nativeSmallWidth = { 102 }))
        assertEquals(359, checkNotNull(mediaExpanded.layout).leftX)
    }

    @Test fun `balanced compact row also keeps each sides own width`() {
        val input = context(scene(cutout = 72), flags(), extras = listOf(extra))
        val layout = checkNotNull(IslandLayoutPolicy.measure(input.copy(nativeSmallWidth = {
            if (it === primary) 180 else 102
        })).layout)
        assertEquals(10, layout.bigX - layout.leftX - 102)
        assertEquals(10, layout.rightX - layout.bigX - layout.bigWidth)
    }

    @Test fun `revealed third seat survives oversized media main content`() {
        val input = context(scene(cutout = 72, maxWidth = 722), flags(portrait = true),
            contentSizes = mapOf(big to IslandContentSize(332, 325)), extras = listOf(extra))
            .copy(nativeResult = nativeResult.copy(bigWidth = 722))
        val outcome = IslandLayoutPolicy.measure(input)
        val layout = checkNotNull(outcome.layout)
        val content = checkNotNull(outcome.portraitContent)
        assertEquals(listOf(extra), outcome.stagedExtras)
        assertTrue(layout.leftX >= input.scene.clockWidth)
        assertTrue(layout.rightX + widths.getValue(primary) <= 1100)
        assertEquals(564, layout.bigX + content.left)
        assertEquals(layout.bigWidth, content.width + 72)
        assertEquals(72, outcome.templateRequests.single().cutoutWidth)
    }

    @Test fun `compact fallback still rejects a row below the native minimum`() {
        val input = context(scene(width = 420), flags(portrait = true),
            contentSizes = mapOf(big to IslandContentSize(332, 325)), extras = listOf(extra))
        val outcome = IslandLayoutPolicy.measure(input)
        assertEquals(null, outcome.layout)
        assertTrue(outcome.stagedExtras.isEmpty())
    }

    @Test fun `balance switch retains or removes excess width without changing task count`() {
        val input = context(scene(orientation = IslandOrientation.LANDSCAPE), flags(full = true),
            contentSizes = mapOf(big to IslandContentSize(60, 200), primary to IslandContentSize(80, 80)))
        val trimmed = IslandLayoutPolicy.measure(input)
        val balanced = IslandLayoutPolicy.measure(input.copy(removePlaceholder = false))
        assertEquals(IslandContentSize(60, 200), trimmed.fullFrames.getValue(big).content)
        assertEquals(IslandContentSize(200, 200), balanced.fullFrames.getValue(big).content)
        assertEquals(trimmed.fullFrames.keys, balanced.fullFrames.keys)
        assertEquals(140, balanced.fullFrames.getValue(big).width - trimmed.fullFrames.getValue(big).width)
        assertEquals(trimmed.templateRequests.map { it.mode }, balanced.templateRequests.map { it.mode })
    }

    @Test fun `portrait compact layout keeps the camera seat and requests its main template`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(), flags(portrait = true),
            contentSizes = mapOf(big to IslandContentSize(200, 200)),
            extras = listOf(extra),
        ))
        val layout = checkNotNull(outcome.layout)
        assertEquals(350, layout.bigX)
        assertEquals(500, layout.bigWidth)
        assertEquals(270, layout.leftX)
        assertEquals(860, layout.rightX)
        assertEquals(IslandContentSize(200, 200), outcome.portraitContent)
        assertTrue(outcome.fullFrames.isEmpty())
        assertEquals(listOf(extra), outcome.stagedExtras)
        assertEquals(listOf(big), outcome.templateRequests.map { it.view })
        assertEquals(100, outcome.templateRequests.single().cutoutWidth)
        assertEquals(TemplateSeatMode.NORMAL, outcome.templateRequests.single().mode)

        val applied = IslandLayoutPolicy.render(outcome, big, big, isSummary = true,
            nativeMarginWidth = 9, nativeCutoutWidth = 100)
        val geometry = (applied as MeasurementRender.Apply).geometry
        assertEquals(500, geometry.width)
        assertEquals(200, geometry.left)
        assertEquals(200, geometry.right)
        assertEquals(350, geometry.x)
        assertEquals(9, geometry.margin)
        assertEquals(MeasurementRender.Skip, IslandLayoutPolicy.render(outcome, extra, big,
            isSummary = true, nativeMarginWidth = 9, nativeCutoutWidth = 100))
    }

    @Test fun `landscape full row publishes every full frame and releases the camera seat`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(orientation = IslandOrientation.LANDSCAPE, cutout = 0),
            flags(full = true),
            contentSizes = mapOf(
                big to IslandContentSize(100, 100),
                primary to IslandContentSize(40, 40),
                extra to IslandContentSize(35, 35),
            ),
            extras = listOf(extra),
        ))
        val layout = checkNotNull(outcome.layout)
        assertTrue(layout.full)
        assertEquals(listOf("extra", "big", "small"), outcome.displayOrder)
        assertEquals(setOf(big, primary, extra), outcome.fullFrames.keys)
        assertTrue(outcome.fullFrames.values.all { it.full })
        assertEquals(3, outcome.templateRequests.size)
        assertTrue(outcome.templateRequests.all { it.mode == TemplateSeatMode.CAMERA_RELEASING })
        val frame = checkNotNull(outcome.fullFrames[extra])
        val geometry = (IslandLayoutPolicy.render(outcome, extra, big, isSummary = true,
            nativeMarginWidth = 9, nativeCutoutWidth = 0) as MeasurementRender.Apply).geometry
        assertEquals(frame.x, geometry.x)
        assertEquals(frame.width, geometry.width)
        assertEquals(0, geometry.margin)
    }

    @Test fun `more-only row keeps borrowed summaries compact and leases only the main frame`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(orientation = IslandOrientation.LANDSCAPE, cutout = 0),
            flags(more = true),
            contentSizes = mapOf(big to IslandContentSize(100, 100)),
            extras = listOf(extra),
        ))
        val layout = checkNotNull(outcome.layout)
        assertTrue(layout.full)
        assertEquals(setOf(big, primary, extra), outcome.fullFrames.keys)
        assertTrue(outcome.fullFrames.getValue(big).full)
        assertTrue(!outcome.fullFrames.getValue(primary).full)
        assertTrue(!outcome.fullFrames.getValue(extra).full)
        assertEquals(listOf(big), outcome.templateRequests.map { it.view })
        assertEquals(MeasurementRender.Skip, IslandLayoutPolicy.render(outcome, primary, big,
            isSummary = true, nativeMarginWidth = 9, nativeCutoutWidth = 0))
    }

    @Test fun `stable display order survives a newly admitted island`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(orientation = IslandOrientation.LANDSCAPE, cutout = 0),
            flags(full = true),
            contentSizes = mapOf(
                big to IslandContentSize(100, 100),
                primary to IslandContentSize(40, 40),
                extra to IslandContentSize(35, 35),
            ),
            extras = listOf(extra),
            displayOrder = listOf("extra", "big", "small"),
        ))
        assertEquals(listOf("extra", "big", "small"), outcome.displayOrder)
        val xs = outcome.fullFrames.entries.sortedBy { outcome.displayOrder.indexOf(keys[it.key]) }.map { it.value.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun `oversized row falls back to the native two-slot compact layout`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(width = 600, orientation = IslandOrientation.LANDSCAPE, cutout = 0),
            flags(full = true),
            contentSizes = mapOf(big to IslandContentSize(150, 150)),
            extras = listOf(extra),
        ))
        val layout = checkNotNull(outcome.layout)
        assertTrue(!layout.full)
        assertEquals(220, layout.bigWidth)
        assertEquals(listOf(extra), outcome.stagedExtras)
        assertTrue(outcome.fullFrames.isEmpty())
        assertTrue(outcome.templateRequests.isEmpty())
    }

    @Test fun `native two-slot media layout projects the asymmetric host allocation`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(cutout = 100), flags(), nativeMusicRight = true,
        ))
        val layout = checkNotNull(outcome.layout)
        assertEquals(400, layout.bigX)
        assertEquals(350, layout.bigWidth)
        assertEquals(310, layout.leftX)
        assertEquals(760, layout.rightX)
        assertTrue(!layout.full)
        assertEquals(MeasurementRender.Skip, IslandLayoutPolicy.render(outcome, big, big,
            isSummary = true, nativeMarginWidth = 7, nativeCutoutWidth = 100))
        // The native two-slot branch keeps the host's own result; geometry only feeds lookups.
        assertEquals(MeasurementRender.Skip, IslandLayoutPolicy.render(outcome, primary, big,
            isSummary = true, nativeMarginWidth = 7, nativeCutoutWidth = 100))
    }

    @Test fun `width narrower than the camera cutout invalidates the published layout`() {
        val outcome = IslandLayoutPolicy.measure(context(
            scene(width = 1000, cutout = 300), flags(),
            contentSizes = mapOf(big to IslandContentSize(100, 100)),
            extras = listOf(extra),
        ))
        assertEquals(MeasurementRender.Invalidate, IslandLayoutPolicy.render(outcome, extra, big,
            isSummary = true, nativeMarginWidth = 7, nativeCutoutWidth = 300))
    }
}
