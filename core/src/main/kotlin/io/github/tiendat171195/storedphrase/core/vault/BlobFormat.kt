package io.github.tiendat171195.storedphrase.core.vault

/**
 * Container for one AES-256-GCM ciphertext stored on disk.
 *
 * ```
 * offset  size  field
 * 0       4     magic "SPV1"
 * 4       1     key slot (1 = index, 2 = secret)
 * 5       1     IV length (12)
 * 6       12    IV
 * 18      n     ciphertext || 128-bit GCM tag
 * ```
 */
object BlobFormat {
    const val IV_LENGTH = 12
    const val TAG_LENGTH_BITS = 128
    private const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    private val MAGIC = byteArrayOf(0x53, 0x50, 0x56, 0x31) // "SPV1"
    private const val HEADER_LENGTH = 4 + 1 + 1 + IV_LENGTH

    enum class KeySlot(val id: Byte) {
        INDEX(1),
        SECRET(2);

        companion object {
            fun fromId(id: Byte): KeySlot =
                entries.firstOrNull { it.id == id } ?: throw BlobFormatException("Unknown key slot $id")
        }
    }

    class Blob(val keySlot: KeySlot, val iv: ByteArray, val ciphertext: ByteArray) {
        init {
            require(iv.size == IV_LENGTH) { "IV must be $IV_LENGTH bytes" }
            require(ciphertext.size >= TAG_LENGTH_BYTES) { "Ciphertext shorter than the GCM tag" }
        }
    }

    fun encode(blob: Blob): ByteArray {
        val out = ByteArray(HEADER_LENGTH + blob.ciphertext.size)
        MAGIC.copyInto(out, 0)
        out[4] = blob.keySlot.id
        out[5] = IV_LENGTH.toByte()
        blob.iv.copyInto(out, 6)
        blob.ciphertext.copyInto(out, HEADER_LENGTH)
        return out
    }

    fun decode(bytes: ByteArray): Blob {
        if (bytes.size < HEADER_LENGTH + TAG_LENGTH_BYTES) throw BlobFormatException("Blob too short")
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) throw BlobFormatException("Bad magic")
        }
        val slot = KeySlot.fromId(bytes[4])
        if (bytes[5].toInt() != IV_LENGTH) throw BlobFormatException("Unsupported IV length")
        val iv = bytes.copyOfRange(6, HEADER_LENGTH)
        val ciphertext = bytes.copyOfRange(HEADER_LENGTH, bytes.size)
        return Blob(slot, iv, ciphertext)
    }

    /**
     * Additional authenticated data binding a ciphertext to what it is.
     * Swapping blobs between entries, or between index and entry, fails the GCM tag check.
     */
    fun aad(slot: KeySlot, context: String): ByteArray =
        "storedphrase/v1|${slot.id}|$context".encodeToByteArray()

    fun indexAad(): ByteArray = aad(KeySlot.INDEX, "index")

    fun entryAad(blobId: String): ByteArray = aad(KeySlot.SECRET, "entry/$blobId")
}

class BlobFormatException(message: String) : Exception(message)
