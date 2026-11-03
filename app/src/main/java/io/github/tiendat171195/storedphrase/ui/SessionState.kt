package io.github.tiendat171195.storedphrase.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.core.backup.BackupFormat
import io.github.tiendat171195.storedphrase.core.backup.PassphraseGenerator
import io.github.tiendat171195.storedphrase.core.bip39.Bip39Wordlist
import io.github.tiendat171195.storedphrase.core.bip39.SeedInput
import io.github.tiendat171195.storedphrase.core.vault.Layer2Envelope
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

    /** Bumped on every change, so comparisons in composables re-run even when the length is unchanged. */
    var revision by mutableIntStateOf(0)
        private set

    fun set(source: CharSequence) {
        val next = CharArray(source.length) { source[it] }
        chars.fill('\u0000')
        chars = next
        length = next.size
        revision++
    }

    fun copy(): CharArray = chars.copyOf()

    /** Content comparison that is tracked by Compose (reads both revisions). */
    fun sameAs(other: SecureChars): Boolean = (revision or other.revision) >= 0 && chars.contentEquals(other.chars)

    fun wipe() {
        chars.fill('\u0000')
        chars = CharArray(0)
        length = 0
        revision++
    }
}

/** Editor state for a new or edited seed phrase. */
class SeedDraft(val editingId: String?, label: String, wordCount: Int) {
    val input = SeedInput(wordCount)
    val passphrase = SecureChars()
    val notes = SecureChars()
    var label by mutableStateOf(label)
    var showOptional by mutableStateOf(false)

    /** Wallet birthday height as typed (digits only); empty means none. */
    var birthday by mutableStateOf("")

    /** Layer-2 protection: the entry is sealed with its own passphrase before Knox Vault encryption. */
    var layer2Enabled by mutableStateOf(false)
    val layer2Passphrase = SecureChars()
    val layer2Repeat = SecureChars()

    @StringRes
    fun layer2Error(): Int? = when {
        !layer2Enabled -> null
        layer2Passphrase.length < Layer2Envelope.MIN_PASSPHRASE_LENGTH -> R.string.layer2_too_short
        !layer2Passphrase.sameAs(layer2Repeat) -> R.string.layer2_mismatch
        else -> null
    }

    val birthdayHeight: Long? get() = birthday.toLongOrNull()
    val birthdayValid: Boolean
        get() = birthday.isEmpty() || birthdayHeight?.let { it in 0..SeedSecret.MAX_BIRTHDAY_HEIGHT } == true
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
        layer2Passphrase.wipe()
        layer2Repeat.wipe()
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

/** Progress line for long operations (one prompt per entry, Argon2id key derivation). */
data class ProgressInfo(@StringRes val text: Int, val done: Int = 0, val total: Int = 0)

/** "Back up to file" screen. */
class BackupExportState {
    var useGenerated by mutableStateOf(true)
    var generated: CharArray = CharArray(0)
        private set

    /** Bumped when [generated] changes so the screen re-reads it. */
    var generatedVersion by mutableIntStateOf(0)
        private set
    val custom = SecureChars()
    val customRepeat = SecureChars()
    var confirmedWrittenDown by mutableStateOf(false)
    var exportedCount: Int? by mutableStateOf(null)

    fun regenerate() {
        generated.fill('\u0000')
        generated = PassphraseGenerator.generate()
        generatedVersion++
        confirmedWrittenDown = false
    }

    @StringRes
    fun validationError(): Int? = when {
        useGenerated -> null
        custom.length < MIN_CUSTOM_LENGTH -> R.string.backup_passphrase_too_short
        !custom.sameAs(customRepeat) -> R.string.backup_passphrase_mismatch
        else -> null
    }

    fun canExport(): Boolean = confirmedWrittenDown && validationError() == null

    /** Copy of the chosen passphrase; the caller wipes it. */
    fun passphraseCopy(): CharArray = if (useGenerated) generated.copyOf() else custom.copy()

    fun wipe() {
        generated.fill('\u0000')
        custom.wipe()
        customRepeat.wipe()
    }

    companion object {
        const val MIN_CUSTOM_LENGTH = 12
    }
}

/** "Restore from file" screen: pick file, enter passphrase, preview, import. */
class BackupImportState {
    var file: ByteArray? by mutableStateOf(null)
    var fileName: String? by mutableStateOf(null)
    val passphrase = SecureChars()

    /** Decrypted entries waiting to be imported (plaintext: wiped on leave/lock). */
    var preview: List<BackupFormat.Entry>? by mutableStateOf(null)
    var skipped by mutableIntStateOf(0)
    var imported by mutableIntStateOf(0)
    var done by mutableStateOf(false)

    fun wipe() {
        preview?.forEach { it.wipe() }
        preview = null
        passphrase.wipe()
        file = null
    }
}

/** What to do once a layer-2 entry has been opened with its passphrase. */
enum class Layer2Action { VIEW, EDIT, QUIZ }

/** Dialog asking for an entry's layer-2 passphrase. Holds the still-sealed envelope. */
class Layer2Prompt(val entryId: String, val envelope: ByteArray, val action: Layer2Action) {
    val passphrase = SecureChars()
    var wrongAttempts by mutableIntStateOf(0)

    fun wipe() {
        envelope.fill(0)
        passphrase.wipe()
    }

    companion object {
        /** After this many wrong passphrases the envelope is dropped and Knox Vault must authorise again. */
        const val MAX_ATTEMPTS = 5
    }
}
