package dev.phonebridge.core.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortCodeTest {
    @Test
    fun checkSymbolMatchesTheDocumentedCrc5() {
        assertEquals('B', ShortCode.checkSymbol("7K4Q9"))
        assertEquals('Q', ShortCode.checkSymbol("ZZZZZ"))
        assertEquals('T', ShortCode.checkSymbol("00000"))
        assertEquals("7K4-Q9B", ShortCode.format("7K4Q9B"))
    }

    @Test
    fun normaliseAppliesCrockfordFixes() {
        assertEquals("7K4Q9B", ShortCode.normalise("7k4-q9b"))
        assertEquals("7K4Q9B", ShortCode.normalise(" 7K4 Q9B "))
        assertEquals("001111", ShortCode.normalise("O0I1Ll"))
        assertEquals("7K4Q9B", ShortCode.normalise("7K4.Q9B"))
    }

    @Test
    fun generatedCodesAreValidAndWellFormed() {
        repeat(50) {
            val code = ShortCode.generate()
            assertTrue(code.matches(Regex("[0-9A-HJKMNP-TV-Z]{3}-[0-9A-HJKMNP-TV-Z]{3}")), "bad display form: $code")
            assertTrue(ShortCode.isValid(code), "generated code failed its own check: $code")
            assertEquals(code, ShortCode.format(ShortCode.normalise(code)))
        }
    }

    @Test
    fun aSingleWrongSymbolIsRejected() {
        val code = ShortCode.generateBare()
        val data = code.substring(0, ShortCode.DATA_SYMBOLS)
        val check = code[ShortCode.DATA_SYMBOLS]
        assertTrue(ShortCode.isValid(code))

        // Corrupt each data symbol in turn.
        for (index in 0 until ShortCode.DATA_SYMBOLS) {
            val replacement = ShortCode.symbolFor((ShortCode.valueOf(data[index]) + 1) % ShortCode.ALPHABET.length)
            val corrupted = data.substring(0, index) + replacement + data.substring(index + 1) + check
            assertFalse(ShortCode.isValid(corrupted), "wrong data symbol was accepted: $corrupted")
        }

        // Corrupt the check symbol.
        val wrongCheck = ShortCode.symbolFor((ShortCode.valueOf(check) + 1) % ShortCode.ALPHABET.length)
        assertFalse(ShortCode.isValid(data + wrongCheck))
    }

    @Test
    fun malformedCodesAreRejected() {
        assertFalse(ShortCode.isValid(""))
        assertFalse(ShortCode.isValid("7K4-Q9"))
        assertFalse(ShortCode.isValid("7K4-Q9BB"))
        assertFalse(ShortCode.isValid("7K4-U9B"))
        assertFalse(ShortCode.isValid("7K4-Q9!"))
    }

    @Test
    fun normalisationAcceptsEveryAmbiguousSpelling() {
        // The five data symbols of a code with the ambiguous characters: O/o -> 0, I/i/L/l -> 1.
        assertEquals("10011", ShortCode.normalise("lOO11"))
        assertEquals("10011", ShortCode.normalise("IOOII"))
        assertEquals("10011", ShortCode.normalise("1ool1"))

        // And the whole code, check symbol included, survives a round trip through normalisation.
        val canonical = "10011" + ShortCode.checkSymbol("10011")
        assertTrue(ShortCode.isValid(canonical))
        assertEquals(canonical, ShortCode.normalise(canonical.lowercase()))
        assertEquals(canonical, ShortCode.normalise("l0O1I" + canonical[5]))
    }
}
