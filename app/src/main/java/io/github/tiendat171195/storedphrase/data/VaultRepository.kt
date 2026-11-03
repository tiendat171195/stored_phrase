package io.github.tiendat171195.storedphrase.data

import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricPrompt
import io.github.tiendat171195.storedphrase.core.backup.BackupFormat
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat.KeySlot
import io.github.tiendat171195.storedphrase.core.vault.BlobFormatException
import io.github.tiendat171195.storedphrase.core.vault.EntryCodec
import io.github.tiendat171195.storedphrase.core.vault.EntryFormatException
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
import io.github.tiendat171195.storedphrase.core.vault.Layer2Envelope
import io.github.tiendat171195.storedphrase.core.vault.SeedSecret
import io.github.tiendat171195.storedphrase.core.vault.VaultIndex
import io.github.tiendat171195.storedphrase.core.vault.VaultIndexCodec
import io.github.tiendat171195.storedphrase.security.AuthReason
import io.github.tiendat171195.storedphrase.security.Authenticator
import io.github.tiendat171195.storedphrase.security.VaultCorruptedException
import io.github.tiendat171195.storedphrase.security.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/**
 * All vault operations. Every encryption/decryption runs inside StrongBox through [VaultCrypto];
 * plaintext only exists in memory for as long as the caller keeps the returned objects.
 */
class VaultRepository(
    private val store: VaultFileStore,
    private val crypto: VaultCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Metadata for an entry being written. Null timestamps mean "now" / "keep existing". */
    data class NewEntry(
        val label: String,
        val wordCount: Int,
        val hasPassphrase: Boolean,
        val hasNotes: Boolean,
        val hasBirthday: Boolean,
        val layer2: Boolean = false,
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
        val verifiedAt: Long? = null,
    )

    fun vaultExists(): Boolean = crypto.keysExist()

    fun keyReport(): VaultCrypto.KeyReport? = crypto.currentReport()

    /** Creates fresh keys in StrongBox. Any previous vault is destroyed. */
    suspend fun createVault(): VaultCrypto.KeyReport = withContext(Dispatchers.IO) {
        crypto.deleteKeys()
        store.wipe()
        crypto.createKeys()
    }

    /** Authenticates, then decrypts the index (labels and metadata). */
    suspend fun unlock(auth: Authenticator): VaultIndex {
        auth.authenticate(AuthReason.UNLOCK, null, null)
        return withIndexAuth(auth) { readIndex() }
    }

    /** Result of decrypting an entry inside StrongBox. */
    sealed interface RevealResult {
        class Plain(val secret: SeedSecret) : RevealResult

        /** Layer-2 entry: the envelope still needs the entry's own passphrase ([openLayer2]). Caller wipes it. */
        class Locked(val envelope: ByteArray) : RevealResult
    }

    /** Decrypts one entry inside StrongBox. Needs a fresh authentication bound to this operation. */
    suspend fun reveal(auth: Authenticator, entry: IndexEntry): RevealResult {
        val plaintext = decryptContent(auth, entry, AuthReason.REVEAL, null)
        if (entry.layer2) {
            try {
                parse { Layer2Envelope.validate(plaintext) }
            } catch (e: Exception) {
                plaintext.fill(0)
                throw e
            }
            return RevealResult.Locked(plaintext)
        }
        try {
            return RevealResult.Plain(decodeChecked(plaintext, entry.wordCount))
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Opens a layer-2 envelope (Argon2id + AES-GCM, no Knox Vault involved).
     * @throws Layer2WrongPassphraseException for a wrong passphrase.
     */
    suspend fun openLayer2(entry: IndexEntry, envelope: ByteArray, passphrase: CharArray): SeedSecret =
        withContext(Dispatchers.Default) {
            val inner = parse { Layer2Envelope.open(envelope, passphrase) }
            try {
                decodeChecked(inner, entry.wordCount)
            } finally {
                inner.fill(0)
            }
        }

    /**
     * Encrypts [secret] into a new blob (copy-on-write), commits the index, then removes the old blob.
     * With [layer2Passphrase], the content is first sealed in a layer-2 envelope.
     * The caller still owns [secret] and the passphrase and must wipe them.
     */
    suspend fun save(
        auth: Authenticator,
        index: VaultIndex,
        existing: IndexEntry?,
        label: String,
        secret: SeedSecret,
        layer2Passphrase: CharArray? = null,
    ): VaultIndex {
        val plaintext = EntryCodec.encode(secret)
        var content = plaintext
        try {
            if (layer2Passphrase != null) {
                content = withContext(Dispatchers.Default) { Layer2Envelope.seal(plaintext, layer2Passphrase) }
            }
            val meta = NewEntry(
                label = label,
                wordCount = secret.wordCount,
                hasPassphrase = secret.hasPassphrase,
                hasNotes = secret.hasNotes,
                hasBirthday = secret.hasBirthday,
                layer2 = layer2Passphrase != null,
            )
            return storeContent(auth, index, existing, meta, content, AuthReason.SAVE, null)
        } finally {
            plaintext.fill(0)
            content.fill(0)
        }
    }

    suspend fun delete(auth: Authenticator, index: VaultIndex, entry: IndexEntry): VaultIndex {
        val updated = index.remove(entry.id)
        writeIndex(auth, updated)
        withContext(Dispatchers.IO) { store.deleteEntry(entry.blobId) }
        return updated
    }

    suspend fun markVerified(auth: Authenticator, index: VaultIndex, entry: IndexEntry): VaultIndex {
        val updated = index.upsert(entry.copy(verifiedAt = clock()))
        writeIndex(auth, updated)
        return updated
    }

    /**
     * Decrypts every entry for a backup file, one authentication per entry (each key use is
     * bound to its own prompt). The caller must wipe the returned entries.
     */
    suspend fun exportEntries(
        auth: Authenticator,
        index: VaultIndex,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<BackupFormat.Entry> {
        val total = index.entries.size
        val out = ArrayList<BackupFormat.Entry>(total)
        try {
            index.entries.forEachIndexed { i, entry ->
                onProgress(i, total)
                val content = decryptContent(auth, entry, AuthReason.BACKUP, "${i + 1}/$total · ${entry.label}")
                val meta = BackupFormat.EntryMeta(
                    label = entry.label,
                    wordCount = entry.wordCount,
                    hasPassphrase = entry.hasPassphrase,
                    hasNotes = entry.hasNotes,
                    hasBirthday = entry.hasBirthday,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt,
                    verifiedAt = entry.verifiedAt,
                )
                // Layer-2 entries are exported still sealed: the backup never weakens them.
                val kind = if (entry.layer2) BackupFormat.Kind.LAYER2 else BackupFormat.Kind.PLAIN
                out.add(BackupFormat.Entry(kind, meta, content))
            }
            onProgress(total, total)
            return out
        } catch (e: Throwable) {
            out.forEach { it.wipe() }
            throw e
        }
    }

    /**
     * Re-encrypts backup entries with this phone's StrongBox keys, one authentication per entry.
     * The index is committed after each entry; [onCommitted] reports it so a cancelled import
     * still shows what was already saved.
     */
    suspend fun importEntries(
        auth: Authenticator,
        index: VaultIndex,
        entries: List<BackupFormat.Entry>,
        onProgress: (done: Int, total: Int) -> Unit,
        onCommitted: (VaultIndex) -> Unit,
    ): VaultIndex {
        var current = index
        entries.forEachIndexed { i, entry ->
            onProgress(i, entries.size)
            val meta = NewEntry(
                label = entry.meta.label,
                wordCount = entry.meta.wordCount,
                hasPassphrase = entry.meta.hasPassphrase,
                hasNotes = entry.meta.hasNotes,
                hasBirthday = entry.meta.hasBirthday,
                layer2 = entry.kind == BackupFormat.Kind.LAYER2,
                createdAt = entry.meta.createdAt,
                updatedAt = entry.meta.updatedAt,
                verifiedAt = entry.meta.verifiedAt,
            )
            current = storeContent(
                auth, current, null, meta, entry.content, AuthReason.IMPORT, "${i + 1}/${entries.size} · ${entry.meta.label}",
            )
            onCommitted(current)
        }
        onProgress(entries.size, entries.size)
        return current
    }

    /** Destroys the keys in StrongBox and deletes every file. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        crypto.deleteKeys()
        store.wipe()
    }

    /** Raw plaintext of an entry, decrypted inside StrongBox after an authentication bound to it. */
    private suspend fun decryptContent(auth: Authenticator, entry: IndexEntry, reason: AuthReason, detail: String?): ByteArray {
        val blob = withContext(Dispatchers.IO) { loadBlob(store.readEntry(entry.blobId), KeySlot.SECRET) }
        val cipher = withContext(Dispatchers.IO) { crypto.decryptCipher(KeySlot.SECRET, blob.iv) }
        val authorised = auth.authenticate(reason, BiometricPrompt.CryptoObject(cipher), detail)?.cipher ?: cipher
        return withContext(Dispatchers.IO) { finish(authorised, BlobFormat.entryAad(entry.blobId), blob.ciphertext) }
    }

    private fun decodeChecked(plaintext: ByteArray, expectedWords: Int): SeedSecret {
        val secret = parse { EntryCodec.decode(plaintext) }
        if (secret.wordCount != expectedWords) {
            secret.wipe()
            throw VaultCorruptedException("Entry does not match index")
        }
        return secret
    }

    /** Writes [content] as a new blob under the secret key and commits the index. */
    private suspend fun storeContent(
        auth: Authenticator,
        index: VaultIndex,
        existing: IndexEntry?,
        meta: NewEntry,
        content: ByteArray,
        reason: AuthReason,
        detail: String?,
    ): VaultIndex {
        val blobId = UUID.randomUUID().toString()
        val cipher = withContext(Dispatchers.IO) { crypto.encryptCipher(KeySlot.SECRET) }
        val authorised = auth.authenticate(reason, BiometricPrompt.CryptoObject(cipher), detail)?.cipher ?: cipher
        withContext(Dispatchers.IO) {
            authorised.updateAAD(BlobFormat.entryAad(blobId))
            val ciphertext = authorised.doFinal(content)
            store.writeEntry(blobId, BlobFormat.encode(BlobFormat.Blob(KeySlot.SECRET, authorised.iv, ciphertext)))
        }

        val now = clock()
        val entry = IndexEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            blobId = blobId,
            label = meta.label,
            wordCount = meta.wordCount,
            hasPassphrase = meta.hasPassphrase,
            hasNotes = meta.hasNotes,
            hasBirthday = meta.hasBirthday,
            layer2 = meta.layer2,
            createdAt = existing?.createdAt ?: meta.createdAt ?: now,
            updatedAt = meta.updatedAt ?: now,
            // An edited entry must have its paper copy checked again.
            verifiedAt = meta.verifiedAt,
        )
        val updated = index.upsert(entry)
        try {
            writeIndex(auth, updated)
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { store.deleteEntry(blobId) }
            throw e
        }
        if (existing != null) withContext(Dispatchers.IO) { store.deleteEntry(existing.blobId) }
        return updated
    }

    private suspend fun readIndex(): VaultIndex = withContext(Dispatchers.IO) {
        val bytes = store.readIndex() ?: return@withContext VaultIndex()
        val blob = loadBlob(bytes, KeySlot.INDEX)
        val cipher = crypto.decryptCipher(KeySlot.INDEX, blob.iv)
        val plaintext = finish(cipher, BlobFormat.indexAad(), blob.ciphertext)
        try {
            parse { VaultIndexCodec.decode(plaintext) }
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun writeIndex(auth: Authenticator, index: VaultIndex) {
        val plaintext = VaultIndexCodec.encode(index)
        try {
            val bytes = withIndexAuth(auth) {
                withContext(Dispatchers.IO) {
                    val cipher = crypto.encryptCipher(KeySlot.INDEX)
                    cipher.updateAAD(BlobFormat.indexAad())
                    val ciphertext = cipher.doFinal(plaintext)
                    BlobFormat.encode(BlobFormat.Blob(KeySlot.INDEX, cipher.iv, ciphertext))
                }
            }
            withContext(Dispatchers.IO) { store.writeIndex(bytes) }
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * The index key is time-bound (valid for a short time after any strong authentication).
     * If that window has passed, ask once more and retry.
     */
    private suspend fun <T> withIndexAuth(auth: Authenticator, block: suspend () -> T): T = try {
        block()
    } catch (e: UserNotAuthenticatedException) {
        auth.authenticate(AuthReason.UPDATE, null, null)
        block()
    }

    private fun loadBlob(bytes: ByteArray?, expected: KeySlot): BlobFormat.Blob {
        if (bytes == null) throw VaultCorruptedException("Missing vault file")
        val blob = try {
            BlobFormat.decode(bytes)
        } catch (e: BlobFormatException) {
            throw VaultCorruptedException("Bad vault file", e)
        }
        if (blob.keySlot != expected) throw VaultCorruptedException("Unexpected key slot")
        return blob
    }

    private fun finish(cipher: Cipher, aad: ByteArray, ciphertext: ByteArray): ByteArray = try {
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
        throw VaultCorruptedException("Authentication tag mismatch", e)
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: EntryFormatException) {
        throw VaultCorruptedException("Bad plaintext format", e)
    }
}
