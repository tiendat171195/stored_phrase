package io.github.tiendat171195.storedphrase.core.vault

import io.github.tiendat171195.storedphrase.core.crypto.Argon2idKdf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class Layer2EnvelopeTest {

    private val fast = Argon2idKdf.Params(memoryKiB = 8192, iterations = 1, parallelism = 1)
    private val plaintext = EntryCodec.encode(SeedSecret(IntArray(12) { it * 3 }, "pw".toCharArray()))

    @Test
    fun roundTrip() {
        val envelope = Layer2Envelope.seal(plaintext, "lớp hai bí mật".toCharArray(), fast)
        Layer2Envelope.validate(envelope)
        assertContentEquals(plaintext, Layer2Envelope.open(envelope, "lớp hai bí mật".toCharArray()))
    }

    @Test
    fun wrongPassphraseAndTamperingAreRejected() {
        val envelope = Layer2Envelope.seal(plaintext, "correct passphrase".toCharArray(), fast)
        assertFailsWith<Layer2WrongPassphraseException> { Layer2Envelope.open(envelope, "wrong passphrase".toCharArray()) }
        val tampered = envelope.copyOf().also { it[50] = (it[50].toInt() xor 1).toByte() }
        assertFailsWith<Layer2WrongPassphraseException> { Layer2Envelope.open(tampered, "correct passphrase".toCharArray()) }
    }

    @Test
    fun rejectsMalformedEnvelopesAndShortPassphrases() {
        // An EntryCodec plaintext is not an envelope.
        assertFailsWith<EntryFormatException> { Layer2Envelope.validate(plaintext) }
        val envelope = Layer2Envelope.seal(plaintext, "correct passphrase".toCharArray(), fast)
        assertFailsWith<EntryFormatException> { Layer2Envelope.open(envelope.copyOf(40), "correct passphrase".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { Layer2Envelope.seal(plaintext, "short".toCharArray(), fast) }
    }
}
