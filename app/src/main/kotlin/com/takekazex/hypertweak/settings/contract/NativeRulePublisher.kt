package com.takekazex.hypertweak.settings.contract

import android.content.Context

/** Publishes the diagnostic native-rule file; omitted fields use effective settings at call time. */
internal interface NativeRulePublisher {
    fun publish(context: Context, hideRecentsClearButton: Boolean,
        openedFolderColumns: Int? = null, contextualSearchLongPress: Boolean? = null,
        assistantWidgets: Boolean? = null, recentsHaptic: Boolean? = null,
        folderStack: Boolean? = null, widgetStack: Boolean? = null, xiaoaiOptions: Boolean? = null)
}
