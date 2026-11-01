package com.uminocelo.candle.remote

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** The IPv4 address of the active network (Wi-Fi or Ethernet), or null when offline. */
fun Context.ipv4Address(): String? {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    return connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses
        ?.map { it.address }
        ?.firstOrNull { it is Inet4Address }
        ?.hostAddress
}
