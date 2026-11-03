package io.github.tiendat171195.storedphrase.core.backup

import io.github.tiendat171195.storedphrase.core.crypto.Argon2idKdf
import io.github.tiendat171195.storedphrase.core.crypto.PassphraseBox
import io.github.tiendat171195.storedphrase.core.crypto.PassphraseBoxFormatException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Passphrase-encrypted backup file (`.spbak`): a [PassphraseBox] with magic "SPBK" whose
 * plaintext is the payload below. Specified in docs/BACKUP_FORMAT.md and readable without the
 * app by tools/recover_backup.py.
 *
 * ```
 * u8          payload version = 1
 * u32 BE + …  manifest JSON
 * u16 BE      entry count N
 * N × { u8 kind, u32 BE + … metadata JSON, u32 BE + … content }
 * ```
 */
object BackupFormat {
    const val FILE_EXTENSION = "spbak"
    const val MIME_TYPE = "application/octet-stream"
    const val PAYLOAD_VERSION = 1
    const val HEADER_LENGTH = PassphraseBox.HEADER_LENGTH
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

    fun encrypt(
        backup: Backup,
        passphrase: CharArray,
        params: Argon2idKdf.Params = Argon2idKdf.Params.DEFAULT,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val payload = encodePayload(backup)
        try {
            return PassphraseBox.seal(MAGIC, payload, passphrase, params, random)
        } finally {
            payload.fill(0)
        }
    }

    /** Validates the unencrypted header (cheap; no passphrase needed). */
    fun readHeader(file: ByteArray): PassphraseBox.Header = try {
        PassphraseBox.readHeader(MAGIC, file)
    } catch (e: PassphraseBoxFormatException) {
        throw BackupFormatException("Not a valid Stored Phrase backup: ${e.message}")
    }

    /**
     * @throws WrongPassphraseException when the passphrase is wrong or the file was modified.
     * @throws BackupFormatException for files that are not valid backups.
     */
    fun decrypt(file: ByteArray, passphrase: CharArray): Backup {
        readHeader(file)
        val payload = try {
            PassphraseBox.open(MAGIC, file, passphrase)
        } catch (e: PassphraseBoxFormatException) {
            throw BackupFormatException("Not a valid Stored Phrase backup: ${e.message}")
        } ?: throw WrongPassphraseException()
        try {
            return decodePayload(payload)
        } finally {
            payload.fill(0)
        }
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

    private fun <T> parseJson(serializer: KSerializer<T>, bytes: ByteArray): T = try {
        json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (e: SerializationException) {
        throw BackupFormatException("Bad metadata")
    } catch (e: CharacterCodingException) {
        throw BackupFormatException("Bad metadata")
    }

    private fun writeBlock(out: ByteArrayOutputStream, bytes: ByteArray) {
        out.write(bytes.size ushr 24)
        out.write(bytes.size ushr 16)
        out.write(bytes.size ushr 8)
        out.write(bytes.size)
        out.write(bytes)
    }

    private class Reader(private val bytes: ByteArray) {
        private var p = 0
        fun u8(): Int = bytes[p++].toInt() and 0xFF
        fun block(max: Int): ByteArray {
            val length = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()
            if (length > max || p + length > bytes.size) throw BackupFormatException("Bad block length")
            return bytes.copyOfRange(p, p + length.toInt()).also { p += length.toInt() }
        }
        fun atEnd(): Boolean = p == bytes.size
    }
}

open class BackupFormatException(message: String) : Exception(message)

class WrongPassphraseException : BackupFormatException("Wrong passphrase or modified file")
