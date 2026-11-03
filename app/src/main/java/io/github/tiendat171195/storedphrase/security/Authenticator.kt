package io.github.tiendat171195.storedphrase.security

import androidx.annotation.StringRes
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.tiendat171195.storedphrase.R
import io.github.tiendat171195.storedphrase.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AuthReason(@StringRes val title: Int, @StringRes val subtitle: Int) {
    UNLOCK(R.string.auth_unlock_title, R.string.auth_unlock_subtitle),
    REVEAL(R.string.auth_reveal_title, R.string.auth_reveal_subtitle),
    SAVE(R.string.auth_save_title, R.string.auth_save_subtitle),
    UPDATE(R.string.auth_update_title, R.string.auth_update_subtitle),
    BACKUP(R.string.auth_backup_title, R.string.auth_backup_subtitle),
    IMPORT(R.string.auth_import_title, R.string.auth_import_subtitle),
}

/** Asks the user to authenticate with a strong fingerprint or the device screen lock. */
fun interface Authenticator {
    /**
     * @param cryptoObject operation to authorise (per-use keys). Null for time-bound keys.
     * @param detail optional extra line, e.g. which entry of a backup is being processed.
     * @return the authorised CryptoObject, or null when none was passed.
     * @throws AuthException when the prompt is cancelled or fails.
     */
    suspend fun authenticate(
        reason: AuthReason,
        cryptoObject: BiometricPrompt.CryptoObject?,
        detail: String?,
    ): BiometricPrompt.CryptoObject?
}

class AuthException(val code: Int, message: String) : Exception(message) {
    val isUserCancel: Boolean
        get() = code == BiometricPrompt.ERROR_USER_CANCELED ||
            code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
            code == BiometricPrompt.ERROR_CANCELED

    val isLockout: Boolean
        get() = code == BiometricPrompt.ERROR_LOCKOUT || code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
}

/** System BiometricPrompt: Class 3 biometrics or the device PIN/pattern/password. */
class PromptAuthenticator(
    private val activity: FragmentActivity,
    private val session: SessionManager,
) : Authenticator {

    override suspend fun authenticate(
        reason: AuthReason,
        cryptoObject: BiometricPrompt.CryptoObject?,
        detail: String?,
    ): BiometricPrompt.CryptoObject? = withContext(Dispatchers.Main) {
        // The prompt (or the credential screen) may briefly stop our activity; don't auto-lock for it.
        session.beginExternalFlow()
        try {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(result.cryptoObject)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resumeWithException(AuthException(errorCode, errString.toString()))
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(reason.title))
                    .setSubtitle(activity.getString(reason.subtitle))
                    .setDescription(detail)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                    .setConfirmationRequired(false)
                    .build()
                if (cryptoObject != null) prompt.authenticate(info, cryptoObject) else prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        } finally {
            session.endExternalFlow()
        }
    }
}
