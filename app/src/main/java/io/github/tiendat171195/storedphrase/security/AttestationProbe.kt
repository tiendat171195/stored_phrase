package io.github.tiendat171195.storedphrase.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.tiendat171195.storedphrase.core.attestation.AttestationReport
import io.github.tiendat171195.storedphrase.core.attestation.AttestationVerifier
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Asks the secure hardware to vouch for a fresh key: generates a temporary EC P-256 key in
 * StrongBox with a random challenge, then verifies its attestation chain offline against the
 * embedded Google roots. (AES vault keys have no attestation; this probe key stands in.)
 */
class AttestationProbe(private val context: Context) {

    fun run(): AttestationReport {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keyStore = KeyStore.getInstance(KnoxVaultKeys.KEYSTORE).apply { load(null) }
        return try {
            val chain = try {
                generate(keyStore, challenge, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox (emulator / debug): attest the TEE key so the screen still explains why.
                generate(keyStore, challenge, strongBox = false)
            }
            val expectations = AttestationVerifier.Expectations(
                challenge = challenge,
                packageName = context.packageName,
                signingCertDigests = signingCertDigests(),
            )
            AttestationVerifier().verify(chain, expectations)
        } catch (e: Exception) {
            AttestationReport.failed(e.javaClass.simpleName + (e.message?.let { ": ${it.take(160)}" } ?: ""))
        } finally {
            runCatching { keyStore.deleteEntry(ALIAS) }
        }
    }

    private fun generate(keyStore: KeyStore, challenge: ByteArray, strongBox: Boolean): List<X509Certificate> {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KnoxVaultKeys.KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
        val chain = keyStore.getCertificateChain(ALIAS) ?: throw IllegalStateException("No attestation chain")
        return chain.map { it as X509Certificate }
    }

    /** SHA-256 of each APK signing certificate, as listed in the attestation application id. */
    private fun signingCertDigests(): List<ByteArray> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        return signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    }

    private companion object {
        const val ALIAS = "sp_attest_probe"
    }
}
