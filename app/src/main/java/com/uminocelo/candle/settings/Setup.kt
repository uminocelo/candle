package com.uminocelo.candle.settings

/** What Candle needs beyond being installed. The phone remote itself is a choice, so it is not an item. */
enum class SetupItem { FILES, HOME, OVERLAY }

/** One way to do a [SetupItem]; [Setup.way] picks the first one the device has. */
sealed interface SetupWay {
    /** A system screen; [forPackage] passes `package:<candle>` as the intent data. */
    data class Screen(val action: String, val forPackage: Boolean) : SetupWay

    /** The system's "make Candle the home app" dialog (`RoleManager.ROLE_HOME`). */
    data object HomeRole : SetupWay

    /** The device has no screen for it: a command to run from a computer. Always available. */
    data class Adb(val command: String) : SetupWay
}

/** Which items are done right now, and whether the phone remote is on. */
data class SetupState(val files: Boolean, val home: Boolean, val overlay: Boolean, val remote: Boolean) {
    /** Whether the device has granted [item], needed or not. */
    fun granted(item: SetupItem): Boolean = when (item) {
        SetupItem.FILES -> files
        SetupItem.HOME -> home
        SetupItem.OVERLAY -> overlay
    }

    // Drawing over apps only matters to the phone remote (its PIN, its "home" command).
    fun done(item: SetupItem): Boolean = granted(item) || (item == SetupItem.OVERLAY && !remote)

    val missing: List<SetupItem> get() = SetupItem.entries.filterNot(::done)

    /** Without file access or the home screen Candle is barely usable, so the checklist opens by itself. */
    val needsFirstRun: Boolean get() = !files || !home
}

object Setup {
    // Android TV builds often leave these screens out; the HY300 has the overlay screen and the
    // home-role dialog, but no all-files-access screen (and its app details page has no such switch).
    fun ways(item: SetupItem, packageName: String): List<SetupWay> = when (item) {
        SetupItem.FILES -> listOf(
            SetupWay.Screen("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", forPackage = true),
            SetupWay.Screen("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", forPackage = false),
            SetupWay.Adb("adb shell appops set $packageName MANAGE_EXTERNAL_STORAGE allow"),
        )
        SetupItem.HOME -> listOf(
            SetupWay.HomeRole,
            SetupWay.Screen("android.settings.HOME_SETTINGS", forPackage = false),
            SetupWay.Adb("adb shell cmd package set-home-activity $packageName/.MainActivity"),
        )
        SetupItem.OVERLAY -> listOf(
            SetupWay.Screen("android.settings.action.MANAGE_OVERLAY_PERMISSION", forPackage = true),
            SetupWay.Adb("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"),
        )
    }

    /** The first way to do [item] that [available] confirms on this device; the adb command otherwise. */
    fun way(item: SetupItem, packageName: String, available: (SetupWay) -> Boolean): SetupWay =
        ways(item, packageName).first { it is SetupWay.Adb || available(it) }
}
