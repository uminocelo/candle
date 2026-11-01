package com.uminocelo.candle.settings

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.remote.Feature
import com.uminocelo.candle.remote.RemoteService
import com.uminocelo.candle.remote.ipv4Address

/** The checklist as it stands on this device. */
fun Context.setupState(): SetupState = SetupState(
    files = Environment.isExternalStorageManager(),
    home = defaultHomePackage() == packageName,
    overlay = Settings.canDrawOverlays(this),
    remote = RemoteService.isEnabled(this, Feature.REMOTE),
)

private fun Context.defaultHomePackage(): String? =
    packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

/** Opens the checklist once per install when Candle starts without file access or as a guest home screen. */
fun Activity.showSetupOnFirstRun() {
    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_SHOWN, false) || !setupState().needsFirstRun) return
    prefs.edit().putBoolean(KEY_SHOWN, true).apply()
    startActivity(SettingsActivity.intent(this))
}

/**
 * Starts the best way to do [item] here: a system screen or dialog (the caller refreshes in
 * onResume), else a dialog with the adb command, calling [onDialogClosed] when it closes.
 */
fun Activity.startSetup(item: SetupItem, onDialogClosed: () -> Unit) {
    when (val way = Setup.way(item, packageName) { intentFor(it)?.resolveActivity(packageManager) != null }) {
        is SetupWay.Adb -> showAdbCommand(way.command, onDialogClosed)
        else -> try {
            // The role dialog closes at once unless it knows its caller, which only a result request gives.
            startActivityForResult(intentFor(way), REQUEST_SETUP)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Activity.intentFor(way: SetupWay): Intent? = when (way) {
    is SetupWay.Screen -> Intent(way.action).apply { if (way.forPackage) data = Uri.parse("package:$packageName") }
    SetupWay.HomeRole -> getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
        ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
    is SetupWay.Adb -> null
}

private fun Activity.showAdbCommand(command: String, onClosed: () -> Unit) {
    val ip = ipv4Address() ?: getString(R.string.setup_this_device)
    AlertDialog.Builder(this)
        .setTitle(R.string.setup_adb_title)
        .setMessage(getString(R.string.setup_adb_message, ip, command))
        .setPositiveButton(android.R.string.ok, null)
        .setOnDismissListener { onClosed() }
        .show()
}

private const val REQUEST_SETUP = 1
private const val PREFS = "setup"
private const val KEY_SHOWN = "first_run_shown"
