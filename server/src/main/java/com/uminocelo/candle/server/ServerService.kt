package com.uminocelo.candle.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.uminocelo.candle.http.HttpServer
import com.uminocelo.candle.upnp.SsdpAdvertiser
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * Runs the server in the foreground. The phone lives on its charger, so the service
 * holds a Wi-Fi lock and a CPU wake lock for as long as it runs: a film streamed with
 * the screen off must not stall when the phone would otherwise doze. While the user
 * shares with players, it announces the DLNA media server over SSDP.
 */
class ServerService : Service() {
    private var server: HttpServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var mediaServer: MediaServer

    /** The phone's Wi-Fi address, followed through the default network. */
    @Volatile
    private var ip: String? = null

    /** SSDP starts and stops here, off the main thread: closing waits for the byebyes to go out. */
    private val ssdpThread = Executors.newSingleThreadExecutor { Thread(it, "server-ssdp") }
    private var advertiser: SsdpAdvertiser? = null
    private var advertisedIp: String? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            ip = linkProperties.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
            updateNotification(ip)
            refreshAdvertiser()
        }

        override fun onLost(network: Network) {
            ip = null
            updateNotification(null)
            refreshAdvertiser()
        }
    }

    // The locks last as long as the service on purpose: it is released in onDestroy.
    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(ipv4Address()))
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "candle-server").apply {
                setReferenceCounted(false)
                acquire()
            }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "candle:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        val settings = serverSettings()
        val index = MediaIndex(settings::sharedFolders)
        val thumbnails = AndroidThumbnails(File(cacheDir, "thumbs"))
        mediaServer = MediaServer(settings.uuid, { settings.name }, index, thumbnails, { settings.shareWithPlayers })
        val api = ServerApi(
            settings, packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(), mediaServer, index, thumbnails,
            webAuth(), Uploads(File(Environment.getExternalStorageDirectory(), Uploads.FOLDER)), ::asset,
        )
        server = HttpServer(ServerApi.PORT, MAX_CONNECTIONS, ServerApi::streamed, api::handle).also {
            try {
                it.start()
            } catch (e: IOException) {
                Log.e(TAG, "port ${ServerApi.PORT} unavailable", e)
                stopSelf()
            }
        }
        ip = ipv4Address()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    // Also called by sync() after a settings change: the notification shows the name.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification(ipv4Address())
        refreshAdvertiser()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        server?.close()
        ssdpThread.execute(::stopAdvertiser)
        ssdpThread.shutdown()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun asset(name: String): ByteArray? =
        try {
            assets.open("web/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }

    /** Announces while sharing with players is on and the phone has an address; restarts when the address changes. */
    private fun refreshAdvertiser() {
        val here = ip
        val on = here != null && serverSettings().shareWithPlayers
        ssdpThread.execute {
            if (advertiser != null && (!on || here != advertisedIp)) stopAdvertiser()
            if (on && advertiser == null) startAdvertiser(here!!)
        }
    }

    private fun startAdvertiser(here: String) {
        // Without it, Android drops multicast packets (M-SEARCHes) before they reach the socket.
        val lock = getSystemService(WifiManager::class.java).createMulticastLock("server-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            advertiser = SsdpAdvertiser(mediaServer.ssdpDevice, { "http://$here:${ServerApi.PORT}${MediaServer.DEVICE_PATH}" }, { Log.w(TAG, it) })
                .also { it.start() }
            advertisedIp = here
            multicastLock = lock
        } catch (e: IOException) {
            Log.w(TAG, "SSDP unavailable on $here", e)
            lock.release()
        }
    }

    private fun stopAdvertiser() {
        advertiser?.close()
        advertiser = null
        advertisedIp = null
        multicastLock?.release()
        multicastLock = null
    }

    private fun updateNotification(ip: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ip))
    }

    private fun notification(ip: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(serverSettings().name)
            .setContentText(if (ip != null) getString(R.string.notification, ServerApi.address(ip)) else getString(R.string.notification_no_network))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CandleServer"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1

        /** Films playing on several screens at once, each holding a connection, plus page requests. */
        private const val MAX_CONNECTIONS = 16

        /** Starts or stops the service to match the on/off setting. */
        fun sync(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (context.serverSettings().enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
