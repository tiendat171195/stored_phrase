package io.github.tiendat171195.storedphrase.data

import android.content.Context
import androidx.core.content.edit
import io.github.tiendat171195.storedphrase.security.KnoxVaultKeys.KeyConfig

/** Non-secret preferences. Excluded from backup and device transfer like everything else. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoLockSeconds: Int
        get() = prefs.getInt(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK_SECONDS)
        set(value) = prefs.edit { putInt(KEY_AUTO_LOCK, value) }

    /** How the current vault keys were created (KeyInfo does not expose every flag). */
    var keyConfig: KeyConfig?
        get() = if (!prefs.contains(KEY_STRONGBOX)) {
            null
        } else {
            KeyConfig(
                strongBox = prefs.getBoolean(KEY_STRONGBOX, false),
                unlockedDeviceRequired = prefs.getBoolean(KEY_UDR, false),
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_STRONGBOX)
                remove(KEY_UDR)
            } else {
                putBoolean(KEY_STRONGBOX, value.strongBox)
                putBoolean(KEY_UDR, value.unlockedDeviceRequired)
            }
        }

    companion object {
        const val DEFAULT_AUTO_LOCK_SECONDS = 120
        val AUTO_LOCK_CHOICES = listOf(30, 60, 120, 300)
        private const val KEY_AUTO_LOCK = "auto_lock_seconds"
        private const val KEY_STRONGBOX = "key_strongbox"
        private const val KEY_UDR = "key_unlocked_device_required"
    }
}
