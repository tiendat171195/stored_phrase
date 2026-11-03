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
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[0] = 3 }) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[1] = 13 }) }
        assertFailsWith<EntryFormatException> { EntryCodec.decode(good.copyOf().also { it[2] = 0x08 }) } // index 2048+
    }

    @Test
    fun birthdayHeightRoundTripsAndVersion1StillDecodes() {
        val secret = SeedSecret(IntArray(24) { it }, birthdayHeight = 2_726_400)
        val encoded = EntryCodec.encode(secret)
        assertEquals(2_726_400L, EntryCodec.decode(encoded).birthdayHeight)
        assertEquals(SeedSecret.MAX_BIRTHDAY_HEIGHT, EntryCodec.decode(EntryCodec.encode(SeedSecret(IntArray(12), birthdayHeight = SeedSecret.MAX_BIRTHDAY_HEIGHT))).birthdayHeight)
        // Version 1 (written by app 0.1.0) had no birthday flag at the end.
        val v2NoBirthday = EntryCodec.encode(SeedSecret(IntArray(12) { it + 5 }, "pw".toCharArray()))
        val v1 = v2NoBirthday.copyOf(v2NoBirthday.size - 1).also { it[0] = 1 }
        val decoded = EntryCodec.decode(v1)
        assertContentEquals(IntArray(12) { it + 5 }, decoded.wordIndices)
        assertEquals("pw", String(decoded.bip39Passphrase))
        assertEquals(null, decoded.birthdayHeight)
        assertFailsWith<EntryFormatException> { EntryCodec.decode(v2NoBirthday.copyOf().also { it[it.size - 1] = 2 }) }
        assertFailsWith<IllegalArgumentException> { SeedSecret(IntArray(12), birthdayHeight = -1) }
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
