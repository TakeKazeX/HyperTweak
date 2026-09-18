package com.takekazex.hypertweak.hook.rules.thememanager

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Shared resolution helpers for MIUI's theme DRM gate.
 *
 * Locally imported (third-party) `.mtz` themes carry no OMA DRM rights file, so
 * `miui.drm.DrmManager#isLegal` reports a mismatch for every component. Two separate processes
 * enforce that verdict:
 *  - `com.android.thememanager` checks the rights before applying a resource
 *    (see [ThemeManagerRightsCheckHooker]);
 *  - system_server re-validates `/data/system/theme/` a while after boot and every few hours and
 *    restores the default theme on failure (see
 *    [com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker]).
 *
 * Both hooks answer with the same enum constant, so it is resolved once here instead of through a
 * compile-time dependency on MIUI's framework jar (which is not on this module's compile
 * classpath).
 */
internal object ThemeDrmSupport {
    /** The only DRM verdict that lets a theme component through. */
    const val DRM_SUCCESS = "DRM_SUCCESS"

    /**
     * Returns the `DRM_SUCCESS` constant of [returnType] when it is MIUI's DRM result enum, or
     * null when the passed type is not that enum. Matching by constant name rather than by class
     * name keeps the hook working if the enum is ever moved or renamed.
     */
    fun drmSuccessConstant(returnType: Class<*>?): Any? {
        val constants = returnType?.enumConstants ?: return null
        return constants.firstOrNull { (it as? Enum<*>)?.name == DRM_SUCCESS }
    }

    /** Whether [returnType] is MIUI's DRM result enum. */
    fun isDrmResult(returnType: Class<*>?): Boolean = drmSuccessConstant(returnType) != null

    /**
     * Static `isLegal` verdicts declared on MIUI's DRM manager. The shipped build declares two
     * public overloads (content file / pre-computed asset hash) that both funnel into a private
     * one; hooking all of them keeps the bypass effective whichever entry point a caller uses.
     */
    fun isLegalOverloads(drmManager: Class<*>): List<Method> =
        drmManager.declaredMethods.filter { method ->
            method.name == "isLegal" &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterCount >= 2 &&
                isDrmResult(method.returnType)
        }
}
