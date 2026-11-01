package com.uminocelo.candle.server

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address

class PrefsSettingsStore(private val prefs: SharedPreferences) : SettingsStore {
    override fun string(key: String): String? = prefs.getString(key, null)

    override fun stringSet(key: String): Set<String>? = prefs.getStringSet(key, null)

    override fun boolean(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun putStringSet(key: String, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()

    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

private fun Context.store() = PrefsSettingsStore(getSharedPreferences("server", Context.MODE_PRIVATE))

/** The settings, backed by the app's preferences; the default name is the phone's own. */
fun Context.serverSettings(): ServerSettings {
    val phoneName = ServerSettings.defaultName(Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL)
    return ServerSettings(store(), Environment.getExternalStorageDirectory(), phoneName)
}

/** The web page's mode, password and logins, in the same preferences. */
fun Context.webAuth(): WebAuth = WebAuth(store())

/** The IPv4 address of the active network (Wi-Fi), or null when offline. */
fun Context.ipv4Address(): String? {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    return connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address }
        ?.hostAddress
}

/** A square QR code of [text], dark on white (phone cameras want the contrast), [size] px wide. */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
