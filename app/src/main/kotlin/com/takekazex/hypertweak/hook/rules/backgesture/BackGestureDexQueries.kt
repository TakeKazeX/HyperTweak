package com.takekazex.hypertweak.hook.rules.backgesture

import android.os.Looper
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import org.luckypray.dexkit.DexKitBridge

/** Upstream graph queries borrow the shared backend and finish on the preparation worker. */
internal object BackGestureDexQueries {
    fun interface Query<T> {
        @Throws(Throwable::class)
        fun resolve(bridge: DexKitBridge): T
    }

    @JvmStatic
    @Throws(Throwable::class)
    fun <T> query(path: String, query: Query<T>): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Back gesture DEX preparation requires a worker" }
        return requireNotNull(DexRuntime.query(path, "backgesture.upstream0130") { query.resolve(it) })
    }
}
