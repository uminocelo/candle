package com.uminocelo.candle.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.Tiles
import com.uminocelo.candle.home.toComponentName
import com.uminocelo.candle.remote.Feature
import com.uminocelo.candle.remote.RemoteApi
import com.uminocelo.candle.remote.RemoteService
import com.uminocelo.candle.remote.ipv4Address
import com.uminocelo.candle.remote.qrBitmap
import com.uminocelo.candle.replaceChildren
import com.uminocelo.candle.usb.mountedDrives
import java.io.File

/**
 * The settings hub: direct toggles (OK cycles the value), tiles that open system
 * and vendor screens, and a device panel refreshed every 5 s while visible.
 */
class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var tiles: Tiles
    private val handler = Handler(Looper.getMainLooper())

    private val infoTick = object : Runnable {
        override fun run() {
            showDeviceInfo()
            handler.postDelayed(this, INFO_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = AndroidSettingsStore(contentResolver)
        tiles = Tiles(this)
        val screens = SystemScreens.available { target -> intentFor(target).resolveActivity(packageManager) != null }
        findViewById<LinearLayout>(R.id.screens_row).replaceChildren(
            screens.map { (screen, target) -> tiles.text(getString(screen.title)) { open(intentFor(target)) } } +
                tiles.text(getString(R.string.about)) { startActivity(AboutActivity.intent(this)) },
        )
    }

    override fun onResume() {
        super.onResume()
        showSetup()
        showSettings()
        showRemote()
        handler.post(infoTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(infoTick)
    }

    /** The setup checklist; on first open, focus goes to the first item still missing. */
    private fun showSetup() {
        val state = setupState()
        val row = findViewById<LinearLayout>(R.id.setup_row)
        val focused = row.indexOfChild(row.findFocus())
        row.replaceChildren(
            SetupItem.entries.map { item ->
                tiles.text("${getString(item.title)}\n${getString(item.status(state))}") {
                    if (state.granted(item)) Toast.makeText(this, R.string.setup_already_done, Toast.LENGTH_SHORT).show() else startSetup(item, ::showSetup)
                }
            },
        )
        val first = state.missing.firstOrNull()?.ordinal?.takeIf { currentFocus == null }
        (focused.takeIf { it >= 0 } ?: first)?.let { row.getChildAt(it)?.requestFocus() }
    }

    private val SetupItem.title: Int
        get() = when (this) {
            SetupItem.FILES -> R.string.setup_files
            SetupItem.HOME -> R.string.setup_home
            SetupItem.OVERLAY -> R.string.setup_overlay
        }

    private fun SetupItem.status(state: SetupState): Int = when (this) {
        SetupItem.FILES -> if (state.files) R.string.setup_files_done else R.string.setup_files_missing
        SetupItem.HOME -> if (state.home) R.string.setup_home_done else R.string.setup_home_missing
        SetupItem.OVERLAY -> when {
            state.overlay -> R.string.setup_overlay_done
            state.remote -> R.string.setup_overlay_missing
            else -> R.string.setup_overlay_unused
        }
    }

    private fun showSettings() {
        val grants = settingsGrants()
        val row = findViewById<LinearLayout>(R.id.settings_row)
        // Rebuilding drops focus; give it back only if this row had it, or nothing does (first open).
        val takeFocus = row.hasFocus() || currentFocus == null
        val focused = row.indexOfChild(row.findFocus()).coerceAtLeast(0)
        row.replaceChildren(
            DirectSettings.ALL.map { setting ->
                tiles.text(label(setting)) { change(setting) }.apply {
                    if (!grants.allows(setting.table)) {
                        isEnabled = false
                        isFocusable = false
                        alpha = DISABLED_ALPHA
                    }
                }
            },
        )
        if (takeFocus) {
            val tilesInRow = (0 until row.childCount).map(row::getChildAt)
            (tilesInRow.getOrNull(focused)?.takeIf { it.isFocusable } ?: tilesInRow.firstOrNull { it.isFocusable })?.requestFocus()
        }

        val message = findViewById<TextView>(R.id.grant_message)
        message.visibility = if (grants.complete) View.GONE else View.VISIBLE
        message.text = grantMessage(grants)
    }

    private fun showRemote() {
        val enabled = RemoteService.isEnabled(this, Feature.REMOTE)
        val row = findViewById<LinearLayout>(R.id.remote_row)
        val focused = row.indexOfChild(row.findFocus())
        row.replaceChildren(
            listOf(
                featureTile(Feature.REMOTE, R.string.remote_title),
                featureTile(Feature.RENDERER, R.string.renderer_title),
                tiles.text(getString(R.string.remote_forget)) {
                    RemoteService.pairing(this).forgetAll()
                    Toast.makeText(this, R.string.remote_forgotten, Toast.LENGTH_SHORT).show()
                },
            ),
        )
        if (focused >= 0) row.getChildAt(focused)?.requestFocus()
        val ip = ipv4Address().takeIf { enabled }
        val qr = findViewById<ImageView>(R.id.remote_qr)
        qr.visibility = if (ip != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.remote_info).text = when {
            ip != null -> {
                qr.setImageBitmap(qrBitmap(RemoteApi.address(ip), resources.getDimensionPixelSize(R.dimen.remote_qr)))
                getString(R.string.remote_address_scan, ip, RemoteApi.PORT)
            }
            enabled -> getString(R.string.remote_address, getString(R.string.unknown), RemoteApi.PORT)
            else -> getString(R.string.remote_off_hint)
        }
    }

    /** OK turns [feature] on or off. */
    private fun featureTile(feature: Feature, title: Int): View {
        val on = RemoteService.isEnabled(this, feature)
        return tiles.text("${getString(title)}\n${getString(if (on) R.string.choice_on else R.string.choice_off)}") {
            RemoteService.setEnabled(this, feature, !on)
            showRemote()
        }
    }

    private fun label(setting: DirectSetting): String {
        val value = setting.current(store)?.let { getString(it.label) } ?: getString(R.string.choice_custom)
        return "${getString(setting.title)}\n$value"
    }

    private fun change(setting: DirectSetting) {
        val choice = setting.next(store)
        if (!setting.needsConfirmation(choice)) return write(setting, choice)
        val dialog = AlertDialog.Builder(this)
            .setTitle(setting.title)
            .setMessage(R.string.network_debugging_off_warning)
            .setPositiveButton(R.string.turn_off) { _, _ -> write(setting, choice) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // A stray OK should not cut off adb.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun write(setting: DirectSetting, choice: Choice) {
        if (!setting.apply(store, choice)) Toast.makeText(this, R.string.setting_not_saved, Toast.LENGTH_SHORT).show()
        showSettings()
    }

    private fun grantMessage(grants: Grants): String {
        val commands = listOfNotNull(
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS".takeUnless { grants.secureSettings },
            "adb shell appops set $packageName WRITE_SETTINGS allow".takeUnless { grants.systemSettings },
        )
        return getString(R.string.grant_missing) + "\n" + commands.joinToString("\n")
    }

    private fun intentFor(target: ScreenTarget): Intent = when (target) {
        is ScreenTarget.Action -> Intent(target.action)
        is ScreenTarget.Component -> Intent(Intent.ACTION_MAIN).setComponent(target.component.toComponentName())
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeviceInfo() {
        val memory = runCatching { MemInfo.parse(File("/proc/meminfo").readText()) }
            .getOrDefault(MemInfo(null, null, null, null))
        val data = StatFs(Environment.getDataDirectory().path)
        val drives = runCatching { mountedDrives() }.getOrDefault(emptyList())
        val unknown = getString(R.string.unknown)
        findViewById<TextView>(R.id.device_info).text = listOf(
            getString(R.string.info_ip, ipv4Address() ?: unknown),
            getString(R.string.info_ram, kb(memory.availableKb), kb(memory.totalKb)),
            getString(R.string.info_zram, kb(memory.swapUsedKb), kb(memory.swapTotalKb)),
            getString(R.string.info_storage, formatKb(data.availableBytes / 1024), formatKb(data.totalBytes / 1024)),
            getString(R.string.info_usb, drives.joinToString { it.label }.ifEmpty { getString(R.string.none) }),
            getString(R.string.info_versions, Build.VERSION.RELEASE, appVersion()),
        ).joinToString("\n")
    }

    private fun kb(value: Long?) = value?.let(::formatKb) ?: getString(R.string.unknown)

    private fun appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    companion object {
        private const val INFO_INTERVAL_MS = 5_000L
        private const val DISABLED_ALPHA = 0.4f

        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
