package io.github.tiendat171195.storedphrase.core.bip39

import java.security.MessageDigest

/**
 * BIP39 mnemonic <-> entropy conversion and checksum validation, working on word indices.
 * Seed derivation (PBKDF2) is intentionally not implemented: the app only stores phrases.
 */
object Mnemonic {
    val VALID_WORD_COUNTS: List<Int> = listOf(12, 15, 18, 21, 24)

    /** True when [indices] form a mnemonic with a valid BIP39 checksum. */
    fun isValid(indices: IntArray): Boolean = try {
        toEntropy(indices).fill(0)
        true
    } catch (e: InvalidMnemonicException) {
        false
    }

    /** Returns the entropy encoded by [indices]; the caller should wipe it. */
    fun toEntropy(indices: IntArray): ByteArray {
        val n = indices.size
        if (n !in VALID_WORD_COUNTS) throw InvalidMnemonicException("Unsupported word count $n")
        val totalBits = n * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits

        val bits = ByteArray((totalBits + 7) / 8)
        try {
            var pos = 0
            for (index in indices) {
                if (index !in 0 until Bip39Wordlist.SIZE) throw InvalidMnemonicException("Word index out of range")
                for (b in 10 downTo 0) {
                    if ((index ushr b) and 1 == 1) setBit(bits, pos)
                    pos++
                }
            }
            val entropy = bits.copyOf(entropyBits / 8)
            val hash = sha256(entropy)
            var matches = true
            for (i in 0 until checksumBits) {
                if (bit(bits, entropyBits + i) != bit(hash, i)) matches = false
            }
            hash.fill(0)
            if (!matches) {
                entropy.fill(0)
                throw InvalidMnemonicException("Checksum mismatch")
            }
            return entropy
        } finally {
            bits.fill(0)
        }
    }

    /** Encodes 128..256 bits of entropy (multiple of 32) as word indices. */
    fun fromEntropy(entropy: ByteArray): IntArray {
        val entropyBits = entropy.size * 8
        require(entropyBits in 128..256 && entropyBits % 32 == 0) { "Entropy must be 128-256 bits, multiple of 32" }
        val checksumBits = entropyBits / 32
        val hash = sha256(entropy)
        try {
            val n = (entropyBits + checksumBits) / 11
            return IntArray(n) { w ->
                var value = 0
                for (b in 0 until 11) {
                    val pos = w * 11 + b
                    val bit = if (pos < entropyBits) bit(entropy, pos) else bit(hash, pos - entropyBits)
                    value = (value shl 1) or bit
                }
                value
            }
        } finally {
            hash.fill(0)
        }
    }

    /** Parses a space separated phrase (tests and tooling). Returns null for unknown words. */
    fun parse(phrase: String): IntArray? {
        val parts = phrase.trim().split(Regex("\\s+"))
        val indices = IntArray(parts.size) { Bip39Wordlist.indexOf(parts[it].lowercase()) }
        return if (indices.any { it < 0 }) null else indices
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun bit(bytes: ByteArray, i: Int): Int = (bytes[i ushr 3].toInt() ushr (7 - (i and 7))) and 1

    private fun setBit(bytes: ByteArray, i: Int) {
        bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
    }
}

class InvalidMnemonicException(message: String) : Exception(message)
