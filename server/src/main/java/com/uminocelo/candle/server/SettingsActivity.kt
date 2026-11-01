package com.uminocelo.candle.server

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** The server's one screen: on/off, sharing with players, its address and QR code, its name, and the shared folders. */
class SettingsActivity : Activity() {
    private val settings by lazy { serverSettings() }
    private val auth by lazy { webAuth() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ServerService.sync(this)

        findViewById<Switch>(R.id.server_switch).setOnCheckedChangeListener { _, on ->
            if (on != settings.enabled) {
                settings.enabled = on
                ServerService.sync(this)
                render()
            }
        }
        findViewById<Switch>(R.id.players_switch).setOnCheckedChangeListener { _, on ->
            if (on != settings.shareWithPlayers) {
                settings.shareWithPlayers = on
                ServerService.sync(this)
            }
        }
        findViewById<Switch>(R.id.private_switch).setOnCheckedChangeListener { _, on ->
            val mode = if (on) WebMode.PRIVATE else WebMode.PUBLIC
            if (mode != auth.mode) {
                auth.mode = mode
                render()
            }
        }
        findViewById<Button>(R.id.password).setOnClickListener { askForPassword() }
        val name = findViewById<EditText>(R.id.name)
        name.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) saveName(name)
            false
        }
        name.setOnFocusChangeListener { _, focused -> if (!focused) saveName(name) }
        findViewById<Button>(R.id.files_access).setOnClickListener { askForFilesAccess() }
        findViewById<Button>(R.id.battery).setOnClickListener { askToIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.about).setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        name.setText(settings.name)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        saveName(findViewById(R.id.name))
        super.onPause()
    }

    private fun saveName(field: EditText) {
        if (field.text.toString().trim() == settings.name) return
        settings.name = field.text.toString()
        field.setText(settings.name)
        ServerService.sync(this)
    }

    private fun render() {
        findViewById<Switch>(R.id.server_switch).isChecked = settings.enabled
        findViewById<Switch>(R.id.players_switch).apply {
            isChecked = settings.shareWithPlayers
            isEnabled = settings.enabled
        }
        val ip = ipv4Address()
        val qr = findViewById<ImageView>(R.id.qr)
        val address = findViewById<TextView>(R.id.address)
        when {
            !settings.enabled -> address.setText(R.string.server_off)
            ip == null -> address.setText(R.string.server_no_network)
            else -> address.text = getString(R.string.server_running, ServerApi.address(ip))
        }
        val showQr = settings.enabled && ip != null
        qr.visibility = if (showQr) View.VISIBLE else View.GONE
        if (showQr) qr.setImageBitmap(qrBitmap(ServerApi.address(ip!!), resources.getDimensionPixelSize(R.dimen.qr)))

        findViewById<Switch>(R.id.private_switch).apply {
            isChecked = auth.mode == WebMode.PRIVATE
            isEnabled = auth.passwordSet
        }
        findViewById<TextView>(R.id.private_hint).setText(
            when {
                !auth.passwordSet -> R.string.no_password
                auth.mode == WebMode.PRIVATE -> R.string.private_on
                else -> R.string.private_off
            },
        )
        findViewById<Button>(R.id.password).setText(if (auth.passwordSet) R.string.change_password else R.string.set_password)

        val firstRun = FirstRun(
            filesAccess = Environment.isExternalStorageManager(),
            batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
        show(firstRun.visible, R.id.first_run)
        show(firstRun.showFilesAccess, R.id.files_access, R.id.files_access_hint)
        show(firstRun.showBattery, R.id.battery, R.id.battery_hint)
        renderFolders()
    }

    /** Two password fields; a new password signs every phone out. */
    private fun askForPassword() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val first = field(getString(R.string.password_hint, WebAuth.MIN_PASSWORD))
        val second = field(getString(R.string.password_again))
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (auth.passwordSet) R.string.change_password else R.string.set_password)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Set here, not in the builder, so a mistake keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text.toString()
                when {
                    password.length < WebAuth.MIN_PASSWORD -> first.error = getString(R.string.password_too_short, WebAuth.MIN_PASSWORD)
                    password != second.text.toString() -> second.error = getString(R.string.password_mismatch)
                    else -> {
                        auth.setPassword(password)
                        Toast.makeText(this, R.string.password_saved, Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        render()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun show(visible: Boolean, vararg ids: Int) {
        ids.forEach { findViewById<View>(it).visibility = if (visible) View.VISIBLE else View.GONE }
    }

    // Both screens are declared under <queries> in the manifest.
    @SuppressLint("QueryPermissionsNeeded")
    private fun askForFilesAccess() {
        val intent = FirstRun.FILES_ACCESS_ACTIONS
            .map { (action, forPackage) -> Intent(action).apply { if (forPackage) data = Uri.parse("package:$packageName") } }
            .firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent != null) startActivity(intent) else Toast.makeText(this, R.string.files_access_no_screen, Toast.LENGTH_LONG).show()
    }

    private fun renderFolders() {
        val list = findViewById<LinearLayout>(R.id.folders)
        list.removeAllViews()
        for (folder in settings.allFolders()) {
            list.addView(
                CheckBox(this).apply {
                    text = if (folder.exists) folder.name else getString(R.string.folder_missing, folder.name)
                    textSize = 17f
                    minHeight = (48 * resources.displayMetrics.density).toInt()
                    isChecked = folder.shared
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) settings.share(folder.name) else settings.unshare(folder.name)
                    }
                },
            )
        }
    }

    // A media server that stops when the screen goes off is useless, and this app is sideloaded,
    // not on Google Play, whose policy limits this request.
    @SuppressLint("BatteryLife")
    private fun askToIgnoreBatteryOptimizations() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
