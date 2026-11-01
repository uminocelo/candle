import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ThirdPartyTest {
    private val markdown = """
        # Third-party software

        ## Candle (projector)

        | Library | Version | Licence |
        | --- | --- | --- |
        | com.hierynomus:smbj | 0.15.0 | Apache-2.0 |
        | org.slf4j:slf4j-api | 2.0.18 | MIT |

        ## Candle Server (phone)

        | Library | Version | Licence |
        | --- | --- | --- |
        | com.google.zxing:core | 3.5.3 | Apache-2.0 |
    """.trimIndent()

    @Test
    fun `rows are read per section`() {
        assertEquals(setOf("com.hierynomus:smbj:0.15.0", "org.slf4j:slf4j-api:2.0.18"), ThirdParty.listed(markdown, "Candle (projector)"))
        assertEquals(setOf("com.google.zxing:core:3.5.3"), ThirdParty.listed(markdown, "Candle Server (phone)"))
    }

    @Test
    fun `a new library or a new version is missing`() {
        val shipped = listOf("com.hierynomus:smbj:0.16.0", "org.slf4j:slf4j-api:2.0.18", "net.engio:mbassador:1.3.2")
        assertEquals(listOf("com.hierynomus:smbj:0.16.0", "net.engio:mbassador:1.3.2"), ThirdParty.missing(markdown, "Candle (projector)", shipped))
    }

    @Test
    fun `a library listed only for the other app is missing`() {
        assertEquals(listOf("com.hierynomus:smbj:0.15.0"), ThirdParty.missing(markdown, "Candle Server (phone)", listOf("com.hierynomus:smbj:0.15.0")))
    }

    @Test
    fun `an unknown section is an error`() {
        assertThrows(IllegalArgumentException::class.java) { ThirdParty.listed(markdown, "Candle (tablet)") }
    }

    @Test
    fun `the real file has both apps`() {
        val real = File("../THIRD_PARTY.md").readText()
        assertEquals(38, ThirdParty.listed(real, "Candle (projector)").size)
        assertEquals(3, ThirdParty.listed(real, "Candle Server (phone)").size)
    }
}
