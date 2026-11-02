package io.github.tiendat171195.storedphrase.core.bip39

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeedInputTest {

    private fun SeedInput.type(text: String) = text.forEach { typeLetter(it) }

    @Test
    fun uniquePrefixAutoFillsAndAdvances() {
        val input = SeedInput(12)
        input.type("aba") // only "abandon" starts with "aba"
        assertEquals(Bip39Wordlist.indexOf("abandon"), input.word(0))
        assertEquals(1, input.current)
        assertEquals("", input.prefix)
    }

    @Test
    fun ambiguousPrefixWaitsForSuggestion() {
        val input = SeedInput(12)
        input.type("act")
        assertEquals("act", input.prefix)
        assertEquals(SeedInput.EMPTY, input.word(0))
        assertEquals(listOf("act", "action", "actor", "actress", "actual"), input.suggestions().map(Bip39Wordlist::word))
        input.choose(input.suggestions().first())
        assertEquals(Bip39Wordlist.indexOf("act"), input.word(0))
    }

    @Test
    fun lettersThatMatchNothingAreRejected() {
        val input = SeedInput(12)
        assertTrue(input.typeLetter('z'))
        assertFalse(input.typeLetter('z'))
        assertFalse(input.typeLetter('1'))
        assertEquals("z", input.prefix)
        assertEquals(setOf('e', 'o'), input.enabledLetters())
    }

    @Test
    fun backspaceRemovesLetterThenWordThenMovesBack() {
        val input = SeedInput(12)
        input.type("zoo")
        input.type("ze")
        assertEquals("ze", input.prefix)
        input.backspace()
        assertEquals("z", input.prefix)
        input.backspace()
        assertEquals("", input.prefix)
        input.backspace() // slot 1 empty -> back to slot 0 and clear it
        assertEquals(0, input.current)
        assertEquals(SeedInput.EMPTY, input.word(0))
    }

    @Test
    fun completeMnemonicReportsChecksum() {
        val input = SeedInput(12)
        repeat(11) { input.type("aba") }
        assertFalse(input.isComplete())
        input.type("abov") // "above" -> wrong checksum
        assertTrue(input.isComplete())
        assertFalse(input.isChecksumValid())
        input.select(11)
        input.type("abou") // "about" -> valid
        assertTrue(input.isChecksumValid())
    }

    @Test
    fun changingWordCountKeepsOnlyFittingWords() {
        val input = SeedInput(24)
        input.load(IntArray(24) { it })
        input.setWordCount(12)
        assertEquals(12, input.wordCount)
        assertEquals(12, input.filledCount())
        input.setWordCount(15)
        assertEquals(12, input.filledCount())
        assertEquals(SeedInput.EMPTY, input.word(14))
        input.wipe()
        assertEquals(0, input.filledCount())
    }
}
