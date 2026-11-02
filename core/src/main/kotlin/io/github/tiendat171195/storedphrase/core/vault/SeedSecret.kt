package io.github.tiendat171195.storedphrase.core.vault

/**
 * Decrypted content of one vault entry. Holds only primitive arrays so it can be wiped.
 *
 * @property wordIndices BIP39 word indices (0..2047). Words are rendered from the public
 *   wordlist, so the secret never has to exist as a String.
 */
class SeedSecret(
    val wordIndices: IntArray,
    val bip39Passphrase: CharArray = CharArray(0),
    val notes: CharArray = CharArray(0),
) {
    val wordCount: Int get() = wordIndices.size
    val hasPassphrase: Boolean get() = bip39Passphrase.isNotEmpty()
    val hasNotes: Boolean get() = notes.isNotEmpty()

    @Volatile
    var isWiped: Boolean = false
        private set

    /** Overwrites every secret array. The object must not be used afterwards. */
    fun wipe() {
        wordIndices.fill(0)
        bip39Passphrase.fill('\u0000')
        notes.fill('\u0000')
        isWiped = true
    }

    fun copy(): SeedSecret = SeedSecret(wordIndices.copyOf(), bip39Passphrase.copyOf(), notes.copyOf())
}
