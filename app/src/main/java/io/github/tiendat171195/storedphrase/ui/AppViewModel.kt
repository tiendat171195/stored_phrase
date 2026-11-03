package io.github.tiendat171195.storedphrase.ui

import android.app.Application
import android.app.KeyguardManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tiendat171195.storedphrase.BuildConfig
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.StoredPhraseApp
import io.github.tiendat171195.storedphrase.core.attestation.AttestationReport
import io.github.tiendat171195.storedphrase.data.VaultRepository
import io.github.tiendat171195.storedphrase.core.backup.BackupFormat
import io.github.tiendat171195.storedphrase.core.backup.BackupFormatException
import io.github.tiendat171195.storedphrase.core.backup.WrongPassphraseException
import io.github.tiendat171195.storedphrase.core.bip39.Mnemonic
import io.github.tiendat171195.storedphrase.core.vault.EntryCodec
import io.github.tiendat171195.storedphrase.core.vault.EntryFormatException
import io.github.tiendat171195.storedphrase.core.vault.IndexEntry
import io.github.tiendat171195.storedphrase.core.vault.Layer2Envelope
import io.github.tiendat171195.storedphrase.core.vault.Layer2WrongPassphraseException
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
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object DeviceCheck : Screen
    data object Locked : Screen
    data object VaultList : Screen
    data object KeyInvalidated : Screen
    data object Settings : Screen
    data object BackupExport : Screen
    data object BackupImport : Screen
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

    /** Last key attestation verification (device check, or once after unlocking). */
    var attestation: AttestationReport? by mutableStateOf(null)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var progress: ProgressInfo? by mutableStateOf(null)
        private set
    var message: UiMessage? by mutableStateOf(null)
        private set
    var reveal: RevealSession? by mutableStateOf(null)
        private set
    var draft: SeedDraft? by mutableStateOf(null)
        private set
    var quiz: QuizSession? by mutableStateOf(null)
        private set
    var backupExport: BackupExportState? by mutableStateOf(null)
        private set
    var backupImport: BackupImportState? by mutableStateOf(null)
        private set
    var layer2Prompt: Layer2Prompt? by mutableStateOf(null)
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
            Screen.BackupExport -> clearBackupExport()
            Screen.BackupImport -> clearBackupImport()
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

    /** Pickers and system prompts temporarily cover the app; they must not trigger auto-lock. */
    fun beginExternalFlow() = session.beginExternalFlow()

    fun endExternalFlow() = session.endExternalFlow()

    // endregion

    // region Device check and vault lifecycle

    fun runDeviceCheck() {
        if (deviceCheckRunning) return
        viewModelScope.launch {
            deviceCheckRunning = true
            try {
                deviceReport = withContext(Dispatchers.IO) { container.probe.run() }
                attestation = withContext(Dispatchers.IO) { container.attestation.run() }
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
        if (attestation == null) {
            viewModelScope.launch { attestation = withContext(Dispatchers.IO) { container.attestation.run() } }
        }
    }

    fun lock() = session.requestLock()

    /** Wipes every decrypted object and returns to the lock screen. */
    private fun lockNow() {
        clearSensitive()
        index = null
        session.onLocked()
        resetTo(if (repository.vaultExists()) Screen.Locked else Screen.DeviceCheck)
    }

    fun wipeAndStartOver() = launchBusy {
        clearSensitive()
        repository.wipe()
        index = null
        keyReport = null
        session.onLocked()
        deviceReport = null
        resetTo(Screen.DeviceCheck)
    }

    private fun clearSensitive() {
        cancelLayer2()
        clearReveal()
        clearDraft()
        clearQuiz()
        clearBackupExport()
        clearBackupImport()
        progress = null
    }

    // endregion

    // region Reveal

    fun reveal(auth: Authenticator, id: String) = launchBusy {
        val entry = entry(id) ?: return@launchBusy
        val secret = obtainSecret(auth, entry, Layer2Action.VIEW) ?: return@launchBusy
        showReveal(id, secret)
    }

    private fun showReveal(id: String, secret: SeedSecret) {
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

    /**
     * Words already on screen if any, otherwise decrypted with a new authentication.
     * Returns null for a layer-2 entry: its passphrase dialog is shown and [action] continues after it.
     */
    private suspend fun obtainSecret(auth: Authenticator, entry: IndexEntry, action: Layer2Action): SeedSecret? {
        reveal?.takeIf { it.entryId == entry.id }?.let { return it.secret.copy() }
        return when (val result = repository.reveal(auth, entry)) {
            is VaultRepository.RevealResult.Plain -> result.secret
            is VaultRepository.RevealResult.Locked -> {
                cancelLayer2()
                layer2Prompt = Layer2Prompt(entry.id, result.envelope, action)
                null
            }
        }
    }

    // endregion

    // region Layer-2 passphrase

    fun submitLayer2() = launchBusy {
        val prompt = layer2Prompt ?: return@launchBusy
        val entry = entry(prompt.entryId) ?: return@launchBusy cancelLayer2()
        val passphrase = prompt.passphrase.copy()
        val secret = try {
            repository.openLayer2(entry, prompt.envelope, passphrase)
        } catch (e: Layer2WrongPassphraseException) {
            prompt.wrongAttempts += 1
            if (prompt.wrongAttempts >= Layer2Prompt.MAX_ATTEMPTS) {
                // Drop the envelope: trying again needs a new Knox Vault authentication.
                cancelLayer2()
                message = UiMessage(R.string.layer2_too_many_attempts)
            }
            return@launchBusy
        } finally {
            passphrase.fill('\u0000')
        }
        val action = prompt.action
        cancelLayer2()
        when (action) {
            Layer2Action.VIEW -> showReveal(entry.id, secret)
            Layer2Action.EDIT -> openEditor(entry, secret)
            Layer2Action.QUIZ -> openQuiz(entry, secret)
        }
    }

    fun cancelLayer2() {
        layer2Prompt?.wipe()
        layer2Prompt = null
    }

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
        val secret = obtainSecret(auth, entry, Layer2Action.EDIT) ?: return@launchBusy
        openEditor(entry, secret)
    }

    /** Takes ownership of [secret] (wiped here). */
    private fun openEditor(entry: IndexEntry, secret: SeedSecret) {
        try {
            clearDraft()
            draft = SeedDraft(editingId = entry.id, label = entry.label, wordCount = secret.wordCount).apply {
                input.load(secret.wordIndices)
                passphrase.set(java.nio.CharBuffer.wrap(secret.bip39Passphrase))
                notes.set(java.nio.CharBuffer.wrap(secret.notes))
                birthday = secret.birthdayHeight?.toString().orEmpty()
                showOptional = secret.hasPassphrase || secret.hasNotes || secret.hasBirthday
                // The layer-2 passphrase is never kept: it must be typed again (same or new) to save.
                layer2Enabled = entry.layer2
            }
        } finally {
            secret.wipe()
        }
        clearReveal()
        navigate(Screen.Editor(entry.id))
    }

    fun saveDraft(auth: Authenticator) = launchBusy {
        val current = draft ?: return@launchBusy
        val currentIndex = index ?: return@launchBusy
        // Switching the optional section off means "no passphrase, birthday or notes".
        val optional = current.showOptional
        if (!current.input.isChecksumValid() || (optional && !current.birthdayValid) || current.layer2Error() != null) {
            return@launchBusy
        }
        val secret = SeedSecret(
            wordIndices = current.input.indices(),
            bip39Passphrase = if (optional) current.passphrase.copy() else CharArray(0),
            notes = if (optional) current.notes.copy() else CharArray(0),
            birthdayHeight = if (optional) current.birthdayHeight else null,
        )
        val layer2 = if (current.layer2Enabled) current.layer2Passphrase.copy() else null
        try {
            val existing = current.editingId?.let { currentIndex.find(it) }
            val label = current.label.trim().ifEmpty {
                getApplication<Application>().getString(R.string.default_label, currentIndex.entries.size + 1)
            }
            if (layer2 != null) progress = ProgressInfo(R.string.layer2_sealing)
            index = repository.save(auth, currentIndex, existing, label.take(MAX_LABEL_LENGTH), secret, layer2)
        } finally {
            secret.wipe()
            layer2?.fill('\u0000')
            progress = null
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
        val secret = obtainSecret(auth, entry, Layer2Action.QUIZ) ?: return@launchBusy
        openQuiz(entry, secret)
    }

    /** Takes ownership of [secret]. */
    private fun openQuiz(entry: IndexEntry, secret: SeedSecret) {
        clearReveal() // words stay hidden during the quiz
        clearQuiz()
        quiz = QuizSession(entry.id, secret, randomPositions(secret.wordCount))
        navigate(Screen.Quiz(entry.id))
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

    // region Backup export

    fun openBackupExport() {
        clearBackupExport()
        backupExport = BackupExportState().apply { regenerate() }
        navigate(Screen.BackupExport)
    }

    fun backupFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        return "stored-phrase-backup-$date.${BackupFormat.FILE_EXTENSION}"
    }

    /** Decrypts every entry (one prompt each), encrypts them with the passphrase, writes [uri]. */
    fun exportBackup(auth: Authenticator, uri: Uri) = launchBusy {
        val state = backupExport ?: return@launchBusy
        val currentIndex = index ?: return@launchBusy
        if (!state.canExport()) return@launchBusy
        val passphrase = state.passphraseCopy()
        var entries: List<BackupFormat.Entry> = emptyList()
        try {
            entries = repository.exportEntries(auth, currentIndex) { done, total ->
                progress = ProgressInfo(R.string.backup_progress_decrypt, done, total)
            }
            progress = ProgressInfo(R.string.backup_progress_encrypt)
            val backup = BackupFormat.Backup(
                manifest = BackupFormat.Manifest(createdAt = System.currentTimeMillis(), generator = "Stored Phrase ${BuildConfig.VERSION_NAME}"),
                entries = entries,
            )
            val file = withContext(Dispatchers.Default) { BackupFormat.encrypt(backup, passphrase) }
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")).use { it.write(file) }
            }
            state.exportedCount = entries.size
        } finally {
            entries.forEach { it.wipe() }
            passphrase.fill('\u0000')
            progress = null
        }
    }

    private fun clearBackupExport() {
        backupExport?.wipe()
        backupExport = null
    }

    // endregion

    // region Backup import

    fun openBackupImport() {
        clearBackupImport()
        backupImport = BackupImportState()
        navigate(Screen.BackupImport)
    }

    fun loadBackupFile(uri: Uri) = launchBusy {
        val state = backupImport ?: return@launchBusy
        val resolver = getApplication<Application>().contentResolver
        val bytes = withContext(Dispatchers.IO) {
            (resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > MAX_BACKUP_BYTES) throw BackupFormatException("File too large")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        BackupFormat.readHeader(bytes) // fail fast for files that are not backups
        state.preview?.forEach { it.wipe() }
        state.preview = null
        state.file = bytes
        state.fileName = withContext(Dispatchers.IO) { displayName(uri) }
    }

    fun decryptBackup() = launchBusy {
        val state = backupImport ?: return@launchBusy
        val file = state.file ?: return@launchBusy
        val passphrase = state.passphrase.copy()
        try {
            progress = ProgressInfo(R.string.backup_progress_unlock)
            val backup = withContext(Dispatchers.Default) { BackupFormat.decrypt(file, passphrase) }
            val (valid, invalid) = backup.entries.partition(::isImportable)
            invalid.forEach { it.wipe() }
            state.skipped = invalid.size
            state.preview = valid
            state.passphrase.wipe()
        } finally {
            passphrase.fill('\u0000')
            progress = null
        }
    }

    fun importBackup(auth: Authenticator) = launchBusy {
        val state = backupImport ?: return@launchBusy
        val entries = state.preview ?: return@launchBusy
        val currentIndex = index ?: return@launchBusy
        try {
            index = repository.importEntries(
                auth,
                currentIndex,
                entries,
                onProgress = { done, total -> progress = ProgressInfo(R.string.backup_progress_import, done, total) },
                onCommitted = { updated ->
                    index = updated
                    state.imported += 1
                },
            )
            state.done = true
            state.preview?.forEach { it.wipe() }
            state.preview = null
        } finally {
            progress = null
        }
    }

    /** A plain entry is imported only if it decodes and its checksum is valid. */
    private fun isImportable(entry: BackupFormat.Entry): Boolean = when (entry.kind) {
        BackupFormat.Kind.PLAIN -> try {
            val secret = EntryCodec.decode(entry.content)
            val ok = secret.wordCount == entry.meta.wordCount && Mnemonic.isValid(secret.wordIndices)
            secret.wipe()
            ok
        } catch (e: EntryFormatException) {
            false
        }
        // Still sealed with its own passphrase: only the envelope header can be checked.
        BackupFormat.Kind.LAYER2 -> try {
            Layer2Envelope.validate(entry.content)
            true
        } catch (e: EntryFormatException) {
            false
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    private fun clearBackupImport() {
        backupImport?.wipe()
        backupImport = null
    }

    // endregion

    // region Settings

    fun setAutoLock(seconds: Int) {
        container.settings.autoLockSeconds = seconds
        autoLockSeconds = seconds
    }

    // endregion

    fun showMessage(@StringRes text: Int) {
        message = UiMessage(text)
    }

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
                clearSensitive()
                index = null
                session.onLocked()
                resetTo(Screen.KeyInvalidated)
                null
            }
            is VaultCorruptedException -> UiMessage(R.string.error_corrupted)
            is NoStrongBoxException -> UiMessage(R.string.error_no_strongbox)
            is NoLockScreenException -> UiMessage(R.string.error_no_lock_screen)
            is WrongPassphraseException -> UiMessage(R.string.error_wrong_backup_passphrase)
            is BackupFormatException -> UiMessage(R.string.error_bad_backup_file)
            is IOException -> UiMessage(R.string.error_file_io, e.message)
            else -> UiMessage(R.string.error_generic, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    override fun onCleared() {
        clearSensitive()
    }

    private companion object {
        const val IDLE_CHECK_INTERVAL_MS = 5_000L
        const val MAX_LABEL_LENGTH = 60
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
    }
}
