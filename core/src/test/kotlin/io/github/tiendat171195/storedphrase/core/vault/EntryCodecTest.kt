package io.github.tiendat171195.storedphrase.core.vault

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EntryCodecTest {

    @Test
    fun roundTripWithUnicodePassphraseAndNotes() {
        val indices = IntArray(24) { (it * 97) % 2048 }
        val secret = SeedSecret(indices.copyOf(), "mật khẩu 🔐".toCharArray(), "m/84'/0'/0'\nví lạnh".toCharArray())
        val decoded = EntryCodec.decode(EntryCodec.encode(secret))
        assertContentEquals(indices, decoded.wordIndices)
        assertEquals("mật khẩu 🔐", String(decoded.bip39Passphrase))
        assertEquals("m/84'/0'/0'\nví lạnh", String(decoded.notes))
    }

    @Test
    fun roundTripAllWordCountsAndEmptyOptionalFields() {
        for (n in EntryCodec.VALID_WORD_COUNTS) {
            val indices = IntArray(n) { 2047 - it }
            val decoded = EntryCodec.decode(EntryCodec.encode(SeedSecret(indices.copyOf())))
            assertContentEquals(indices, decoded.wordIndices)
            assertEquals(0, decoded.bip39Passphrase.size)
            assertEquals(0, decoded.notes.size)
        }
    }

    @Test
    fun encodeRejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> { EntryCodec.encode(SeedSecret(IntArray(13))) }
        assertFailsWith<IllegalArgumentException> { EntryCodec.encode(SeedSecret(IntArray(12) { 2048 })) }
        assertFailsWith<IllegalArgumentException> {
            EntryCodec.encode(SeedSecret(IntArray(12), CharArray(EntryCodec.MAX_PASSPHRASE_CHARS + 1) { 'a' }))
        }
        // Lone surrogate is not valid Unicode.
        assertFailsWith<IllegalArgumentException> { EntryCodec.encode(SeedSecret(IntArray(12), charArrayOf('\uD800'))) }
    }

    @Test
    fun decodeRejectsCorruptedBytes() {
        val good = EntryCodec.encode(SeedSecret(IntArray(12) { it }, "pw".toCharArray()))
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf(good.size - 1)) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good + 0.toByte()) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[0] = 2 }) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[1] = 13 }) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[2] = 0x08 }) } // index 2048+
    }

    @Test
    fun wipeClearsEverySecretArray() {
        val secret = SeedSecret(IntArray(12) { it + 1 }, "abc".toCharArray(), "note".toCharArray())
        secret.wipe()
        assertTrue(secret.isWiped)
        assertTrue(secret.wordIndices.all { it == 0 })
        assertTrue(secret.bip39Passphrase.all { it == '\u0000' })
        assertTrue(secret.notes.all { it == '\u0000' })
    }
}
