package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.content.Context
import android.graphics.Picture
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostIconBridge
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import com.takekazex.hypertweak.presentation.icon.IconSvgRepository
import com.takekazex.hypertweak.presentation.icon.duo.DuoContent
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalBars
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.logging.DebugLog

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingIconStackedWeight by hookSetting(0f) { Preferences.iconConfig().float(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT) }

internal fun prepareDuoSignalArtworkSettings() { settingIconStackedWeight }

/** Signal documents and bounded AOD recordings; no host views or hook handles. */
internal class DuoSignalArtwork {
    data class CellularSignalAssets(
        val single: IconSvgRenderer.Document,
        val stacked: IconSvgRenderer.Document
    )
    private var cellularSignalAssets: CellularSignalAssets? = null
    private data class CellularPictureKey(val levels: List<Int>, val weight: Float)
    private val cellularPictures = LinkedHashMap<CellularPictureKey, Picture>()

    fun loadCellularSignalAssets(context: Context): CellularSignalAssets? {
        cellularSignalAssets?.let { return it }
        return runCatching {
            val moduleContext = context.createPackageContext(
                HostIconBridge.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val repository = IconSvgRepository(moduleContext)
            CellularSignalAssets(
                single = repository.loadSignalSingle(SignalSvgStyle.HYPEROS3).getOrThrow().document,
                stacked = repository.loadSignalStacked(SignalSvgStyle.HYPEROS3).getOrThrow().document
            ).also { cellularSignalAssets = it }
        }.onFailure { DebugLog.w(TAG, "cellular signal artwork unavailable", it) }
            .getOrNull()
    }

    fun renderCellularSignal(context: Context, content: DuoContent): Picture? {
        if (content.airplaneMode || content.wifiLevel != null ||
            !content.hasCellularSignal) return null
        val levels = content.cellularSignalLevels.take(2)
        if (levels.isEmpty()) return null
        val key = CellularPictureKey(levels, settingIconStackedWeight)
        return synchronized(cellularPictures) {
            cellularPictures[key]?.let { return it }
            val assets = loadCellularSignalAssets(context) ?: return null
            val picture = runCatching {
                IconSvgRenderer.signalPicture(if (levels.size > 1) assets.stacked else assets.single,
                    levels, weight = key.weight)
            }.onFailure { DebugLog.w(TAG, "cellular signal vector recording failed", it) }
                .getOrNull() ?: return null
            // Reuse the recording through pre-draw and panel callbacks. A new Picture would
            // also defeat the view's identity check and invalidate the same icon again.
            if (cellularPictures.size >= 32) cellularPictures.remove(cellularPictures.keys.first())
            cellularPictures[key] = picture
            picture
        }
    }
    /** Dot hand-off endpoint recordings are immutable per level; a drag requests them per frame. */
    private val destinationPictures = HashMap<Int, Picture>()
    fun destinationSignal(context: Context, level: Int): Picture? = synchronized(destinationPictures) {
        destinationPictures[level] ?: run {
            val assets = loadCellularSignalAssets(context) ?: return null
            runCatching { IconSvgRenderer.signalPicture(assets.single, listOf(level)) }
                .onFailure { DebugLog.w(TAG, "destination signal recording failed", it) }
                .getOrNull()?.also { destinationPictures[level] = it }
        }
    }
    private var morphResolved = false
    private var morphBars: DuoSignalBars? = null
    fun signalBars(context: Context): DuoSignalBars? {
        if (!morphResolved) {
            val assets = loadCellularSignalAssets(context) ?: return null
            morphResolved = true
            morphBars = runCatching {
                DuoSignalBars.fromDocument(assets.single)
            }.onFailure { DebugLog.w(TAG, "signal morph SVG contract unavailable", it) }.getOrNull()
        }
        return morphBars
    }

    // A dual-SIM ring can hand off one Wi-Fi dot row. Reconcile and handover request both
    // shapes in the same frame; a single last-key cache reparsed every contour on each switch.
    private var rowMorphWeight: Float? = null
    private val rowMorphs = HashMap<Int, List<SignalBarMorph>>()
    fun rowMorph(context: Context, row: Int, rows: Int): SignalBarMorph? {
        if (rows !in 1..2 || row !in 0 until rows) return null
        val assets = loadCellularSignalAssets(context) ?: return null
        val weight = settingIconStackedWeight
        if (rowMorphWeight != weight) {
            rowMorphWeight = weight
            rowMorphs.clear()
        }
        return rowMorphs.getOrPut(rows) {
            runCatching { (0 until rows).map { index ->
                SignalBarMorph.create(
                    if (rows > 1) assets.stacked else assets.single, assets.single, index, rows, .632f, weight)
            } }.onFailure { DebugLog.w(TAG, "cellular row morph unavailable", it) }.getOrDefault(emptyList())
        }.getOrNull(row)
    }

    fun retire() { rowMorphWeight = null; rowMorphs.clear(); morphResolved = false; morphBars = null; cellularSignalAssets = null; cellularPictures.clear(); destinationPictures.clear() }
    private companion object { const val TAG = "DuoSignal" }
}
