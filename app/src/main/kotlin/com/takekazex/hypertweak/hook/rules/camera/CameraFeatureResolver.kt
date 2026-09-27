package com.takekazex.hypertweak.hook.rules.camera

import java.lang.reflect.Modifier

/** Semantic entry points shared by camera hookers; no obfuscated owner names live here. */
internal object CameraFeatureResolver {
    fun captureSettings(ctx: CameraResolver.Ctx, scope: String): Class<*>? =
        CameraResolver.resolveClassByStrings(
            scope, "camera_capture_settings_fragment", ctx, listOf("pref_camera_auto_fallback"),
        ) { type ->
            type.declaredMethods.any { it.name == "addPhotoPreferences" && it.parameterCount == 0 } &&
                type.declaredMethods.any { it.name == "addCurrentPreferences" && it.parameterCount == 0 }
        }

    fun selfieSettings(ctx: CameraResolver.Ctx, scope: String): Class<*>? =
        CameraResolver.resolveClassByStrings(
            scope, "camera_selfie_settings_fragment", ctx, listOf("category_ai_aperture", "category_beauty_nevus_wipe_switch"),
        ) { type ->
            type.declaredMethods.any { it.name == "addCurrentPreferences" && it.parameterCount == 0 }
        }

    fun commonSettings(ctx: CameraResolver.Ctx, scope: String): Class<*>? =
        CameraResolver.resolveClassByStrings(
            scope, "camera_common_settings_fragment", ctx,
            listOf("pref_front_mirror_boolean_key"),
        ) { type ->
            type.declaredMethods.any { it.name == "addCommonPreferences1" && it.parameterCount == 0 }
        }
}
