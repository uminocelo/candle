package com.uminocelo.candle.smb

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved SMB servers in private preferences; passwords are encrypted by [PasswordVault]. */
class SmbServers(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<SmbServer> =
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull(::get).sortedBy { it.displayName.lowercase() }

    fun get(id: String): SmbServer? {
        val host = prefs.getString(key(id, "host"), null) ?: return null
        return SmbServer(
            id = id,
            host = host,
            share = prefs.getString(key(id, "share"), "").orEmpty(),
            username = prefs.getString(key(id, "username"), "").orEmpty(),
            domain = prefs.getString(key(id, "domain"), "").orEmpty(),
        )
    }

    fun password(id: String): CharArray =
        prefs.getString(key(id, "password"), null)?.let(PasswordVault::decrypt) ?: CharArray(0)

    fun save(server: SmbServer, password: CharArray) {
        prefs.edit()
            .putStringSet(KEY_IDS, prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() + server.id)
            .putString(key(server.id, "host"), server.host)
            .putString(key(server.id, "share"), server.share)
            .putString(key(server.id, "username"), server.username)
            .putString(key(server.id, "domain"), server.domain)
            .putString(key(server.id, "password"), PasswordVault.encrypt(password))
            .apply()
        SmbjShare.forget(server.id)
    }

    fun remove(id: String) {
        val editor = prefs.edit().putStringSet(KEY_IDS, prefs.getStringSet(KEY_IDS, emptySet()).orEmpty() - id)
        FIELDS.forEach { editor.remove(key(id, it)) }
        editor.apply()
        SmbjShare.forget(id)
    }

    /** A browsable source for [server], connecting on first use. */
    fun source(server: SmbServer): SmbMediaSource = SmbMediaSource(server) { SmbjShare(server, password(server.id)) }

    private fun key(id: String, field: String) = "$id.$field"

    companion object {
        private const val PREFS = "smb_servers"
        private const val KEY_IDS = "ids"
        private val FIELDS = listOf("host", "share", "username", "domain", "password")

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** AES-GCM with a key that never leaves the Android Keystore. */
object PasswordVault {
    private const val KEY_ALIAS = "candle_smb_passwords"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(password: CharArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(String(password).toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null when the stored value cannot be decrypted (e.g. the key was reset). */
    fun decrypt(stored: String): CharArray? = runCatching {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8).toCharArray()
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
