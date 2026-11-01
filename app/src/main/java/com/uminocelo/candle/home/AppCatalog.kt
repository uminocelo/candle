package com.uminocelo.candle.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

/** The installed apps the home screen can show and launch. */
interface AppCatalog {
    /** Activities registered for the TV launcher (`LEANBACK_LAUNCHER`). */
    fun tvApps(): List<LaunchableApp>

    fun isInstalled(component: ComponentRef): Boolean
}

class PackageManagerCatalog(private val packageManager: PackageManager) : AppCatalog {
    override fun tvApps(): List<LaunchableApp> =
        packageManager.queryIntentActivities(tvLauncherIntent(), 0).map { info ->
            LaunchableApp(
                ComponentRef(info.activityInfo.packageName, info.activityInfo.name),
                info.loadLabel(packageManager).toString(),
            )
        }

    override fun isInstalled(component: ComponentRef): Boolean =
        try {
            packageManager.getActivityInfo(component.toComponentName(), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    companion object {
        fun tvLauncherIntent(): Intent =
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    }
}

fun ComponentRef.toComponentName() = ComponentName(packageName, className)
