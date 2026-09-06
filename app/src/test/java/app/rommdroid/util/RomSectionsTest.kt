package app.rommdroid.util

import app.rommdroid.data.db.RomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomSectionsTest {

    private fun group(name: String?, fsNameNoTags: String = "File"): RomGroup {
        val rom = RomEntity(
            id = name.hashCode(),
            platformId = 1,
            platformSlug = "nes",
            platformDisplayName = "NES",
            fsName = "$fsNameNoTags.nes",
            fsNameNoTags = fsNameNoTags,
            fsExtension = "nes",
            fsSizeBytes = 1024,
            name = name,
            slug = null,
            summary = null,
            regions = "",
            languages = "",
            tags = "",
            urlCover = null,
            pathCoverSmall = null,
            pathCoverLarge = null,
            updatedAt = null,
        )
        return RomGroup(key = "$name|$fsNameNoTags", primary = rom, variants = listOf(rom), regions = emptyList())
    }

    @Test fun `a row without a name is filed under its filename`() {
        assertEquals("Metroid", group(name = null, fsNameNoTags = "Metroid").primary.displayName)
        assertEquals("Metroid", group(name = "  ", fsNameNoTags = "Metroid").primary.displayName)
        assertEquals("Metroid", group(name = "Metroid", fsNameNoTags = "mtrd").primary.displayName)
    }

    @Test fun `labels are the first letter, upper-cased`() {
        assertEquals("Z", sectionLabel("Zelda II"))
        assertEquals("Z", sectionLabel("  zelda"))
    }

    @Test fun `titles starting with anything but a letter share one section`() {
        listOf("1942", "3-D WorldRunner", "!Hack", "").forEach {
            assertEquals(OTHER_SECTION, sectionLabel(it))
        }
    }

    @Test fun `accented titles fold onto the plain letter`() {
        assertEquals("O", sectionLabel("Ōkami"))
        assertEquals("E", sectionLabel("Étoile"))
    }

    @Test fun `sections follow the order the rows came in`() {
        val sections = sectionsOf(
            listOf(group("1942"), group("Adventure"), group("Astro"), group("Bomberman")),
        )
        assertEquals(listOf("#", "A", "B"), sections.map { it.label })
        assertEquals(listOf("Adventure", "Astro"), sections[1].groups.map { it.primary.displayName })
    }

    @Test fun `the index counts headers, since the list gives each one a slot`() {
        val index = sectionIndexOf(
            sectionsOf(listOf(group("1942"), group("Adventure"), group("Astro"), group("Bomberman"))),
        )
        // "#" header at 0, its row at 1; "A" header at 2, rows at 3 and 4; "B" at 5.
        assertEquals("#", index.labelAt(0))
        assertEquals("#", index.labelAt(1))
        assertEquals("A", index.labelAt(2))
        assertEquals("A", index.labelAt(4))
        assertEquals("B", index.labelAt(5))
    }

    @Test fun `stepping walks to the next header and back to the one just passed`() {
        val index = sectionIndexOf(
            sectionsOf(listOf(group("1942"), group("Adventure"), group("Astro"), group("Bomberman"))),
        )
        assertEquals(2, index.startAfter(0))
        assertEquals(5, index.startAfter(3))
        assertNull(index.startAfter(5))

        // From inside a section, back lands on that section's own header first.
        assertEquals(2, index.startBefore(4))
        assertEquals(0, index.startBefore(2))
        assertNull(index.startBefore(0))
    }

    @Test fun `a filtered list has no headers to index, so nothing offers to jump`() {
        val filtered = listOf(RomSection(label = null, groups = listOf(group("Lemmings"))))
        assertTrue(sectionIndexOf(filtered).isEmpty)
    }

    @Test fun `a letter already seen keeps its own section rather than opening a second`() {
        val sections = sectionsOf(listOf(group("Ogre Battle"), group("Zelda"), group("Ōkami")))
        assertEquals(listOf("O", "Z"), sections.map { it.label })
        assertEquals(listOf("Ogre Battle", "Ōkami"), sections[0].groups.map { it.primary.displayName })
    }
}
