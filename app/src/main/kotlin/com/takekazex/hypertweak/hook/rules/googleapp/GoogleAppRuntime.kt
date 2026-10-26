package com.takekazex.hypertweak.hook.rules.googleapp

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexRuntime

import android.content.Context
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the independent Google App features using one DexKit bridge per process.
 *
 * This is the Kotlin equivalent of upstream `GoogleAppRuntime`: Live Translate and Lensient Ask
 * Screen remain separate feature implementations, but they share resolution ownership and the
 * hot-reload replacement boundary. Preferences are intentionally read by each callback, so a
 * transient remote-preferences outage cannot make a feature permanently miss its first attach.
 */
object GoogleAppRuntime : StaticHooker() {
    const val PACKAGE = "com.google.android.googlequicksearchbox"

    private const val TAG = "GoogleAppRuntime"
    private const val RETIRED_CAPABILITY = "google_lens_aim_screen_capability"
    private const val RETIRED_DIAGNOSTIC_PREFIX = "google_lens_diagnostic_"

    private val preReplacedIds = generationResource(ConcurrentHashMap.newKeySet<String>()) { it.clear() }

    private var sourceDir: String? by generationState { null }

    private var installationStarted by generationState { false }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE || installationStarted) return
        // LensExportedActivity (including its aliases) and LensientActivity execute here.
        // The current manifest was verified against 693c70e37b329840e93fe1b1683a251fea773f511adb4aa01b396193b8936ae2.
        // Interactor/search/background providers never own these UI gates; scanning their entire
        // APK at boot needlessly blocks bindApplication before the voice service can publish.
        if (!GoogleAppProcessPolicy.ownsUi(hookParam.packageName, hookParam.processName)) return
        installationStarted = true

        // The children are lifecycle participants so BaseHooker can collect and replace their
        // managed handles. Their own onHook methods are intentionally inert; all resolution below
        // happens through this one coordinator bridge.
        attach(GoogleAppLiveTranslateHooker, classLoader, hookParam)
        attach(GoogleAppAskAboutScreenHooker, classLoader, hookParam)
        attach(GoogleAppLensEntryHooker, classLoader, hookParam)

        val apk = hookParam.appInfo?.sourceDir?.takeIf { it.isNotBlank() }
            ?: resolveSourceDir()
        sourceDir = apk
        if (apk.isNullOrBlank()) {
            DebugLog.w(TAG, "Google App source unavailable; preserving native behavior")
            preReplacedIds.clear()
            return
        }

        val existingHookIds = preReplacedIds.toSet()
        // This framework hook is independent of DexKit, matching upstream's install order and
        // keeping the cheap system-feature compatibility path available if native resolution fails.
        runCatching {
            GoogleAppLiveTranslateHooker.installSystemFeature(existingHookIds)
        }.onFailure { failure ->
            DebugLog.w(TAG, "Live Translate system-feature hook failed", failure)
        }
        // The Lens entry hook is the only one whose target the Google App calls *during* its own
        // cold start: the exported Lens activity asks for the caller package and the eligibility
        // decision as soon as it resumes. It therefore stays on the synchronous path.
        //
        // Live Translate and Ask Screen fire when the user actually invokes those features, which
        // is always after this process is up. Resolving them here made every Google App start pay
        // the whole three-feature graph walk on the main thread inside bindApplication (2301 ms and
        // 2002 ms on 2026-10-24 across two starts); deferring them to the shared preparation moves
        // that work off the startup path without changing when either feature can first fire.
        var lensResolved = false
        try {
            runCatching {
                GoogleAppLensEntryHooker.installEntry(apk, existingHookIds)
                lensResolved = true
            }.onFailure { DebugLog.e(TAG, "Lens entry resolution failed", it) }
        } finally {
            existingHookIds.forEach(preReplacedIds::remove)
        }
        // The deferred pass needs the carried ids itself: they are consumed here, so they are
        // captured before the clear above rather than re-read from the map later.
        deferFeatureTargets(apk, existingHookIds, lensResolved)
    }

    /** Resolve both graphs off the main thread, then publish under the managed generation fence. */
    private fun deferFeatureTargets(apk: String, carried: Set<String>, lensResolved: Boolean) {
        prepareDexTargets(name = "googleapp.omni",
            resolve = {
                DexRuntime.withScope(apk) {
                    listOfNotNull(
                        runCatching { GoogleAppLiveTranslateHooker.prepareInstall(apk, carried) }
                            .onFailure { DebugLog.e(TAG, "Live Translate resolution failed", it) }.getOrNull(),
                        runCatching { GoogleAppAskAboutScreenHooker.prepareInstall(apk, carried) }
                            .onFailure { DebugLog.e(TAG, "Ask Screen resolution failed", it) }.getOrNull(),
                    )
                }
            },
            install = { actions ->
                actions.forEach { action -> runCatching(action)
                    .onFailure { DebugLog.e(TAG, "OMNI hook publication failed", it) } }
                DebugLog.i(TAG, "OMNI hooks published lensResolved=$lensResolved")
            })
    }

    /** Recover the Google App base APK path for a hot-reload callback without a PackageLoaded param. */
    fun resolveSourceDir(): String? {
        sourceDir?.takeIf { it.isNotBlank() }?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val application = HostAccess.requireDeclaredMethod(activityThread, "currentApplication", accessible = false).invoke(null)
            (application as? Context)?.applicationInfo?.sourceDir
        }.onFailure { failure ->
            DebugLog.w(TAG, "Could not recover Google App source path", failure)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        sourceDir = resolved
        return resolved
    }

    /**
     * Replace a carried old-generation Google hook before package dispatch. Returning true means
     * the caller may mark the old handle consumed; the replacement itself keeps that hook alive
     * while the new generation is being attached.
     */
    fun replaceOldHandle(handle: XposedInterface.HookHandle): Boolean {
        val id = handle.id ?: return false
        val owner = replacementOwner(id) ?: return false
        val replacement = replacement(id) ?: return false
        return runCatching {
            val replaced = handle.replaceHook(replacement)
            owner.adoptManagedHookHandle(replaced)
            preReplacedIds += id
            true
        }.onFailure { failure ->
            DebugLog.w(TAG, "Failed to replace old Google hook id=$id", failure)
        }.getOrDefault(false)
    }

    /** Returns the callback for a stable Google hook id, including retired hooks. */
    fun replacement(id: String): XposedInterface.Hooker? {
        GoogleAppLiveTranslateHooker.replacement(id)?.let { return it }
        GoogleAppAskAboutScreenHooker.replacement(id)?.let { return it }
        GoogleAppLensEntryHooker.replacement(id)?.let { return it }
        if (id == RETIRED_CAPABILITY || id.startsWith(RETIRED_DIAGNOSTIC_PREFIX)) {
            return XposedInterface.Hooker { chain -> chain.proceed() }
        }
        return null
    }

    private fun replacementOwner(id: String): StaticHooker? {
        if (GoogleAppLiveTranslateHooker.replacement(id) != null) {
            return GoogleAppLiveTranslateHooker
        }
        if (GoogleAppAskAboutScreenHooker.replacement(id) != null) {
            return GoogleAppAskAboutScreenHooker
        }
        if (GoogleAppLensEntryHooker.replacement(id) != null) {
            return GoogleAppLensEntryHooker
        }
        if (id == RETIRED_CAPABILITY || id.startsWith(RETIRED_DIAGNOSTIC_PREFIX)) {
            return this
        }
        return null
    }

}
