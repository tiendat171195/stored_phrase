package io.github.tiendat171195.storedphrase.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Definitions of the keys that live in StrongBox (Knox Vault on Samsung Galaxy devices).
 */
object KnoxVaultKeys {
    const val KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Encrypts the vault index (labels and metadata). Usable for a short time after authentication. */
    const val ALIAS_INDEX = "sp_index_v1"

    /** Encrypts seed content. Every single operation needs its own authentication. */
    const val ALIAS_SECRET = "sp_secret_v1"

    /** Temporary keys used by the device check. */
    const val ALIAS_PROBE = "sp_probe"
    const val ALIAS_PROBE_AUTH = "sp_probe_auth"

    const val INDEX_AUTH_VALIDITY_SECONDS = 60
    const val AUTH_TYPES = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

    /**
     * @property strongBox key generated inside StrongBox (Knox Vault) instead of the TEE.
     * @property unlockedDeviceRequired key unusable while the phone is locked. Some StrongBox
     *   implementations may reject this flag; the app then falls back to a key without it.
     */
    data class KeyConfig(val strongBox: Boolean, val unlockedDeviceRequired: Boolean)

    fun indexSpec(config: KeyConfig, alias: String = ALIAS_INDEX): KeyGenParameterSpec =
        base(alias, config)
            .setUserAuthenticationParameters(INDEX_AUTH_VALIDITY_SECONDS, AUTH_TYPES)
            .build()

    fun secretSpec(config: KeyConfig, alias: String = ALIAS_SECRET): KeyGenParameterSpec =
        base(alias, config)
            // 0 = authentication is bound to each individual operation (BiometricPrompt CryptoObject).
            .setUserAuthenticationParameters(0, AUTH_TYPES)
            .build()

    /** Plain StrongBox AES key without user authentication, used only to test encryption in the chip. */
    fun probeSpec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(ALIAS_PROBE, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(true)
            .build()

    private fun base(alias: String, config: KeyConfig): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // Enrolling or removing a fingerprint must not destroy the vault.
            .setInvalidatedByBiometricEnrollment(false)
            .setUnlockedDeviceRequired(config.unlockedDeviceRequired)
            .setIsStrongBoxBacked(config.strongBox)
}
