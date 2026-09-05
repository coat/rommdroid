package app.rommdroid.util

import app.rommdroid.data.db.RomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsTest {

    @Test fun `normalises the three naming conventions onto one code`() {
        listOf("USA", "usa", "U", "us", "America", "NTSC-U").forEach {
            assertEquals("US", normalizeRegion(it))
        }
        listOf("Europe", "E", "eu", "PAL").forEach { assertEquals("EU", normalizeRegion(it)) }
        listOf("Japan", "J", "jp", "NTSC-J").forEach { assertEquals("JP", normalizeRegion(it)) }
    }

    @Test fun `unknown regions survive as their own code rather than vanishing`() {
        assertEquals("ZZZ", normalizeRegion("zzz"))
        assertEquals("", normalizeRegion("  "))
    }

    @Test fun `known regions render as flags and unknown ones as text`() {
        assertEquals("🇺🇸", regionLabel("USA"))
        assertEquals("🇯🇵", regionLabel("Japan"))
        assertEquals("🌍", regionLabel("World"))
        assertEquals("ZZZ", regionLabel("ZZZZ"))
    }

    @Test fun `summary caps the list and reports the overflow`() {
        assertEquals("", regionSummary(emptyList()))
        val many = listOf("US", "EU", "JP", "AU", "BR", "KR")
        assertTrue(regionSummary(many).endsWith("+2"))
        assertEquals(4, regionSummary(many).split(" ").size - 1)
    }

    @Test fun `parses regions out of filename tags when the server had none`() {
        assertEquals(
            listOf("US", "EU"),
            parseRegionsFromFileName("Super Mario Bros. (USA, Europe).nes"),
        )
        assertEquals(
            listOf("JP"),
            parseRegionsFromFileName("Rockman (J) [!].nes"),
        )
    }

    @Test fun `does not mistake the language tag for a region`() {
        // No-Intro puts languages in their own group; "(En,Fr,De)" is not a
        // claim that the ROM is the French release.
        assertEquals(
            listOf("EU"),
            parseRegionsFromFileName("Some Game (Europe) (En,Fr,De).gba"),
        )
    }

    @Test fun `ignores the dump-status bracket`() {
        assertEquals(emptyList<String>(), parseRegionsFromFileName("Game [b1][o1].nes"))
    }
}

class RomGroupingTest {

    private fun rom(
        id: Int,
        fsName: String,
        regions: List<String> = emptyList(),
        igdbId: Int? = null,
        slug: String? = null,
        platformId: Int = 1,
        name: String = "Game",
    ): RomEntity {
        val noTags = fsName.substringBefore(" (").substringBeforeLast('.')
        return RomEntity(
            id = id,
            platformId = platformId,
            platformSlug = "nes",
            platformDisplayName = "NES",
            fsName = fsName,
            fsNameNoTags = noTags,
            fsExtension = "nes",
            fsSizeBytes = 1024,
            name = name,
            slug = slug,
            summary = null,
            regions = regions.joinToString(","),
            languages = "",
            tags = "",
            urlCover = null,
            pathCoverSmall = null,
            pathCoverLarge = null,
            updatedAt = null,
            groupKey = romGroupKey(platformId, igdbId, slug, noTags),
        )
    }

    private val regionsOf: (RomEntity) -> List<String> = { entity ->
        romRegions(entity) { raw -> raw.split(',').filter { it.isNotBlank() } }
    }

    @Test fun `metadata id groups variants whose filenames differ`() {
        val roms = listOf(
            rom(1, "Legend of Zelda, The (USA).nes", listOf("USA"), igdbId = 1022),
            rom(2, "Zelda no Densetsu (Japan).nes", listOf("Japan"), igdbId = 1022),
        )
        val groups = groupRoms(roms, listOf("US"), regionsOf)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
        assertTrue(groups.single().hasVariants)
    }

    @Test fun `filename grouping catches ROMs the server never identified`() {
        val roms = listOf(
            rom(1, "Cool Homebrew (USA).nes", listOf("USA")),
            rom(2, "Cool Homebrew (Europe).nes", listOf("Europe")),
        )
        assertEquals(1, groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf).size)
    }

    @Test fun `same game on two platforms stays two rows`() {
        val roms = listOf(
            rom(1, "Aladdin (USA).smc", listOf("USA"), igdbId = 7, platformId = 1),
            rom(2, "Aladdin (USA).md",  listOf("USA"), igdbId = 7, platformId = 2),
        )
        assertEquals(2, groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf).size)
    }

    @Test fun `distinct games are not merged`() {
        val roms = listOf(
            rom(1, "Mega Man (USA).nes", listOf("USA"), igdbId = 1),
            rom(2, "Mega Man 2 (USA).nes", listOf("USA"), igdbId = 2),
        )
        assertEquals(2, groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf).size)
    }

    @Test fun `preferred region becomes the row shown`() {
        val roms = listOf(
            rom(1, "Game (Japan).nes", listOf("Japan"), igdbId = 5),
            rom(2, "Game (Europe).nes", listOf("Europe"), igdbId = 5),
            rom(3, "Game (USA).nes", listOf("USA"), igdbId = 5),
        )
        assertEquals(3, groupRoms(roms, listOf("US", "EU", "JP"), regionsOf).single().primary.id)
        assertEquals(1, groupRoms(roms, listOf("JP", "US", "EU"), regionsOf).single().primary.id)
    }

    @Test fun `no variant is dropped`() {
        val roms = listOf(
            rom(1, "Game (USA).nes", listOf("USA"), igdbId = 5),
            rom(2, "Game (USA) (Rev 1).nes", listOf("USA"), igdbId = 5),
            rom(3, "Game (Japan).nes", listOf("Japan"), igdbId = 5),
        )
        val group = groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf).single()
        assertEquals(setOf(1, 2, 3), group.variants.map { it.id }.toSet())
    }

    @Test fun `variant order is stable when regions tie`() {
        val roms = listOf(
            rom(2, "Game (USA) (Rev 1).nes", listOf("USA"), igdbId = 5),
            rom(1, "Game (USA).nes", listOf("USA"), igdbId = 5),
        )
        val group = groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf).single()
        assertEquals(listOf("Game (USA) (Rev 1).nes", "Game (USA).nes"), group.variants.map { it.fsName })
    }

    @Test fun `single-copy games are left alone`() {
        val group = groupRoms(listOf(rom(1, "Solo (USA).nes", listOf("USA"))), DEFAULT_REGION_PREFERENCE, regionsOf).single()
        assertFalse(group.hasVariants)
        assertEquals(listOf("US"), group.regions)
    }

    @Test fun `group order follows the order rows arrived in`() {
        val roms = listOf(
            rom(1, "Alpha (USA).nes", listOf("USA"), igdbId = 1, name = "Alpha"),
            rom(2, "Beta (USA).nes", listOf("USA"), igdbId = 2, name = "Beta"),
            rom(3, "Alpha (Japan).nes", listOf("Japan"), igdbId = 1, name = "Alpha"),
        )
        val groups = groupRoms(roms, DEFAULT_REGION_PREFERENCE, regionsOf)
        assertEquals(listOf("Alpha", "Beta"), groups.map { it.primary.name })
    }

    @Test fun `regions fall back to the filename when the server sent none`() {
        val group = groupRoms(
            listOf(rom(1, "Unmatched Game (Japan).nes")),
            DEFAULT_REGION_PREFERENCE,
            regionsOf,
        ).single()
        assertEquals(listOf("JP"), group.regions)
    }

    @Test fun `locale country leads the preference`() {
        assertEquals("DE", regionPreference("de").first())
        // A PAL territory should not default to the US release of everything.
        assertTrue(regionPreference("DE").indexOf("EU") < regionPreference("DE").indexOf("US"))
        assertEquals("US", regionPreference("US").first())
        assertEquals(DEFAULT_REGION_PREFERENCE, regionPreference(null))
        assertEquals(DEFAULT_REGION_PREFERENCE, regionPreference("bogus"))
    }
}
