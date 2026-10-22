package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

/**
 * Where the host currently is in the keyguard/full-AOD lifecycle, as far as the module can prove
 * it from the host's own state.
 *
 * The module must distinguish four separate facts before it may touch a keyguard battery row:
 * whether the ROM supports full-screen AOD, whether the user enabled full-screen AOD, where the
 * host actually is right now (transitioning, dozing, or a lit lock screen), and whether the module
 * owns the AOD row. Mixing "the ROM supports full-screen AOD" with "the host is showing AOD" is
 * what let an AOD battery icon option hide the ordinary lock-screen battery.
 */
internal enum class AodHostPhase {
    /** No keyguard host, or a state the module cannot classify: keep the native result. */
    UNKNOWN,
    /** Full-AOD enter/exit animation is running on the keyguard status bar. */
    TRANSITIONING,
    /** The host is settled in AOD (or legacy AOD with the keyguard header faded). */
    DOZING,
    /** The keyguard is showing while the display is lit or waking. */
    LIT
}

/** What the module is allowed to change on the keyguard battery row in one host phase. */
internal enum class KeyguardRowOwner {
    /** Nothing: the host's own visibility, alpha and layout references stay untouched. */
    NONE,
    /**
     * Duo replaces the cluster for ordinary (non-AOD) lock-screen use. Ownership follows the
     * accepted lock/duo switches exactly as before, never the AOD options.
     */
    DUO,
    /** Full-AOD row: the native battery children (or Duo) render the AOD configuration. */
    AOD_NATIVE,
    /** Full-AOD row rendered by Duo. */
    AOD_DUO,
    /**
     * No AOD replacement renderer (legacy AOD, or full-AOD support unavailable): keep the native
     * keyguard cluster leased to Duo so the host's fade never exposes the stock row.
     */
    AOD_HOLD
}

/**
 * Pure keyguard-row decision. Inputs are only observable facts; this type never reads preferences
 * or host fields itself, so every transition is a unit-testable function of its inputs.
 */
internal class KeyguardRowDecision private constructor(
    val phase: AodHostPhase,
    val owner: KeyguardRowOwner,
    /** The module's keyguard cluster lease must stay on while the replacement owns it. */
    val holdsCluster: Boolean,
    /** AOD options may be applied to the native battery children in this phase. */
    val aodMarksNativeRow: Boolean,
    /** The native battery row must keep its own visibility in this phase. */
    val keepsNativeRow: Boolean
) {
    val hidesNativeRow: Boolean get() = !keepsNativeRow
    val duoSupersedesAod: Boolean get() = owner == KeyguardRowOwner.DUO

    companion object {
        fun of(phase: AodHostPhase, owner: KeyguardRowOwner) = KeyguardRowDecision(
            phase = phase,
            owner = owner,
            holdsCluster = owner != KeyguardRowOwner.NONE,
            aodMarksNativeRow = owner == KeyguardRowOwner.AOD_NATIVE,
            keepsNativeRow = owner == KeyguardRowOwner.NONE || owner == KeyguardRowOwner.DUO
        )
    }
}

internal object AodKeyguardPolicy {
    /**
     * Decides who owns the keyguard battery row.
     *
     * - An unknown host state keeps the native row: the AOD options must never suppress the
     *   ordinary lock-screen battery just because the module cannot tell where the host is.
     * - A lit lock screen never carries AOD configuration. AOD battery options therefore cannot
     *   change its visibility, children, layout references or masks; only the accepted Duo switch
     *   replaces that cluster.
     * - During a transition the row belongs to the AOD renderer, because the host is carrying
     *   the keyguard row into (or out of) AOD and revealing the stock row here flashes it.
     * - A settled host with full-AOD rendering available uses the native AOD children, or Duo
     *   when the AOD Duo option is on. Without an AOD renderer the lease alone is held.
     */
    fun decide(
        phase: AodHostPhase,
        standardDuo: Boolean,
        aodCustomized: Boolean,
        aodDuo: Boolean,
        fullAodRendering: Boolean
    ): KeyguardRowDecision = when (phase) {
        AodHostPhase.UNKNOWN -> KeyguardRowDecision.of(phase, KeyguardRowOwner.NONE)
        AodHostPhase.LIT -> KeyguardRowDecision.of(
            phase,
            if (standardDuo) KeyguardRowOwner.DUO else KeyguardRowOwner.NONE
        )
        AodHostPhase.TRANSITIONING, AodHostPhase.DOZING -> KeyguardRowDecision.of(
            phase,
            when {
                aodDuo -> KeyguardRowOwner.AOD_DUO
                fullAodRendering -> KeyguardRowOwner.AOD_NATIVE
                aodCustomized -> KeyguardRowOwner.AOD_HOLD
                else -> KeyguardRowOwner.NONE
            }
        )
    }

    /**
     * Classifies the host from the authoritative dozing flag.
     *
     * [dozing] is `MiuiFullAodManager.isDozing`, written by the host's
     * `StatusBarStateController.StateListener.onDozingChanged`. It proves whether the AOD surface
     * is showing, so it - not `mToLockScreen` - decides between AOD and a lit lock screen:
     * - dozing with the keyguard status bar attached: the AOD row is settled;
     * - dozing without the keyguard host: the row cannot be owned, keep the host result;
     * - not dozing: a lit or waking lock screen; only the ordinary Duo switch may replace it.
     *
     * `mToLockScreen` cannot express this: it is rewritten only by the keyguard re-attachment
     * callback and the full-AOD handshakes, and stays false after full AOD exits.
     */
    fun phaseFromDozing(dozing: Boolean?, keyguardVisible: Boolean): AodHostPhase = when {
        !keyguardVisible -> AodHostPhase.UNKNOWN
        dozing == true -> AodHostPhase.DOZING
        // Unknown evidence may only resolve to "a lock screen exists", never to "AOD is showing".
        else -> AodHostPhase.LIT
    }

    /**
     * Full-AOD enter/exit handshake. The host rewrote `mToLockScreen` to [toLockScreen] before
     * rendering; false there during full AOD is the transition marker. The dozing flag, when the
     * host exposes it, always wins because it describes the surface that is actually showing.
     */
    fun phaseAfterHandshake(
        toLockScreen: Boolean,
        keyguardVisible: Boolean,
        dozing: Boolean?
    ): AodHostPhase = when {
        !keyguardVisible -> AodHostPhase.UNKNOWN
        dozing != null -> phaseFromDozing(dozing, keyguardVisible)
        toLockScreen -> AodHostPhase.LIT
        else -> AodHostPhase.TRANSITIONING
    }

    /**
     * The keyguard re-attachment callback rewrote `mToLockScreen` from
     * `isKeyguardShowing() && !fullAodEnable`. Without a dozing read this is weak evidence: it may
     * report a lit lock screen, never a settled AOD, because the field stays false after an exit.
     */
    fun phaseAfterKeyguardCallback(toLockScreen: Boolean): AodHostPhase =
        if (toLockScreen) AodHostPhase.LIT else AodHostPhase.TRANSITIONING

    /**
     * A render frame after a transition. Only the dozing flag can settle the row; without it the
     * transition owner is kept, which is still safe because the AOD options are only applied to
     * an attached keyguard host.
     */
    fun phaseAfterTransitionFrame(dozing: Boolean?, keyguardVisible: Boolean): AodHostPhase =
        if (dozing == true && keyguardVisible) AodHostPhase.DOZING else AodHostPhase.TRANSITIONING
}
