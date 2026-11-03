package io.github.tiendat171195.storedphrase.core.backup

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassphraseGeneratorTest {

    @Test
    fun wordlistMatchesTheOfficialEffFile() {
        // eff_large_wordlist.txt lines are "<five dice digits>\t<word>\n", in dice order.
        val dice = sequence {
            for (a in 1..6) for (b in 1..6) for (c in 1..6) for (d in 1..6) for (e in 1..6) yield("$a$b$c$d$e")
        }.toList()
        val file = EffLargeWordlist.words.mapIndexed { i, w -> "${dice[i]}\t$w\n" }.joinToString("").encodeToByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        assertEquals("addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e", sha256)
    }

    @Test
    fun generatesSevenUnambiguousWordsWithAbout90Bits() {
        val passphrase = String(PassphraseGenerator.generate(random = SecureRandom()))
        val words = passphrase.split('-')
        assertEquals(7, words.size)
        assertTrue(words.all { it in PassphraseGenerator.candidates })
        assertTrue(passphrase.matches(Regex("[a-z]+(-[a-z]+){6}")))
        assertTrue(PassphraseGenerator.entropyBits() > 90.0)
    }
}
