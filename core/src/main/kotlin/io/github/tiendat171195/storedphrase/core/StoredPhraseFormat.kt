package io.github.tiendat171195.storedphrase.core

/**
 * Version markers for everything Stored Phrase writes to disk.
 *
 * The `:core` module is plain Kotlin/JVM on purpose: formats, BIP39 logic and
 * other security-relevant code can be unit tested on the JVM without Android.
 */
object StoredPhraseFormat {
    /** Version of the on-device vault layout (index + entry blobs). */
    const val VAULT_FORMAT_VERSION: Int = 1
}
