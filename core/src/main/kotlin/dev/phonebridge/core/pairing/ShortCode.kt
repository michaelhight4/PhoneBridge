package dev.phonebridge.core.pairing

import dev.phonebridge.core.crypto.Primitives
import java.security.SecureRandom

/**
 * The short pairing code, e.g. `7K4-Q9B`.
 *
 * Six Crockford base32 symbols: five data symbols (25 bits) plus one check symbol. Crockford's
 * ambiguity fixes are applied on input (`O`→`0`, `I`→`1`, `L`→`1`, case folded, `-` and spaces
 * ignored). `U` is not part of the alphabet and is rejected.
 *
 * The check symbol is a CRC-5 over the 25 data bits, MSB first, polynomial `0x05` (x^5 + x^2 + 1),
 * initial value `0x1F`, no reflection. That catches any single wrong symbol, which is the mistake
 * people actually make when copying a code.
 */
object ShortCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val SYMBOLS = 6
    const val DATA_SYMBOLS = 5

    private const val CRC_INIT = 0x1F
    private const val CRC_POLY = 0x05
    private const val CRC_MASK = 0x1F

    /** Random code in display form, e.g. `7K4-Q9B`. */
    fun generate(random: SecureRandom = Primitives.defaultRandom()): String = format(generateBare(random))

    /** Random code without the display dash, e.g. `7K4Q9M`. */
    fun generateBare(random: SecureRandom = Primitives.defaultRandom()): String {
        val data = StringBuilder(DATA_SYMBOLS)
        repeat(DATA_SYMBOLS) { data.append(symbolFor(random.nextInt(ALPHABET.length))) }
        return data.toString() + checkSymbol(data.toString())
    }

    /** Canonical machine form: uppercase, no separators, `O`→`0`, `I`/`L`→`1`, whitespace dropped. */
    fun normalise(input: String): String {
        val out = StringBuilder(input.length)
        for (raw in input) {
            when (raw) {
                '-', ' ', '\t', '\n', '\r', '_', '.' -> Unit
                else -> {
                    val upper = raw.uppercaseChar()
                    out.append(
                        when (upper) {
                            'O' -> '0'
                            'I', 'L' -> '1'
                            else -> upper
                        },
                    )
                }
            }
        }
        return out.toString()
    }

    /** Display form with the dash after three symbols: `7K4-Q9B`. */
    fun format(input: String): String {
        val normalised = normalise(input)
        if (normalised.length != SYMBOLS) return normalised
        return normalised.substring(0, 3) + "-" + normalised.substring(3)
    }

    /** True when [input] normalises to six known symbols whose last symbol matches the data. */
    fun isValid(input: String): Boolean {
        val normalised = normalise(input)
        if (normalised.length != SYMBOLS) return false
        if (normalised.any { valueOf(it) < 0 }) return false
        val data = normalised.substring(0, DATA_SYMBOLS)
        val expected = checkSymbol(data)
        return normalised[DATA_SYMBOLS] == expected
    }

    fun checkSymbol(dataSymbols: String): Char = symbolFor(checkValue(dataSymbols))

    /** CRC-5 over the data symbols' 25 bits as documented in the class comment. */
    fun checkValue(dataSymbols: String): Int {
        require(dataSymbols.length == DATA_SYMBOLS) { "need $DATA_SYMBOLS data symbols" }
        var crc = CRC_INIT
        for (symbol in dataSymbols) {
            val value = valueOf(symbol)
            require(value >= 0) { "invalid symbol: $symbol" }
            for (bit in 4 downTo 0) {
                val incoming = (value ushr bit) and 1
                val top = (crc ushr 4) and 1
                crc = (crc shl 1) and CRC_MASK
                if (top xor incoming != 0) crc = crc xor CRC_POLY
            }
        }
        return crc
    }

    fun valueOf(symbol: Char): Int = ALPHABET.indexOf(symbol.uppercaseChar())

    fun symbolFor(value: Int): Char {
        require(value in 0 until ALPHABET.length) { "bad symbol value: $value" }
        return ALPHABET[value]
    }

    /** Display form of a stored code, safe for any input. */
    fun display(code: String): String = format(code)
}
