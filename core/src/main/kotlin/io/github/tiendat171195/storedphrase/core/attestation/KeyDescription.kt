package io.github.tiendat171195.storedphrase.core.attestation

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1TaggedObject
import java.security.cert.X509Certificate

/** Security levels used by Android key attestation. */
object SecurityLevel {
    const val SOFTWARE = 0
    const val TRUSTED_ENVIRONMENT = 1
    const val STRONGBOX = 2

    fun name(level: Int): String = when (level) {
        SOFTWARE -> "Software"
        TRUSTED_ENVIRONMENT -> "TEE"
        STRONGBOX -> "StrongBox"
        else -> "Unknown($level)"
    }
}

/** Verified boot states (RootOfTrust.verifiedBootState). */
object VerifiedBootState {
    const val VERIFIED = 0
    const val SELF_SIGNED = 1
    const val UNVERIFIED = 2
    const val FAILED = 3

    fun name(state: Int): String = when (state) {
        VERIFIED -> "Verified"
        SELF_SIGNED -> "SelfSigned"
        UNVERIFIED -> "Unverified"
        FAILED -> "Failed"
        else -> "Unknown($state)"
    }
}

data class RootOfTrust(val deviceLocked: Boolean, val verifiedBootState: Int)

class ApplicationId(val packageNames: List<String>, val signatureDigests: List<ByteArray>)

/** The fields of the attestation extension that Stored Phrase checks or displays. */
class KeyDescription(
    val attestationVersion: Int,
    val attestationSecurityLevel: Int,
    val keyMintVersion: Int,
    val keyMintSecurityLevel: Int,
    val challenge: ByteArray,
    val rootOfTrust: RootOfTrust?,
    val osVersion: Int?,
    val osPatchLevel: Int?,
    val applicationId: ApplicationId?,
)

/**
 * Parses the Android key attestation extension (OID 1.3.6.1.4.1.11129.2.1.17), schema
 * "KeyDescription" from the Android developer documentation (Keymaster 3 to KeyMint).
 */
object KeyDescriptionParser {
    const val OID = "1.3.6.1.4.1.11129.2.1.17"

    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_OS_VERSION = 705
    private const val TAG_OS_PATCH_LEVEL = 706
    private const val TAG_ATTESTATION_APPLICATION_ID = 709

    fun parse(certificate: X509Certificate): KeyDescription {
        val extension = certificate.getExtensionValue(OID) ?: throw AttestationException("Certificate has no attestation extension")
        return parse(ASN1OctetString.getInstance(extension).octets)
    }

    fun parse(der: ByteArray): KeyDescription = try {
        val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
        if (seq.size() < 8) throw AttestationException("KeyDescription too short")
        val softwareEnforced = authorizationList(seq.getObjectAt(6))
        val hardwareEnforced = authorizationList(seq.getObjectAt(7))
        fun field(tag: Int): ASN1Encodable? = hardwareEnforced[tag] ?: softwareEnforced[tag]
        KeyDescription(
            attestationVersion = int(seq.getObjectAt(0)),
            attestationSecurityLevel = enumerated(seq.getObjectAt(1)),
            keyMintVersion = int(seq.getObjectAt(2)),
            keyMintSecurityLevel = enumerated(seq.getObjectAt(3)),
            challenge = ASN1OctetString.getInstance(seq.getObjectAt(4)).octets,
            // RootOfTrust is only meaningful when enforced by hardware.
            rootOfTrust = hardwareEnforced[TAG_ROOT_OF_TRUST]?.let(::rootOfTrust),
            osVersion = field(TAG_OS_VERSION)?.let(::int),
            osPatchLevel = field(TAG_OS_PATCH_LEVEL)?.let(::int),
            applicationId = (softwareEnforced[TAG_ATTESTATION_APPLICATION_ID] ?: hardwareEnforced[TAG_ATTESTATION_APPLICATION_ID])
                ?.let { applicationId(ASN1OctetString.getInstance(it).octets) },
        )
    } catch (e: AttestationException) {
        throw e
    } catch (e: Exception) {
        throw AttestationException("Malformed attestation extension: ${e.javaClass.simpleName}")
    }

    private fun authorizationList(encodable: ASN1Encodable): Map<Int, ASN1Encodable> {
        val out = HashMap<Int, ASN1Encodable>()
        for (element in ASN1Sequence.getInstance(encodable)) {
            val tagged = ASN1TaggedObject.getInstance(element)
            out[tagged.tagNo] = tagged.explicitBaseObject
        }
        return out
    }

    private fun rootOfTrust(encodable: ASN1Encodable): RootOfTrust {
        val seq = ASN1Sequence.getInstance(encodable)
        return RootOfTrust(
            deviceLocked = ASN1Boolean.getInstance(seq.getObjectAt(1)).isTrue,
            verifiedBootState = enumerated(seq.getObjectAt(2)),
        )
    }

    private fun applicationId(der: ByteArray): ApplicationId {
        val seq = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
        val packages = ASN1Set.getInstance(seq.getObjectAt(0)).map { info ->
            String(ASN1OctetString.getInstance(ASN1Sequence.getInstance(info).getObjectAt(0)).octets, Charsets.UTF_8)
        }
        val digests = ASN1Set.getInstance(seq.getObjectAt(1)).map { ASN1OctetString.getInstance(it).octets }
        return ApplicationId(packages, digests)
    }

    private fun int(encodable: ASN1Encodable): Int = ASN1Integer.getInstance(encodable).intValueExact()

    private fun enumerated(encodable: ASN1Encodable): Int = ASN1Enumerated.getInstance(encodable).intValueExact()
}

class AttestationException(message: String) : Exception(message)
