package com.takekazex.hypertweak.hook.rules.shared

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Every installed application, queried from inside a hooked system app.
 *
 * Package visibility is decided by the calling host, and the hosted system apps hold
 * `QUERY_ALL_PACKAGES`. Lint checks this module's own manifest, which never applies here.
 */
object HostInstalledApplications {
    @Suppress("QueryPermissionsNeeded")
    fun list(packageManager: PackageManager): List<ApplicationInfo> = packageManager.getInstalledApplications(0)
}
