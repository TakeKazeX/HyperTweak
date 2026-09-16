package com.takekazex.hypertweak.hook.rules.googleapp

import android.content.Context
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    private val dexResolutionInFlight = AtomicInteger()
    private val preReplacedIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sourceDir: String? = null

    @Volatile
    private var installationStarted = false

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    /** True while this process owns the shared DexKit bridge. */
    val isResolvingDex: Boolean
        get() = dexResolutionInFlight.get() != 0

    override fun onHook() {
        if (hookParam.packageName != PACKAGE || installationStarted) return
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
        dexResolutionInFlight.incrementAndGet()
        try {
            DexKitManager.withBridge(apk) { bridge ->
                // The Lens entry hook resolves first on purpose: it is the one whose target the
                // Google App calls *during* its own cold start (the exported Lens activity asks for
                // the caller package and the eligibility decision as soon as it resumes), so its
                // query must not queue behind the two OMNI features' scans.
                runCatching {
                    GoogleAppLensEntryHooker.installWithBridge(bridge, existingHookIds)
                }.onFailure { failure ->
                    DebugLog.e(TAG, "Lens entry resolution failed", failure)
                }
                runCatching {
                    GoogleAppLiveTranslateHooker.installWithBridge(bridge, existingHookIds)
                }.onFailure { failure ->
                    DebugLog.e(TAG, "Live Translate resolution failed", failure)
                }
                runCatching {
                    GoogleAppAskAboutScreenHooker.installWithBridge(bridge, existingHookIds)
                }.onFailure { failure ->
                    DebugLog.e(TAG, "Ask Screen resolution failed", failure)
                }
            }
        } finally {
            dexResolutionInFlight.decrementAndGet()
            existingHookIds.forEach(preReplacedIds::remove)
        }
    }

    /** Recover the Google App base APK path for a hot-reload callback without a PackageLoaded param. */
    fun resolveSourceDir(): String? {
        sourceDir?.takeIf { it.isNotBlank() }?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val application = activityThread.getDeclaredMethod("currentApplication").invoke(null)
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

    override fun onPrepareHotReload() {
        // The old generation's targets remain available for replaceOldHandle() until the new
        // generation has consumed its old handles. Only reset the attach guard here.
        installationStarted = false
    }
}
