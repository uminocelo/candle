package com.uminocelo.candle.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Who may see the web page without the password. */
enum class WebMode {
    /** Browsing and downloading open to the home Wi-Fi; uploads need the password. */
    PUBLIC,

    /** Everything needs the password. */
    PRIVATE,
}

/** What a request may do, from the mode and whether its phone is logged in. */
data class Access(val mode: WebMode, val passwordSet: Boolean, val loggedIn: Boolean) {
    /** Without a password nobody could log in, so the page stays public until one is set. */
    val canBrowse: Boolean get() = loggedIn || mode == WebMode.PUBLIC || !passwordSet
    val canUpload: Boolean get() = loggedIn
}

/** The result of a login attempt. */
sealed class LoginResult {
    data class Success(val token: String) : LoginResult()

    data class Wrong(val triesLeft: Int) : LoginResult()

    data class LockedOut(val secondsLeft: Long) : LoginResult()

    object NoPassword : LoginResult()
}

/**
 * The web page's password and logins. The password is kept as a salted PBKDF2 hash.
 * A login is a signed token holding its expiry and the password's generation, so it
 * survives restarts, lasts [SESSION_DAYS] days, and stops working when the password
 * changes. [MAX_TRIES] wrong passwords from one address lock it out for [LOCKOUT_MS].
 */
class WebAuth(private val store: SettingsStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private val failures = HashMap<String, Failures>()

    private class Failures(var count: Int, var lockedUntil: Long)

    var mode: WebMode
        get() = WebMode.entries.firstOrNull { it.name == store.string(KEY_MODE) } ?: WebMode.PRIVATE
        set(value) = store.putString(KEY_MODE, value.name)

    val passwordSet: Boolean get() = store.string(KEY_HASH) != null

    /** Sets a new password and signs every phone out. */
    fun setPassword(password: String) {
        require(password.length >= MIN_PASSWORD) { "the password needs at least $MIN_PASSWORD characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        store.putString(KEY_SALT, encode(salt))
        store.putString(KEY_HASH, encode(hash(password, salt)))
        store.putString(KEY_GENERATION, ((generation.toLongOrNull() ?: 0L) + 1).toString())
        synchronized(failures) { failures.clear() }
    }

    fun access(token: String?): Access = Access(mode, passwordSet, isValid(token))

    @Synchronized
    fun login(address: String, password: String): LoginResult {
        val salt = store.string(KEY_SALT)?.let(::decode)
        val expected = store.string(KEY_HASH)?.let(::decode)
        if (salt == null || expected == null) return LoginResult.NoPassword
        val now = clock()
        val record = synchronized(failures) { failures.getOrPut(address) { Failures(0, 0) } }
        if (record.lockedUntil > now) return LoginResult.LockedOut((record.lockedUntil - now + 999) / 1000)
        if (MessageDigest.isEqual(hash(password, salt), expected)) {
            synchronized(failures) { failures.remove(address) }
            return LoginResult.Success(token(now + SESSION_MS))
        }
        record.count++
        if (record.count >= MAX_TRIES) {
            record.count = 0
            record.lockedUntil = now + LOCKOUT_MS
            return LoginResult.LockedOut(LOCKOUT_MS / 1000)
        }
        return LoginResult.Wrong(MAX_TRIES - record.count)
    }

    private val generation: String get() = store.string(KEY_GENERATION) ?: "0"

    /** `expiry.generation.signature` */
    private fun token(expiry: Long): String {
        val payload = "$expiry.$generation"
        return "$payload.${sign(payload)}"
    }

    private fun isValid(token: String?): Boolean {
        if (token == null || !passwordSet) return false
        val parts = token.split('.')
        if (parts.size != 3) return false
        val expiry = parts[0].toLongOrNull() ?: return false
        val payload = "${parts[0]}.${parts[1]}"
        return MessageDigest.isEqual(sign(payload).toByteArray(), parts[2].toByteArray()) &&
            parts[1] == generation && expiry > clock()
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret(), "HmacSHA256"))
        return encode(mac.doFinal(payload.toByteArray()))
    }

    /** The signing key, made once and kept in the settings. */
    private fun secret(): ByteArray = store.string(KEY_SECRET)?.let(::decode)
        ?: ByteArray(32).also(random::nextBytes).also { store.putString(KEY_SECRET, encode(it)) }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String) = Base64.getUrlDecoder().decode(text)

    companion object {
        const val COOKIE = "candle_server"
        const val SESSION_DAYS = 30
        const val SESSION_MS = SESSION_DAYS * 24L * 60 * 60 * 1000
        const val MAX_TRIES = 5
        const val LOCKOUT_MS = 5 * 60 * 1000L
        const val MIN_PASSWORD = 4
        private const val ITERATIONS = 20_000

        private const val KEY_MODE = "web_mode"
        private const val KEY_HASH = "password_hash"
        private const val KEY_SALT = "password_salt"
        private const val KEY_GENERATION = "password_generation"
        private const val KEY_SECRET = "session_secret"
    }
}
