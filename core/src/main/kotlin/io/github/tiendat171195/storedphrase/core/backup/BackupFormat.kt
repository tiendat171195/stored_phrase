package io.github.tiendat171195.storedphrase.core.backup

import io.github.tiendat171195.storedphrase.core.crypto.AesGcm
import io.github.tiendat171195.storedphrase.core.crypto.Argon2idKdf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Passphrase-encrypted backup file (`.spbak`). Specified in docs/BACKUP_FORMAT.md and readable
 * without the app by tools/recover_backup.py.
 *
 * ```
 * offset size  field
 * 0      4     magic "SPBK"
 * 4      1     format version = 1
 * 5      1     KDF = 1 (Argon2id v1.3)
 * 6      4     Argon2 memory in KiB (u32 BE)
 * 10     4     Argon2 iterations (u32 BE)
 * 14     1     Argon2 parallelism
 * 15     1     salt length = 16
 * 16     16    salt
 * 32     1     nonce length = 12
 * 33     12    nonce
 * 45     …     AES-256-GCM(payload) || 128-bit tag; AAD = bytes 0..44
 * ```
 */
object BackupFormat {
    const val FILE_EXTENSION = "spbak"
    const val MIME_TYPE = "application/octet-stream"
    const val FORMAT_VERSION = 1
    const val KDF_ARGON2ID = 1
    const val PAYLOAD_VERSION = 1
    const val HEADER_LENGTH = 45
    private val MAGIC = byteArrayOf(0x53, 0x50, 0x42, 0x4B) // "SPBK"
    private const val MAX_ENTRIES = 1000
    private const val MAX_META_BYTES = 64 * 1024
    private const val MAX_CONTENT_BYTES = 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    enum class Kind(val id: Int) {
        /** content = EntryCodec plaintext. */
        PLAIN(1),

        /** content = layer-2 envelope, still encrypted with the entry's own passphrase. */
        LAYER2(2);

        companion object {
            fun fromId(id: Int): Kind = entries.firstOrNull { it.id == id } ?: throw BackupFormatException("Unknown entry kind $id")
        }
    }

    @Serializable
    data class Manifest(val createdAt: Long, val generator: String = "")

    @Serializable
    data class EntryMeta(
        val label: String,
        val wordCount: Int,
        val hasPassphrase: Boolean = false,
        val hasNotes: Boolean = false,
        val hasBirthday: Boolean = false,
        val createdAt: Long,
        val updatedAt: Long,
        val verifiedAt: Long? = null,
    )

    class Entry(val kind: Kind, val meta: EntryMeta, val content: ByteArray) {
        fun wipe() = content.fill(0)
    }

    class Backup(val manifest: Manifest, val entries: List<Entry>) {
        fun wipe() = entries.forEach { it.wipe() }
    }

    class Header(val params: Argon2idKdf.Params, val salt: ByteArray, val nonce: ByteArray)

    fun encrypt(
        backup: Backup,
        passphrase: CharArray,
        params: Argon2idKdf.Params = Argon2idKdf.Params.DEFAULT,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val salt = ByteArray(Argon2idKdf.SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(AesGcm.NONCE_LENGTH).also(random::nextBytes)
        val header = encodeHeader(Header(params, salt, nonce))
        val payload = encodePayload(backup)
        val key = Argon2idKdf.deriveKey(passphrase, salt, params)
        try {
            return header + AesGcm.encrypt(key, nonce, header, payload)
        } finally {
            key.fill(0)
            payload.fill(0)
        }
    }

    /** Validates the unencrypted header (cheap; no passphrase needed). */
    fun readHeader(file: ByteArray): Header {
        if (file.size < HEADER_LENGTH + AesGcm.TAG_BITS / 8) throw BackupFormatException("File too short")
        for (i in MAGIC.indices) if (file[i] != MAGIC[i]) throw BackupFormatException("Not a Stored Phrase backup")
        if (file[4].toInt() != FORMAT_VERSION) throw BackupFormatException("Unsupported backup version ${file[4]}")
        if (file[5].toInt() != KDF_ARGON2ID) throw BackupFormatException("Unsupported KDF ${file[5]}")
        val params = try {
            Argon2idKdf.Params(
                memoryKiB = u32(file, 6),
                iterations = u32(file, 10),
                parallelism = file[14].toInt() and 0xFF,
            )
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Unsupported KDF parameters")
        }
        if (file[15].toInt() != Argon2idKdf.SALT_LENGTH) throw BackupFormatException("Bad salt length")
        if (file[32].toInt() != AesGcm.NONCE_LENGTH) throw BackupFormatException("Bad nonce length")
        return Header(params, file.copyOfRange(16, 32), file.copyOfRange(33, 45))
    }

    /**
     * @throws WrongPassphraseException when the passphrase is wrong or the file was modified.
     * @throws BackupFormatException for files that are not valid backups.
     */
    fun decrypt(file: ByteArray, passphrase: CharArray): Backup {
        val header = readHeader(file)
        val aad = file.copyOfRange(0, HEADER_LENGTH)
        val key = Argon2idKdf.deriveKey(passphrase, header.salt, header.params)
        val payload = try {
            AesGcm.decrypt(key, header.nonce, aad, file.copyOfRange(HEADER_LENGTH, file.size))
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } finally {
            key.fill(0)
        }
        try {
            return decodePayload(payload)
        } finally {
            payload.fill(0)
        }
    }

    private fun encodeHeader(header: Header): ByteArray {
        val out = ByteArray(HEADER_LENGTH)
        MAGIC.copyInto(out, 0)
        out[4] = FORMAT_VERSION.toByte()
        out[5] = KDF_ARGON2ID.toByte()
        putU32(out, 6, header.params.memoryKiB)
        putU32(out, 10, header.params.iterations)
        out[14] = header.params.parallelism.toByte()
        out[15] = Argon2idKdf.SALT_LENGTH.toByte()
        header.salt.copyInto(out, 16)
        out[32] = AesGcm.NONCE_LENGTH.toByte()
        header.nonce.copyInto(out, 33)
        return out
    }

    private fun encodePayload(backup: Backup): ByteArray {
        require(backup.entries.size <= MAX_ENTRIES) { "Too many entries" }
        val out = ByteArrayOutputStream()
        out.write(PAYLOAD_VERSION)
        writeBlock(out, json.encodeToString(Manifest.serializer(), backup.manifest).encodeToByteArray())
        out.write(backup.entries.size ushr 8)
        out.write(backup.entries.size and 0xFF)
        for (entry in backup.entries) {
            out.write(entry.kind.id)
            writeBlock(out, json.encodeToString(EntryMeta.serializer(), entry.meta).encodeToByteArray())
            writeBlock(out, entry.content)
        }
        return out.toByteArray()
    }

    private fun decodePayload(payload: ByteArray): Backup {
        val reader = Reader(payload)
        val entries = ArrayList<Entry>()
        try {
            if (reader.u8() != PAYLOAD_VERSION) throw BackupFormatException("Unsupported payload version")
            val manifest = parseJson(Manifest.serializer(), reader.block(MAX_META_BYTES))
            val count = (reader.u8() shl 8) or reader.u8()
            if (count > MAX_ENTRIES) throw BackupFormatException("Too many entries")
            repeat(count) {
                val kind = Kind.fromId(reader.u8())
                val meta = parseJson(EntryMeta.serializer(), reader.block(MAX_META_BYTES))
                val content = reader.block(MAX_CONTENT_BYTES)
                entries.add(Entry(kind, meta, content))
            }
            if (!reader.atEnd()) throw BackupFormatException("Trailing data")
            return Backup(manifest, entries)
        } catch (e: IndexOutOfBoundsException) {
            entries.forEach { it.wipe() }
            throw BackupFormatException("Truncated payload")
        } catch (e: BackupFormatException) {
            entries.forEach { it.wipe() }
            throw e
        }
    }

    private fun <T> parseJson(serializer: kotlinx.serialization.KSerializer<T>, bytes: ByteArray): T = try {
        json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (e: SerializationException) {
        throw BackupFormatException("Bad metadata")
    } catch (e: CharacterCodingException) {
        throw BackupFormatException("Bad metadata")
    }

    private fun writeBlock(out: ByteArrayOutputStream, bytes: ByteArray) {
        val header = ByteArray(4)
        putU32(header, 0, bytes.size)
        out.write(header)
        out.write(bytes)
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
        if (value > Int.MAX_VALUE) throw BackupFormatException("Value out of range")
        return value.toInt()
    }

    private class Reader(private val bytes: ByteArray) {
        private var p = 0
        fun u8(): Int = bytes[p++].toInt() and 0xFF
        fun block(max: Int): ByteArray {
            val length = u32(bytes, p)
            p += 4
            if (length > max || p + length > bytes.size) throw BackupFormatException("Bad block length")
            return bytes.copyOfRange(p, p + length).also { p += length }
        }
        fun atEnd(): Boolean = p == bytes.size
    }
}

open class BackupFormatException(message: String) : Exception(message)

class WrongPassphraseException : BackupFormatException("Wrong passphrase or modified file")
