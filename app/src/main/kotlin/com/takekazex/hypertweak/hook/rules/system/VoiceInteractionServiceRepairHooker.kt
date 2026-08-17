package com.takekazex.hypertweak.hook.rules.system

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.GESTURE_BAR_ASSIST_REQUEST_MARKER
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Repairs HyperOS voice-interaction bindings only for gesture-bar assistant requests. */
object VoiceInteractionServiceRepairHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val SCOPE = "GestureBarAssistant"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val LINK_TO_DEATH_SETTING = "assist_interaction_link_to_death"
    private const val RECONNECT_POLL_MS = 150L
    private const val MAX_RECONNECT_POLLS = 10

    private val repairLock = Any()
    private val managedAssistantPackages = ConcurrentHashMap.newKeySet<String>()

    private var outerServiceField: Field? = null
    private var contextField: Field? = null
    private var currentUserField: Field? = null
    private var implementationField: Field? = null
    private var boundServiceField: Field? = null
    private var switchImplementationMethod: Method? = null
    private var showSessionMethod: Method? = null
    private var getCurrentInteractorMethod: Method? = null
    private var secureGetIntForUserMethod: Method? = null
    private var repairThread: HandlerThread? = null
    private var repairHandler: Handler? = null
    private var repairPending = false

    @Volatile
    private var systemUiUid = -1

    override fun onPrepareHotReload() {
        repairHandler?.removeCallbacksAndMessages(null)
        repairThread?.quitSafely()
        synchronized(repairLock) { repairPending = false }
        managedAssistantPackages.clear()
        outerServiceField = null
        contextField = null
        currentUserField = null
        implementationField = null
        boundServiceField = null
        switchImplementationMethod = null
        showSessionMethod = null
        getCurrentInteractorMethod = null
        secureGetIntForUserMethod = null
        repairThread = null
        repairHandler = null
        systemUiUid = -1
    }

    override fun onHook() {
        val stubClass = classLoader.loadClass(
            "com.android.server.voiceinteraction.VoiceInteractionManagerService\$VoiceInteractionManagerServiceStub"
        )
        val outerClass = classLoader.loadClass(
            "com.android.server.voiceinteraction.VoiceInteractionManagerService"
        )
        val implementationClass = classLoader.loadClass(
            "com.android.server.voiceinteraction.VoiceInteractionManagerServiceImpl"
        )
        val callbackClass = classLoader.loadClass(
            "com.android.internal.app.IVoiceInteractionSessionShowCallback"
        )

        outerServiceField = findField(stubClass, "this\$0")
        contextField = findField(outerClass, "mContext")
        currentUserField = findField(stubClass, "mCurUser")
        implementationField = findField(stubClass, "mImpl")
        boundServiceField = findField(implementationClass, "mService")
        switchImplementationMethod = stubClass.getDeclaredMethod(
            "switchImplementationIfNeeded",
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        getCurrentInteractorMethod = stubClass.getDeclaredMethod(
            "getCurInteractor",
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        secureGetIntForUserMethod = Settings.Secure::class.java.getDeclaredMethod(
            "getIntForUser",
            ContentResolver::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        showSessionMethod = stubClass.getDeclaredMethod(
            "showSessionForActiveService",
            Bundle::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            callbackClass,
            IBinder::class.java
        ).apply { isAccessible = true }

        hookHyperOsServiceLiveness(stubClass)
        hookHyperOsServiceCleanup(stubClass)
        hookStaleServiceRecovery()
    }

    private fun hookHyperOsServiceLiveness(stubClass: Class<*>) {
        val method = runCatching {
            stubClass.getDeclaredMethod(
                "isVoiceAssistSettingsEnabled",
                String::class.java
            ).apply { isAccessible = true }
        }.onFailure {
            DebugLog.w(SCOPE, "HyperOS voice-assist liveness method is unavailable", it)
        }.getOrNull() ?: return

        method.hook("gesture_bar_assistant_liveness") {
            intercept { chain ->
                val original = chain.proceed() as? Boolean == true
                if (original) {
                    true
                } else {
                    runCatching {
                        shouldKeepActiveAssistantBound(
                            stub = chain.thisObject,
                            packageName = chain.args.getOrNull(0) as? String
                        )
                    }.onFailure {
                        DebugLog.w(SCOPE, "failed to evaluate assistant liveness", it)
                    }.getOrDefault(false)
                }
            }
        }
    }

    private fun hookHyperOsServiceCleanup(stubClass: Class<*>) {
        val method = runCatching {
            stubClass.getDeclaredMethod(
                "isVoiceAssist",
                String::class.java
            ).apply { isAccessible = true }
        }.onFailure {
            DebugLog.w(SCOPE, "HyperOS voice-assist cleanup method is unavailable", it)
        }.getOrNull() ?: return

        method.hook("gesture_bar_assistant_cleanup") {
            intercept { chain ->
                val original = chain.proceed() as? Boolean == true
                val packageName = chain.args.getOrNull(0) as? String
                when {
                    original -> true
                    packageName == null -> false
                    else -> runCatching {
                        managedAssistantPackages.contains(packageName) ||
                            isConfiguredAssistant(chain.thisObject, packageName)
                    }.onFailure {
                        DebugLog.w(SCOPE, "failed to evaluate assistant cleanup", it)
                    }.getOrDefault(false)
                }
            }
        }
    }

    private fun hookStaleServiceRecovery() {
        val method = showSessionMethod ?: return
        method.hook("gesture_bar_assistant_rebind") {
            intercept { chain ->
                val request = chain.args.getOrNull(0) as? Bundle
                val shouldRepair = runCatching {
                    request?.getBoolean(GESTURE_BAR_ASSIST_REQUEST_MARKER, false) == true &&
                        isDefaultAssistantActionEnabled() &&
                        isSystemUiCaller(chain.thisObject) &&
                        isActiveServiceDisconnected(chain.thisObject)
                }.onFailure {
                    DebugLog.w(SCOPE, "failed to inspect voice-interaction request", it)
                }.getOrDefault(false)
                if (!shouldRepair || request == null) {
                    return@intercept chain.proceed()
                }

                val retryArgs = chain.args.toTypedArray().also { args ->
                    // Drop the marker so replaying showSession does not re-enter this intercept and
                    // schedule a second repair for the request we are already repairing.
                    args[0] = Bundle(request).apply { remove(GESTURE_BAR_ASSIST_REQUEST_MARKER) }
                }
                val scheduled = runCatching {
                    beginRepair(chain.thisObject, retryArgs)
                }.onFailure {
                    DebugLog.w(SCOPE, "failed to repair voice-interaction binding", it)
                }.getOrDefault(false)

                if (scheduled) true else chain.proceed()
            }
        }
    }

    private fun shouldKeepActiveAssistantBound(stub: Any, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty() || !isDefaultAssistantActionEnabled()) return false
        val context = contextFor(stub) ?: return false
        val userId = currentUserField?.getInt(stub) ?: return false
        val linkToDeathValue = secureGetIntForUserMethod?.invoke(
            null,
            context.contentResolver,
            LINK_TO_DEATH_SETTING,
            1,
            userId
        ) as? Int ?: 0
        if (linkToDeathValue == 0) return false

        val shouldKeepBound = isConfiguredAssistant(stub, packageName)
        if (shouldKeepBound) {
            managedAssistantPackages += packageName
            DebugLog.i(SCOPE, "enabled death recovery for $packageName")
        }
        return shouldKeepBound
    }

    private fun isConfiguredAssistant(stub: Any, packageName: String): Boolean {
        if (!isDefaultAssistantActionEnabled()) return false
        val userId = currentUserField?.getInt(stub) ?: return false
        val configuredService = getCurrentInteractorMethod?.invoke(
            stub,
            userId
        ) as? ComponentName
        return configuredService?.packageName == packageName
    }

    private fun beginRepair(stub: Any, retryArgs: Array<Any?>): Boolean {
        synchronized(repairLock) {
            // A repair is already in flight. Return false so the caller serves this request normally
            // instead of claiming it as handled and dropping it without scheduling anything.
            if (repairPending) return false
            repairPending = true
        }

        return try {
            val switchMethod = switchImplementationMethod
                ?: error("switchImplementationIfNeeded is unavailable")
            switchMethod.invoke(stub, true)
            DebugLog.w(SCOPE, "stale assistant service detected; forced a rebind")
            // If the retry could not be scheduled, fall through to the original call.
            scheduleSessionRetry(stub, retryArgs, poll = 0)
        } catch (t: Throwable) {
            clearRepairPending()
            throw t
        }
    }

    /**
     * Lazily creates a dedicated worker thread for repair retries. The replay must never run on the
     * system_server main looper, where a stall trips the Watchdog and restarts the device. Guarded
     * so a construction failure degrades to "no repair scheduled" instead of throwing into the hook.
     */
    private fun ensureRepairHandler(): Handler? {
        synchronized(repairLock) {
            repairHandler?.let { return it }
            return runCatching {
                val thread = HandlerThread("HyperTweakVoiceRepair").apply { start() }
                Handler(thread.looper).also {
                    repairThread = thread
                    repairHandler = it
                }
            }.onFailure {
                DebugLog.w(SCOPE, "failed to start voice-repair worker thread", it)
            }.getOrNull()
        }
    }

    private fun scheduleSessionRetry(stub: Any, retryArgs: Array<Any?>, poll: Int): Boolean {
        val handler = ensureRepairHandler() ?: run {
            clearRepairPending()
            return false
        }
        val posted = handler.postDelayed(
            {
                runCatching {
                    val connected = !isActiveServiceDisconnected(stub)
                    if (!connected && poll < MAX_RECONNECT_POLLS) {
                        scheduleSessionRetry(stub, retryArgs, poll + 1)
                        return@runCatching
                    }
                    if (!connected) {
                        DebugLog.w(SCOPE, "assistant service did not reconnect before retry")
                    }
                    val shown = showSessionMethod?.invoke(stub, *retryArgs) as? Boolean == true
                    if (shown) {
                        DebugLog.i(SCOPE, "assistant request replayed after service repair")
                    } else {
                        DebugLog.w(SCOPE, "assistant request replay returned false")
                    }
                    clearRepairPending()
                }.onFailure {
                    clearRepairPending()
                    DebugLog.w(SCOPE, "assistant request replay failed", it)
                }
            },
            RECONNECT_POLL_MS
        )
        if (!posted) clearRepairPending()
        return posted
    }

    private fun isActiveServiceDisconnected(stub: Any): Boolean {
        val implementation = implementationField?.get(stub) ?: return false
        return boundServiceField?.get(implementation) == null
    }

    private fun isSystemUiCaller(stub: Any): Boolean {
        val expectedUid = resolveSystemUiUid(stub)
        return expectedUid >= 0 && Binder.getCallingUid() == expectedUid
    }

    @Synchronized
    private fun resolveSystemUiUid(stub: Any): Int {
        if (systemUiUid >= 0) return systemUiUid
        val packageManager = contextFor(stub)?.packageManager ?: return -1
        systemUiUid = runCatching {
            packageManager.getPackageUid(
                SYSTEM_UI_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        }.onFailure {
            DebugLog.w(SCOPE, "failed to resolve SystemUI uid", it)
        }.getOrDefault(-1)
        return systemUiUid
    }

    private fun contextFor(stub: Any): Context? {
        val outer = outerServiceField?.get(stub) ?: return null
        return contextField?.get(outer) as? Context
    }

    private fun isDefaultAssistantActionEnabled(): Boolean {
        if (!Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false) ||
            !GestureBarAction.actionsAvailable
        ) {
            return false
        }
        val longPress = GestureBarAction.fromPersistedId(
            Preferences.getInt(
                Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                GestureBarAction.DEFAULT_ASSISTANT.persistedId
            )
        )
        val doubleTap = GestureBarAction.fromPersistedId(
            Preferences.getInt(
                Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                GestureBarAction.CIRCLE_TO_SEARCH.persistedId
            )
        )
        return longPress == GestureBarAction.DEFAULT_ASSISTANT ||
            doubleTap == GestureBarAction.DEFAULT_ASSISTANT
    }

    private fun clearRepairPending() {
        synchronized(repairLock) { repairPending = false }
    }

    private fun findField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let { field ->
                return field.apply { isAccessible = true }
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}#$name")
    }
}
