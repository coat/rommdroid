package app.rommdroid.data.db

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot is the only copy of the folder mappings that survives a database
 * rebuild, so what matters is that it still decodes when the app around it has
 * moved on — an older file read by a newer build, or the other way round.
 */
class FolderMappingSnapshotTest {

    private val format = FolderMappingSnapshot.format

    @Test
    fun `round-trips every kind of mapping`() {
        val snapshot = FolderMappingSnapshot(
            base = FolderMappingSnapshot.Folder(
                uri = "content://com.android.externalstorage.documents/tree/primary%3ARoms",
                displayPath = "Roms",
            ),
            overrides = listOf(
                FolderMappingSnapshot.Override(
                    platformId = 12,
                    uri = "content://com.android.externalstorage.documents/tree/0000-1111%3APS1",
                    displayPath = "PS1",
                ),
            ),
            subfolders = listOf(FolderMappingSnapshot.Subfolder(platformId = 3, name = "SNES")),
        )

        assertEquals(snapshot, format.decodeFromString<FolderMappingSnapshot>(format.encodeToString(snapshot)))
    }

    @Test
    fun `an empty snapshot knows it is empty`() {
        assertTrue(FolderMappingSnapshot().isEmpty)
        assertTrue(format.decodeFromString<FolderMappingSnapshot>("{}").isEmpty)
    }

    /** A file written before per-platform overrides existed still has a base folder. */
    @Test
    fun `absent fields fall back to nothing configured`() {
        val decoded = format.decodeFromString<FolderMappingSnapshot>(
            """{"base":{"uri":"content://tree/primary%3ARoms","displayPath":"Roms"}}"""
        )

        assertEquals("Roms", decoded.base?.displayPath)
        assertTrue(decoded.overrides.isEmpty())
        assertTrue(decoded.subfolders.isEmpty())
    }

    /** A file written by a later build carries fields this one has never heard of. */
    @Test
    fun `unknown fields do not cost the mappings alongside them`() {
        val decoded = format.decodeFromString<FolderMappingSnapshot>(
            """
            {
              "base": {"uri": "content://tree/primary%3ARoms", "displayPath": "Roms"},
              "savesFolder": {"uri": "content://tree/primary%3ASaves"},
              "schemaVersion": 9
            }
            """.trimIndent()
        )

        assertEquals("content://tree/primary%3ARoms", decoded.base?.uri)
    }

    @Test
    fun `a base folder is optional`() {
        val decoded = format.decodeFromString<FolderMappingSnapshot>(
            """{"subfolders":[{"platformId":3,"name":"snes"}]}"""
        )

        assertNull(decoded.base)
        assertEquals(listOf(FolderMappingSnapshot.Subfolder(3, "snes")), decoded.subfolders)
    }
}
