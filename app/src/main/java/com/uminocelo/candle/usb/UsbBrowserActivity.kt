package com.uminocelo.candle.usb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.uminocelo.candle.R
import com.uminocelo.candle.browse.BrowserRow
import com.uminocelo.candle.browse.FolderBrowser
import com.uminocelo.candle.browse.openFromBrowser
import com.uminocelo.candle.media.MediaItem
import com.uminocelo.candle.media.MediaSource
import com.uminocelo.candle.media.UsbDrive
import com.uminocelo.candle.media.UsbMediaSource

/**
 * Lists mounted USB drives, then the folders inside one. OK opens, Back goes up a folder.
 * The drive list follows drives being plugged in and out.
 */
class UsbBrowserActivity : Activity() {
    private lateinit var driveWatch: AutoCloseable
    private lateinit var browser: FolderBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        browser = FolderBrowser(this, ::openFile)
        driveWatch = watchDrives(::onDriveEvent)
        showDrives()
    }

    override fun onDestroy() {
        super.onDestroy()
        driveWatch.close()
        browser.close()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            browser.source == null -> super.onBackPressed()
            !browser.up() -> showDrives()
        }
    }

    private fun onDriveEvent(event: DriveEvent) {
        val open = browser.source as UsbMediaSource?
        when {
            open == null -> showDrives(select = browser.selectedPosition)
            !event.mounted && event.affects(open.drive.mountPath) -> {
                Toast.makeText(this, R.string.drive_removed, Toast.LENGTH_SHORT).show()
                showDrives()
            }
        }
    }

    private fun showDrives(select: Int = 0) {
        browser.showTop(getString(R.string.tile_usb), R.string.no_drives, select, {
            mountedDrives().also { Log.i(TAG, "drives: ${it.map(UsbDrive::mountPath)}") }
        }) { drive ->
            val detail = if (drive.readOnly) getString(R.string.read_only) else ""
            BrowserRow(drive.label, detail) { browser.openSource(UsbMediaSource(drive), drive::breadcrumb) }
        }
    }

    private fun openFile(source: MediaSource, item: MediaItem, folder: List<MediaItem>) {
        openFromBrowser(source, item, folder)
    }

    companion object {
        private const val TAG = "CandleUsb"

        fun intent(context: Context): Intent = Intent(context, UsbBrowserActivity::class.java)
    }
}
