package com.uminocelo.candle.smb

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.browse.BrowserRow
import com.uminocelo.candle.browse.FolderBrowser
import com.uminocelo.candle.browse.openFromBrowser
import com.uminocelo.candle.dlna.DlnaClient
import com.uminocelo.candle.dlna.DlnaMediaSource
import com.uminocelo.candle.dlna.DlnaServer
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import java.util.concurrent.Executors

/**
 * Saved SMB servers and the DLNA servers found on the network, then the folders of
 * one. OK opens, Back goes up a folder; a long press of OK (or Menu) on an SMB
 * server edits or removes it.
 */
class NetworkActivity : Activity() {
    private lateinit var servers: SmbServers
    private lateinit var browser: FolderBrowser
    private val discovery = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        servers = SmbServers(this)
        browser = FolderBrowser(this, ::openFile)
        showServers()
    }

    override fun onDestroy() {
        super.onDestroy()
        browser.close()
        discovery.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            browser.source == null -> super.onBackPressed()
            !browser.up() -> showServers()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyCode == KeyEvent.KEYCODE_MENU && browser.openSelectedMenu() || super.onKeyDown(keyCode, event)

    /**
     * Shows the server list at once with the DLNA servers found last time, then
     * searches the network again and refreshes the list if it is still showing.
     */
    private fun showServers(select: Int = 0) {
        showServerRows(select)
        discovery.execute {
            val found = runCatching { DlnaClient.discover() }
                .onFailure { Log.w(TAG, "DLNA discovery failed", it) }
                .getOrNull() ?: return@execute
            Log.i(TAG, "DLNA servers: ${found.map(DlnaServer::name)}")
            runOnUiThread {
                if (isDestroyed || browser.source != null || found == dlnaServers) return@runOnUiThread
                dlnaServers = found
                showServerRows(browser.selectedPosition)
            }
        }
    }

    private fun showServerRows(select: Int) {
        val dlna = dlnaServers
        // null is the "Add SMB server" row, always last.
        browser.showTop(getString(R.string.tile_network), R.string.folder_empty, select, {
            servers.all() + dlna + listOf(null)
        }) { server ->
            when (server) {
                null -> BrowserRow(getString(R.string.smb_add), getString(R.string.smb_hint)) { editServer(null) }
                is DlnaServer -> BrowserRow(server.name, getString(R.string.dlna_detail)) { open(server) }
                else -> {
                    server as SmbServer
                    val user = server.username.ifEmpty { getString(R.string.smb_guest) }
                    BrowserRow(server.displayName, user, menu = { showServerMenu(server) }) { open(server) }
                }
            }
        }
    }

    private fun open(server: DlnaServer) {
        browser.openSource(DlnaMediaSource(server)) { server.name }
    }

    private fun open(server: SmbServer) {
        browser.openSource(servers.source(server)) { path -> if (path.isEmpty()) server.displayName else "${server.displayName}/$path" }
    }

    private fun openFile(source: MediaSource, item: MediaItem, folder: List<MediaItem>) {
        openFromBrowser(source, item, folder)
    }

    private fun showServerMenu(server: SmbServer) {
        val actions = arrayOf(getString(R.string.smb_edit), getString(R.string.smb_remove))
        AlertDialog.Builder(this)
            .setTitle(server.displayName)
            .setItems(actions) { _, which ->
                if (which == 0) editServer(server) else confirmRemove(server)
            }
            .show()
    }

    private fun confirmRemove(server: SmbServer) {
        AlertDialog.Builder(this)
            .setTitle(server.displayName)
            .setMessage(R.string.smb_remove_question)
            .setPositiveButton(R.string.smb_remove) { _, _ ->
                servers.remove(server.id)
                showServers()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    /** Adds a server ([existing] null) or edits one; an empty password keeps the saved one. */
    private fun editServer(existing: SmbServer?) {
        val form = layoutInflater.inflate(R.layout.dialog_smb_server, null)
        val host = form.findViewById<EditText>(R.id.host)
        val share = form.findViewById<EditText>(R.id.share)
        val username = form.findViewById<EditText>(R.id.username)
        val password = form.findViewById<EditText>(R.id.password)
        existing?.let {
            host.setText(it.host)
            share.setText(it.share)
            username.setText(it.username)
            password.setHint(R.string.smb_password_keep)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.smb_add else R.string.smb_edit)
            .setView(form)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val server = SmbServer.fromInput(
                    existing?.id ?: SmbServers.newId(), host.text.toString(), share.text.toString(), username.text.toString(),
                )
                if (server == null) {
                    Toast.makeText(this, R.string.smb_missing_fields, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val typed = password.text.toString().toCharArray()
                val secret = if (typed.isEmpty() && existing != null) servers.password(existing.id) else typed
                servers.save(server, secret)
                open(server)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "CandleNetwork"

        /** DLNA servers found by the last search, kept for the life of the process. */
        private var dlnaServers: List<DlnaServer> = emptyList()

        fun intent(context: Context): Intent = Intent(context, NetworkActivity::class.java)
    }
}
