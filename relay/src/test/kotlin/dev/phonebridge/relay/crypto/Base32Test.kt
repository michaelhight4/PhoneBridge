package dev.phonebridge.relay.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Base32Test {
    @Test
    fun `round trips arbitrary bytes`() {
        repeat(50) {
            val data = Codec.randomBytes((1..40).random())
            assertContentEquals(data, Base32.decode(Base32.encode(data)))
        }
    }

    @Test
    fun `encodes peer ids as 26 lowercase characters`() {
        val pair = PeerKeyPair.generate()
        assertEquals(26, pair.peerId.length)
        assertEquals(pair.peerId.lowercase(), pair.peerId)
        assertTrue(pair.peerId.all { it in "0123456789abcdefghjkmnpqrstvwxyz" })
    }

    @Test
    fun `fingerprints are grouped in two blocks of four`() {
        val pair = PeerKeyPair.generate()
        val fingerprint = pair.fingerprint
        assertEquals(9, fingerprint.length)
        assertEquals('-', fingerprint[4])
        assertTrue(fingerprint.replace("-", "").all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    @Test
    fun `normalizes confusable characters and separators`() {
        assertEquals("7K4Q9M", Base32.normalize("7k4-q9m"))
        assertEquals("011", Base32.normalize("O-I l"))
        assertNull(Base32.normalize("7K4Q9!"))
        assertNull(Base32.normalize("7K4Q9U"))
    }

    @Test
    fun `canonicalizes a six symbol short code`() {
        assertEquals("7K4-Q9M", Base32.canonicalCode("7k4 q9m"))
        assertEquals("7K4-Q9M", Base32.canonicalCode("7K4Q9M"))
        assertNull(Base32.canonicalCode("7K4Q9"))
        assertNull(Base32.canonicalCode("7K4Q9MB"))
    }

    @Test
    fun `validates the check symbol when asked`() {
        val code = "7K4Q9"
        val check = Base32.checkSymbol(code)
        val canonical = assertNotNull(Base32.canonicalShortCode(code + check))
        assertEquals(code.substring(0, 3) + "-" + code.substring(3) + check, canonical)

        val wrong = if (check == '0') '1' else '0'
        assertNull(Base32.canonicalShortCode(code + wrong))
    }
}
