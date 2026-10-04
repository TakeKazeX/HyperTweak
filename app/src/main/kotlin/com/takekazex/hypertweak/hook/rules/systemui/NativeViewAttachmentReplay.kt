package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import android.view.ViewGroup

/** Dispose/recollect an existing native attachment lifecycle without recreating the view or its model. */
internal object NativeViewAttachmentReplay {
    fun replay(view: View) {
        if (!view.isAttachedToWindow) return
        val parent = view.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view)
        check(index >= 0) { "Native view is not owned by its parent" }
        val params = view.layoutParams
        parent.removeView(view)
        try { parent.addView(view, index.coerceAtMost(parent.childCount), params) }
        finally {
            if (view.parent == null) parent.addView(view, index.coerceAtMost(parent.childCount), params)
        }
        view.requestLayout()
    }
}
