package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.BackGestureKeys

/** Whether the module's back gesture owns input; the vendored runtime reads this on every gesture. */
internal object BackGesturePrefs {
    /** An installed upstream back-gesture module owns input unless the user disabled its detection. */
    @JvmStatic fun upstreamBackGestureOwnsInput(): Boolean =
        com.takekazex.hypertweak.util.platform.UpstreamBackGesturePolicy.shouldYield(
            com.takekazex.hypertweak.util.platform.UpstreamBackGesture.installed,
            Preferences.backGestureBoolean(BackGestureKeys.KEY_BACK_GESTURE_UPSTREAM_DETECTION_DISABLED, false))

    @JvmStatic fun backGestureEnabled(): Boolean = !upstreamBackGestureOwnsInput() &&
        Preferences.backGestureBoolean(BackGestureKeys.KEY_BACK_GESTURE_ENABLED, false)
}
