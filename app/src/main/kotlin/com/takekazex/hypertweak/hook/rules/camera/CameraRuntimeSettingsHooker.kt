package com.takekazex.hypertweak.hook.rules.camera

import android.app.Activity
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker

/** Callback policy changes immediately; cached camera UI rebuilds on the next safe resume. */
object CameraRuntimeSettingsHooker : StaticHooker() {
    private val pages = generationMainResource(HostActivityRefresh()) { it.clear() }

    private var recording: java.lang.reflect.Method? by generationState { null }
    private var profilePending by generationState { false }

    override fun onHook() {
        prepareDexTargets(name = "camera.live-session-safety", resolve = {
            val semantics = CameraSemantics.create(CameraResolver.Ctx(classLoader, hookParam.appInfo), hookerName)
                ?: return@prepareDexTargets null
            semantics.cachedUnique("native recording state", validate = {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
            }) {
                semantics.dex.strings("pref_video_record_state").filter(CameraSemantics::booleanGetter)
            }
        }, install = { recording = it })
        val create = HostAccess.requireDeclaredMethod(Activity::class.java, "onCreate", android.os.Bundle::class.java, accessible = true)
        create.hook { before {
            HookFailurePolicy.open(hookerName, "initialize accepted camera profile", Unit) { applyProfile() }
        } }
        val resume = HostAccess.requireDeclaredMethod(Activity::class.java, "onPostResume", accessible = true)
        resume.hook {
            after { param ->
                val activity = param.thisObject as? Activity ?: return@after
                HookFailurePolicy.open(hookerName, "resume camera configuration", Unit) {
                    if (safeToRebuild()) {
                        applyProfile()
                        pages.resume(activity)
                    }
                }
            }
        }
    }

    fun refreshSettings(): Boolean = HostAccess.onMain {
        HookFailurePolicy.open(hookerName, "apply camera configuration", false) {
            profilePending = true
            CameraImpersonationHooker.refreshSettings()
            // Never recreate an active capture session from a settings notification.
            pages.request { it.packageName == "com.android.camera" }
            true
        }
    }

    private fun safeToRebuild(): Boolean = recording?.invoke(null) == false

    private fun applyProfile() {
        if (!profilePending || !safeToRebuild()) return
        CameraLegendaryProfileHooker.refreshSettings()
        CameraImpersonationHooker.refreshModeRegistries()
        profilePending = false
    }

    override fun recoverHotReloadHosts() { refreshSettings() }
}
