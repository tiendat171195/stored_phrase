package io.github.tiendat171195.storedphrase.core.bip39

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Bip39WordlistTest {

    @Test
    fun matchesTheOfficialEnglishWordlistFile() {
        // english.txt is the words joined by "\n" with a trailing newline.
        val file = Bip39Wordlist.words.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", sha256)
    }

    @Test
    fun wordsAreSortedUniqueLowercaseAndUniqueInFirstFourLetters() {
        val words = Bip39Wordlist.words
        assertEquals(2048, words.size)
        assertEquals(words.sorted(), words)
        assertEquals(2048, words.toSet().size)
        assertTrue(words.all { it.matches(Regex("[a-z]{3,8}")) })
        assertEquals(2048, words.map { it.take(4) }.toSet().size)
    }

    @Test
    fun lookupsAndPrefixes() {
        assertEquals(0, Bip39Wordlist.indexOf("abandon"))
        assertEquals(2047, Bip39Wordlist.indexOf("zoo"))
        assertEquals(-1, Bip39Wordlist.indexOf("bitcoin"))
        assertEquals(listOf("zone", "zoo"), Bip39Wordlist.indicesWithPrefix("zo").map(Bip39Wordlist::word))
        assertEquals(listOf("act", "action"), Bip39Wordlist.indicesWithPrefix("act", limit = 2).map(Bip39Wordlist::word))
        assertTrue(Bip39Wordlist.indicesWithPrefix("").isEmpty())
        assertTrue(Bip39Wordlist.indicesWithPrefix("qz").isEmpty())
        assertEquals(setOf('n', 'o'), Bip39Wordlist.nextLetters("zo"))
        assertTrue(Bip39Wordlist.nextLetters("zoo").isEmpty())
        assertTrue('a' in Bip39Wordlist.nextLetters(""))
    }
}
