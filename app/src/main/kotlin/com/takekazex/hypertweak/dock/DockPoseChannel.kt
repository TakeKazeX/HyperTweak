package com.takekazex.hypertweak.dock

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** One capability per leased renderer. WMS never waits on the renderer's main thread. */
internal class DockPoseChannel(
    private val main: Handler,
    private val apply: (DockPose) -> Unit,
) : Binder(), AutoCloseable {
    private val pending = DockPoseMailbox()
    private var reported = false // Main thread only.
    private val drain = Runnable {
        pending.take()?.let { pose ->
            apply(pose)
            if (!reported) {
                reported = true
                com.takekazex.hypertweak.util.logging.DebugLog.i("DockHost", "View-tree pose channel active")
            }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != POSE) return super.onTransact(code, data, reply, flags)
        check(getCallingUid() == Process.SYSTEM_UID) { "Untrusted Dock pose caller" }
        data.enforceInterface(DESCRIPTOR)
        val pose = DockPose(data.readFloat(), data.readFloat(), data.readFloat())
        data.enforceNoDataAvail()
        require(pose.x.isFinite() && pose.y.isFinite() && pose.scale.isFinite() && pose.scale in 0f..256f)
        if (pending.offer(pose)) main.post(drain)
        return true
    }

    override fun close() {
        pending.close()
        main.removeCallbacks(drain)
    }

    companion object {
        const val PROTOCOL = 1
        private const val DESCRIPTOR = "com.takekazex.hypertweak.dock.pose.v1"
        private const val POSE = IBinder.FIRST_CALL_TRANSACTION

        fun send(renderer: IBinder, pose: DockPose) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeFloat(pose.x)
                data.writeFloat(pose.y)
                data.writeFloat(pose.scale)
                check(renderer.transact(POSE, data, null, IBinder.FLAG_ONEWAY)) { "Dock pose channel unavailable" }
            } finally { data.recycle() }
        }
    }
}
