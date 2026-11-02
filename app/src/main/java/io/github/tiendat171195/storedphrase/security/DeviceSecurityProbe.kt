package io.github.tiendat171195.storedphrase.security

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import io.github.tiendat171195.storedphrase.security.KnoxVaultKeys.KeyConfig
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

enum class BiometricStatus { AVAILABLE, NOT_ENROLLED, UNAVAILABLE }

data class ProbeResult(
    val ok: Boolean,
    val securityLevel: Int? = null,
    val detail: String? = null,
) {
    val isStrongBox: Boolean get() = securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
}

data class DeviceReport(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val hasStrongBoxFeature: Boolean,
    val isDeviceSecure: Boolean,
    val biometric: BiometricStatus,
    val canAuthenticate: Boolean,
    /** AES-GCM encrypt + decrypt with a temporary key inside StrongBox. */
    val strongBoxRoundTrip: ProbeResult,
    /** Creating a StrongBox key with the vault's authentication settings (null without a screen lock). */
    val protectedKey: ProbeResult?,
    /** Strongest key configuration that worked in [protectedKey], if any. */
    val protectedKeyConfig: KeyConfig?,
) {
    val isSamsung: Boolean get() = manufacturer.equals("samsung", ignoreCase = true)

    /** All requirements for a StrongBox (Knox Vault) vault are met. */
    val strongBoxReady: Boolean
        get() = isDeviceSecure && strongBoxRoundTrip.ok && strongBoxRoundTrip.isStrongBox &&
            protectedKey?.ok == true && protectedKey.isStrongBox
}

/** Read-only checks shown on the Device check screen. Uses temporary keys that are deleted afterwards. */
class DeviceSecurityProbe(private val context: Context) {

    fun run(): DeviceReport {
        val pm = context.packageManager
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val biometricManager = BiometricManager.from(context)
        val secure = keyguard?.isDeviceSecure == true

        val biometric = when (biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
        val canAuthenticate =
            biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

        val roundTrip = probeRoundTrip()
        var protectedConfig: KeyConfig? = null
        val protectedKey = if (secure) {
            // Same settings as the real secret key, strongest first.
            val withUdr = probeProtectedKey(KeyConfig(strongBox = true, unlockedDeviceRequired = true))
            if (withUdr.ok) {
                protectedConfig = KeyConfig(strongBox = true, unlockedDeviceRequired = true)
                withUdr
            } else {
                val withoutUdr = probeProtectedKey(KeyConfig(strongBox = true, unlockedDeviceRequired = false))
                if (withoutUdr.ok) {
                    protectedConfig = KeyConfig(strongBox = true, unlockedDeviceRequired = false)
                    withoutUdr.copy(detail = "unlockedDeviceRequired unsupported: ${withUdr.detail}")
                } else {
                    withUdr
                }
            }
        } else {
            null
        }

        return DeviceReport(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            hasStrongBoxFeature = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
            isDeviceSecure = secure,
            biometric = biometric,
            canAuthenticate = canAuthenticate,
            strongBoxRoundTrip = roundTrip,
            protectedKey = protectedKey,
            protectedKeyConfig = protectedConfig,
        )
    }

    private fun probeRoundTrip(): ProbeResult = try {
        generate(KnoxVaultKeys.probeSpec())
        val key = keyStore().getKey(KnoxVaultKeys.ALIAS_PROBE, null) as SecretKey
        val data = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val encrypt = Cipher.getInstance(KnoxVaultKeys.TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = encrypt.doFinal(data)
        val decrypt = Cipher.getInstance(KnoxVaultKeys.TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypt.iv))
        }
        val ok = decrypt.doFinal(ciphertext).contentEquals(data)
        ProbeResult(ok = ok, securityLevel = securityLevel(key))
    } catch (e: Exception) {
        ProbeResult(ok = false, detail = describe(e))
    } finally {
        delete(KnoxVaultKeys.ALIAS_PROBE)
    }

    private fun probeProtectedKey(config: KeyConfig): ProbeResult = try {
        generate(KnoxVaultKeys.secretSpec(config, alias = KnoxVaultKeys.ALIAS_PROBE_AUTH))
        val key = keyStore().getKey(KnoxVaultKeys.ALIAS_PROBE_AUTH, null) as SecretKey
        ProbeResult(ok = true, securityLevel = securityLevel(key))
    } catch (e: Exception) {
        ProbeResult(ok = false, detail = describe(e))
    } finally {
        delete(KnoxVaultKeys.ALIAS_PROBE_AUTH)
    }

    private fun describe(e: Exception): String = buildString {
        append(e.javaClass.simpleName)
        e.message?.let { append(": ").append(it.take(160)) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KnoxVaultKeys.KEYSTORE).apply { load(null) }

    private fun generate(spec: android.security.keystore.KeyGenParameterSpec) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KnoxVaultKeys.KEYSTORE).apply {
            init(spec)
            generateKey()
        }
    }

    private fun securityLevel(key: SecretKey): Int {
        val factory = SecretKeyFactory.getInstance(key.algorithm, KnoxVaultKeys.KEYSTORE)
        return (factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo).securityLevel
    }

    private fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }
}
