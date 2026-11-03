package io.github.tiendat171195.storedphrase

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tiendat171195.storedphrase.security.AttestationProbe
import io.github.tiendat171195.storedphrase.security.DeviceSecurityProbe
import io.github.tiendat171195.storedphrase.security.KnoxVaultKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Runs on a real device (`./gradlew connectedDebugAndroidTest`).
 * Tests that need StrongBox or a screen lock are skipped on devices without them.
 */
@RunWith(AndroidJUnit4::class)
class KnoxVaultInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyStore = KeyStore.getInstance(KnoxVaultKeys.KEYSTORE).apply { load(null) }

    private fun assumeStrongBox() =
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))

    private fun assumeScreenLock() =
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)

    @After
    fun cleanUp() {
        if (keyStore.containsAlias(TEST_ALIAS)) keyStore.deleteEntry(TEST_ALIAS)
    }

    @Test
    fun encryptionRoundTripRunsInsideStrongBox() {
        assumeStrongBox()
        val report = DeviceSecurityProbe(context).run()
        assertTrue(report.strongBoxRoundTrip.detail ?: "round trip failed", report.strongBoxRoundTrip.ok)
        assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, report.strongBoxRoundTrip.securityLevel)
    }

    @Test
    fun vaultKeySettingsAreAcceptedByStrongBox() {
        assumeStrongBox()
        assumeScreenLock()
        val report = DeviceSecurityProbe(context).run()
        val protectedKey = requireNotNull(report.protectedKey)
        assertTrue(protectedKey.detail ?: "protected key failed", protectedKey.ok)
        assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, protectedKey.securityLevel)
    }

    @Test
    fun perOperationKeyCannotBeUsedWithoutAuthentication() {
        assumeStrongBox()
        assumeScreenLock()
        val spec = KeyGenParameterSpec.Builder(TEST_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KnoxVaultKeys.AUTH_TYPES)
            .setIsStrongBoxBacked(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KnoxVaultKeys.KEYSTORE).apply {
            init(spec)
            generateKey()
        }
        val key = keyStore.getKey(TEST_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(KnoxVaultKeys.TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // Without a BiometricPrompt bound to this operation the chip refuses to encrypt.
        assertThrows(Exception::class.java) { cipher.doFinal(ByteArray(16)) }
    }

    @Test
    fun strongBoxAttestationChainVerifiesAgainstGoogleRoots() {
        assumeStrongBox()
        val report = AttestationProbe(context).run()
        assertTrue(report.error ?: "chain", report.chainValid)
        assertTrue("root not trusted", report.rootTrusted)
        assertTrue("not StrongBox", report.strongBox)
        assertTrue("challenge", report.challengeMatches)
        assertTrue("application id", report.appIdMatches)
        // Bootloader / verified boot are reported but not asserted: test phones may be unlocked.
    }

    private companion object {
        const val TEST_ALIAS = "sp_instrumented_test"
    }
}
