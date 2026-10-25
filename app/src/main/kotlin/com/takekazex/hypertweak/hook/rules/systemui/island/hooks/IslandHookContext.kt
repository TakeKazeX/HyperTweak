package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandAccess
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandHost
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaRenderContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandSessionState

internal class IslandHookContext(
    val access: NativeIslandAccess,
    val host: NativeIslandHost,
    val runtime: IslandSessionState,
    val registrar: IslandHookRegistrar,
    val spectrum: NativeMusicSpectrum?,
    val media: NativeMediaRenderContract?,
    val refreshSettings: () -> Unit,
)
