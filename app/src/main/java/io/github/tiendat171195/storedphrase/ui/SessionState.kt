package io.github.tiendat171195.storedphrase.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tiendat171195.storedphrase.core.bip39.Bip39Wordlist
import io.github.tiendat171195.storedphrase.core.bip39.SeedInput
import io.github.tiendat171195.storedphrase.core.vault.SeedSecret

/** A decrypted entry currently shown on the detail screen. Wiped when hidden, timed out or locked. */
class RevealSession(val entryId: String, val secret: SeedSecret) {
    var showAll by mutableStateOf(false)
    val shownPositions = mutableStateListOf<Int>()
    var showPassphrase by mutableStateOf(false)
    var showNotes by mutableStateOf(false)
    var secondsLeft by mutableIntStateOf(REVEAL_SECONDS)

    fun isVisible(position: Int): Boolean = showAll || position in shownPositions

    fun toggle(position: Int) {
        if (!shownPositions.remove(position)) shownPositions.add(position)
    }

    companion object {
        /** Seconds without interaction before the words are wiped from memory. */
        const val REVEAL_SECONDS = 30
    }
}

/** Text typed into a secure field, kept as a wipeable CharArray instead of a String. */
class SecureChars {
    var chars: CharArray = CharArray(0)
        private set
    var length by mutableIntStateOf(0)
        private set

    fun set(source: CharSequence) {
        val next = CharArray(source.length) { source[it] }
        chars.fill('\u0000')
        chars = next
        length = next.size
    }

    fun copy(): CharArray = chars.copyOf()

    fun wipe() {
        chars.fill('\u0000')
        chars = CharArray(0)
        length = 0
    }
}

/** Editor state for a new or edited seed phrase. */
class SeedDraft(val editingId: String?, label: String, wordCount: Int) {
    val input = SeedInput(wordCount)
    val passphrase = SecureChars()
    val notes = SecureChars()
    var label by mutableStateOf(label)
    var showOptional by mutableStateOf(false)
    var dirty by mutableStateOf(false)
        private set

    /** Bumped on every change of [input] so Compose re-reads the IntArray-backed slots. */
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
        dirty = true
    }

    fun wipe() {
        input.wipe()
        passphrase.wipe()
        notes.wipe()
        version++
    }
}

/** Paper-copy check: the user types the words at a few random positions. */
class QuizSession(val entryId: String, private val secret: SeedSecret, val positions: IntArray) {
    var step by mutableIntStateOf(0)
        private set
    var prefix by mutableStateOf("")
        private set
    var finished by mutableStateOf(false)
        private set

    /** 1-based positions that did not match, filled when [finished]. */
    var mismatches: List<Int> by mutableStateOf(emptyList())
        private set

    private val answers = IntArray(positions.size) { -1 }

    val passed: Boolean get() = finished && mismatches.isEmpty()

    fun suggestions(limit: Int = 6): List<Int> = Bip39Wordlist.indicesWithPrefix(prefix, limit)

    fun enabledLetters(): Set<Char> = Bip39Wordlist.nextLetters(prefix)

    fun typeLetter(c: Char): Boolean {
        val candidate = prefix + c.lowercaseChar()
        val matches = Bip39Wordlist.indicesWithPrefix(candidate, limit = 2)
        return when (matches.size) {
            0 -> false
            1 -> {
                answer(matches[0])
                true
            }
            else -> {
                prefix = candidate
                true
            }
        }
    }

    fun backspace() {
        if (prefix.isNotEmpty()) prefix = prefix.dropLast(1)
    }

    fun answer(wordIndex: Int) {
        if (finished) return
        answers[step] = wordIndex
        prefix = ""
        if (step + 1 < positions.size) {
            step++
        } else {
            mismatches = positions.indices
                .filter { answers[it] != secret.wordIndices[positions[it]] }
                .map { positions[it] + 1 }
            answers.fill(-1)
            finished = true
        }
    }

    /** Copy of the decrypted words, used to retry with new positions. The caller owns it. */
    fun copySecret(): SeedSecret = secret.copy()

    fun wipe() {
        secret.wipe()
        answers.fill(-1)
        prefix = ""
    }
}
