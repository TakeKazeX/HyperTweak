package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoContent

import android.content.Context
import android.graphics.Picture
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostIconBridge
import com.takekazex.hypertweak.presentation.icon.IconSvgRenderer
import com.takekazex.hypertweak.presentation.icon.IconSvgRepository
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.SignalSvgStyle
import com.takekazex.hypertweak.util.logging.DebugLog

/** Signal documents and bounded AOD recordings; no host views or hook handles. */
internal class DuoSignalArtwork {
    data class CellularSignalAssets(
        val single: IconSvgRenderer.Document,
        val stacked: IconSvgRenderer.Document
    )
    private var cellularSignalAssets: CellularSignalAssets? = null
    private val aodCellularPictures = LinkedHashMap<List<Int>, Picture>()
    fun renderAodCellularSignal(context: Context, content: DuoContent): Picture? {
        if (content.airplaneMode || content.wifiLevel != null ||
            !content.hasCellularSignal) return null
        val levels = content.cellularSignalLevels.take(2)
        if (levels.isEmpty()) return null
        val key = levels
        synchronized(aodCellularPictures) {
            aodCellularPictures[key]?.let { return it }
            val picture = renderCellularSignal(context, content) ?: return null
            if (aodCellularPictures.size >= 32) aodCellularPictures.remove(aodCellularPictures.keys.first())
            aodCellularPictures[key] = picture
            return picture
        }
    }

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
        val assets = loadCellularSignalAssets(context) ?: return null
        return runCatching {
            IconSvgRenderer.signalPicture(if (levels.size > 1) assets.stacked else assets.single, levels,
                weight = Preferences.getFloat(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f))
        }.onFailure { DebugLog.w(TAG, "cellular signal vector recording failed", it) }.getOrNull()
    }
    fun retire() { cellularSignalAssets = null; aodCellularPictures.clear() }
    private companion object { const val TAG = "DuoSignal" }
}
