package io.github.tiendat171195.storedphrase.core.bip39

/**
 * State machine behind the in-app BIP39 keyboard. Words are kept as indices in a fixed
 * IntArray that is wiped when the editor closes; the system keyboard is never involved.
 */
class SeedInput(wordCount: Int = 12) {

    var wordCount: Int = wordCount
        private set

    /** Slot currently being typed into. */
    var current: Int = 0
        private set

    /** Letters typed for the current slot (a prefix of a public BIP39 word). */
    var prefix: String = ""
        private set

    private val slots = IntArray(MAX_WORDS) { EMPTY }

    init {
        require(wordCount in Mnemonic.VALID_WORD_COUNTS) { "Unsupported word count" }
    }

    /** Word index in slot [i], or [EMPTY]. */
    fun word(i: Int): Int = slots[i]

    fun setWordCount(n: Int) {
        require(n in Mnemonic.VALID_WORD_COUNTS) { "Unsupported word count" }
        for (i in n until MAX_WORDS) slots[i] = EMPTY
        wordCount = n
        if (current >= n) current = n - 1
        prefix = ""
    }

    fun select(i: Int) {
        require(i in 0 until wordCount)
        current = i
        prefix = ""
    }

    fun suggestions(limit: Int = 6): List<Int> = Bip39Wordlist.indicesWithPrefix(prefix, limit)

    fun enabledLetters(): Set<Char> = Bip39Wordlist.nextLetters(prefix)

    /**
     * Adds a letter if at least one word still matches. When exactly one word matches,
     * it is filled in and the cursor moves on. Returns false for a letter that matches nothing.
     */
    fun typeLetter(c: Char): Boolean {
        val letter = c.lowercaseChar()
        if (letter !in 'a'..'z') return false
        val candidate = prefix + letter
        val matches = Bip39Wordlist.indicesWithPrefix(candidate, limit = 2)
        return when (matches.size) {
            0 -> false
            1 -> {
                choose(matches[0])
                true
            }
            else -> {
                prefix = candidate
                true
            }
        }
    }

    /** Deletes a typed letter, else clears the current word, else moves back and clears that one. */
    fun backspace() {
        when {
            prefix.isNotEmpty() -> prefix = prefix.dropLast(1)
            slots[current] != EMPTY -> slots[current] = EMPTY
            current > 0 -> {
                current--
                slots[current] = EMPTY
            }
        }
    }

    /** Puts [wordIndex] into the current slot and jumps to the next empty slot. */
    fun choose(wordIndex: Int) {
        require(wordIndex in 0 until Bip39Wordlist.SIZE)
        slots[current] = wordIndex
        prefix = ""
        val next = ((current + 1) until wordCount).firstOrNull { slots[it] == EMPTY }
            ?: (0 until wordCount).firstOrNull { slots[it] == EMPTY }
        if (next != null) current = next
    }

    fun filledCount(): Int = (0 until wordCount).count { slots[it] != EMPTY }

    fun isComplete(): Boolean = filledCount() == wordCount

    fun isChecksumValid(): Boolean {
        if (!isComplete()) return false
        val copy = indices()
        return try {
            Mnemonic.isValid(copy)
        } finally {
            copy.fill(0)
        }
    }

    /** Copy of the filled words. The caller must wipe it. */
    fun indices(): IntArray = IntArray(wordCount) { slots[it] }

    fun load(indices: IntArray) {
        require(indices.size in Mnemonic.VALID_WORD_COUNTS)
        wipe()
        wordCount = indices.size
        indices.copyInto(slots)
    }

    fun wipe() {
        slots.fill(EMPTY)
        prefix = ""
        current = 0
    }

    companion object {
        const val EMPTY = -1
        const val MAX_WORDS = 24
    }
}
