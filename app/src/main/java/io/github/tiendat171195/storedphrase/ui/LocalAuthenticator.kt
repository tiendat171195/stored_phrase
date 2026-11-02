package io.github.tiendat171195.storedphrase.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.tiendat171195.storedphrase.security.Authenticator

/** Activity-bound BiometricPrompt wrapper, provided by MainActivity. */
val LocalAuthenticator = staticCompositionLocalOf<Authenticator> {
    error("No Authenticator provided")
}
