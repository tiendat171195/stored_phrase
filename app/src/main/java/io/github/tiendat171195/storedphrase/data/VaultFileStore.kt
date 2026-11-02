package io.github.tiendat171195.storedphrase.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * Ciphertext files under noBackupFilesDir/vault. Every write is atomic (AtomicFile),
 * so a crash never leaves a half-written file.
 */
class VaultFileStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "vault")
    private val entriesDir = File(root, "entries")
    private val indexFile = File(root, "index.bin")

    fun readIndex(): ByteArray? = read(indexFile)

    fun writeIndex(bytes: ByteArray) = write(indexFile, bytes)

    fun readEntry(blobId: String): ByteArray? = read(entryFile(blobId))

    fun writeEntry(blobId: String, bytes: ByteArray) = write(entryFile(blobId), bytes)

    fun deleteEntry(blobId: String) {
        AtomicFile(entryFile(blobId)).delete()
    }

    /** Removes every vault file. Used together with deleting the keys. */
    fun wipe() {
        root.deleteRecursively()
    }

    private fun entryFile(blobId: String): File {
        require(BLOB_ID.matches(blobId)) { "Invalid blob id" }
        return File(entriesDir, "$blobId.bin")
    }

    private fun read(file: File): ByteArray? {
        val atomic = AtomicFile(file)
        return if (atomic.baseFile.exists()) atomic.readFully() else null
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create $it") }
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    private companion object {
        val BLOB_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
