package io.github.tiendat171195.storedphrase.security

import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat
import io.github.tiendat171195.storedphrase.core.vault.BlobFormat.KeySlot
import io.github.tiendat171195.storedphrase.data.SettingsStore
import io.github.tiendat171195.storedphrase.security.KnoxVaultKeys.KeyConfig
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Creates the vault keys in StrongBox and hands out ciphers bound to them.
 * Key material never leaves the secure hardware; this class only holds references.
 */
class VaultCrypto(
    private val allowTeeFallback: Boolean,
    private val settings: SettingsStore,
) {
    data class KeyReport(
        val config: KeyConfig,
        /** Lowest [KeyProperties] SECURITY_LEVEL_* of the two vault keys. */
        val securityLevel: Int,
    ) {
        val isStrongBox: Boolean get() = securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KnoxVaultKeys.KEYSTORE).apply { load(null) } }

    fun keysExist(): Boolean =
        keyStore.containsAlias(KnoxVaultKeys.ALIAS_INDEX) && keyStore.containsAlias(KnoxVaultKeys.ALIAS_SECRET)

    /**
     * Generates both vault keys, strongest configuration first:
     * StrongBox + unlocked-device-required, then StrongBox alone, then (debug only) TEE.
     */
    fun createKeys(): KeyReport {
        deleteKeys()
        val candidates = buildList {
            add(KeyConfig(strongBox = true, unlockedDeviceRequired = true))
            add(KeyConfig(strongBox = true, unlockedDeviceRequired = false))
            if (allowTeeFallback) add(KeyConfig(strongBox = false, unlockedDeviceRequired = true))
        }
        var lastError: Exception? = null
        for (config in candidates) {
            try {
                generate(KnoxVaultKeys.indexSpec(config))
                generate(KnoxVaultKeys.secretSpec(config))
                val report = KeyReport(config, minOf(securityLevel(KnoxVaultKeys.ALIAS_INDEX), securityLevel(KnoxVaultKeys.ALIAS_SECRET)))
                if (!allowTeeFallback && !report.isStrongBox) {
                    deleteKeys()
                    throw NoStrongBoxException()
                }
                settings.keyConfig = config
                return report
            } catch (e: StrongBoxUnavailableException) {
                lastError = e
            } catch (e: ProviderException) {
                lastError = e
            } catch (e: GeneralSecurityException) {
                lastError = e
            }
            deleteKeys()
        }
        if (!allowTeeFallback) throw NoStrongBoxException(lastError)
        throw lastError ?: IllegalStateException("Key generation failed")
    }

    /** Current protection of the existing keys, or null when there is no vault. */
    fun currentReport(): KeyReport? {
        if (!keysExist()) return null
        val config = settings.keyConfig ?: KeyConfig(strongBox = false, unlockedDeviceRequired = false)
        return try {
            KeyReport(config, minOf(securityLevel(KnoxVaultKeys.ALIAS_INDEX), securityLevel(KnoxVaultKeys.ALIAS_SECRET)))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Cipher ready to encrypt. For [KeySlot.SECRET] it still needs BiometricPrompt before use. */
    fun encryptCipher(slot: KeySlot): Cipher = initCipher { cipher ->
        cipher.init(Cipher.ENCRYPT_MODE, key(slot))
    }

    fun decryptCipher(slot: KeySlot, iv: ByteArray): Cipher = initCipher { cipher ->
        cipher.init(Cipher.DECRYPT_MODE, key(slot), GCMParameterSpec(BlobFormat.TAG_LENGTH_BITS, iv))
    }

    /** Destroys the keys inside the secure hardware. All ciphertext becomes unreadable. */
    fun deleteKeys() {
        for (alias in listOf(KnoxVaultKeys.ALIAS_INDEX, KnoxVaultKeys.ALIAS_SECRET)) {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
        settings.keyConfig = null
    }

    private inline fun initCipher(init: (Cipher) -> Unit): Cipher {
        val cipher = Cipher.getInstance(KnoxVaultKeys.TRANSFORMATION)
        try {
            init(cipher)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultKeyInvalidatedException(e)
        } catch (e: UnrecoverableKeyException) {
            throw VaultKeyInvalidatedException(e)
        }
        return cipher
    }

    private fun key(slot: KeySlot): SecretKey {
        val alias = when (slot) {
            KeySlot.INDEX -> KnoxVaultKeys.ALIAS_INDEX
            KeySlot.SECRET -> KnoxVaultKeys.ALIAS_SECRET
        }
        return keyStore.getKey(alias, null) as? SecretKey ?: throw VaultKeyInvalidatedException()
    }

    private fun securityLevel(alias: String): Int {
        val key = keyStore.getKey(alias, null) as SecretKey
        val factory = SecretKeyFactory.getInstance(key.algorithm, KnoxVaultKeys.KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.securityLevel
    }

    private fun generate(spec: android.security.keystore.KeyGenParameterSpec) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KnoxVaultKeys.KEYSTORE).apply {
            init(spec)
            generateKey()
        }
    }
}
