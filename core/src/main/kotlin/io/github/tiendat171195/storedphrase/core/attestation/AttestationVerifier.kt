package io.github.tiendat171195.storedphrase.core.attestation

import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Offline verification of an Android key attestation certificate chain:
 * signatures up to a trusted Google root, then the claims in the leaf's KeyDescription.
 *
 * This proves the device's secure hardware vouches for the key. It cannot detect a revoked
 * attestation key (that needs Google's online revocation list) and it runs on the same phone
 * it checks, so it is assurance for the user, not protection against a compromised OS.
 */
class AttestationVerifier(
    private val trustedRootKeys: List<ByteArray> = GoogleAttestationRoots.publicKeys,
    private val rootName: (ByteArray) -> String? = GoogleAttestationRoots::nameFor,
    private val now: () -> Date = ::Date,
) {
    class Expectations(
        val challenge: ByteArray,
        val packageName: String,
        val signingCertDigests: List<ByteArray>,
    )

    fun verify(chain: List<X509Certificate>, expected: Expectations): AttestationReport {
        if (chain.isEmpty()) return AttestationReport.failed("Empty certificate chain")
        val chainValid = chainSignaturesValid(chain)
        val rootKey = chain.last().publicKey.encoded
        val rootTrusted = trustedRootKeys.any { it.contentEquals(rootKey) }
        val description = try {
            KeyDescriptionParser.parse(chain.first())
        } catch (e: AttestationException) {
            return AttestationReport.failed(e.message ?: "Bad attestation extension", chainValid, rootTrusted)
        }
        val rootOfTrust = description.rootOfTrust
        val appId = description.applicationId
        val appIdMatches = appId != null && expected.packageName in appId.packageNames &&
            expected.signingCertDigests.isNotEmpty() &&
            expected.signingCertDigests.all { digest -> appId.signatureDigests.any { it.contentEquals(digest) } }
        return AttestationReport(
            chainValid = chainValid,
            rootTrusted = rootTrusted,
            rootName = if (rootTrusted) rootName(rootKey) else null,
            chainLength = chain.size,
            description = description,
            challengeMatches = description.challenge.contentEquals(expected.challenge),
            strongBox = description.attestationSecurityLevel == SecurityLevel.STRONGBOX &&
                description.keyMintSecurityLevel == SecurityLevel.STRONGBOX,
            bootloaderLocked = rootOfTrust?.deviceLocked == true,
            verifiedBoot = rootOfTrust?.verifiedBootState == VerifiedBootState.VERIFIED,
            appIdMatches = appIdMatches,
        )
    }

    /** Each certificate is signed by the next; the root is self-signed; CA certificates are in date. */
    private fun chainSignaturesValid(chain: List<X509Certificate>): Boolean = try {
        for (i in chain.indices) {
            val issuer = if (i + 1 < chain.size) chain[i + 1] else chain[i]
            chain[i].verify(issuer.publicKey)
            // Leaf validity dates vary between devices; only CA certificates are checked.
            if (i > 0) chain[i].checkValidity(now())
        }
        true
    } catch (e: GeneralSecurityException) {
        false
    }
}

class AttestationReport(
    val chainValid: Boolean,
    val rootTrusted: Boolean,
    val rootName: String?,
    val chainLength: Int,
    val description: KeyDescription?,
    val challengeMatches: Boolean,
    val strongBox: Boolean,
    val bootloaderLocked: Boolean,
    val verifiedBoot: Boolean,
    val appIdMatches: Boolean,
    val error: String? = null,
) {
    val passed: Boolean
        get() = error == null && chainValid && rootTrusted && challengeMatches && strongBox &&
            bootloaderLocked && verifiedBoot && appIdMatches

    companion object {
        fun failed(error: String, chainValid: Boolean = false, rootTrusted: Boolean = false) = AttestationReport(
            chainValid = chainValid,
            rootTrusted = rootTrusted,
            rootName = null,
            chainLength = 0,
            description = null,
            challengeMatches = false,
            strongBox = false,
            bootloaderLocked = false,
            verifiedBoot = false,
            appIdMatches = false,
            error = error,
        )
    }
}
