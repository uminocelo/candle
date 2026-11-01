package com.uminocelo.candle.settings

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

class AndroidSettingsStore(private val resolver: ContentResolver) : SettingsStore {
    override fun get(table: Table, key: String): String? = when (table) {
        Table.SYSTEM -> Settings.System.getString(resolver, key)
        Table.SECURE -> Settings.Secure.getString(resolver, key)
        Table.GLOBAL -> Settings.Global.getString(resolver, key)
    }

    override fun put(table: Table, key: String, value: String): Boolean = try {
        when (table) {
            Table.SYSTEM -> Settings.System.putString(resolver, key, value)
            Table.SECURE -> Settings.Secure.putString(resolver, key, value)
            Table.GLOBAL -> Settings.Global.putString(resolver, key, value)
        }
    } catch (e: SecurityException) {
        false
    }
}

fun Context.settingsGrants() = Grants(
    secureSettings = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
    systemSettings = Settings.System.canWrite(this),
)
