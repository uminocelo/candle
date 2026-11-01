package com.uminocelo.candle.home

import com.uminocelo.candle.resume.ResumeEntry

enum class SourceTile { USB, NETWORK, HDMI, SETTINGS }

object Shortcuts {
    /** The projector's HDMI input screen (Allwinner `AwSource`). */
    val HDMI = ComponentRef("com.softwinner.awsource", "com.softwinner.awsource.MainActivity")

    /** Shown as the Settings tile, so it is left out of the apps row. */
    const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"
}

data class HomeContent(
    val sources: List<SourceTile>,
    val continueWatching: List<ResumeEntry>,
    val apps: List<LaunchableApp>,
) {
    val showContinueWatching: Boolean get() = continueWatching.isNotEmpty()
}

/**
 * Decides what the home screen shows. [selfPackage] is Candle itself, which never
 * lists itself as an app.
 */
fun buildHome(catalog: AppCatalog, resume: List<ResumeEntry>, selfPackage: String): HomeContent {
    val sources = SourceTile.entries.filter { it != SourceTile.HDMI || catalog.isInstalled(Shortcuts.HDMI) }
    val hidden = setOf(selfPackage, Shortcuts.TV_SETTINGS_PACKAGE)
    val apps = catalog.tvApps()
        .filter { it.component.packageName !in hidden }
        .distinctBy { it.component }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    return HomeContent(sources, resume, apps)
}
