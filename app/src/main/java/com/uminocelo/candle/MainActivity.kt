package com.uminocelo.candle

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import com.uminocelo.candle.home.AppCatalog
import com.uminocelo.candle.home.HomeContent
import com.uminocelo.candle.home.LaunchableApp
import com.uminocelo.candle.home.PackageManagerCatalog
import com.uminocelo.candle.home.Shortcuts
import com.uminocelo.candle.home.SourceTile
import com.uminocelo.candle.home.buildHome
import com.uminocelo.candle.home.toComponentName
import com.uminocelo.candle.player.PlaybackTarget
import com.uminocelo.candle.player.VideoPlayerActivity
import com.uminocelo.candle.remote.RemoteService
import com.uminocelo.candle.resume.ResumeEntry
import com.uminocelo.candle.resume.ResumeStore
import com.uminocelo.candle.resume.formatDuration
import com.uminocelo.candle.resume.resumeStore
import com.uminocelo.candle.settings.SettingsActivity
import com.uminocelo.candle.settings.showSetupOnFirstRun
import com.uminocelo.candle.smb.NetworkActivity
import com.uminocelo.candle.usb.UsbBrowserActivity

/**
 * The home screen. A plain [Activity] (no AppCompat) keeps idle memory low on the HY300.
 */
class MainActivity : Activity() {
    private lateinit var catalog: AppCatalog
    private lateinit var resumeStore: ResumeStore
    private lateinit var tiles: Tiles
    private var shown: HomeContent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        catalog = PackageManagerCatalog(packageManager)
        resumeStore = resumeStore()
        tiles = Tiles(this)
        // Candle is the home screen, so this runs at boot: start the phone remote if it is on.
        RemoteService.sync(this)
        showSetupOnFirstRun()
    }

    override fun onResume() {
        super.onResume()
        val content = buildHome(catalog, resumeStore.entries(), packageName)
        // Rebuilding the rows drops focus, so only do it when something changed.
        if (content != shown) render(content, focusFirst = shown == null)
    }

    // This is the home screen: there is nothing to go back to.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit

    private fun render(content: HomeContent, focusFirst: Boolean) {
        shown = content
        val sources = findViewById<LinearLayout>(R.id.sources_row)
        sources.replaceChildren(content.sources.map { tiles.text(getString(it.labelRes())) { open(it) } })

        findViewById<View>(R.id.continue_section).visibility =
            if (content.showContinueWatching) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.continue_row).replaceChildren(
            content.continueWatching.map { entry -> tiles.text(entry.tileLabel()) { resume(entry) } },
        )

        findViewById<View>(R.id.apps_section).visibility =
            if (content.apps.isEmpty()) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.apps_row).replaceChildren(content.apps.map(::appTile))

        if (focusFirst) sources.getChildAt(0)?.requestFocus()
    }

    private fun appTile(app: LaunchableApp): View {
        val component = app.component.toComponentName()
        val banner = runCatching { packageManager.getActivityBanner(component) }.getOrNull()
        val icon = runCatching { packageManager.getActivityIcon(component) }.getOrNull()
        return tiles.app(app.label, banner, icon) {
            launch(PackageManagerCatalog.tvLauncherIntent().setComponent(component))
        }
    }

    private fun open(tile: SourceTile) = when (tile) {
        SourceTile.USB -> launch(UsbBrowserActivity.intent(this))
        SourceTile.NETWORK -> launch(NetworkActivity.intent(this))
        SourceTile.HDMI -> launch(Intent(Intent.ACTION_MAIN).setComponent(Shortcuts.HDMI.toComponentName()))
        SourceTile.SETTINGS -> launch(SettingsActivity.intent(this))
    }

    private fun resume(entry: ResumeEntry) {
        val target = PlaybackTarget.forEntry(entry)
        if (target == null) {
            Toast.makeText(this, R.string.source_unavailable, Toast.LENGTH_SHORT).show()
        } else {
            launch(VideoPlayerActivity.intent(this, target, startAtSaved = true))
        }
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ResumeEntry.tileLabel() = "$title\n${formatDuration(positionMs)} / ${formatDuration(durationMs)}"

    private fun SourceTile.labelRes() = when (this) {
        SourceTile.USB -> R.string.tile_usb
        SourceTile.NETWORK -> R.string.tile_network
        SourceTile.HDMI -> R.string.tile_hdmi
        SourceTile.SETTINGS -> R.string.tile_settings
    }
}
