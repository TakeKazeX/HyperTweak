package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaRenderContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum

/** Per-generation composition root. Lazy capabilities preserve the existing initialization order. */
internal class IslandRuntime(
    host: IslandHostPorts,
    preferences: IslandSettings,
    retiring: () -> Boolean,
    musicSpectrum: NativeMusicSpectrum?,
    mediaRender: NativeMediaRenderContract?,
) : IslandSessionState(host, preferences, retiring, musicSpectrum, mediaRender) {
    override val diagnostics by lazy { DiagnosticsCoordinator(this) }
    override val spectrum by lazy { SpectrumCoordinator(this) }
    override val templates by lazy { TemplateCoordinator(this) }
    override val relayout by lazy { RelayoutCoordinator(this) }
    override val settlement by lazy { SettlementCoordinator(this) }
    override val measurement by lazy { MeasurementCoordinator(this) }
    override val gestures by lazy { GestureCoordinator(this) }
    override val coordinates by lazy { CoordinatesCoordinator(this) }
    override val presentation by lazy { PresentationCoordinator(this) }

    init {
        boundary.restorer = { settlement.restore(it) }
    }
}
