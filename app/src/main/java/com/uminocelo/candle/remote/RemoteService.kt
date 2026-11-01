package com.uminocelo.candle.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.uminocelo.candle.R
import com.uminocelo.candle.http.HttpResponse
import com.uminocelo.candle.http.HttpServer
import com.uminocelo.candle.player.NowPlaying
import com.uminocelo.candle.renderer.AndroidRendererPlayer
import com.uminocelo.candle.renderer.MediaRenderer
import com.uminocelo.candle.renderer.RendererDescription
import com.uminocelo.candle.upnp.SsdpAdvertiser
import java.io.IOException
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.Executors

/** What the HTTP server on [RemoteApi.PORT] offers; each one has its own switch in Settings. */
enum class Feature(val key: String) {
    /** The phone remote page and its `/api`. */
    REMOTE("enabled"),

    /** The DLNA player ("Accept videos from phones"): SSDP and `/dlna`. */
    RENDERER("renderer"),
}

/**
 * Runs the HTTP server for the phone remote and the DLNA player, and the renderer's
 * SSDP announcements. A foreground service, so both keep answering while YouTube or
 * HDMI input is in front. A feature that is off answers 404.
 */
class RemoteService : Service() {
    private var server: HttpServer? = null
    private var device: AndroidRemoteDevice? = null
    private var renderer: MediaRenderer? = null

    /** SSDP starts and stops here: sockets are network I/O, which the main thread may not do. */
    private val ssdpThread = Executors.newSingleThreadExecutor()
    private var advertiser: SsdpAdvertiser? = null
    private var advertisedIp: String? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** The projector's address; null while offline. Written on the main thread and the callback's. */
    @Volatile private var ip: String? = null

    /**
     * Wi-Fi connects after the service starts at boot, and may reconnect with a new
     * address; the advertiser follows it, since a multicast socket is tied to one network.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            ip = linkProperties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
            refreshAdvertiser()
        }

        override fun onLost(network: Network) {
            ip = null
            refreshAdvertiser()
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        val androidDevice = AndroidRemoteDevice(this)
        val api = RemoteApi(
            pairing(this),
            androidDevice,
            AndroidRemoteLibrary(this),
            NowPlaying::current,
            SystemClock::elapsedRealtime,
            ::assetFile,
        )
        val renderer = MediaRenderer(rendererUuid(this), AndroidRendererPlayer(this), SystemClock::elapsedRealtime)
            .also { it.start() }
        this.renderer = renderer
        device = androidDevice
        server = HttpServer(RemoteApi.PORT) { request ->
            val feature = if (request.path.startsWith("/dlna/")) Feature.RENDERER else Feature.REMOTE
            when {
                !isEnabled(this, feature) -> HttpResponse.error(404, "not found")
                feature == Feature.RENDERER -> renderer.handle(request)
                else -> api.handle(request)
            }
        }.also {
            try {
                it.start()
            } catch (e: IOException) {
                stopSelf() // port taken
            }
        }
        ip = ipv4Address()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshAdvertiser()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.close()
        renderer?.close()
        device?.hidePin()
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        ip = null
        refreshAdvertiser()
        ssdpThread.shutdown()
        super.onDestroy()
    }

    /** Advertises while the renderer is on and the projector has an address; restarts when the address changes. */
    private fun refreshAdvertiser() {
        val here = ip
        val enabled = isEnabled(this, Feature.RENDERER)
        // Switched off: phones following the state stop getting events.
        if (!enabled) renderer?.clearSubscriptions()
        val on = here != null && enabled
        ssdpThread.execute {
            if (advertiser != null && (!on || here != advertisedIp)) {
                advertiser?.close()
                advertiser = null
                advertisedIp = null
                multicastLock?.release()
                multicastLock = null
            }
            if (on && advertiser == null) startAdvertiser(here!!)
        }
    }

    private fun startAdvertiser(here: String) {
        val lock = getSystemService(WifiManager::class.java).createMulticastLock("candle-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            advertiser = SsdpAdvertiser(
                RendererDescription.ssdpDevice(rendererUuid(this)),
                { "http://$here:${RemoteApi.PORT}${RendererDescription.DEVICE_PATH}" },
                { Log.w(TAG, it) },
            ).also { it.start() }
            advertisedIp = here
            multicastLock = lock
        } catch (e: IOException) {
            Log.w(TAG, "SSDP unavailable on $here", e)
            lock.release()
        }
    }

    private fun assetFile(name: String): ByteArray? =
        try {
            assets.open("remote/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.remote_title), NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_candle)
            .setContentTitle(getString(R.string.remote_title))
            .build()
    }

    companion object {
        private const val TAG = "CandleRemote"
        private const val CHANNEL = "remote"
        private const val NOTIFICATION_ID = 2
        private const val KEY_RENDERER_UUID = "renderer_uuid"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("remote", Context.MODE_PRIVATE)

        fun pairing(context: Context) = Pairing(PrefsTokenStore(prefs(context)))

        fun isEnabled(context: Context, feature: Feature): Boolean = prefs(context).getBoolean(feature.key, false)

        fun setEnabled(context: Context, feature: Feature, enabled: Boolean) {
            prefs(context).edit().putBoolean(feature.key, enabled).apply()
            sync(context)
        }

        /** Starts the service when a feature is on (and tells it what changed), or stops it; the home screen calls it on start. */
        fun sync(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            if (Feature.entries.any { isEnabled(context, it) }) context.startForegroundService(intent) else context.stopService(intent)
        }

        /** The renderer's UPnP id: made once, so phone apps recognise Candle after a restart. */
        private fun rendererUuid(context: Context): String {
            val prefs = prefs(context)
            return prefs.getString(KEY_RENDERER_UUID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_RENDERER_UUID, it).apply() }
        }
    }
}

class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override fun load(): Set<String> = prefs.getStringSet(KEY, null).orEmpty()

    override fun save(hashes: Set<String>) {
        prefs.edit().putStringSet(KEY, hashes).apply()
    }

    private companion object {
        const val KEY = "token_hashes"
    }
}
