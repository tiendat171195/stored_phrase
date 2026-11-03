package io.github.tiendat171195.storedphrase.core.attestation

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttestationVerifierTest {

    private val challenge = ByteArray(32) { it.toByte() }
    private val packageName = "io.github.tiendat171195.storedphrase"
    private val signerDigest = MessageDigest.getInstance("SHA-256").digest("signing cert".encodeToByteArray())

    private fun ecKeyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    private fun cert(
        subject: String,
        subjectKey: KeyPair,
        issuer: String,
        issuerKey: KeyPair,
        ca: Boolean,
        extension: ASN1Encodable? = null,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"),
            BigInteger.valueOf(System.nanoTime()),
            Date(System.currentTimeMillis() - 86_400_000),
            Date(System.currentTimeMillis() + 86_400_000L * 365),
            X500Name("CN=$subject"),
            subjectKey.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        if (extension != null) builder.addExtension(ASN1ObjectIdentifier(KeyDescriptionParser.OID), false, extension)
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun keyDescription(
        securityLevel: Int = SecurityLevel.STRONGBOX,
        challenge: ByteArray = this.challenge,
        deviceLocked: Boolean = true,
        bootState: Int = VerifiedBootState.VERIFIED,
        packageName: String = this.packageName,
        digest: ByteArray = signerDigest,
    ): ASN1Encodable {
        val appId = DERSequence(
            arrayOf(
                DERSet(DERSequence(arrayOf(DEROctetString(packageName.encodeToByteArray()), ASN1Integer(1)))),
                DERSet(DEROctetString(digest)),
            ),
        )
        val rootOfTrust = DERSequence(
            arrayOf(DEROctetString(ByteArray(32) { 7 }), ASN1Boolean.getInstance(deviceLocked), ASN1Enumerated(bootState), DEROctetString(ByteArray(32))),
        )
        val softwareEnforced = DERSequence(arrayOf<ASN1Encodable>(DERTaggedObject(true, 709, DEROctetString(appId.encoded))))
        val hardwareEnforced = DERSequence(
            arrayOf<ASN1Encodable>(
                DERTaggedObject(true, 1, DERSet(ASN1Integer(2))),
                DERTaggedObject(true, 704, rootOfTrust),
                DERTaggedObject(true, 705, ASN1Integer(160000)),
                DERTaggedObject(true, 706, ASN1Integer(202510)),
            ),
        )
        return DERSequence(
            arrayOf(
                ASN1Integer(300), ASN1Enumerated(securityLevel), ASN1Integer(300), ASN1Enumerated(securityLevel),
                DEROctetString(challenge), DEROctetString(ByteArray(0)), softwareEnforced, hardwareEnforced,
            ),
        )
    }

    private class Chain(val certificates: List<X509Certificate>, val root: KeyPair)

    private fun chain(description: ASN1Encodable = keyDescription()): Chain {
        val root = ecKeyPair("secp384r1")
        val intermediate = ecKeyPair()
        val leaf = ecKeyPair()
        return Chain(
            listOf(
                cert("Android Keystore Key", leaf, "Test Intermediate", intermediate, ca = false, extension = description),
                cert("Test Intermediate", intermediate, "Test Root", root, ca = true),
                cert("Test Root", root, "Test Root", root, ca = true),
            ),
            root,
        )
    }

    private fun verifierFor(chain: Chain) =
        AttestationVerifier(trustedRootKeys = listOf(chain.root.public.encoded), rootName = { "Test Root" })

    private val expectations get() = AttestationVerifier.Expectations(challenge, packageName, listOf(signerDigest))

    @Test
    fun validStrongBoxChainPassesEveryCheck() {
        val chain = chain()
        val report = verifierFor(chain).verify(chain.certificates, expectations)
        assertTrue(report.passed, "report should pass")
        assertEquals("Test Root", report.rootName)
        val description = assertNotNull(report.description)
        assertEquals(300, description.attestationVersion)
        assertEquals(202510, description.osPatchLevel)
        assertEquals(listOf(packageName), description.applicationId?.packageNames)
    }

    @Test
    fun chainToAnUnknownRootIsNotTrusted() {
        val chain = chain()
        val report = AttestationVerifier().verify(chain.certificates, expectations) // real Google roots
        assertTrue(report.chainValid)
        assertFalse(report.rootTrusted)
        assertFalse(report.passed)
    }

    @Test
    fun brokenSignatureChainFails() {
        val good = chain()
        val other = chain()
        // Leaf from one chain, CA certificates from another: signatures do not line up.
        val mixed = listOf(good.certificates[0]) + other.certificates.drop(1)
        val report = AttestationVerifier(trustedRootKeys = listOf(other.root.public.encoded)).verify(mixed, expectations)
        assertFalse(report.chainValid)
        assertFalse(report.passed)
    }

    @Test
    fun eachClaimIsChecked() {
        fun reportFor(description: ASN1Encodable): AttestationReport {
            val chain = chain(description)
            return verifierFor(chain).verify(chain.certificates, expectations)
        }
        reportFor(keyDescription(securityLevel = SecurityLevel.TRUSTED_ENVIRONMENT)).let {
            assertFalse(it.strongBox)
            assertFalse(it.passed)
        }
        reportFor(keyDescription(challenge = ByteArray(32))).let {
            assertFalse(it.challengeMatches)
            assertFalse(it.passed)
        }
        reportFor(keyDescription(deviceLocked = false, bootState = VerifiedBootState.UNVERIFIED)).let {
            assertFalse(it.bootloaderLocked)
            assertFalse(it.verifiedBoot)
            assertFalse(it.passed)
        }
        reportFor(keyDescription(packageName = "com.example.other")).let {
            assertFalse(it.appIdMatches)
            assertFalse(it.passed)
        }
        reportFor(keyDescription(digest = ByteArray(32))).let {
            assertFalse(it.appIdMatches)
            assertFalse(it.passed)
        }
    }

    @Test
    fun certificateWithoutExtensionIsReported() {
        val root = ecKeyPair()
        val plain = cert("No extension", root, "No extension", root, ca = true)
        val report = AttestationVerifier(trustedRootKeys = listOf(root.public.encoded)).verify(listOf(plain), expectations)
        assertFalse(report.passed)
        assertNotNull(report.error)
        assertFailsWith<AttestationException> { KeyDescriptionParser.parse(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)) }
    }

    @Test
    fun embeddedGoogleRootsAreThePublishedOnes() {
        val keys = GoogleAttestationRoots.publicKeys.map(GoogleAttestationRoots::sha256)
        assertEquals(
            listOf(
                "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",
                "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec",
            ),
            keys,
        )
        GoogleAttestationRoots.certificates.forEach { it.verify(it.publicKey) } // self-signed
        assertEquals("Google Key Attestation CA1 (ECDSA P-384, 2025)", GoogleAttestationRoots.nameFor(GoogleAttestationRoots.publicKeys[1]))
    }
}
