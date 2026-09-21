package com.takekazex.hypertweak.hook.rules.downloads

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Parcelable
import android.os.Bundle
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.io.File
import kotlin.math.roundToInt

/** Hooks the Download Manager UI features that live next to the provider-side .xlDownload hook. */
object DownloadUiHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "DownloadUi"
    private const val HOME_CACHE_KEY = "download_ui_home_fragment"
    private const val DETAIL_CACHE_KEY = "download_ui_detail_fragment"
    private const val DETAIL_MARKER = "from_browser"
    private const val NEW_DOWNLOAD_ACTIVITY =
        "com.android.providers.downloads.ui.activity.NewDownloadTaskActivity"
    private const val NEW_DOWNLOAD_STRING = "new_add"
    private const val MORE_BUTTON_ID = "more"
    private const val ACTION_MENU_CHILD_ICON_ID = "action_menu_item_child_icon"
    private const val ACTION_MENU_CHILD_TEXT_ID = "action_menu_item_child_text"
    private const val END_ACTION_BUTTON_STYLE = "endActionButtonStyle"
    private const val END_ACTION_BUTTON_CLASS =
        "miuix.appcompat.internal.view.menu.action.EndActionMenuItemView"
    private val newDownloadButtonMarker = Any()
    private val newDownloadContainerMarker = Any()

    override fun onHook() {
        val alwaysShowLink = Preferences.getBoolean(
            Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK,
            false
        )
        val hideXl = Preferences.getBoolean(Preferences.KEY_DOWNLOAD_HIDE_XL, false)
        val addNewButton = Preferences.getBoolean(
            Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON,
            false
        )

        var installed = 0
        if (alwaysShowLink) installed += installAlwaysShowFullLink()
        if (hideXl || addNewButton) {
            installed += installXunleiReplacement(hideXl, addNewButton)
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, "Download UI features", "no verified target method")
        } else {
            DebugLog.i(
                TAG,
                "download UI hooks installed=$installed fullLink=$alwaysShowLink " +
                    "hideXl=$hideXl addNewButton=$addNewButton"
            )
        }
    }

    /**
     * DexKit resolves the final parcelable detail renderer from the browser-origin marker and
     * renderer prototype. The hook then restores the source URL and copy views after rendering.
     */
    private fun installAlwaysShowFullLink(): Int {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "download detail renderer", "source APK unavailable")
            return 0
        }
        val detailClass = resolveDetailClass(apkPath) ?: run {
            DebugLog.hookSkipped(TAG, "download detail renderer", "unique DexKit target not found")
            return 0
        }
        val renderMethod = resolveDetailRenderer(detailClass) ?: run {
            DebugLog.hookSkipped(TAG, detailClass.name, "unique parcelable renderer not found")
            return 0
        }

        deoptimize(renderMethod)
        renderMethod.hook("download_ui_full_link") {
            after { param ->
                runCatching {
                    forceFullLinkViews(
                        owner = param.thisObject,
                        info = param.args.getOrNull(0) ?: return@runCatching
                    )
                }.onFailure {
                    DebugLog.w(TAG, "failed to force full download source view", it)
                }
            }
        }
        DebugLog.hookRegistered(TAG, "DexKit detail renderer ${renderMethod.toGenericString()}")
        return 1
    }

    /**
     * The installed UI creates a Xunlei-specific end view. Keep that view untouched and replace
     * the action-bar end slot with a fresh instance of the same native end-menu button class used
     * by the adjacent "more" action. A legacy DownloadListDelegate path is also supported.
     */
    private fun installXunleiReplacement(hideXl: Boolean, addNewButton: Boolean): Int {
        var installed = installCurrentHomeActionBarHook(hideXl, addNewButton)
        installed += installLegacyActionBarHook(hideXl, addNewButton)
        return installed
    }

    private fun installCurrentHomeActionBarHook(hideXl: Boolean, addNewButton: Boolean): Int {
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "download home actions", "source APK unavailable")
            return 0
        }
        val homeClass = resolveHomeClass(apkPath) ?: run {
            DebugLog.hookSkipped(TAG, "download home actions", "unique DexKit target not found")
            return 0
        }
        val iconField = homeClass.declaredFields.singleOrNull {
            ImageView::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true }

        var installed = 0
        val actionBarMethod = homeClass.declaredMethods.firstOrNull { method ->
            method.parameterTypes.size == 1 &&
                method.parameterTypes[0].name == "miuix.appcompat.app.ActionBar" &&
                method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers)
        }?.apply { isAccessible = true }
        if (actionBarMethod != null) {
            deoptimize(actionBarMethod)
            actionBarMethod.hook("download_ui_action_bar_options") {
                after { param ->
                    runCatching {
                        val owner = param.thisObject
                        val actionBar = param.args.getOrNull(0)
                        applyHomeDownloadActions(
                            owner,
                            null,
                            iconField,
                            actionBar,
                            hideXl,
                            addNewButton
                        )
                        DebugLog.i(TAG, "download action-bar options applied after e0")
                    }.onFailure {
                        DebugLog.w(TAG, "failed to apply Download Manager action-bar options", it)
                    }
                }
            }
            DebugLog.hookRegistered(TAG, "DexKit home action-bar initializer")
            installed++
        }

        // Keep lifecycle fallbacks because the host can rebuild the action-bar view after a
        // configuration change. They refresh/reinstall our separate end button instead of
        // mutating the original Xunlei ImageView.
        val viewInflatedMethod = homeClass.declaredMethods.firstOrNull { method ->
            method.name == "onViewInflated" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == View::class.java &&
                method.parameterTypes[1] == Bundle::class.java
        }?.apply { isAccessible = true }
        if (viewInflatedMethod != null) {
            deoptimize(viewInflatedMethod)
            viewInflatedMethod.hook("download_ui_action_bar_options_after_inflate") {
                after { param ->
                    runCatching {
                        val owner = param.thisObject
                        val root = param.args.getOrNull(0) as? View
                        applyHomeDownloadActions(
                            owner,
                            root,
                            iconField,
                            null,
                            hideXl,
                            addNewButton
                        )
                        DebugLog.i(TAG, "download action-bar options checked after view inflation")
                    }.onFailure {
                        DebugLog.w(TAG, "failed to apply action-bar options after view inflation", it)
                    }
                }
            }
            DebugLog.hookRegistered(TAG, "download home onViewInflated(View,Bundle)")
            installed++

            val onResumeMethod = homeClass.declaredMethods.firstOrNull { method ->
                method.name == "onResume" && method.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }
            if (onResumeMethod != null) {
                deoptimize(onResumeMethod)
                onResumeMethod.hook("download_ui_action_bar_options_on_resume") {
                    after { param ->
                        runCatching {
                            val owner = param.thisObject
                            val root = findMethodInHierarchy(owner.javaClass) {
                                it.name == "getView" && it.parameterTypes.isEmpty()
                            }?.invoke(owner) as? View
                            applyHomeDownloadActions(
                                owner,
                                root,
                                iconField,
                                null,
                                hideXl,
                                addNewButton
                            )
                        }.onFailure {
                            DebugLog.w(TAG, "failed to restore new-download button on resume", it)
                        }
                    }
                }
                DebugLog.hookRegistered(TAG, "${homeClass.name}#onResume()")
                installed++
            }
        }

        if (installed == 0) {
            DebugLog.hookSkipped(TAG, homeClass.name, "action-bar and lifecycle methods not found")
        }
        return installed
    }

    private fun resolveHomeClass(apkPath: String): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexKitManager.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                HOME_CACHE_KEY to { bridge ->
                    val candidates = bridge.findMethod {
                        matcher {
                            name("onInflateView")
                            paramTypes(LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
                            returnType(View::class.java)
                        }
                    }.toList()
                    candidates.asSequence()
                        .map { it.className }
                        .distinct()
                        .filter { name -> runCatching { isHomeClass(classLoader.loadClass(name)) }.getOrDefault(false) }
                        .singleOrNull()
                }
            ),
            validators = mapOf(HOME_CACHE_KEY to ::isHomeClass)
        )[HOME_CACHE_KEY]
    }

    private fun resolveDetailClass(apkPath: String): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexKitManager.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                DETAIL_CACHE_KEY to { bridge ->
                    val candidates = bridge.findMethod {
                        matcher {
                            paramCount(1)
                            returnType(Void.TYPE)
                            addUsingString(DETAIL_MARKER, StringMatchType.Equals)
                        }
                    }.toList()
                    candidates.asSequence()
                        .filter { data ->
                            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                                ?: return@filter false
                            !Modifier.isStatic(method.modifiers) &&
                                method.parameterCount == 1 &&
                                method.returnType == Void.TYPE &&
                                Parcelable::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                                isFragmentClass(method.declaringClass)
                        }
                        .map { it.className }
                        .distinct()
                        .singleOrNull()
                }
            ),
            validators = mapOf(DETAIL_CACHE_KEY to ::isDetailClass)
        )[DETAIL_CACHE_KEY]
    }

    private fun isHomeClass(type: Class<*>): Boolean =
        isFragmentClass(type) &&
            View.OnClickListener::class.java.isAssignableFrom(type) &&
            type.declaredFields.count { ImageView::class.java.isAssignableFrom(it.type) } == 1 &&
            type.declaredFields.any { it.type.name == "miuix.viewpager.widget.ViewPager" } &&
            type.declaredMethods.any { method ->
                method.name == "onOptionsItemSelected" &&
                    method.parameterTypes.contentEquals(arrayOf(android.view.MenuItem::class.java)) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }

    private fun isDetailClass(type: Class<*>): Boolean =
        isFragmentClass(type) && type.declaredMethods.count(::isDetailRenderer) == 1

    private fun isFragmentClass(type: Class<*>): Boolean = runCatching {
        val fragment = classLoader.loadClass("miuix.appcompat.app.Fragment")
        fragment.isAssignableFrom(type)
    }.getOrDefault(false)

    private fun isDetailRenderer(method: Method): Boolean =
        !Modifier.isStatic(method.modifiers) &&
            method.parameterCount == 1 &&
            method.returnType == Void.TYPE &&
            Parcelable::class.java.isAssignableFrom(method.parameterTypes[0])

    private fun resolveDetailRenderer(detailClass: Class<*>): Method? =
        detailClass.declaredMethods.singleOrNull(::isDetailRenderer)
            ?.apply { isAccessible = true }

    private fun installLegacyActionBarHook(hideXl: Boolean, addNewButton: Boolean): Int {
        val delegateClass =
            "com.android.providers.downloads.ui.DownloadListDelegate".toClassOrNull() ?: return 0
        val methods = delegateClass.declaredMethods.filter { method ->
            val types = method.parameterTypes
            method.returnType == Void.TYPE &&
                types.size == 4 &&
                Activity::class.java.isAssignableFrom(types[0]) &&
                Window::class.java.isAssignableFrom(types[1]) &&
                ImageView::class.java.isAssignableFrom(types[3])
        }
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "$delegateClass#actionBarInit", "method not found")
            return 0
        }

        methods.forEach { method ->
            method.isAccessible = true
            deoptimize(method)
            method.hook("download_ui_replace_legacy_xl_${method.name}") {
                before { param ->
                    runCatching {
                        val original = param.args.getOrNull(3) as? ImageView
                        val activity = param.args.getOrNull(0) as? Activity
                        if (hideXl && addNewButton) {
                            val replacement = newDownloadButton(
                                context = activity ?: original?.context,
                                original = original,
                                activity = activity
                            ) ?: return@runCatching
                            param.args[3] = replacement
                        } else if (hideXl) {
                            original?.visibility = View.GONE
                            original?.isClickable = false
                        }
                    }.onFailure {
                        DebugLog.w(TAG, "failed to replace legacy Xunlei icon argument", it)
                    }
                }
                after { param ->
                    if (hideXl) {
                        runCatching { removeLegacyXunleiText(param.thisObject) }
                            .onFailure { DebugLog.w(TAG, "failed to remove legacy Xunlei text", it) }
                    }
                }
            }
        }
        return methods.size
    }

    private fun applyHomeDownloadActions(
        owner: Any,
        root: View?,
        iconField: Field?,
        actionBarOverride: Any?,
        hideXl: Boolean,
        addNewButton: Boolean
    ) {
        val activity = activityOf(owner)
        val actionBar = actionBarOverride ?: findMethodInHierarchy(owner.javaClass) {
            it.name == "getActionBar" && it.parameterTypes.isEmpty()
        }?.let { runCatching { it.invoke(owner) }.getOrNull() } ?: return
        val currentEndView = getActionBarEndView(actionBar)
        val context = root?.context ?: currentEndView?.context ?: activity ?: return
        val moreButton = findMoreButton(activity, context)
        val setEndView = findMethodInHierarchy(actionBar.javaClass) {
            it.name == "setEndView" &&
                it.parameterTypes.size == 1 &&
                View::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return

        if (currentEndView?.tag === newDownloadContainerMarker ||
            currentEndView?.tag === newDownloadButtonMarker
        ) {
            refreshNativeNewButton(currentEndView)
            return
        }

        if (!hideXl && !addNewButton) return

        val iconId = root?.resources?.getIdentifier(
            "icon_xl",
            "id",
            root.context.packageName
        ) ?: 0
        val originalIcon = (currentEndView as? ImageView) ?: if (iconId != 0) {
            root?.findViewById<ImageView>(iconId)
        } else {
            null
        } ?: iconField?.get(owner) as? ImageView
        val originalEndView = currentEndView ?: originalIcon

        if (hideXl && !addNewButton) {
            // Removing the end view leaves the original Xunlei object untouched and detached;
            // the host's later icon-tint refreshes therefore cannot make it visible again.
            setEndView.invoke(actionBar, null)
            DebugLog.i(TAG, "hid Xunlei end view without adding a new button")
            return
        }

        val replacement = newNativeEndActionButton(
            context = context,
            originalEndView = originalEndView,
            moreButton = moreButton,
            activity = activity
        ) ?: return

        if (hideXl || originalEndView == null) {
            setEndView.invoke(actionBar, replacement)
            DebugLog.i(
                TAG,
                if (hideXl) {
                    "hid Xunlei end view and installed separate new-download button"
                } else {
                    "installed separate new-download button"
                }
            )
            return
        }

        // With only the add switch enabled, preserve the original Xunlei view and place the new
        // native action beside it in a small end-view container.
        val container = android.widget.LinearLayout(context).apply {
            tag = newDownloadContainerMarker
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        setEndView.invoke(actionBar, null)
        container.addView(originalEndView)
        container.addView(replacement)
        setEndView.invoke(actionBar, container)
        DebugLog.i(TAG, "kept Xunlei end view and added separate new-download button")
    }

    private fun getActionBarEndView(actionBar: Any): View? = findMethodInHierarchy(actionBar.javaClass) {
        it.name == "getEndView" && it.parameterTypes.isEmpty()
    }?.let { runCatching { it.invoke(actionBar) as? View }.getOrNull() }

    private fun findMoreButton(activity: Activity?, context: Context): View? {
        val decor = activity?.window?.decorView ?: return null
        val id = decor.resources.getIdentifier(MORE_BUTTON_ID, "id", context.packageName)
        return id.takeIf { it != 0 }?.let { decor.findViewById(it) }
    }

    /** Creates a fresh copy of the host's native end-action button instead of mutating Xunlei's. */
    private fun newNativeEndActionButton(
        context: Context,
        originalEndView: View?,
        moreButton: View?,
        activity: Activity?
    ): View? {
        val buttonClass = moreButton?.javaClass
            ?: END_ACTION_BUTTON_CLASS.toClassOrNull()
            ?: return null
        val styleAttr = if (buttonClass.name.endsWith("OverflowMenuButton")) {
            android.R.attr.actionOverflowButtonStyle
        } else {
            context.resources.getIdentifier(END_ACTION_BUTTON_STYLE, "attr", context.packageName)
        }
        val button = runCatching {
            val constructor = buttonClass.getDeclaredConstructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
            constructor.isAccessible = true
            constructor.newInstance(context, null, styleAttr) as? View
        }.getOrNull() ?: runCatching {
            val constructor = buttonClass.getDeclaredConstructor(Context::class.java)
            constructor.isAccessible = true
            constructor.newInstance(context) as? View
        }.getOrNull() ?: return null

        button.layoutParams = nativeEndLayoutParams(context, originalEndView, moreButton)
        val fallbackSize = (44f * context.resources.displayMetrics.density).roundToInt()
        val nativeSize = maxOf(
            moreButton?.measuredWidth ?: 0,
            moreButton?.measuredHeight ?: 0,
            fallbackSize
        )
        button.minimumWidth = nativeSize
        button.minimumHeight = nativeSize
        val iconId = context.resources.getIdentifier(
            ACTION_MENU_CHILD_ICON_ID,
            "id",
            context.packageName
        )
        button.findViewById<ImageView>(iconId.takeIf { it != 0 } ?: View.NO_ID)?.let {
            setNativeNewIcon(it)
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val textId = context.resources.getIdentifier(
            ACTION_MENU_CHILD_TEXT_ID,
            "id",
            context.packageName
        )
        button.findViewById<TextView>(textId.takeIf { it != 0 } ?: View.NO_ID)?.apply {
            text = ""
            visibility = View.GONE
        }
        button.tag = newDownloadButtonMarker
        button.contentDescription = hostString(context, NEW_DOWNLOAD_STRING) ?: "New download"
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        button.visibility = View.VISIBLE
        button.alpha = 1f
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener { startNewDownload(context, activity ?: activityOf(it)) }
        return button
    }

    private fun nativeEndLayoutParams(
        context: Context,
        originalEndView: View?,
        moreButton: View?
    ): ViewGroup.LayoutParams {
        val source = moreButton ?: originalEndView
        val sourceParams = source?.layoutParams
        val fallback = (44f * context.resources.displayMetrics.density).roundToInt()
        val width = source?.measuredWidth?.takeIf { it > 0 }
            ?: sourceParams?.width?.takeIf { it > 0 }
            ?: fallback
        val height = source?.measuredHeight?.takeIf { it > 0 }
            ?: sourceParams?.height?.takeIf { it > 0 }
            ?: fallback
        return if (sourceParams is ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams(width, height).apply {
                leftMargin = sourceParams.leftMargin
                topMargin = sourceParams.topMargin
                rightMargin = sourceParams.rightMargin
                bottomMargin = sourceParams.bottomMargin
            }
        } else {
            ViewGroup.LayoutParams(width, height)
        }
    }

    private fun refreshNativeNewButton(button: View) {
        val iconId = button.resources.getIdentifier(
            ACTION_MENU_CHILD_ICON_ID,
            "id",
            button.context.packageName
        )
        if (iconId != 0) {
            button.findViewById<ImageView>(iconId)?.let(::setNativeNewIcon)
        } else if (button is ImageView) {
            setNativeNewIcon(button)
        }
    }

    private fun newDownloadButton(
        context: Context?,
        original: ImageView?,
        activity: Activity?
    ): ImageView? {
        context ?: return null
        val button = ImageView(context)
        original?.let { source ->
            button.id = source.id
            button.layoutParams = source.layoutParams
            button.scaleType = source.scaleType
            button.setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
            button.imageTintList = source.imageTintList
            button.minimumWidth = source.minimumWidth
            button.minimumHeight = source.minimumHeight
        }
        setNativeNewIcon(button)
        button.contentDescription = hostString(context, NEW_DOWNLOAD_STRING) ?: "New download"
        button.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        button.isClickable = true
        button.setOnClickListener { startNewDownload(context, activity ?: activityOf(it)) }
        return button
    }

    /** Loads the host's own light/dark action-bar symbol so theme changes follow the UI. */
    private fun setNativeNewIcon(button: ImageView) {
        val isNight = (button.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val drawableName = if (isNight) {
            "miuix_action_icon_new_dark"
        } else {
            "miuix_action_icon_new_light"
        }
        val drawableId = button.resources.getIdentifier(
            drawableName,
            "drawable",
            button.context.packageName
        )
        if (drawableId != 0) {
            // The Xunlei icon has its own blue tint. The MIUI symbol drawable already contains
            // the correct theme color, so clear the inherited ImageView tint before using it.
            button.imageTintList = null
            button.clearColorFilter()
            button.setImageResource(drawableId)
            return
        }

        // Keep an adaptive fallback for older Download Manager builds without MIUI's symbol.
        button.setImageResource(android.R.drawable.ic_input_add)
        button.imageTintList = resolveActionBarTint(button.context)
    }

    @SuppressLint("ResourceType")
    private fun resolveActionBarTint(context: Context): ColorStateList {
        val attributes = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorControlNormal, android.R.attr.textColorPrimary)
        )
        return try {
            attributes.getColorStateList(0)
                ?: attributes.getColorStateList(1)
                ?: ColorStateList.valueOf(
                    if ((context.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    ) Color.WHITE else Color.BLACK
                )
        } finally {
            attributes.recycle()
        }
    }

    private fun startNewDownload(context: Context, activity: Activity?) {
        val target = activity ?: (context as? Activity)
        if (target == null) {
            DebugLog.w(TAG, "cannot start new download without an Activity")
            return
        }
        val existingLauncher = findMethodInHierarchy(target.javaClass) {
            it.name == "startNewTaskActivity" && it.parameterTypes.isEmpty()
        }
        if (existingLauncher != null) {
            runCatching {
                existingLauncher.invoke(target)
                DebugLog.d(TAG, "started new download through DownloadList.startNewTaskActivity")
            }.onFailure { DebugLog.w(TAG, "DownloadList new-task launcher failed", it) }
            return
        }

        // Older builds may not expose the ActivityResult launcher helper. The activity is
        // exported and still posts NewTaskEvent, so this fallback retains the functional path.
        runCatching {
            val intent = Intent().setClassName(target, NEW_DOWNLOAD_ACTIVITY)
            val fromPackage = findMethodInHierarchy(target.javaClass) {
                it.name == "getCurFromPkgName" && it.parameterTypes.isEmpty()
            }?.invoke(target) as? String
            intent.putExtra("FromPkgName", fromPackage)
            intent.putExtra("from_browser", fromPackage == "com.android.browser")
            target.startActivity(intent)
            DebugLog.d(TAG, "started new download through fallback activity")
        }.onFailure { DebugLog.w(TAG, "fallback new-download launcher failed", it) }
    }

    private fun removeLegacyXunleiText(owner: Any?) {
        owner ?: return
        val field = findFieldInHierarchy(owner.javaClass) { TextView::class.java.isAssignableFrom(it.type) }
            ?: return
        val textView = runCatching { field.get(owner) as? TextView }.getOrNull() ?: return
        (textView.parent as? ViewGroup)?.removeView(textView)
        runCatching { field.set(owner, null) }
        DebugLog.d(TAG, "removed legacy Xunlei text field=${field.name}")
    }

    private fun forceFullLinkViews(owner: Any?, info: Any) {
        owner ?: return
        val url = findUrlValue(info) ?: return
        val root = findMethodInHierarchy(owner.javaClass) {
            it.name == "getView" && it.parameterTypes.isEmpty()
        }?.let { runCatching { it.invoke(owner) as? View }.getOrNull() } ?: return
        val packageName = root.context.packageName
        val sourceId = root.resources.getIdentifier("from_uri", "id", packageName)
        val copyId = root.resources.getIdentifier("from_content", "id", packageName)
        val layoutId = root.resources.getIdentifier("from_layout", "id", packageName)
        if (sourceId == 0 || copyId == 0) return

        val source = root.findViewById<TextView>(sourceId) ?: return
        source.text = url
        source.ellipsize = null
        source.isSingleLine = false
        source.maxLines = Int.MAX_VALUE
        source.setHorizontallyScrolling(false)
        source.layoutParams = source.layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        root.findViewById<View>(layoutId)?.visibility = View.VISIBLE
        root.findViewById<View>(copyId)?.let { copy ->
            copy.visibility = View.VISIBLE
            (copy as? TextView)?.let { textView ->
                if (TextUtils.isEmpty(textView.text)) {
                    textView.text = hostString(root.context, "copy") ?: "Copy"
                }
            }
            (owner as? View.OnClickListener)?.let(copy::setOnClickListener)
        }
        source.requestLayout()
        DebugLog.d(TAG, "forced full download source text length=${url.length}")
    }

    private fun findUrlValue(info: Any): String? {
        val values = fieldsInHierarchy(info.javaClass)
            .filter { it.type == String::class.java && !Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> runCatching { field.get(info) as? String }.getOrNull() }
            .map(String::trim)
            .filter { URL_PATTERN.matches(it) }
            .distinct()
        return values.singleOrNull()
    }

    private fun activityOf(value: Any?): Activity? {
        if (value is Activity) return value
        return findMethodInHierarchy(value?.javaClass ?: return null) {
            it.name == "getActivity" && it.parameterTypes.isEmpty()
        }?.let { runCatching { it.invoke(value) as? Activity }.getOrNull() }
    }

    private fun hostString(context: Context, name: String): String? = runCatching {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        id.takeIf { it != 0 }?.let(context::getString)
    }.getOrNull()

    private fun findMethodInHierarchy(type: Class<*>, predicate: (Method) -> Boolean): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull(predicate)?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun findFieldInHierarchy(type: Class<*>, predicate: (Field) -> Boolean): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull(predicate)?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    private fun fieldsInHierarchy(type: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = type
        while (current != null) {
            fields += current.declaredFields
            current = current.superclass
        }
        fields.forEach { it.isAccessible = true }
        return fields
    }

    private val URL_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://\\S+$")
}
