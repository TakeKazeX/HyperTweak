package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.SeekBar
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppVolumePercentColor
import com.takekazex.hypertweak.settings.contract.SliderPercentagePolicy
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.ignoreFailure
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Per-app media volume in the HyperOS hardware-key volume dialog (OS4 `miui.systemui.plugin`).
 *
 * Attached ONLY from `SystemUIPluginHooker` with the plugin loader, and only once the switch has
 * been on. The hooks observe `VolumePanelViewController` transitions; [AppVolumePanel] owns the
 * module views and [AppVolumeAudio] the playback snapshot and gain calls. Every host member is
 * bound by exact signature, and any missing member leaves the dialog native.
 *
 * Live changes apply in place. Hot reload and APK upgrade remove the older generation's views by
 * [AppVolumePanel.MARKER]; a living dialog is found again through its sliders' change listeners,
 * and otherwise on its next `initPanelView` or show.
 */
class AppVolumeHooker : DynamicHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingEnabled by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME, false)
    }
    private val settingAutoExpand by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME_AUTO_EXPAND, false)
    }

    private val settingPercent by hookSetting(SystemUiSettingsKeys.APP_VOLUME_PERCENT_INSIDE) {
        Preferences.getInt(SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME_PERCENT, SystemUiSettingsKeys.APP_VOLUME_PERCENT_INSIDE)
    }
    private val settingPercentColor by hookSetting(AppVolumePercentColor.INHERITED) {
        AppVolumePercentColor.fromStored(Preferences.getInt(SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME_PERCENT_COLOR, 0))
    }
    // Label style is shared with the native volume percentages (slider-percentage page).
    private val settingPercentPolicy by hookSetting(SliderPercentagePolicy()) {
        SliderPercentagePolicy.read(Preferences::getBoolean, Preferences::getInt)
    }

    internal var percentMode = SystemUiSettingsKeys.APP_VOLUME_PERCENT_INSIDE
        private set
    internal var percentColor = AppVolumePercentColor.INHERITED
        private set
    internal var percentPolicy = SliderPercentagePolicy()
        private set

    @Volatile internal var enabled = false
        private set
    @Volatile internal var autoExpand = false
        private set

    /** Main thread only, like the panels that read it. */
    internal var audio: AppVolumeAudio? by generationState { null }
        private set
    private var audioRegistration: AutoCloseable? by generationState { null }
    private var host: AppVolumeHost? by generationState { null }
    private var api: AppVolumeAudio.Api? by generationState { null }
    private var controllerType: Class<*>? by generationState { null }
    private val panels = generationMainResource(WeakHashMap<Any, AppVolumePanel>()) { map ->
        map.values.toList().forEach { ignoreFailure { it.detach() } }
        map.clear()
    }
    private val main = generationMainHandler()
    private val worker = generationWorker("HT-AppVolume")

    override fun onHook() {
        readSettings()
        if (!PlatformLevel.isOs4) {
            DebugLog.hookSkippedDebug(TAG, "volume dialog", "not OS4")
            return
        }
        val type = CONTROLLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CONTROLLER, "class not found")
            return
        }
        fun method(name: String, vararg parameters: Class<*>): Method? = type.declaredMethods.singleOrNull {
            it.name == name && !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(parameters)
        }
        val z = Boolean::class.javaPrimitiveType!!
        val i = Int::class.javaPrimitiveType!!
        val initPanel = method("initPanelView")
        val show = method("showVolumePanelH", i)
        val expand = method("updateExpandedH", z, z, z)
        val destroy = method("destroy")
        val config = method("onConfigChanged", i)
        val material = method("onMaterialModeChanged")
        val timeout = method("rescheduleTimeoutH")
        val fields = listOf("mVolumeView", "mVolumeContainerView", "mRingerModeLayout", "mContext",
            "mVolumeContentView", "mTempColumnContainer", "mSuperVolumeBg",
            "mExpanded", "mShowing", "mNeedShowDialog", "isControlCenterPanel")
            .all { HostAccess.declaredField(type, it, accessible = true) != null }
        if (initPanel == null || show == null || expand == null || destroy == null || config == null ||
            material == null || timeout == null || !fields) {
            DebugLog.hookSkipped(TAG, "$CONTROLLER contract",
                "init=${initPanel != null} show=${show != null} expand=${expand != null} destroy=${destroy != null} " +
                    "config=${config != null} material=${material != null} timeout=${timeout != null} fields=$fields")
            return
        }
        val resolvedHost = AppVolumeHost.resolve(classLoader) ?: return
        val resolvedApi = AppVolumeAudio.Api.resolve() ?: return
        host = resolvedHost
        api = resolvedApi
        controllerType = type

        initPanel.hook { after { param -> onController(param.thisObject) { it.attach(); it.sync() } } }
        // Before the host show: the strip is decided before the show pre-draw reads getShowX.
        show.hook { before { param -> onController(param.thisObject) { it.onShow() } } }
        hookShowTarget()
        expand.hook {
            // Leave the expand animation an unchanged native layout to start from.
            before { param -> if (param.args[0] == true) panels[param.thisObject]?.hide() }
            after { param -> panels[param.thisObject]?.sync() }
        }
        config.hook { after { param -> panels[param.thisObject]?.applyHostStyle() } }
        material.hook { after { param -> panels[param.thisObject]?.applyHostStyle() } }
        destroy.hook {
            before { param ->
                HookFailurePolicy.open(TAG, "destroy", Unit) { panels.remove(param.thisObject)?.detach() }
            }
        }
        hostRecovery(HostRecoveryPhase.PRESENTATION) { scope -> HostAccess.onMain { recover(scope.views) } }
        DebugLog.d(TAG, "hooked ${type.simpleName} enabled=$enabled autoExpand=$autoExpand")
    }

    /** Accepted-snapshot refresh; the switch and auto-expand apply to living dialogs in place. */
    fun refreshSettings() = HostAccess.onMain {
        readSettings()
        if (!enabled) teardown() else recover(windowViews())
        panels.values.forEach { it.onSettingsChanged() }
        DebugLog.i(TAG, "settings applied enabled=$enabled autoExpand=$autoExpand percentColor=$percentColor panels=${panels.size}")
    }

    private fun readSettings() {
        enabled = settingEnabled
        autoExpand = settingAutoExpand
        percentMode = settingPercent.coerceIn(SystemUiSettingsKeys.APP_VOLUME_PERCENT_INSIDE, SystemUiSettingsKeys.APP_VOLUME_PERCENT_HIDDEN)
        percentColor = settingPercentColor
        percentPolicy = settingPercentPolicy
    }

    /**
     * `MiuiVolumeDialogMotion.getShowX()` is the collapsed dialog's absolute show X, sized for the
     * native columns only; the visible strip widens the dialog, so the target moves left by the
     * strip width. Without this hook the strip still works and only auto-expand overshoots.
     */
    private fun hookShowTarget() {
        val type = MOTION.toClassOrNull()
        val target = type?.declaredMethods?.singleOrNull {
            it.name == "getShowX" && !Modifier.isStatic(it.modifiers) && it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes.isEmpty()
        } ?: run {
            DebugLog.hookSkipped(TAG, "$MOTION#getShowX", "signature not found")
            return
        }
        target.hook {
            after { param ->
                val result = param.result as? Int ?: return@after
                val offset = HookFailurePolicy.open(TAG, "getShowX", 0) {
                    if (!enabled) 0 else panels.values.sumOf { it.showXOffset(param.thisObject) }
                }
                if (offset != 0) param.result = result - offset
            }
        }
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain { teardown() }
    }

    private fun onController(controller: Any, action: (AppVolumePanel) -> Unit) {
        HookFailurePolicy.open(TAG, "volume dialog", Unit) { panel(controller)?.let(action) }
    }

    private fun panel(controller: Any): AppVolumePanel? {
        if (!enabled || generationRetired) return null
        val host = host ?: return null
        if (!ensureAudio(controller)) return null
        return panels.getOrPut(controller) { AppVolumePanel(this, controller, host) }
    }

    private fun ensureAudio(controller: Any): Boolean {
        if (audio != null) return true
        val api = api ?: return false
        val context = HostAccess.readDeclared(controller, "mContext") as? Context ?: return false
        val created = AppVolumeAudio(context, api, worker, main) { panels.values.forEach { it.onAudioChanged() } }
        var stop: (() -> Unit)? = null
        audioRegistration = generationRegistration(register = { stop = created.start() }, unregister = { stop?.invoke() })
        audio = created
        return true
    }

    /** Disable or retirement: drop the views, the playback callback and queued work. */
    private fun teardown() {
        panels.values.toList().forEach { ignoreFailure { it.detach() } }
        panels.clear()
        runCatching { audioRegistration?.close() }.onFailure { DebugLog.w(TAG, "playback callback release failed", it) }
        audioRegistration = null
        audio = null
        // A dialog first seen by an older generation may still carry its views.
        windowViews().filterIsInstance<ViewGroup>().forEach(AppVolumePanel::removeStale)
    }

    /** A living dialog from before this generation: each native slider's listener holds its controller. */
    private fun recover(views: List<View>) {
        if (!enabled) return
        val type = controllerType ?: return
        views.filterIsInstance<SeekBar>().forEach { slider ->
            runCatching {
                val listener = HostAccess.declaredField(SeekBar::class.java, "mOnSeekBarChangeListener", accessible = true)
                    ?.get(slider) ?: return@runCatching
                val field = HostAccess.cached(listener.javaClass, "appVolume.listenerOwner", Field::class.java) {
                    listener.javaClass.declaredFields.singleOrNull { it.type == type && !Modifier.isStatic(it.modifiers) }
                        ?.apply { isAccessible = true }
                } ?: return@runCatching
                val controller = field.get(listener) ?: return@runCatching
                if (controller !in panels) panel(controller)?.let { it.attach(); it.sync() }
            }.onFailure { DebugLog.w(TAG, "Cannot recover volume dialog", it) }
        }
    }

    private fun windowViews(): List<View> = buildList {
        fun collect(view: View) {
            add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        runCatching { WindowInspector.getGlobalWindowViews() }.getOrDefault(emptyList()).forEach(::collect)
    }

    private companion object {
        const val TAG = "AppVolume"
        const val CONTROLLER = "com.android.systemui.miui.volume.VolumePanelViewController"
        const val MOTION = "com.android.systemui.miui.volume.MiuiVolumeDialogMotion"
    }
}
