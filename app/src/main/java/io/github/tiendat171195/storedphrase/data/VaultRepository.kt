package io.github.tiendat171195.storedphrase.data

import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricPrompt
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat.KeySlot
import io.github.tiendat171195.storedphrase.core.vault.BlobFormatException
import io.github.tiendat171195.storedphrase.core.vault.EntryCodec
import io.github.tiendat171195.storedphrase.core.vault.EntryFormatException
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
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
        auth.authenticate(AuthReason.UNLOCK, null)
        return withIndexAuth(auth) { readIndex() }
    }

    /** Decrypts one entry inside StrongBox. Needs a fresh authentication bound to this operation. */
    suspend fun reveal(auth: Authenticator, entry: IndexEntry): SeedSecret {
        val blob = withContext(Dispatchers.IO) { loadBlob(store.readEntry(entry.blobId), KeySlot.SECRET) }
        val cipher = withContext(Dispatchers.IO) { crypto.decryptCipher(KeySlot.SECRET, blob.iv) }
        val authorised = auth.authenticate(AuthReason.REVEAL, BiometricPrompt.CryptoObject(cipher))?.cipher ?: cipher
        return withContext(Dispatchers.IO) {
            val plaintext = finish(authorised, BlobFormat.entryAad(entry.blobId), blob.ciphertext)
            try {
                val secret = parse { EntryCodec.decode(plaintext) }
                if (secret.wordCount != entry.wordCount) {
                    secret.wipe()
                    throw VaultCorruptedException("Entry does not match index")
                }
                secret
            } finally {
                plaintext.fill(0)
            }
        }
    }

    /**
     * Encrypts [secret] into a new blob (copy-on-write), commits the index, then removes the old blob.
     * The caller still owns [secret] and must wipe it.
     */
    suspend fun save(
        auth: Authenticator,
        index: VaultIndex,
        existing: IndexEntry?,
        label: String,
        secret: SeedSecret,
    ): VaultIndex {
        val blobId = UUID.randomUUID().toString()
        val plaintext = EntryCodec.encode(secret)
        try {
            val cipher = withContext(Dispatchers.IO) { crypto.encryptCipher(KeySlot.SECRET) }
            val authorised = auth.authenticate(AuthReason.SAVE, BiometricPrompt.CryptoObject(cipher))?.cipher ?: cipher
            withContext(Dispatchers.IO) {
                authorised.updateAAD(BlobFormat.entryAad(blobId))
                val ciphertext = authorised.doFinal(plaintext)
                store.writeEntry(blobId, BlobFormat.encode(BlobFormat.Blob(KeySlot.SECRET, authorised.iv, ciphertext)))
            }
        } finally {
            plaintext.fill(0)
        }

        val now = clock()
        val entry = IndexEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            blobId = blobId,
            label = label,
            wordCount = secret.wordCount,
            hasPassphrase = secret.hasPassphrase,
            hasNotes = secret.hasNotes,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            // Content may have changed, so the paper copy must be checked again.
            verifiedAt = null,
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

    /** Destroys the keys in StrongBox and deletes every file. */
    suspend fun wipe() = withContext(Dispatchers.IO) {
        crypto.deleteKeys()
        store.wipe()
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
        auth.authenticate(AuthReason.UPDATE, null)
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
