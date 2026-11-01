package com.uminocelo.candle.server

/**
 * The "finish setting up" card: what a fresh install still needs before the server can share
 * everything and keep running with the screen off. It hides once nothing is missing.
 */
data class FirstRun(val filesAccess: Boolean, val batteryExempt: Boolean) {
    val showFilesAccess: Boolean get() = !filesAccess
    val showBattery: Boolean get() = !batteryExempt
    val visible: Boolean get() = showFilesAccess || showBattery

    companion object {
        /** The app's own all-files screen first; some phones only have the list of all apps. */
        val FILES_ACCESS_ACTIONS = listOf(
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" to true,
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION" to false,
        )
    }
}
