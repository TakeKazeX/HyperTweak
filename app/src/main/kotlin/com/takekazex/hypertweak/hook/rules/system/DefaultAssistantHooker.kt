package com.takekazex.hypertweak.hook.rules.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method

/**
 * Repairs the platform state that keeps long-press power from reaching the assistant selected in
 * Settings → 默认应用 → 默认数字助理应用 (AOSP's `android.app.role.ASSISTANT`).
 *
 * ## What HyperOS changed
 *
 * AOSP keeps two values in step:
 * - `Settings.Secure.assistant` — the assistant *component*, which is what
 *   `AssistUtils.getAssistComponentForUser()` returns and what SystemUI actually launches;
 * - `Settings.Secure.voice_interaction_service` — the active voice-interaction service, which
 *   `AssistUtils.getActiveServiceComponentName()` reports.
 *
 * AOSP's `VoiceInteractionManagerService$RoleObserver` writes **both** whenever the ASSISTANT role
 * changes: the role holder's `VoiceInteractionService` when that service declares `supportsAssist`
 * and a recognition service, otherwise the role holder's `ACTION_ASSIST` *activity*.
 *
 * HyperOS keeps XiaoAI as the voice-interaction service and never aligns it with the role.
 * Verified on OS4.0.0.30: role holder = `com.google.android.googlequicksearchbox`,
 * `assistant` = `...GsaVoiceInteractionService`, active service =
 * `com.miui.voiceassist/...AssistInteractionService`. `AssistManager` (SystemUI) then takes its
 * broken branch — it treats the assistant component as launchable and does
 * `assistIntent.setComponent(getAssistComponentForUser()); startActivity(...)` — which for a
 * *service* component cannot work:
 *
 * ```
 * ActivityStarterImpl: Error: Activity class {.../GsaVoiceInteractionService} does not exist.
 * ```
 *
 * ## What this hooker does
 *
 * While the power-button action is the default assistant, it applies AOSP's own alignment — the
 * one `RoleObserver` would have applied:
 *
 * - when the role holder declares a usable `VoiceInteractionService` (its voice-interaction
 *   metadata resolves, `supportsAssist` is true and it names a recognition service), both
 *   `assistant` **and** `voice_interaction_service` are set to that service. `AssistManager` then
 *   finds `getAssistComponentForUser() == getActiveServiceComponentName()` and takes the session
 *   branch, so the assistant's real voice session starts (Gemini / Assistant UI), not a bare
 *   activity;
 * - otherwise `assistant` is set to the role holder's `ACTION_ASSIST` activity and
 *   `voice_interaction_service` is left alone, which is AOSP's representation for an assistant
 *   that is not a voice-interaction service.
 *
 * An earlier revision always wrote the activity form. It made the launch "succeed", but through
 * `AssistManager`'s bare `startActivity` branch, which opens the Google app's assist gateway
 * instead of engaging the assistant — so the second form above is now preferred whenever the
 * service is usable.
 *
 * The previous values are saved in secure settings and restored as soon as the action is no longer
 * the default assistant, so this is not a one-way change to global settings.
 *
 * The hooker installs no framework hooks; it aligns state and keeps it aligned by observing the
 * settings AOSP itself rewrites. Everything it touches outside the public SDK
 * (`ActivityThread.getSystemContext`, `RoleManager.getRoleHoldersAsUser`,
 * `Context.startActivityAsUser`, `VoiceInteractionServiceInfo`,
 * `IVoiceInteractionManagerService`) goes through reflection because the module is compiled
 * against the public stub jar.
 */
object DefaultAssistantHooker : StaticHooker() {
    override val hookerName = "DefaultAssistant"
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "DefaultAssistant"
    private const val ROLE_ASSISTANT = "android.app.role.ASSISTANT"
    private const val SETTING_ASSISTANT = "assistant"
    private const val SETTING_VOICE_SERVICE = "voice_interaction_service"
    private const val ACTION_VOICE_INTERACTION_SERVICE = "android.service.voice.VoiceInteractionService"

    /** `Context.VOICE_INTERACTION_MANAGER_SERVICE`; the Binder name has no underscore. */
    private const val SERVICE_VOICE_INTERACTION = "voiceinteraction"

    /**
     * Flags for loading a `VoiceInteractionService`'s `ServiceInfo`. `GET_META_DATA` is required or
     * `loadXmlMetaData` returns null. The other two bits are the `@hide`
     * `MATCH_KNOWN_PACKAGES | MATCH_INSTANT` the framework's own lookup
     * (`VoiceInteractionServiceInfo#getServiceInfoOrThrow`) passes; kept numerically because they
     * are not in the public SDK.
     */
    private const val VOICE_SERVICE_INFO_FLAGS =
        PackageManager.GET_META_DATA or 0x00080000 or 0x00040000

    /** Preference slots holding what this hooker overwrote, so the change can be undone. */
    // Recorded originals live in Settings.Secure, not Preferences: this hooker runs in
    // system_server, where Preferences writes do not reliably persist. See SecureSettingStore.
    private const val BACKUP_ASSISTANT = SecureSettingStore.ASSISTANT
    private const val BACKUP_VOICE = SecureSettingStore.VOICE_INTERACTION_SERVICE

    /** The component `AssistManager` should launch for the selected assistant. */
    data class Target(
        val packageName: String,
        /** The assistant's `ACTION_ASSIST` activity, when it declares one. */
        val activity: ComponentName?,
        /** The assistant's `VoiceInteractionService`, when it declares one. */
        val voiceService: ComponentName?
    )

    @Volatile
    private var observerInstalled = false

    @Volatile
    private var settingObserver: android.database.ContentObserver? = null

    override fun onHook() {
        applyAlignment()
        installSettingObserver()
    }

    override fun onPrepareHotReload() {
        // The alignment is a setting write, not a hook. Unregister the old observer before the
        // replacement generation installs its own one; otherwise every hot reload leaves a live
        // callback behind and each assistant-setting update is processed multiple times.
        val observer = settingObserver
        if (observer != null) {
            runCatching { systemContext()?.contentResolver?.unregisterContentObserver(observer) }
                .onFailure { DebugLog.w(SCOPE, "could not unregister the assistant observer", it) }
            settingObserver = null
        }
        observerInstalled = false
    }

    /**
     * Brings the assistant setting in line with the current power-button action: aligned while the
     * action is the default assistant, restored otherwise. Safe to call repeatedly.
     */
    fun applyAlignment() {
        if (!isDefaultAssistantAction()) {
            restore()
            return
        }
        val context = systemContext() ?: return
        runCatching { align(context) }.onFailure {
            DebugLog.w(SCOPE, "could not align the assistant setting", it)
        }
    }

    /** True when `AssistManager`'s own path can launch the selected assistant as configured. */
    fun platformPathUsable(context: Context): Boolean {
        val component = ComponentName.unflattenFromString(currentAssistantSetting(context).orEmpty())
            ?: return false
        // AssistManager renders the assist intent with the assistant component, so that component
        // must be an activity, or the active voice service (the branch that shows a session).
        if (resolveActivity(context, component) != null) return true
        return component == activeVoiceService()
    }

    /**
     * Starts the selected assistant directly, bypassing `AssistManager`. Used by
     * [PowerButtonCtsHooker] when the platform path would take its broken branch — for example
     * before this hooker's alignment has been applied, or on a build where the setting cannot be
     * written.
     */
    fun launchSelectedAssistant(context: Context, eventTime: Long, invocationType: Int): Boolean {
        val target = resolveTarget(context) ?: return false
        // A voice-interaction service must be shown through VIMS/AssistManager. Starting the
        // package's ACTION_ASSIST activity here is the exact failure this hook repairs: on GSA it
        // resolves to GoogleAppImplicitActionAssistGatewayInternal, which opens Google instead of
        // engaging Gemini. If the service path is temporarily unavailable, fail closed and let
        // the caller preserve the platform action rather than silently launch the wrong surface.
        if (target.voiceService != null && isVoiceServiceUsable(context, target.voiceService)) {
            DebugLog.w(
                SCOPE,
                "selected assistant has a usable voice service but the platform session " +
                    "path is unavailable; refusing ACTION_ASSIST activity fallback"
            )
            return false
        }
        val activity = target.activity ?: return false
        val intent = Intent(Intent.ACTION_ASSIST)
            .setComponent(activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("android.intent.extra.TIME", eventTime)
            .putExtra("invocation_type", invocationType)
            .putExtra("invocation_time_ms", android.os.SystemClock.elapsedRealtime())
            .putExtra("android.intent.extra.ASSIST_DISPLAY_ID", 0)
        // Hidden API: the module compiles against the public stub, so this is reflective.
        val started = runCatching {
            val method: Method = Context::class.java.getMethod(
                "startActivityAsUser",
                Intent::class.java,
                Class.forName("android.os.UserHandle")
            )
            method.invoke(context, intent, Process.myUserHandle())
            true
        }.getOrElse {
            runCatching {
                context.startActivity(intent)
                true
            }.getOrElse { t ->
                DebugLog.w(SCOPE, "could not launch the selected assistant", t)
                false
            }
        }
        if (started) DebugLog.i(SCOPE, "launched the selected assistant activity $activity")
        return started
    }

    // ─── Resolution ───────────────────────────────────────────────────────────

    /** The assistant the user chose: the ASSISTANT role holder, else whatever the setting names. */
    private fun assistantPackage(context: Context): String? {
        roleAssistantHolder(context)?.let { if (it.isNotEmpty()) return it }
        return ComponentName.unflattenFromString(currentAssistantSetting(context).orEmpty())?.packageName
    }

    /** Resolves the selected assistant's launchable activity and voice-interaction service. */
    private fun resolveTarget(context: Context): Target? {
        val packageName = assistantPackage(context) ?: return null
        return Target(
            packageName = packageName,
            activity = resolveAssistActivity(context, packageName),
            voiceService = resolveVoiceInteractionService(context, packageName)
        )
    }

    private fun resolveAssistActivity(context: Context, packageName: String): ComponentName? =
        runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_ASSIST).setPackage(packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { it.activityInfo?.let { info -> ComponentName(info.packageName, info.name) } }

    private fun resolveVoiceInteractionService(context: Context, packageName: String): ComponentName? =
        runCatching {
            context.packageManager.queryIntentServices(
                Intent(ACTION_VOICE_INTERACTION_SERVICE).setPackage(packageName),
                0
            )
        }.getOrDefault(emptyList())
            .firstNotNullOfOrNull { it.serviceInfo?.let { info -> ComponentName(info.packageName, info.name) } }

    private fun resolveActivity(context: Context, component: ComponentName): ComponentName? =
        runCatching {
            context.packageManager.getActivityInfo(component, 0)
            component
        }.getOrNull()

    /** `RoleManager.getRoleHoldersAsUser` is @hide, so this is reflective. */
    private fun roleAssistantHolder(context: Context): String? = runCatching {
        val roleManager = context.getSystemService("role") ?: return null
        @Suppress("UNCHECKED_CAST")
        val holders = roleManager.javaClass
            .getMethod("getRoleHoldersAsUser", String::class.java, Class.forName("android.os.UserHandle"))
            .invoke(roleManager, ROLE_ASSISTANT, Process.myUserHandle()) as? List<String>
        holders?.firstOrNull()
    }.onFailure {
        DebugLog.w(SCOPE, "could not read the ASSISTANT role holder", it)
    }.getOrNull()

    /** The active voice-interaction service, via the hidden AIDL. */
    private fun activeVoiceService(): ComponentName? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, SERVICE_VOICE_INTERACTION) as? android.os.IBinder ?: return null
        val stub = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
        val service = stub.getMethod("asInterface", android.os.IBinder::class.java)
            .invoke(null, binder) ?: return null
        // Resolve the method from the generated AIDL interface, not from the runtime Binder proxy.
        // On this ROM the proxy is a hidden framework implementation and reflective lookup on its
        // concrete class can fail even though the interface exposes the method.
        val interfaceClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        interfaceClass.getMethod("getActiveServiceComponentName").invoke(service) as? ComponentName
    }.onFailure {
        DebugLog.w(SCOPE, "could not read the active voice-interaction service", it)
    }.getOrNull()

    // ─── Alignment ────────────────────────────────────────────────────────────

    /**
     * Writes AOSP's alignment for the selected assistant, saving the previous values first so
     * [restore] can undo it.
     *
     * Preference order mirrors `VoiceInteractionManagerService$RoleObserver`: a usable
     * voice-interaction service wins, and the activity form is the fallback.
     */
    private fun align(context: Context) {
        val target = resolveTarget(context) ?: run {
            DebugLog.w(SCOPE, "no assistant is selected; leaving the settings alone")
            return
        }
        val voiceService = target.voiceService
        if (voiceService != null && isVoiceServiceUsable(context, voiceService)) {
            val desired = voiceService.flattenToShortString()
            rememberBackupIfNeeded(context)
            Settings.Secure.putString(context.contentResolver, SETTING_ASSISTANT, desired)
            Settings.Secure.putString(context.contentResolver, SETTING_VOICE_SERVICE, desired)
            DebugLog.i(SCOPE, "aligned assistant + voice service to $desired")
            return
        }
        val activity = target.activity ?: run {
            // Nothing launchable and no usable service: leave the settings untouched rather than
            // make them worse.
            DebugLog.w(
                SCOPE,
                "${target.packageName} has no ACTION_ASSIST activity and no usable " +
                    "voice-interaction service; settings left untouched"
            )
            return
        }
        val desired = activity.flattenToShortString()
        rememberBackupIfNeeded(context)
        // Activity form: `assistant` names it and the active voice service is left as it is, so a
        // device-wide voice service (XiaoAI here) keeps its role. AssistManager's else-branch then
        // renders the assist intent against this activity.
        Settings.Secure.putString(context.contentResolver, SETTING_ASSISTANT, desired)
        DebugLog.i(SCOPE, "aligned assistant to activity $desired (no usable voice service)")
    }

    /**
     * AOSP's own usability test, via `VoiceInteractionServiceInfo`: the voice-interaction metadata
     * must parse, declare `supportsAssist`, and name a recognition service. The recognition part is
     * not cosmetic — AOSP refuses the service without it and warns about boot loops on older
     * platforms.
     *
     * The `ServiceInfo` **must** be loaded with [VOICE_SERVICE_INFO_FLAGS]. Without `GET_META_DATA`
     * the framework's `loadXmlMetaData` returns null and the check fails with "No
     * android.voice_interaction meta-data for <pkg>" on a perfectly valid service — observed on
     * device, and the reason the service form was never selected.
     */
    private fun isVoiceServiceUsable(context: Context, component: ComponentName): Boolean =
        runCatching {
            val serviceInfo = context.packageManager.getServiceInfo(component, VOICE_SERVICE_INFO_FLAGS)
            val infoClass = Class.forName("android.service.voice.VoiceInteractionServiceInfo")
            val info = infoClass
                .getConstructor(PackageManager::class.java, android.content.pm.ServiceInfo::class.java)
                .newInstance(context.packageManager, serviceInfo)
            val parseError = infoClass.getMethod("getParseError").invoke(info) as? String
            val supportsAssist = infoClass.getMethod("getSupportsAssist").invoke(info) as? Boolean
            val recognition = infoClass.getMethod("getRecognitionService").invoke(info)
            if (parseError != null) {
                DebugLog.w(SCOPE, "$component voice-interaction metadata: $parseError")
                return@runCatching false
            }
            val usable = supportsAssist == true && recognition != null
            DebugLog.i(
                SCOPE,
                "$component voice-interaction usable=$usable " +
                    "(supportsAssist=$supportsAssist recognition=${recognition != null})"
            )
            usable
        }.onFailure {
            DebugLog.w(SCOPE, "could not inspect the voice-interaction service $component", it)
        }.getOrDefault(false)

    /**
     * Puts the assistant settings back the way this hooker found them.
     *
     * Fail-safe: with no recorded original it changes nothing. The voice service is only put back
     * when it was actually recorded, so a value the device owns is never overwritten with a guess.
     */
    private fun restore() {
        val context = systemContext() ?: return
        val previousAssistant = SecureSettingStore.read(context, BACKUP_ASSISTANT)
        val previousService = SecureSettingStore.read(context, BACKUP_VOICE)
        if (!SecureSettingStore.has(context, BACKUP_ASSISTANT) &&
            !SecureSettingStore.has(context, BACKUP_VOICE)
        ) return
        val restoredAssistant =
            SecureSettingStore.restore(context, BACKUP_ASSISTANT, SETTING_ASSISTANT)
        val restoredService =
            SecureSettingStore.restore(context, BACKUP_VOICE, SETTING_VOICE_SERVICE)
        DebugLog.i(
            SCOPE,
            "restored assistant='$previousAssistant' (applied=$restoredAssistant) " +
                "voiceService='$previousService' (applied=$restoredService)"
        )
    }

    /** Records both originals once, before either write. */
    private fun rememberBackupIfNeeded(context: Context) {
        SecureSettingStore.recordIfAbsent(context, BACKUP_ASSISTANT, currentAssistantSetting(context))
        SecureSettingStore.recordIfAbsent(context, BACKUP_VOICE, currentVoiceServiceSetting(context))
    }

    /**
     * AOSP rewrites `assistant` whenever the ASSISTANT role changes, so re-apply when the value
     * this hooker depends on moves.
     */
    private fun installSettingObserver() {
        if (observerInstalled) return
        val context = systemContext() ?: return
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_ASSISTANT),
                false,
                object : android.database.ContentObserver(
                    android.os.Handler(android.os.Looper.getMainLooper())
                ) {
                    override fun onChange(selfChange: Boolean) {
                        applyAlignment()
                    }
                }.also { settingObserver = it }
            )
            settingObserver?.let { observer ->
                context.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_VOICE_SERVICE),
                    false,
                    observer
                )
            }
            observerInstalled = true
        }.onFailure {
            DebugLog.w(SCOPE, "could not observe the assistant setting", it)
        }
    }

    // ─── Platform access ──────────────────────────────────────────────────────

    private fun isDefaultAssistantAction(): Boolean =
        Preferences.powerButtonAction() == Preferences.POWER_BUTTON_ACTION_DEFAULT_ASSISTANT

    private fun currentAssistantSetting(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, SETTING_ASSISTANT) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun currentVoiceServiceSetting(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, SETTING_VOICE_SERVICE) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    /**
     * system_server's own context. This hooker runs in system_server, which has no Application, so
     * `ActivityThread.currentApplication()` is null here; `getSystemContext()` is the usable one.
     */
    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null) ?: return null
        activityThread.getMethod("getSystemContext").invoke(thread) as? Context
    }.onFailure {
        DebugLog.w(SCOPE, "could not resolve the system context", it)
    }.getOrNull()
}
