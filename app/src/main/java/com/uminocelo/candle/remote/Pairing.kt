package com.uminocelo.candle.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/** Where paired phones' tokens live, as SHA-256 hashes; SharedPreferences on the device, a set in tests. */
interface TokenStore {
    fun load(): Set<String>

    fun save(hashes: Set<String>)
}

sealed interface PairResult {
    data class Paired(val token: String) : PairResult

    /** Wrong PIN; the PIN stays valid until [Pairing.MAX_ATTEMPTS] misses. */
    data object WrongPin : PairResult

    /** No PIN on screen: never started, expired, used, or too many misses. */
    data object NoPin : PairResult
}

/**
 * Pairing a phone: [start] makes a 4-digit PIN that the projector shows; [pair] with
 * that PIN returns a token the phone sends with every command.
 */
class Pairing(
    private val store: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    private var pin: String? = null
    private var pinExpiresAt = 0L
    private var misses = 0

    @Synchronized
    fun start(): String {
        val newPin = "%04d".format(random.nextInt(10_000))
        pin = newPin
        pinExpiresAt = now() + PIN_LIFETIME_MS
        misses = 0
        return newPin
    }

    @Synchronized
    fun pair(entered: String): PairResult {
        val current = pin
        if (current == null || now() > pinExpiresAt) {
            pin = null
            return PairResult.NoPin
        }
        if (!MessageDigest.isEqual(entered.toByteArray(), current.toByteArray())) {
            if (++misses >= MAX_ATTEMPTS) pin = null
            return PairResult.WrongPin
        }
        pin = null
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).toHex()
        store.save(store.load() + hash(token))
        return PairResult.Paired(token)
    }

    @Synchronized
    fun isPaired(token: String?): Boolean = token != null && hash(token) in store.load()

    /** Forgets every phone and cancels a PIN on screen. */
    @Synchronized
    fun forgetAll() {
        pin = null
        store.save(emptySet())
    }

    companion object {
        const val PIN_LIFETIME_MS = 2 * 60 * 1000L
        const val MAX_ATTEMPTS = 5
        private const val TOKEN_BYTES = 16

        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
