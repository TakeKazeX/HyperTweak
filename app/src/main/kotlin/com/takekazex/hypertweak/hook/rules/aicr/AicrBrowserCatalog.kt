package com.takekazex.hypertweak.hook.rules.aicr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** Browser entry points, plus the selected default if it omits CATEGORY_APP_BROWSER. */
internal object AicrBrowserCatalog {
    data class Browser(val packageName: String, val label: String)

    fun installed(context: Context): List<Browser> {
        val pm = context.packageManager
        fun web(scheme: String) = Intent(Intent.ACTION_VIEW, "$scheme://example.invalid/".toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val browserPackages = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), PackageManager.MATCH_ALL
        ).mapNotNull { it.activityInfo?.packageName }.toSet() +
            listOfNotNull(pm.resolveActivity(web("https"), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName)
        val httpPackages = pm.queryIntentActivities(web("http"), PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
        return pm.queryIntentActivities(web("https"), PackageManager.MATCH_ALL)
            .mapNotNull { result ->
                val app = result.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (!app.enabled || app.packageName !in browserPackages || app.packageName !in httpPackages) return@mapNotNull null
                val label = pm.getApplicationLabel(app).toString().takeIf(String::isNotBlank) ?: return@mapNotNull null
                Browser(app.packageName, label)
            }.distinctBy { it.packageName }.sortedBy { it.label }
    }
}
