package io.github.tiendat171195.storedphrase.core.backup

import java.security.SecureRandom
import kotlin.math.log2

/**
 * Random passphrases from the EFF large wordlist ("word-word-…", lowercase a-z only).
 * Seven words give about 90 bits of entropy, far beyond what an attacker can brute-force
 * through Argon2id. The EFF list is deliberately different from BIP39 so a backup
 * passphrase is never mistaken for a seed phrase.
 */
object PassphraseGenerator {
    const val DEFAULT_WORDS = 7
    const val SEPARATOR = '-'

    /** EFF words without hyphens, so the separator is unambiguous. */
    internal val candidates: List<String> by lazy { EffLargeWordlist.words.filter { '-' !in it } }

    fun generate(words: Int = DEFAULT_WORDS, random: SecureRandom = SecureRandom()): CharArray {
        require(words in 4..12)
        val chosen = List(words) { candidates[random.nextInt(candidates.size)] }
        val out = CharArray(chosen.sumOf { it.length } + words - 1)
        var p = 0
        chosen.forEachIndexed { i, word ->
            if (i > 0) out[p++] = SEPARATOR
            word.toCharArray().copyInto(out, p)
            p += word.length
        }
        return out
    }

    fun entropyBits(words: Int = DEFAULT_WORDS): Double = words * log2(candidates.size.toDouble())
}
