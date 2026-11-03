package io.github.tiendat171195.storedphrase.core.vault

import io.github.tiendat171195.storedphrase.core.text.Utf8
import java.nio.charset.CharacterCodingException

/**
 * Binary plaintext format of one entry (before encryption).
 *
 * ```
 * u8          version (2; version 1 had no birthday field)
 * u8          word count n (12, 15, 18, 21 or 24)
 * n × u16 BE  BIP39 word indices (0..2047)
 * u16 BE + …  BIP39 passphrase, UTF-8
 * u16 BE + …  notes, UTF-8
 * u8          1 if a birthday height follows, else 0          (version 2)
 * u32 BE      wallet birthday height                          (version 2, optional)
 * ```
 */
object EntryCodec {
    const val VERSION: Int = 2
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

        val passphrase = Utf8.encode(secret.bip39Passphrase)
        val notes = Utf8.encode(secret.notes)
        try {
            val birthday = secret.birthdayHeight
            val out = ByteArray(2 + n * 2 + 2 + passphrase.size + 2 + notes.size + 1 + if (birthday != null) 4 else 0)
            var p = 0
            out[p++] = VERSION.toByte()
            out[p++] = n.toByte()
            for (index in secret.wordIndices) {
                out[p++] = (index ushr 8).toByte()
                out[p++] = index.toByte()
            }
            p = putBytes(out, p, passphrase)
            p = putBytes(out, p, notes)
            if (birthday == null) {
                out[p] = 0
            } else {
                out[p++] = 1
                for (shift in intArrayOf(24, 16, 8, 0)) out[p++] = (birthday ushr shift).toByte()
            }
            return out
        } finally {
            passphrase.fill(0)
            notes.fill(0)
        }
    }

    fun decode(bytes: ByteArray): SeedSecret {
        var indices: IntArray? = null
        var passphrase: CharArray? = null
        var notes: CharArray? = null
        try {
            var p = 0
            fun u8(): Int = bytes[p++].toInt() and 0xFF
            fun u16(): Int = (u8() shl 8) or u8()

            val version = u8()
            if (version != 1 && version != 2) throw EntryFormatException("Unsupported entry version")
            val n = u8()
            if (n !in VALID_WORD_COUNTS) throw EntryFormatException("Unsupported word count $n")
            indices = IntArray(n) { u16() }
            if (indices.any { it >= WORDLIST_SIZE }) throw EntryFormatException("Word index out of range")
            val passLen = u16()
            passphrase = chars(bytes, p, passLen)
            p += passLen
            val notesLen = u16()
            notes = chars(bytes, p, notesLen)
            p += notesLen
            var birthday: Long? = null
            if (version >= 2) {
                when (u8()) {
                    0 -> Unit
                    1 -> birthday = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
                    else -> throw EntryFormatException("Bad birthday flag")
                }
            }
            if (p != bytes.size) throw EntryFormatException("Trailing bytes")
            return SeedSecret(indices, passphrase, notes, birthday)
        } catch (e: IndexOutOfBoundsException) {
            wipe(indices, passphrase, notes)
            throw EntryFormatException("Truncated entry")
        } catch (e: EntryFormatException) {
            wipe(indices, passphrase, notes)
            throw e
        }
    }

    private fun wipe(indices: IntArray?, passphrase: CharArray?, notes: CharArray?) {
        indices?.fill(0)
        passphrase?.fill('\u0000')
        notes?.fill('\u0000')
    }

    private fun putBytes(out: ByteArray, offset: Int, data: ByteArray): Int {
        require(data.size <= 0xFFFF) { "Field too long" }
        var p = offset
        out[p++] = (data.size ushr 8).toByte()
        out[p++] = data.size.toByte()
        data.copyInto(out, p)
        return p + data.size
    }

    private fun chars(bytes: ByteArray, offset: Int, length: Int): CharArray {
        if (length == 0) return CharArray(0)
        if (offset + length > bytes.size) throw EntryFormatException("Truncated entry")
        return try {
            Utf8.decode(bytes, offset, length)
        } catch (e: CharacterCodingException) {
            throw EntryFormatException("Invalid UTF-8")
        }
    }
}

class EntryFormatException(message: String) : Exception(message)
