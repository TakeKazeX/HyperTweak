package com.takekazex.hypertweak

import android.app.Activity
import android.os.Bundle
import com.takekazex.hypertweak.util.platform.LsposedManagerLauncher

/** Dynamic and pinned shortcuts have an independent platform entry; no Compose shell is constructed. */
class ShortcutEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getStringExtra("shortcut_target") == "lsposed") LsposedManagerLauncher.open()
        finish()
    }
}
