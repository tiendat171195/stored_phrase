package io.github.tiendat171195.storedphrase.core.vault

import io.github.tiendat171195.storedphrase.core.crypto.Argon2idKdf
import io.github.tiendat171195.storedphrase.core.crypto.PassphraseBox
import io.github.tiendat171195.storedphrase.core.crypto.PassphraseBoxFormatException
import java.security.SecureRandom

/**
 * Layer-2 protection of one entry: the EntryCodec plaintext is sealed with the entry's own
 * passphrase ([PassphraseBox], magic "SPL2") before it is encrypted again by the Knox Vault key.
 * Someone holding the phone and its PIN still needs this passphrase.
 */
object Layer2Envelope {
    private val MAGIC = byteArrayOf(0x53, 0x50, 0x4C, 0x32) // "SPL2"
    const val MIN_PASSPHRASE_LENGTH = 8

    fun seal(
        entryPlaintext: ByteArray,
        passphrase: CharArray,
        params: Argon2idKdf.Params = Argon2idKdf.Params.DEFAULT,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Layer-2 passphrase too short" }
        return PassphraseBox.seal(MAGIC, entryPlaintext, passphrase, params, random)
    }

    /** @throws EntryFormatException when [envelope] is not a valid layer-2 envelope. */
    fun validate(envelope: ByteArray) {
        try {
            PassphraseBox.readHeader(MAGIC, envelope)
        } catch (e: PassphraseBoxFormatException) {
            throw EntryFormatException("Bad layer-2 envelope: ${e.message}")
        }
    }

    /**
     * Returns the EntryCodec plaintext; the caller wipes it.
     * @throws Layer2WrongPassphraseException for a wrong passphrase (or modified envelope).
     * @throws EntryFormatException for a malformed envelope.
     */
    fun open(envelope: ByteArray, passphrase: CharArray): ByteArray {
        val plaintext = try {
            PassphraseBox.open(MAGIC, envelope, passphrase)
        } catch (e: PassphraseBoxFormatException) {
            throw EntryFormatException("Bad layer-2 envelope: ${e.message}")
        }
        return plaintext ?: throw Layer2WrongPassphraseException()
    }
}

class Layer2WrongPassphraseException : Exception("Wrong layer-2 passphrase")
