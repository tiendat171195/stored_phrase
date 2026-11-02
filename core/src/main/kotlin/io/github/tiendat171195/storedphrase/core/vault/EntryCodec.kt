package io.github.tiendat171195.storedphrase.core.vault

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Binary plaintext format of one entry (before AES-GCM encryption).
 *
 * ```
 * u8          version = 1
 * u8          word count n (12, 15, 18, 21 or 24)
 * n × u16 BE  BIP39 word indices (0..2047)
 * u16 BE + …  BIP39 passphrase, UTF-8
 * u16 BE + …  notes, UTF-8
 * ```
 */
object EntryCodec {
    const val VERSION: Int = 1
    val VALID_WORD_COUNTS: List<Int> = listOf(12, 15, 18, 21, 24)
    const val MAX_PASSPHRASE_CHARS = 256
    const val MAX_NOTES_CHARS = 2000
    private const val WORDLIST_SIZE = 2048

    fun encode(secret: SeedSecret): ByteArray {
        val n = secret.wordIndices.size
        require(n in VALID_WORD_COUNTS) { "Unsupported word count $n" }
        require(secret.wordIndices.all { it in 0 until WORDLIST_SIZE }) { "Word index out of range" }
        require(secret.bip39Passphrase.size <= MAX_PASSPHRASE_CHARS) { "Passphrase too long" }
        require(secret.notes.size <= MAX_NOTES_CHARS) { "Notes too long" }

        val passphrase = utf8(secret.bip39Passphrase)
        val notes = utf8(secret.notes)
        try {
            val out = ByteArray(2 + n * 2 + 2 + passphrase.size + 2 + notes.size)
            var p = 0
            out[p++] = VERSION.toByte()
            out[p++] = n.toByte()
            for (index in secret.wordIndices) {
                out[p++] = (index ushr 8).toByte()
                out[p++] = index.toByte()
            }
            p = putBytes(out, p, passphrase)
            putBytes(out, p, notes)
            return out
        } finally {
            passphrase.fill(0)
            notes.fill(0)
        }
    }

    fun decode(bytes: ByteArray): SeedSecret {
        try {
            var p = 0
            fun u8(): Int = bytes[p++].toInt() and 0xFF
            fun u16(): Int = (u8() shl 8) or u8()

            if (u8() != VERSION) throw EntryFormatException("Unsupported entry version")
            val n = u8()
            if (n !in VALID_WORD_COUNTS) throw EntryFormatException("Unsupported word count $n")
            val indices = IntArray(n) { u16() }
            if (indices.any { it >= WORDLIST_SIZE }) {
                indices.fill(0)
                throw EntryFormatException("Word index out of range")
            }
            val passLen = u16()
            val passphrase = chars(bytes, p, passLen)
            p += passLen
            val notesLen = u16()
            val notes = chars(bytes, p, notesLen)
            p += notesLen
            if (p != bytes.size) {
                indices.fill(0); passphrase.fill('\u0000'); notes.fill('\u0000')
                throw EntryFormatException("Trailing bytes")
            }
            return SeedSecret(indices, passphrase, notes)
        } catch (e: IndexOutOfBoundsException) {
            throw EntryFormatException("Truncated entry")
        }
    }

    private fun putBytes(out: ByteArray, offset: Int, data: ByteArray): Int {
        require(data.size <= 0xFFFF) { "Field too long" }
        var p = offset
        out[p++] = (data.size ushr 8).toByte()
        out[p++] = data.size.toByte()
        data.copyInto(out, p)
        return p + data.size
    }

    /** UTF-8 encode without creating a String; intermediate buffers are wiped. */
    private fun utf8(chars: CharArray): ByteArray {
        if (chars.isEmpty()) return ByteArray(0)
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(chars))
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Text is not valid Unicode")
        }
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill(0)
        return out
    }

    private fun chars(bytes: ByteArray, offset: Int, length: Int): CharArray {
        if (length == 0) return CharArray(0)
        if (offset + length > bytes.size) throw EntryFormatException("Truncated entry")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, length))
        } catch (e: CharacterCodingException) {
            throw EntryFormatException("Invalid UTF-8")
        }
        val out = CharArray(buffer.remaining())
        buffer.get(out)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return out
    }
}

class EntryFormatException(message: String) : Exception(message)
