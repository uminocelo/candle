package com.uminocelo.candle.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebAuthTest {
    private var now = 1_000_000L
    private val store = MapStore()
    private val auth = WebAuth(store) { now }

    private fun token(address: String = "a", password: String = "open sesame") =
        (auth.login(address, password) as LoginResult.Success).token

    @Test
    fun `starts private with no password, and nobody can log in`() {
        assertEquals(WebMode.PRIVATE, auth.mode)
        assertFalse(auth.passwordSet)
        assertEquals(LoginResult.NoPassword, auth.login("a", "anything"))
        assertTrue("open until a password exists", auth.access(null).canBrowse)
        assertFalse(auth.access(null).canUpload)
    }

    @Test
    fun `the password is stored hashed, never as typed`() {
        auth.setPassword("open sesame")
        assertFalse(store.values.values.any { it.toString().contains("open sesame") })
        assertTrue(auth.passwordSet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passwords are refused`() {
        auth.setPassword("abc")
    }

    @Test
    fun `a login lasts 30 days and survives a restart`() {
        auth.setPassword("open sesame")
        val token = token()
        assertTrue(auth.access(token).loggedIn)
        assertTrue("a new WebAuth over the same settings accepts it", WebAuth(store) { now }.access(token).loggedIn)
        now += WebAuth.SESSION_MS - 1
        assertTrue(auth.access(token).loggedIn)
        now += 2
        assertFalse(auth.access(token).loggedIn)
    }

    @Test
    fun `forged, altered or garbage tokens are refused`() {
        auth.setPassword("open sesame")
        val (expiry, generation, signature) = token().split('.')
        assertFalse(auth.access("${expiry.toLong() + 1000}.$generation.$signature").loggedIn)
        assertFalse(auth.access("$expiry.${generation.toInt() + 1}.$signature").loggedIn)
        assertFalse(auth.access("garbage").loggedIn)
        assertFalse(auth.access("").loggedIn)
    }

    @Test
    fun `a new password signs every phone out`() {
        auth.setPassword("open sesame")
        val token = token()
        auth.setPassword("another one")
        assertFalse(auth.access(token).loggedIn)
        assertTrue(auth.access(token(password = "another one")).loggedIn)
    }

    @Test
    fun `five wrong tries lock that address out for five minutes`() {
        auth.setPassword("open sesame")
        assertEquals(LoginResult.Wrong(4), auth.login("a", "x"))
        repeat(3) { auth.login("a", "x") }
        assertEquals(LoginResult.LockedOut(300), auth.login("a", "x"))
        assertTrue(auth.login("a", "open sesame") is LoginResult.LockedOut)
        assertTrue("another phone is not locked", auth.login("b", "open sesame") is LoginResult.Success)
        now += WebAuth.LOCKOUT_MS
        assertTrue(auth.login("a", "open sesame") is LoginResult.Success)
        assertEquals("a success resets the count", LoginResult.Wrong(4), auth.login("a", "x"))
    }

    @Test
    fun `the mode rules`() {
        auth.setPassword("open sesame")
        auth.mode = WebMode.PUBLIC
        assertEquals(Access(WebMode.PUBLIC, true, false), auth.access(null))
        assertTrue(auth.access(null).canBrowse)
        assertFalse(auth.access(null).canUpload)
        auth.mode = WebMode.PRIVATE
        assertFalse(auth.access(null).canBrowse)
        assertTrue(auth.access(token()).canBrowse)
        assertTrue(auth.access(token()).canUpload)
    }
}
