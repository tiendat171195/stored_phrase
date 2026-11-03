package io.github.tiendat171195.storedphrase.core.backup

import io.github.tiendat171195.storedphrase.core.bip39.Mnemonic
import io.github.tiendat171195.storedphrase.core.crypto.Argon2idKdf
import io.github.tiendat171195.storedphrase.core.vault.EntryCodec
import io.github.tiendat171195.storedphrase.core.vault.SeedSecret
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupFormatTest {

    private val fast = Argon2idKdf.Params(memoryKiB = 8192, iterations = 1, parallelism = 1)

    private fun sampleBackup(): BackupFormat.Backup {
        val seed12 = requireNotNull(Mnemonic.parse("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
        val seed24 = requireNotNull(Mnemonic.parse(List(23) { "abandon" }.joinToString(" ") + " art"))
        val first = EntryCodec.encode(SeedSecret(seed12, "TREZOR".toCharArray(), "ví thử".toCharArray()))
        val second = EntryCodec.encode(SeedSecret(seed24, birthdayHeight = 2_726_400))
        return BackupFormat.Backup(
            manifest = BackupFormat.Manifest(createdAt = 1_762_000_000_000, generator = "Stored Phrase test"),
            entries = listOf(
                BackupFormat.Entry(
                    BackupFormat.Kind.PLAIN,
                    BackupFormat.EntryMeta("Test 12", 12, hasPassphrase = true, hasNotes = true, createdAt = 1, updatedAt = 2, verifiedAt = 3),
                    first,
                ),
                BackupFormat.Entry(
                    BackupFormat.Kind.PLAIN,
                    BackupFormat.EntryMeta("Zodl test", 24, hasBirthday = true, createdAt = 4, updatedAt = 5),
                    second,
                ),
            ),
        )
    }

    private fun assertSample(backup: BackupFormat.Backup) {
        assertEquals("Stored Phrase test", backup.manifest.generator)
        assertEquals(listOf("Test 12", "Zodl test"), backup.entries.map { it.meta.label })
        val first = EntryCodec.decode(backup.entries[0].content)
        assertEquals("TREZOR", String(first.bip39Passphrase))
        assertEquals("ví thử", String(first.notes))
        assertNull(first.birthdayHeight)
        val second = EntryCodec.decode(backup.entries[1].content)
        assertEquals(24, second.wordCount)
        assertEquals(2_726_400L, second.birthdayHeight)
        assertEquals(3L, backup.entries[0].meta.verifiedAt)
    }

    @Test
    fun roundTrip() {
        val file = BackupFormat.encrypt(sampleBackup(), "correct-horse-battery-staple".toCharArray(), fast)
        val header = BackupFormat.readHeader(file)
        assertEquals(fast, header.params)
        assertSample(BackupFormat.decrypt(file, "correct-horse-battery-staple".toCharArray()))
    }

    @Test
    fun wrongPassphraseOrModifiedFileIsRejected() {
        val file = BackupFormat.encrypt(sampleBackup(), "right".toCharArray(), fast)
        assertFailsWith<WrongPassphraseException> { BackupFormat.decrypt(file, "wrong".toCharArray()) }
        // Header bytes are authenticated (AAD): changing the salt must fail too.
        val badSalt = file.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<WrongPassphraseException> { BackupFormat.decrypt(badSalt, "right".toCharArray()) }
        val badBody = file.copyOf().also { it[60] = (it[60].toInt() xor 1).toByte() }
        assertFailsWith<WrongPassphraseException> { BackupFormat.decrypt(badBody, "right".toCharArray()) }
    }

    @Test
    fun rejectsInvalidFilesBeforeRunningTheKdf() {
        val file = BackupFormat.encrypt(sampleBackup(), "pw".toCharArray(), fast)
        assertFailsWith<BackupFormatException> { BackupFormat.readHeader(file.copyOf(30)) }
        assertFailsWith<BackupFormatException> { BackupFormat.readHeader(file.copyOf().also { it[0] = 0 }) }
        assertFailsWith<BackupFormatException> { BackupFormat.readHeader(file.copyOf().also { it[4] = 9 }) }
        // A hostile file asking for 4 GiB of memory is refused.
        assertFailsWith<BackupFormatException> { BackupFormat.readHeader(file.copyOf().also { it[6] = 0x7F }) }
    }

    /**
     * tools/testdata/sample-v1.spbak was produced by this code (default Argon2 parameters) and is
     * also decrypted by tools/test_recover_backup.py, keeping the Kotlin and Python sides in sync.
     * Regenerate with: STORED_PHRASE_WRITE_FIXTURE=1 ./gradlew --no-daemon :core:test --tests '*BackupFormatTest*'
     */
    @Test
    fun committedFixtureStillDecrypts() {
        val fixture = File("../tools/testdata/sample-v1.spbak")
        if (System.getenv("STORED_PHRASE_WRITE_FIXTURE") != null) {
            fixture.parentFile.mkdirs()
            fixture.writeBytes(BackupFormat.encrypt(sampleBackup(), FIXTURE_PASSPHRASE.toCharArray()))
        }
        val bytes = fixture.readBytes()
        assertEquals(Argon2idKdf.Params.DEFAULT, BackupFormat.readHeader(bytes).params)
        assertSample(BackupFormat.decrypt(bytes, FIXTURE_PASSPHRASE.toCharArray()))
        assertContentEquals(bytes.copyOfRange(0, 4), "SPBK".encodeToByteArray())
    }

    private companion object {
        const val FIXTURE_PASSPHRASE = "correct-horse-battery-staple"
    }
}
