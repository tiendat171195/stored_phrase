package io.github.tiendat171195.storedphrase

import android.content.Context
import io.github.tiendat171195.storedphrase.data.SettingsStore
import io.github.tiendat171195.storedphrase.data.VaultFileStore
import io.github.tiendat171195.storedphrase.data.VaultRepository
import io.github.tiendat171195.storedphrase.security.AttestationProbe
import io.github.tiendat171195.storedphrase.security.DeviceSecurityProbe
import io.github.tiendat171195.storedphrase.security.VaultCrypto
import io.github.tiendat171195.storedphrase.session.SessionManager

/** Manual dependency wiring (no DI framework, fewer dependencies). */
class AppContainer(context: Context) {
    val allowTeeFallback: Boolean = BuildConfig.ALLOW_TEE_FALLBACK
    val settings = SettingsStore(context)
    val session = SessionManager(settings)
    val probe = DeviceSecurityProbe(context)
    val attestation = AttestationProbe(context)
    private val crypto = VaultCrypto(allowTeeFallback, settings)
    val repository = VaultRepository(VaultFileStore(context), crypto)
}
