import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `a release tag`() {
        val version = AppVersion.parse("v1.2.3")
        assertEquals("1.2.3", version.name)
        assertEquals(1_020_399, version.code)
    }

    @Test
    fun `a release candidate sorts before its release`() {
        val rc = AppVersion.parse("v1.2.3-rc1")
        assertEquals("1.2.3-rc1", rc.name)
        assertEquals(1_020_301, rc.code)
        assertTrue(rc.code < AppVersion.parse("1.2.3").code)
        assertTrue(AppVersion.parse("1.2.3").code < AppVersion.parse("1.2.4-rc1").code)
        assertTrue(AppVersion.parse("0.9.9").code < AppVersion.parse("1.0.0-rc1").code)
    }

    @Test
    fun `codes beat the first sideloaded builds`() {
        assertTrue(AppVersion.parse("0.1.0").code > 1)
    }

    @Test
    fun `the tag wins over gradle properties`() {
        assertEquals("1.0.0", AppVersion.resolve("v1.0.0", "0.9.0").name)
    }

    @Test
    fun `no tag uses gradle properties`() {
        assertEquals("0.9.0", AppVersion.resolve(null, "0.9.0").name)
        assertEquals("0.9.0", AppVersion.resolve(" ", "0.9.0").name)
    }

    @Test
    fun `no version at all is an error`() {
        val error = assertThrows(IllegalArgumentException::class.java) { AppVersion.resolve(null, null) }
        assertTrue(error.message!!.contains("candle.version"))
    }

    @Test
    fun `bad versions are refused`() {
        listOf("1.2", "v1.2.3-beta", "1.2.3-rc0", "1.2.3-rc99", "1.100.0", "latest", "").forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { AppVersion.parse(text) }
        }
    }
}
