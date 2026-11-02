package io.github.tiendat171195.storedphrase.ui

import android.app.Application
import android.app.KeyguardManager
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.StoredPhraseApp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object DeviceCheck : Screen
    data object Locked : Screen
    data object VaultList : Screen
    data object KeyInvalidated : Screen
}

data class UiMessage(@StringRes val text: Int, val arg: String? = null)

/**
 * Holds navigation and the unlocked session. Everything decrypted lives here only while
 * unlocked and is dropped on [lockNow].
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as StoredPhraseApp).container
    private val repository = container.repository
    private val session = container.session
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

    // region Navigation

    fun navigate(screen: Screen) {
        backStack = backStack + screen
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack = backStack.dropLast(1)
        return true
    }

    private fun resetTo(screen: Screen) {
        backStack = listOf(screen)
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

    /** Drops every decrypted object and returns to the lock screen. */
    private fun lockNow() {
        index = null
        session.onLocked()
        resetTo(if (repository.vaultExists()) Screen.Locked else Screen.DeviceCheck)
    }

    fun wipeAndStartOver() = launchBusy {
        repository.wipe()
        index = null
        keyReport = null
        session.onLocked()
        deviceReport = null
        resetTo(Screen.DeviceCheck)
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

    private companion object {
        const val IDLE_CHECK_INTERVAL_MS = 5_000L
    }
}
