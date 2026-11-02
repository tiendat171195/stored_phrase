package io.github.tiendat171195.storedphrase.session

import android.os.SystemClock
import io.github.tiendat171195.storedphrase.data.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicInteger

enum class LockReason { USER, BACKGROUND, IDLE }

/**
 * Decides when the unlocked vault must be locked again: app sent to background,
 * screen turned off (activity stopped) or no interaction for the configured time.
 */
class SessionManager(private val settings: SettingsStore) {
    private val _lockRequests = MutableSharedFlow<LockReason>(extraBufferCapacity = 8)
    val lockRequests: SharedFlow<LockReason> = _lockRequests

    @Volatile
    var isUnlocked: Boolean = false
        private set

    private val externalFlows = AtomicInteger(0)

    @Volatile
    private var inForeground = true

    @Volatile
    private var lastInteraction = SystemClock.elapsedRealtime()

    fun onUnlocked() {
        isUnlocked = true
        touch()
    }

    fun onLocked() {
        isUnlocked = false
    }

    fun touch() {
        lastInteraction = SystemClock.elapsedRealtime()
    }

    /** System prompts / pickers that temporarily cover the app must not trigger a lock. */
    fun beginExternalFlow() {
        externalFlows.incrementAndGet()
    }

    fun endExternalFlow() {
        if (externalFlows.decrementAndGet() < 0) externalFlows.set(0)
        touch()
        // The app was sent to the background while a prompt was open: lock now that it closed.
        if (!inForeground) onAppBackgrounded()
    }

    /** Called from Activity onStart/onStop. Going to the background (or screen off) locks. */
    fun onForegroundChanged(foreground: Boolean) {
        inForeground = foreground
        if (!foreground) onAppBackgrounded()
    }

    private fun onAppBackgrounded() {
        if (isUnlocked && externalFlows.get() == 0) _lockRequests.tryEmit(LockReason.BACKGROUND)
    }

    fun checkIdle() {
        if (!isUnlocked || externalFlows.get() > 0) return
        val idleMs = SystemClock.elapsedRealtime() - lastInteraction
        if (idleMs >= settings.autoLockSeconds * 1000L) _lockRequests.tryEmit(LockReason.IDLE)
    }

    fun requestLock() {
        _lockRequests.tryEmit(LockReason.USER)
    }
}
