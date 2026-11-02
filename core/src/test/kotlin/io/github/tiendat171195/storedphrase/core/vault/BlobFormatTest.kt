package io.github.tiendat171195.storedphrase.core.vault

import io.github.tiendat171195.storedphrase.core.vault.BlobFormat.KeySlot
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BlobFormatTest {

    private val random = SecureRandom()

    @Test
    fun encodeDecodeRoundTrip() {
        val iv = ByteArray(12).also(random::nextBytes)
        val ciphertext = ByteArray(40).also(random::nextBytes)
        val decoded = BlobFormat.decode(BlobFormat.encode(BlobFormat.Blob(KeySlot.SECRET, iv, ciphertext)))
        assertEquals(KeySlot.SECRET, decoded.keySlot)
        assertContentEquals(iv, decoded.iv)
        assertContentEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun rejectsBadMagicTruncationAndUnknownSlot() {
        val good = BlobFormat.encode(BlobFormat.Blob(KeySlot.INDEX, ByteArray(12), ByteArray(16)))
        assertFailsWith<BlobFormatException> { BlobFormat.decode(good.copyOf(20)) }
        assertFailsWith<BlobFormatException> { BlobFormat.decode(good.copyOf().also { it[0] = 0 }) }
        assertFailsWith<BlobFormatException> { BlobFormat.decode(good.copyOf().also { it[4] = 9 }) }
        assertFailsWith<BlobFormatException> { BlobFormat.decode(good.copyOf().also { it[5] = 16 }) }
    }

    /** Mirrors what the app does with the Keystore key, using a software key on the JVM. */
    @Test
    fun aadBindsCiphertextToItsEntry() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "entry payload".encodeToByteArray()
        val blob = encrypt(key, plaintext, BlobFormat.entryAad("blob-A"))

        assertContentEquals(plaintext, decrypt(key, blob, BlobFormat.entryAad("blob-A")))
        // Swapping the file onto another entry, or onto the index, must fail authentication.
        assertFailsWith<AEADBadTagException> { decrypt(key, blob, BlobFormat.entryAad("blob-B")) }
        assertFailsWith<AEADBadTagException> { decrypt(key, blob, BlobFormat.indexAad()) }
        // Any flipped ciphertext bit must fail too.
        val tampered = BlobFormat.Blob(blob.keySlot, blob.iv, blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        assertFailsWith<AEADBadTagException> { decrypt(key, tampered, BlobFormat.entryAad("blob-A")) }
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray, aad: ByteArray): BlobFormat.Blob {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BlobFormat.TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        return BlobFormat.Blob(KeySlot.SECRET, iv, cipher.doFinal(plaintext))
    }

    private fun decrypt(key: SecretKey, blob: BlobFormat.Blob, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BlobFormat.TAG_LENGTH_BITS, blob.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob.ciphertext)
    }
}
