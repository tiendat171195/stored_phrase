package io.github.tiendat171195.storedphrase.core.bip39

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MnemonicTest {

    private data class Vector(val entropy: ByteArray, val mnemonic: String)

    /** Official vectors from trezor/python-mnemonic (all 12/15/18/21/24-word lengths). */
    private val vectors: List<Vector> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/bip39/vectors-english.tsv"))
        stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (hex, mnemonic) = line.split('\t')
                Vector(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), mnemonic)
            }
    }

    @Test
    fun vectorsAreLoaded() {
        // The Trezor set has 12-, 18- and 24-word phrases; 15 and 21 are covered by allLengthsRoundTrip.
        assertEquals(24, vectors.size)
        assertEquals(setOf(12, 18, 24), vectors.map { it.mnemonic.split(' ').size }.toSet())
    }

    @Test
    fun entropyToMnemonicMatchesVectors() {
        for (v in vectors) {
            val words = Mnemonic.fromEntropy(v.entropy).joinToString(" ") { Bip39Wordlist.word(it) }
            assertEquals(v.mnemonic, words)
        }
    }

    @Test
    fun mnemonicToEntropyMatchesVectorsAndChecksumsAreValid() {
        for (v in vectors) {
            val indices = assertNotNull(Mnemonic.parse(v.mnemonic))
            assertTrue(Mnemonic.isValid(indices), v.mnemonic)
            assertContentEquals(v.entropy, Mnemonic.toEntropy(indices))
        }
    }

    @Test
    fun allLengthsRoundTrip() {
        for (bytes in listOf(16, 20, 24, 28, 32)) {
            val entropy = ByteArray(bytes) { (it * 37 + 11).toByte() }
            val indices = Mnemonic.fromEntropy(entropy)
            assertEquals(bytes * 8 * 33 / 32 / 11, indices.size)
            assertContentEquals(entropy, Mnemonic.toEntropy(indices))
        }
    }

    @Test
    fun detectsTypos() {
        val valid = assertNotNull(Mnemonic.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
        assertTrue(Mnemonic.isValid(valid))
        // Swapping in a neighbouring word breaks the checksum.
        val typo = valid.copyOf().also { it[11] = Bip39Wordlist.indexOf("above") }
        assertFalse(Mnemonic.isValid(typo))
        assertFailsWith<InvalidMnemonicException> { Mnemonic.toEntropy(typo) }
        // Wrong lengths and indices are rejected.
        assertFalse(Mnemonic.isValid(valid.copyOf(11)))
        assertFalse(Mnemonic.isValid(valid.copyOf().also { it[0] = 2048 }))
        assertNull(Mnemonic.parse("abandon bitcoin"))
    }
}
