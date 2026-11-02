package io.github.tiendat171195.storedphrase

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tiendat171195.storedphrase.security.PromptAuthenticator
import io.github.tiendat171195.storedphrase.security.SecureWindow
import io.github.tiendat171195.storedphrase.ui.AppViewModel
import io.github.tiendat171195.storedphrase.ui.LocalAuthenticator
import io.github.tiendat171195.storedphrase.ui.StoredPhraseRoot
import io.github.tiendat171195.storedphrase.ui.theme.StoredPhraseTheme

/**
 * Single activity. Extends FragmentActivity because androidx.biometric's BiometricPrompt needs it.
 */
class MainActivity : FragmentActivity() {

    private val container: AppContainer
        get() = (application as StoredPhraseApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.applyTo(this)
        enableEdgeToEdge()
        val authenticator = PromptAuthenticator(this, container.session)
        setContent {
            StoredPhraseTheme {
                CompositionLocalProvider(LocalAuthenticator provides authenticator) {
                    val vm: AppViewModel = viewModel()
                    StoredPhraseRoot(vm)
                }
            }
        }
        SecureWindow.hardenContent(this)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.session.touch()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app or turning the screen off locks the vault (unless a system prompt caused it).
        if (!isChangingConfigurations) container.session.onAppBackgrounded()
    }
}
