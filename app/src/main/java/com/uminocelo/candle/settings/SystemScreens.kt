package com.uminocelo.candle.settings

import com.uminocelo.candle.R
import com.uminocelo.candle.home.ComponentRef

/** How to open a settings screen: a standard intent action, or a vendor activity by name. */
sealed interface ScreenTarget {
    data class Action(val action: String) : ScreenTarget

    data class Component(val component: ComponentRef) : ScreenTarget
}

/**
 * A system or vendor settings screen. The first target that resolves on the device
 * wins; with none, the tile is hidden.
 */
data class SystemScreen(val title: Int, val targets: List<ScreenTarget>) {
    fun pick(resolves: (ScreenTarget) -> Boolean): ScreenTarget? = targets.firstOrNull(resolves)
}

object SystemScreens {
    private fun action(name: String) = ScreenTarget.Action(name)

    /** Allwinner's picture and audio screens inside TV Settings. */
    private fun allwinner(className: String) = ScreenTarget.Component(
        ComponentRef(TV_SETTINGS_PACKAGE, "com.android.tv.settings.device.displaysound.aw_displaysound.$className"),
    )

    const val TV_SETTINGS_PACKAGE = "com.android.tv.settings"

    // The HY300's TV Settings declares the standard display/sound screen (SOUND_SETTINGS) but
    // does not ship its class, so it crashes; Bluetooth only exists in com.htc.htcsettings.
    val ALL = listOf(
        SystemScreen(R.string.screen_wifi, listOf(action("android.settings.WIFI_SETTINGS"))),
        SystemScreen(
            R.string.screen_bluetooth,
            listOf(action("android.settings.BLUETOOTH_SETTINGS"), action("action.htcsettings.bluetooth")),
        ),
        SystemScreen(R.string.screen_projector, listOf(action("action.htcsettings.prjsettings"))),
        SystemScreen(R.string.screen_picture, listOf(allwinner("PictureSettingsActivity"))),
        SystemScreen(R.string.screen_sound, listOf(allwinner("audio.AudioSettingsActivity"))),
        SystemScreen(R.string.screen_date_time, listOf(action("android.settings.DATE_SETTINGS"))),
        SystemScreen(R.string.screen_storage, listOf(action("android.settings.INTERNAL_STORAGE_SETTINGS"))),
        SystemScreen(R.string.screen_apps, listOf(action("android.settings.MANAGE_APPLICATIONS_SETTINGS"))),
        SystemScreen(R.string.screen_all_settings, listOf(action("android.settings.SETTINGS"))),
    )

    /** Each screen with the target that opens it, leaving out screens the device does not have. */
    fun available(resolves: (ScreenTarget) -> Boolean): List<Pair<SystemScreen, ScreenTarget>> =
        ALL.mapNotNull { screen -> screen.pick(resolves)?.let { screen to it } }
}
