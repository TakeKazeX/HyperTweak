package com.takekazex.hypertweak.hook

/**
 * Street snap (街拍, camera mode id 225) unlock-mode selection — the pure parsing/resolution
 * half of [Preferences.KEY_CAMERA_STREET_MODE], kept free of Android types so the migration
 * rules stay unit-testable (`app/src/test/.../hook/CameraStreetModeTest.kt`).
 *
 * Two user-facing unlock strategies exist because the stock gate
 * (`StreetModuleEntry.support()` = device-config `a3()`) sits BEHIND the capability-config
 * swap of the camera impersonation, and a mod that only flips that config stops unlocking
 * street entirely when the impersonation fails or is switched off. The selector therefore
 * offers the impersonation-integrated path and an impersonation-independent path:
 *
 *  - [MODE_NEW] (新街拍): force `a3()` true on the *impersonated* flagship config. Needs the
 *    impersonation master on the K100 Pro Max target; keeps quick-launch classification and
 *    every other `a3()` consumer consistent with a working street mode.
 *  - [MODE_COMPAT] (兼容模式街拍): force `StreetModuleEntry.support()` itself true on the REAL
 *    device config. Works whether or not the impersonation succeeds, touches nothing else
 *    (`a3()` stays native, so quick-launch keeps its stock CAPTURE classification), and still
 *    opens the HAL role-0 main camera.
 *
 * In both modes the entry shows up in the camera's 更多 (overflow) grid — no device config's
 * `M()` order array carries 225 on any verified build, so the carousel is never the landing
 * spot, exactly as on natively street-capable devices. 装备街拍 (229) depends on 17-Ultra
 * modular-lens cameras (13/7) and stays closed in every mode.
 */
internal object CameraStreetMode {

    /** Street snap hidden. */
    const val MODE_OFF = "off"

    /** Impersonation-integrated unlock: force the impersonated config's `a3()` true. */
    const val MODE_NEW = "new"

    /** Impersonation-independent unlock: force `StreetModuleEntry.support()` true. */
    const val MODE_COMPAT = "compat"

    /** Mode used when nothing (or nothing parsable) is stored — preserves the legacy default-on. */
    const val DEFAULT = MODE_NEW

    /** All selectable modes, in UI order ([index]/[fromIndex] map over this list). */
    val MODES = listOf(MODE_OFF, MODE_NEW, MODE_COMPAT)

    /**
     * Parses a stored preference value into a mode constant, or null when it is not one.
     * Matching is exact-after-trim (lowercase keys are written by this app only).
     */
    fun parse(raw: String?): String? = raw?.trim()?.takeIf { it in MODES }

    /**
     * Resolves the effective mode from the raw stores:
     *  - a parsable [stored] always wins;
     *  - when the new key was never written ([stored] == null) the superseded
     *    `camera_street_enable` boolean migrates (true → [MODE_NEW], false → [MODE_OFF]);
     *  - an UNPARSABLE stored value falls back to [DEFAULT] (not to the legacy boolean — a
     *    present-but-garbage key means the new scheme owns the setting);
     *  - nothing stored at all behaves like the legacy default (true → [MODE_NEW]).
     *
     * [legacyEnable] mirrors the legacy boolean: `null` = key absent, `true`/`false` = value.
     */
    fun resolve(stored: String?, legacyEnable: Boolean?): String {
        parse(stored)?.let { return it }
        if (stored != null) return DEFAULT
        return when (legacyEnable) {
            true -> MODE_NEW
            false -> MODE_OFF
            null -> DEFAULT
        }
    }

    /** UI list index of [mode]; unknown values clamp to the first entry (关闭/off). */
    fun index(mode: String?): Int = MODES.indexOf(mode).coerceAtLeast(0)

    /** Mode for a UI list index; out-of-range indices clamp into [MODES]. */
    fun fromIndex(index: Int): String = MODES[index.coerceIn(MODES.indices)]
}
