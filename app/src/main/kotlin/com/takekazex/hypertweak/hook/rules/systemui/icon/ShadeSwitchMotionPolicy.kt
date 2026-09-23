package com.takekazex.hypertweak.hook.rules.systemui.icon

/** The host's switching flag can end while its progress animator is still between endpoints. */
internal object ShadeSwitchMotionPolicy {
    private const val ENDPOINT_EPSILON = 0.001f

    fun isActive(hostSwitching: Boolean, progress: Float): Boolean =
        hostSwitching || progress > ENDPOINT_EPSILON && progress < 1f - ENDPOINT_EPSILON

    /** ShadeSwitchController's 0 endpoint is Control Center; 1 is notifications. */
    fun controlCenterAtRest(progress: Float): Boolean = progress <= ENDPOINT_EPSILON
}
