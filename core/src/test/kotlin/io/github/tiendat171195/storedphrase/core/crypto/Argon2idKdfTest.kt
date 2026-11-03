package io.github.tiendat171195.storedphrase.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class Argon2idKdfTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Expected values computed with argon2-cffi (the reference C implementation). */
    @Test
    fun matchesReferenceImplementation() {
        val key = Argon2idKdf.deriveKey(
            passphrase = "correct-horse-battery-staple".toCharArray(),
            salt = ByteArray(16) { it.toByte() },
            params = Argon2idKdf.Params(memoryKiB = 65536, iterations = 3, parallelism = 1),
        )
        assertEquals("9acedbbff8ce68cae902e239bdccd32578ccc20eb9054e679f9b8cf5a9f0e19a", key.hex())
    }

    @Test
    fun nonAsciiPassphrasesAreNfkdNormalised() {
        val params = Argon2idKdf.Params(memoryKiB = 8192, iterations = 1, parallelism = 1)
        val salt = ByteArray(16) { 7 }
        val composed = Argon2idKdf.deriveKey("mật khẩu".toCharArray(), salt, params)
        val decomposed = Argon2idKdf.deriveKey(java.text.Normalizer.normalize("mật khẩu", java.text.Normalizer.Form.NFD).toCharArray(), salt, params)
        assertEquals("6c24c24d7156bf50daca5c76a66f648422e91ee48f895a956e59dbc11ce11a31", composed.hex())
        assertEquals(composed.hex(), decomposed.hex())
        assertNotEquals(composed.hex(), Argon2idKdf.deriveKey("mat khau".toCharArray(), salt, params).hex())
    }

    @Test
    fun rejectsUnsafeParameters() {
        assertFailsWith<IllegalArgumentException> { Argon2idKdf.Params(memoryKiB = 1024, iterations = 3, parallelism = 1) }
        assertFailsWith<IllegalArgumentException> { Argon2idKdf.Params(memoryKiB = 4 * 1024 * 1024, iterations = 3, parallelism = 1) }
        assertFailsWith<IllegalArgumentException> { Argon2idKdf.Params(memoryKiB = 65536, iterations = 0, parallelism = 1) }
        assertFailsWith<IllegalArgumentException> {
            Argon2idKdf.deriveKey("x".toCharArray(), ByteArray(8), Argon2idKdf.Params.DEFAULT)
        }
    }
}
