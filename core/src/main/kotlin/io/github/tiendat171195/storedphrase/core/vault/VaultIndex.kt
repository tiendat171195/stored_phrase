package io.github.tiendat171195.storedphrase.core.vault

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Decrypted list of entries. Holds metadata only, never seed words. */
@Serializable
data class VaultIndex(
    val version: Int = VERSION,
    val entries: List<IndexEntry> = emptyList(),
) {
    fun find(id: String): IndexEntry? = entries.firstOrNull { it.id == id }

    fun upsert(entry: IndexEntry): VaultIndex {
        val existing = entries.indexOfFirst { it.id == entry.id }
        val updated = if (existing >= 0) {
            entries.toMutableList().also { it[existing] = entry }
        } else {
            entries + entry
        }
        return copy(entries = updated)
    }

    fun remove(id: String): VaultIndex = copy(entries = entries.filterNot { it.id == id })

    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class IndexEntry(
    /** Stable id of the entry. */
    val id: String,
    /** File holding the current ciphertext; changes on every save (copy-on-write). */
    val blobId: String,
    val label: String,
    val wordCount: Int,
    val hasPassphrase: Boolean = false,
    val hasNotes: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Last successful paper-copy check, or null. */
    @SerialName("verifiedAt")
    val verifiedAt: Long? = null,
)

object VaultIndexCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(index: VaultIndex): ByteArray = json.encodeToString(VaultIndex.serializer(), index).encodeToByteArray()

    fun decode(bytes: ByteArray): VaultIndex = try {
        val index = json.decodeFromString(VaultIndex.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        if (index.version > VaultIndex.VERSION) throw EntryFormatException("Vault was written by a newer app version")
        index
    } catch (e: SerializationException) {
        throw EntryFormatException("Corrupted vault index")
    } catch (e: CharacterCodingException) {
        throw EntryFormatException("Corrupted vault index")
    }
}
