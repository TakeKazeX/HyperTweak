package com.takekazex.hypertweak.dock

import android.graphics.SurfaceTexture
import android.view.View
import com.takekazex.hypertweak.util.logging.DebugLog

/** Main-thread ownership of one renderer's sampling registration, independent of material edits. */
internal class DockCaptureSession(
    private val access: DockCaptureAccess,
    private val onRegistered: () -> Unit = {},
) : AutoCloseable {
    constructor(view: View, onRegistered: () -> Unit) : this(ViewCaptureAccess(view), onRegistered)
    private var requested = false
    private var root: Any? = null

    fun request() { requested = true }

    fun reconcile() {
        if (!requested) return
        val current = access.currentRoot() ?: run { releaseRegistration(); return }
        if (root !== current) {
            releaseRegistration()
            root = current
        }
        if (!access.registered(current)) {
            access.register(current)
            onRegistered()
        }
    }

    fun textureTimestamp(): Long {
        if (!requested) return 0
        return runCatching {
            reconcile()
            root?.let(access::timestamp) ?: 0L
        }.getOrDefault(0L)
    }

    private fun releaseRegistration() {
        val previous = root ?: return
        root = null
        access.release(previous)
    }

    override fun close() {
        requested = false
        runCatching { releaseRegistration() }
            .onFailure { DebugLog.w("DockMaterial", "native capture cleanup failed", it) }
    }
}

/** The platform boundary is separate so recovery/retirement can be exercised without a ViewRoot. */
internal interface DockCaptureAccess {
    fun currentRoot(): Any?
    fun registered(root: Any): Boolean
    fun register(root: Any)
    fun timestamp(root: Any): Long
    fun release(root: Any)
}

private class ViewCaptureAccess(private val view: View) : DockCaptureAccess {
    override fun currentRoot(): Any? = if (view.isAttachedToWindow)
        DockReflection.call(view, "getViewRootImpl") else null

    override fun registered(root: Any): Boolean =
        DockReflection.call(root, "hasTextureView", view) == true &&
            DockReflection.field(view, "mNeedPassWindowBlur").getBoolean(view)

    override fun register(root: Any) {
        // ViewRoot can clear its texture map during surface/power transitions while
        // View.mNeedPassWindowBlur remains true. Never use our local flag as admission.
        DockReflection.call(view, "setPassWindowBlurEnabled", true)
        if (DockReflection.call(root, "hasTextureView", view) != true ||
            !DockReflection.field(view, "mNeedPassWindowBlur").getBoolean(view)) {
            // Mirror native View's operations on this module-owned renderer only.
            check(view.context.packageName == "com.takekazex.hypertweak")
            val node = requireNotNull(DockReflection.field(view, "mRenderNode").get(view))
            try {
                DockReflection.call(root, "addTextureView", view)
                DockReflection.call(node, "setPassWindowBlurEnabled", true)
                DockReflection.field(view, "mNeedPassWindowBlur").setBoolean(view, true)
            } catch (error: Throwable) {
                runCatching { release(root) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        view.invalidate()
    }

    override fun timestamp(root: Any): Long {
        val texture = DockReflection.field(root, "mSurTex").get(root) as? SurfaceTexture
        return if (texture == null || texture.isReleased ||
            DockReflection.field(root, "mLastSfState").getInt(root) != 1 ||
            !DockReflection.field(root, "mTextureVis").getBoolean(root)) 0L else texture.timestamp
    }

    override fun release(root: Any) {
        // The public wrapper can reject disable after a whitelist change. Release
        // the exact root we registered even when the View has already detached.
        try { DockReflection.call(root, "clearTextureView", view) } finally {
            val node = requireNotNull(DockReflection.field(view, "mRenderNode").get(view))
            try { DockReflection.call(node, "setPassWindowBlurEnabled", false) } finally {
                DockReflection.field(view, "mNeedPassWindowBlur").setBoolean(view, false)
            }
        }
    }
}
