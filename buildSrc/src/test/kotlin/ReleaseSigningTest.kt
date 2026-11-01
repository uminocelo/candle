import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSigningTest {
    private val all = mapOf(
        "candle.signing.storeFile" to "/keys/candle.jks",
        "candle.signing.storePassword" to "store",
        "candle.signing.keyAlias" to "candle",
        "candle.signing.keyPassword" to "key",
    )

    @Test
    fun `all four properties give a key`() {
        assertEquals(ReleaseSigning("/keys/candle.jks", "store", "candle", "key"), ReleaseSigning.from(all::get))
    }

    @Test
    fun `any missing or blank property gives no key`() {
        all.keys.forEach { key ->
            assertNull(key, ReleaseSigning.from((all - key)::get))
            assertNull(key, ReleaseSigning.from((all + (key to " "))::get))
        }
    }

    @Test
    fun `environment names for CI`() {
        assertEquals(
            listOf("CANDLE_SIGNING_STORE_FILE", "CANDLE_SIGNING_STORE_PASSWORD", "CANDLE_SIGNING_KEY_ALIAS", "CANDLE_SIGNING_KEY_PASSWORD"),
            ReleaseSigning.KEYS.map(ReleaseSigning::envName),
        )
    }
}
