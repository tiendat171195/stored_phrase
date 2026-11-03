package io.github.tiendat171195.storedphrase.core.crypto

import io.github.tiendat171195.storedphrase.core.text.Utf8
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id (version 1.3, RFC 9106) password-based key derivation, used for backup files and
 * layer-2 passphrases. Parameters are stored next to the ciphertext so they can grow later.
 */
object Argon2idKdf {
    const val KEY_LENGTH = 32
    const val SALT_LENGTH = 16

    data class Params(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
        init {
            require(memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "Argon2 memory out of range" }
            require(iterations in 1..MAX_ITERATIONS) { "Argon2 iterations out of range" }
            require(parallelism in 1..MAX_PARALLELISM) { "Argon2 parallelism out of range" }
        }

        companion object {
            /** 64 MiB, 3 passes, 1 lane: about 1-2 s on a recent phone. */
            val DEFAULT = Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

            // Bounds also protect against hostile files asking for absurd amounts of memory.
            const val MIN_MEMORY_KIB = 8 * 1024
            const val MAX_MEMORY_KIB = 256 * 1024
            const val MAX_ITERATIONS = 16
            const val MAX_PARALLELISM = 8
        }
    }

    /** Derives a [KEY_LENGTH]-byte key. The caller must wipe the result. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, params: Params): ByteArray {
        require(salt.size == SALT_LENGTH) { "Salt must be $SALT_LENGTH bytes" }
        val password = Utf8.passphraseBytes(passphrase)
        try {
            val argon2 = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(argon2)
            val key = ByteArray(KEY_LENGTH)
            generator.generateBytes(password, key)
            return key
        } finally {
            password.fill(0)
        }
    }
}
