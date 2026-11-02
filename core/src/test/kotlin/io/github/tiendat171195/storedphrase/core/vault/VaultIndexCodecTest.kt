package io.github.tiendat171195.storedphrase.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VaultIndexCodecTest {

    private fun entry(id: String, label: String = "Wallet $id") = IndexEntry(
        id = id,
        blobId = "blob-$id",
        label = label,
        wordCount = 24,
        hasPassphrase = true,
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    @Test
    fun roundTrip() {
        val index = VaultIndex(entries = listOf(entry("a", "Ledger Nano X"), entry("b", "Ví lạnh")))
        assertEquals(index, VaultIndexCodec.decode(VaultIndexCodec.encode(index)))
    }

    @Test
    fun upsertReplacesAndAppendsAndRemoveDeletes() {
        val index = VaultIndex().upsert(entry("a")).upsert(entry("b"))
        val renamed = index.upsert(entry("a", "Renamed"))
        assertEquals(listOf("Renamed", "Wallet b"), renamed.entries.map { it.label })
        assertEquals(listOf("b"), renamed.remove("a").entries.map { it.id })
        assertNull(renamed.find("zzz"))
    }

    @Test
    fun ignoresUnknownFieldsFromFutureMinorVersions() {
        val json = """{"version":1,"entries":[{"id":"a","blobId":"x","label":"L","wordCount":12,
            "createdAt":1,"updatedAt":2,"futureField":true}],"other":1}"""
        val index = VaultIndexCodec.decode(json.encodeToByteArray())
        assertEquals("L", index.entries.single().label)
        assertNull(index.entries.single().verifiedAt)
    }

    @Test
    fun rejectsGarbageAndNewerVersions() {
        assertFailsWith<EntryFormatException> { VaultIndexCodec.decode("not json".encodeToByteArray()) }
        assertFailsWith<EntryFormatException> { VaultIndexCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertFailsWith<EntryFormatException> { VaultIndexCodec.decode("""{"version":99}""".encodeToByteArray()) }
    }
}
