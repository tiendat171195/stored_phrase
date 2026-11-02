package io.github.tiendat171195.storedphrase.ui

import android.app.Application
import android.app.KeyguardManager
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.StoredPhraseApp
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
import io.github.tiendat171195.storedphrase.core.vault.SeedSecret
import io.github.tiendat171195.storedphrase.core.vault.VaultIndex
import io.github.tiendat171195.storedphrase.security.AuthException
import io.github.tiendat171195.storedphrase.security.Authenticator
import io.github.tiendat171195.storedphrase.security.DeviceReport
import io.github.tiendat171195.storedphrase.security.NoLockScreenException
import io.github.tiendat171195.storedphrase.security.NoStrongBoxException
import io.github.tiendat171195.storedphrase.security.VaultCorruptedException
import io.github.tiendat171195.storedphrase.security.VaultCrypto
import io.github.tiendat171195.storedphrase.security.VaultKeyInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

sealed interface Screen {
    data object DeviceCheck : Screen
    data object Locked : Screen
    data object VaultList : Screen
    data object KeyInvalidated : Screen
    data object Settings : Screen
    data class EntryDetail(val id: String) : Screen
    data class Editor(val id: String?) : Screen
    data class Quiz(val id: String) : Screen
}

data class UiMessage(@StringRes val text: Int, val arg: String? = null)

/**
 * Holds navigation and the unlocked session. Everything decrypted lives here only while
 * unlocked and is wiped on [lockNow].
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as StoredPhraseApp).container
    private val repository = container.repository
    private val session = container.session
    private val random = SecureRandom()
    val allowTeeFallback: Boolean = container.allowTeeFallback

    var backStack: List<Screen> by mutableStateOf(listOf(initialScreen()))
        private set
    val screen: Screen get() = backStack.last()

    var index: VaultIndex? by mutableStateOf(null)
        private set
    var keyReport: VaultCrypto.KeyReport? by mutableStateOf(null)
        private set
    var deviceReport: DeviceReport? by mutableStateOf(null)
        private set
    var deviceCheckRunning: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var message: UiMessage? by mutableStateOf(null)
        private set
    var reveal: RevealSession? by mutableStateOf(null)
        private set
    var draft: SeedDraft? by mutableStateOf(null)
        private set
    var quiz: QuizSession? by mutableStateOf(null)
        private set
    var autoLockSeconds: Int by mutableIntStateOf(container.settings.autoLockSeconds)
        private set

    private var revealTimer: Job? = null

    val vaultExists: Boolean get() = repository.vaultExists()

    init {
        viewModelScope.launch { session.lockRequests.collect { lockNow() } }
        viewModelScope.launch {
            while (isActive) {
                delay(IDLE_CHECK_INTERVAL_MS)
                session.checkIdle()
            }
        }
        viewModelScope.launch { keyReport = withContext(Dispatchers.IO) { repository.keyReport() } }
    }

    private fun initialScreen(): Screen = if (repository.vaultExists()) Screen.Locked else Screen.DeviceCheck

    fun entry(id: String): IndexEntry? = index?.find(id)

    // region Navigation

    fun navigate(screen: Screen) {
        backStack = backStack + screen
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        when (screen) {
            is Screen.Editor -> clearDraft()
            is Screen.EntryDetail -> clearReveal()
            is Screen.Quiz -> clearQuiz()
            else -> Unit
        }
        backStack = backStack.dropLast(1)
        return true
    }

    private fun resetTo(screen: Screen) {
        backStack = listOf(screen)
    }

    fun openEntry(id: String) {
        clearReveal()
        navigate(Screen.EntryDetail(id))
    }

    fun openSettings() = navigate(Screen.Settings)

    fun openDeviceCheck() {
        deviceReport = null
        navigate(Screen.DeviceCheck)
    }

    // endregion

    // region Device check and vault lifecycle

    fun runDeviceCheck() {
        if (deviceCheckRunning) return
        viewModelScope.launch {
            deviceCheckRunning = true
            try {
                deviceReport = withContext(Dispatchers.IO) { container.probe.run() }
            } finally {
                deviceCheckRunning = false
            }
        }
    }

    fun createVault(auth: Authenticator) = launchBusy {
        val keyguard = getApplication<Application>().getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) throw NoLockScreenException()
        keyReport = repository.createVault()
        unlockInternal(auth)
    }

    fun unlock(auth: Authenticator) = launchBusy { unlockInternal(auth) }

    private suspend fun unlockInternal(auth: Authenticator) {
        index = repository.unlock(auth)
        session.onUnlocked()
        keyReport = withContext(Dispatchers.IO) { repository.keyReport() }
        resetTo(Screen.VaultList)
    }

    fun lock() = session.requestLock()

    /** Wipes every decrypted object and returns to the lock screen. */
    private fun lockNow() {
        clearReveal()
        clearDraft()
        clearQuiz()
        index = null
        session.onLocked()
        resetTo(if (repository.vaultExists()) Screen.Locked else Screen.DeviceCheck)
    }

    fun wipeAndStartOver() = launchBusy {
        clearReveal()
        clearDraft()
        clearQuiz()
        repository.wipe()
        index = null
        keyReport = null
        session.onLocked()
        deviceReport = null
        resetTo(Screen.DeviceCheck)
    }

    // endregion

    // region Reveal

    fun reveal(auth: Authenticator, id: String) = launchBusy {
        val entry = entry(id) ?: return@launchBusy
        val secret = repository.reveal(auth, entry)
        clearReveal()
        reveal = RevealSession(id, secret)
        startRevealTimer()
    }

    fun touchReveal() {
        reveal?.secondsLeft = RevealSession.REVEAL_SECONDS
    }

    fun hideReveal() = clearReveal()

    private fun startRevealTimer() {
        revealTimer?.cancel()
        revealTimer = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = reveal ?: break
                current.secondsLeft -= 1
                if (current.secondsLeft <= 0) {
                    clearReveal()
                    break
                }
            }
        }
    }

    private fun clearReveal() {
        revealTimer?.cancel()
        revealTimer = null
        reveal?.secret?.wipe()
        reveal = null
    }

    /** Uses the words already on screen if any, otherwise decrypts with a new authentication. */
    private suspend fun decryptedCopy(auth: Authenticator, entry: IndexEntry): SeedSecret =
        reveal?.takeIf { it.entryId == entry.id }?.secret?.copy() ?: repository.reveal(auth, entry)

    // endregion

    // region Editor

    fun startNewEntry() {
        clearDraft()
        val label = getApplication<Application>().getString(R.string.default_label, (index?.entries?.size ?: 0) + 1)
        draft = SeedDraft(editingId = null, label = label, wordCount = 12)
        navigate(Screen.Editor(null))
    }

    fun editEntry(auth: Authenticator, id: String) = launchBusy {
        val entry = entry(id) ?: return@launchBusy
        val secret = decryptedCopy(auth, entry)
        try {
            clearDraft()
            draft = SeedDraft(editingId = id, label = entry.label, wordCount = secret.wordCount).apply {
                input.load(secret.wordIndices)
                passphrase.set(java.nio.CharBuffer.wrap(secret.bip39Passphrase))
                notes.set(java.nio.CharBuffer.wrap(secret.notes))
                showOptional = secret.hasPassphrase || secret.hasNotes
            }
        } finally {
            secret.wipe()
        }
        clearReveal()
        navigate(Screen.Editor(id))
    }

    fun saveDraft(auth: Authenticator) = launchBusy {
        val current = draft ?: return@launchBusy
        val currentIndex = index ?: return@launchBusy
        if (!current.input.isChecksumValid()) return@launchBusy
        // Switching the optional section off means "no passphrase and no notes".
        val secret = SeedSecret(
            wordIndices = current.input.indices(),
            bip39Passphrase = if (current.showOptional) current.passphrase.copy() else CharArray(0),
            notes = if (current.showOptional) current.notes.copy() else CharArray(0),
        )
        try {
            val existing = current.editingId?.let { currentIndex.find(it) }
            val label = current.label.trim().ifEmpty {
                getApplication<Application>().getString(R.string.default_label, currentIndex.entries.size + 1)
            }
            index = repository.save(auth, currentIndex, existing, label.take(MAX_LABEL_LENGTH), secret)
        } finally {
            secret.wipe()
        }
        clearDraft()
        resetTo(Screen.VaultList)
    }

    fun discardDraft() {
        back()
    }

    private fun clearDraft() {
        draft?.wipe()
        draft = null
    }

    // endregion

    // region Delete

    fun deleteEntry(auth: Authenticator, id: String) = launchBusy {
        val currentIndex = index ?: return@launchBusy
        val entry = currentIndex.find(id) ?: return@launchBusy
        clearReveal()
        index = repository.delete(auth, currentIndex, entry)
        resetTo(Screen.VaultList)
    }

    // endregion

    // region Paper-copy quiz

    fun startQuiz(auth: Authenticator, id: String) = launchBusy {
        val entry = entry(id) ?: return@launchBusy
        val secret = decryptedCopy(auth, entry)
        clearReveal() // words stay hidden during the quiz
        clearQuiz()
        quiz = QuizSession(id, secret, randomPositions(secret.wordCount))
        navigate(Screen.Quiz(id))
    }

    fun retryQuiz() {
        val current = quiz ?: return
        val entry = entry(current.entryId) ?: return
        // Keep the decrypted words, ask different positions.
        quiz = QuizSession(current.entryId, current.copySecret(), randomPositions(entry.wordCount))
        current.wipe()
    }

    fun completeQuiz(auth: Authenticator) = launchBusy {
        val current = quiz ?: return@launchBusy
        if (current.passed) {
            val currentIndex = index ?: return@launchBusy
            val entry = currentIndex.find(current.entryId) ?: return@launchBusy
            index = repository.markVerified(auth, currentIndex, entry)
        }
        back()
    }

    private fun clearQuiz() {
        quiz?.wipe()
        quiz = null
    }

    private fun randomPositions(wordCount: Int): IntArray {
        val count = if (wordCount >= 18) 4 else 3
        val all = (0 until wordCount).toMutableList()
        for (i in all.indices.reversed()) {
            val j = random.nextInt(i + 1)
            all[i] = all[j].also { all[j] = all[i] }
        }
        return all.take(count).sorted().toIntArray()
    }

    // endregion

    // region Settings

    fun setAutoLock(seconds: Int) {
        container.settings.autoLockSeconds = seconds
        autoLockSeconds = seconds
    }

    // endregion

    fun dismissMessage() {
        message = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleError(e)
            } finally {
                busy = false
            }
        }
    }

    private fun handleError(e: Exception) {
        message = when (e) {
            is AuthException -> when {
                e.isUserCancel -> null
                e.isLockout -> UiMessage(R.string.error_auth_lockout)
                else -> UiMessage(R.string.error_auth_failed, e.message)
            }
            is VaultKeyInvalidatedException -> {
                clearReveal()
                clearDraft()
                clearQuiz()
                index = null
                session.onLocked()
                resetTo(Screen.KeyInvalidated)
                null
            }
            is VaultCorruptedException -> UiMessage(R.string.error_corrupted)
            is NoStrongBoxException -> UiMessage(R.string.error_no_strongbox)
            is NoLockScreenException -> UiMessage(R.string.error_no_lock_screen)
            else -> UiMessage(R.string.error_generic, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    override fun onCleared() {
        clearReveal()
        clearDraft()
        clearQuiz()
    }

    private companion object {
        const val IDLE_CHECK_INTERVAL_MS = 5_000L
        const val MAX_LABEL_LENGTH = 60
    }
}
