package io.github.tiendat171195.storedphrase.core.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Data encrypted with a passphrase: Argon2id key derivation + AES-256-GCM, with every
 * parameter in an authenticated header. Used by backup files ("SPBK") and layer-2
 * envelopes ("SPL2"); both share this exact layout.
 *
 * ```
 * offset size  field
 * 0      4     magic
 * 4      1     version = 1
 * 5      1     KDF = 1 (Argon2id v1.3)
 * 6      4     Argon2 memory in KiB (u32 BE)
 * 10     4     Argon2 iterations (u32 BE)
 * 14     1     Argon2 parallelism
 * 15     1     salt length = 16
 * 16     16    salt
 * 32     1     nonce length = 12
 * 33     12    nonce
 * 45     …     AES-256-GCM ciphertext || 128-bit tag; AAD = bytes 0..44
 * ```
 */
object PassphraseBox {
    const val HEADER_LENGTH = 45
    const val VERSION = 1
    const val KDF_ARGON2ID = 1

    class Header(val params: Argon2idKdf.Params, val salt: ByteArray, val nonce: ByteArray)

    fun seal(
        magic: ByteArray,
        plaintext: ByteArray,
        passphrase: CharArray,
        params: Argon2idKdf.Params = Argon2idKdf.Params.DEFAULT,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(magic.size == 4)
        val salt = ByteArray(Argon2idKdf.SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(AesGcm.NONCE_LENGTH).also(random::nextBytes)
        val header = ByteArray(HEADER_LENGTH)
        magic.copyInto(header, 0)
        header[4] = VERSION.toByte()
        header[5] = KDF_ARGON2ID.toByte()
        putU32(header, 6, params.memoryKiB)
        putU32(header, 10, params.iterations)
        header[14] = params.parallelism.toByte()
        header[15] = Argon2idKdf.SALT_LENGTH.toByte()
        salt.copyInto(header, 16)
        header[32] = AesGcm.NONCE_LENGTH.toByte()
        nonce.copyInto(header, 33)
        val key = Argon2idKdf.deriveKey(passphrase, salt, params)
        try {
            return header + AesGcm.encrypt(key, nonce, header, plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** Validates the header without the passphrase. */
    fun readHeader(magic: ByteArray, data: ByteArray): Header {
        if (data.size < HEADER_LENGTH + AesGcm.TAG_BITS / 8) throw PassphraseBoxFormatException("Data too short")
        for (i in magic.indices) if (data[i] != magic[i]) throw PassphraseBoxFormatException("Bad magic")
        if (data[4].toInt() != VERSION) throw PassphraseBoxFormatException("Unsupported version ${data[4]}")
        if (data[5].toInt() != KDF_ARGON2ID) throw PassphraseBoxFormatException("Unsupported KDF ${data[5]}")
        val params = try {
            Argon2idKdf.Params(memoryKiB = u32(data, 6), iterations = u32(data, 10), parallelism = data[14].toInt() and 0xFF)
        } catch (e: IllegalArgumentException) {
            throw PassphraseBoxFormatException("Unsupported KDF parameters")
        }
        if (data[15].toInt() != Argon2idKdf.SALT_LENGTH) throw PassphraseBoxFormatException("Bad salt length")
        if (data[32].toInt() != AesGcm.NONCE_LENGTH) throw PassphraseBoxFormatException("Bad nonce length")
        return Header(params, data.copyOfRange(16, 32), data.copyOfRange(33, 45))
    }

    /** Plaintext, or null when the passphrase is wrong or the data was modified. Caller wipes it. */
    fun open(magic: ByteArray, data: ByteArray, passphrase: CharArray): ByteArray? {
        val header = readHeader(magic, data)
        val key = Argon2idKdf.deriveKey(passphrase, header.salt, header.params)
        return try {
            AesGcm.decrypt(key, header.nonce, data.copyOfRange(0, HEADER_LENGTH), data.copyOfRange(HEADER_LENGTH, data.size))
        } catch (e: AEADBadTagException) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun putU32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun u32(bytes: ByteArray, offset: Int): Int {
        val value = ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)
        if (value > Int.MAX_VALUE) throw PassphraseBoxFormatException("Value out of range")
        return value.toInt()
    }
}

class PassphraseBoxFormatException(message: String) : Exception(message)
