package com.takekazex.hypertweak.util

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.takekazex.hypertweak.R

/**
 * Debug-only helper that posts real heads-up notifications for the user to inspect the
 * notification pop-up "glass" (blur/backdrop) on the device.
 *
 * The module app is the foreground app while its settings UI is open, and Android suppresses
 * heads-up banners for the foreground package — so posting through [NotificationManagerCompat]
 * from the module process silently shows nothing. Instead we route through the shell: run
 * `cmd notification post` as the **shell** uid (2000) via `su`, which posts a *com.android.shell*
 * notification whose package is not in the foreground, so the heads-up banner actually fires.
 * Posting as root (uid 0) does NOT work: NotificationService rejects it with
 * `NameNotFoundException: root` (there is no "root" package) and drops the notification.
 *
 * No cross-process hook is involved; the shell is invoked from the module app process.
 */
object TestNotifier {

    private const val TAG_PREFIX = "hyperglass"
    private const val TAG = "TestNotifier"

    /** Posts [count] distinct test notifications, each its own shell tag so all banners stack. */
    fun post(context: Context, count: Int) {
        if (count <= 0) return
        val baseTag = "$TAG_PREFIX-${SystemClock.elapsedRealtime()}"
        // The exec+waitFor blocks; never run it on the main thread.
        Thread {
            var posted = 0
            var lastError: Throwable? = null
            for (i in 1..count) {
                val title = context.getString(R.string.notification_test_title, i, count)
                val text = context.getString(R.string.notification_test_text)
                val tag = "$baseTag-$i"
                val cmd = "cmd notification post -t ${shellQuote(title)} ${shellQuote(tag)} ${shellQuote(text)}"
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "2000", "-c", cmd))
                    if (process.waitFor() == 0) {
                        posted++
                    }
                    DebugLog.d(TAG, "shell post tag=$tag exit=${process.exitValue()}")
                } catch (e: Throwable) {
                    lastError = e
                    DebugLog.e(TAG, "shell post failed ($tag)", e)
                }
            }
            if (posted == 0) {
                val message = context.getString(R.string.notification_test_shell_failed)
                android.os.Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Single-quote a shell word so embedded spaces/quotes survive `sh -c` inside `su`. */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
