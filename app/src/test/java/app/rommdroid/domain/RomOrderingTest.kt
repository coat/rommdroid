package app.rommdroid.domain

import app.rommdroid.data.db.RomEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RomOrderingTest {

    private fun rom(
        id: Int,
        name: String,
        regions: List<String> = emptyList(),
        rating: Double? = null,
        released: Long? = null,
        added: String? = null,
        size: Long = 1024,
        igdbId: Int? = null,
    ): RomEntity = RomEntity(
        id = id,
        platformId = 1,
        platformSlug = "nes",
        platformDisplayName = "NES",
        fsName = "$name.nes",
        fsNameNoTags = name,
        fsExtension = "nes",
        fsSizeBytes = size,
        name = name,
        slug = null,
        summary = null,
        regions = regions.joinToString(","),
        languages = "",
        tags = "",
        urlCover = null,
        pathCoverSmall = null,
        pathCoverLarge = null,
        updatedAt = null,
        averageRating = rating,
        firstReleaseDate = released,
        createdAt = added,
        groupKey = romGroupKey(1, igdbId, null, name),
    )

    private val regionsOf: (RomEntity) -> List<String> = { entity ->
        romRegions(entity) { raw -> raw.split(',').filter { it.isNotBlank() } }
    }

    private fun groups(vararg roms: RomEntity): List<RomGroup> =
        groupRoms(roms.toList(), DEFAULT_REGION_PREFERENCE, regionsOf)

    private fun names(groups: List<RomGroup>) = groups.map { it.primary.name }

    // Region filter

    @Test fun `an empty selection keeps everything`() {
        val roms = listOf(rom(1, "A", listOf("USA")), rom(2, "B"))
        assertEquals(roms, keepRegions(roms, emptySet(), regionsOf))
    }

    @Test fun `keeps a ROM carrying any selected region`() {
        val roms = listOf(
            rom(1, "A", listOf("USA")),
            rom(2, "B", listOf("Japan")),
            rom(3, "C", listOf("USA", "Europe")),
            rom(4, "D"),
        )
        assertEquals(listOf(1, 3), keepRegions(roms, setOf("US"), regionsOf).map { it.id })
        assertEquals(listOf(1, 2, 3), keepRegions(roms, setOf("US", "JP"), regionsOf).map { it.id })
    }

    @Test fun `no-region stands in for ROMs with no region at all`() {
        val roms = listOf(rom(1, "A", listOf("USA")), rom(2, "B"))
        assertEquals(listOf(2), keepRegions(roms, setOf(NO_REGION), regionsOf).map { it.id })
    }

    @Test fun `filtering before grouping puts the chosen copy in front`() {
        val roms = listOf(
            rom(1, "Zelda (USA)", listOf("USA"), igdbId = 5),
            rom(2, "Zelda (Japan)", listOf("Japan"), igdbId = 5),
        )
        val kept = groupRoms(keepRegions(roms, setOf("JP"), regionsOf), DEFAULT_REGION_PREFERENCE, regionsOf)
        assertEquals(1, kept.size)
        assertEquals(2, kept.single().primary.id)
        assertEquals(1, kept.single().size)
    }

    @Test fun `counts regions per ROM, most common first`() {
        val roms = listOf(
            rom(1, "A", listOf("USA", "Europe")),
            rom(2, "B", listOf("Japan")),
            rom(3, "C", listOf("Japan")),
            rom(4, "D"),
        )
        assertEquals(
            listOf(
                RegionCount("JP", 2),
                RegionCount("EU", 1),
                RegionCount("US", 1),
                RegionCount(NO_REGION, 1),
            ),
            countRegions(roms, regionsOf),
        )
    }

    // Sort

    @Test fun `name keeps the DAO order or mirrors it`() {
        val g = groups(rom(1, "A"), rom(2, "B"), rom(3, "C"))
        assertEquals(listOf("A", "B", "C"), names(sortGroups(g, RomSort(RomSortKey.Name, descending = false))))
        assertEquals(listOf("C", "B", "A"), names(sortGroups(g, RomSort(RomSortKey.Name, descending = true))))
    }

    @Test fun `rating puts the best first and the unrated last either way`() {
        val g = groups(rom(1, "A", rating = 50.0), rom(2, "B"), rom(3, "C", rating = 90.0))
        assertEquals(listOf("C", "A", "B"), names(sortGroups(g, RomSort.of(RomSortKey.Rating))))
        assertEquals(listOf("A", "C", "B"), names(sortGroups(g, RomSort.of(RomSortKey.Rating).reversed())))
    }

    @Test fun `ties fall back to name order`() {
        val g = groups(rom(1, "A", rating = 80.0), rom(2, "B", rating = 90.0), rom(3, "C", rating = 80.0))
        assertEquals(listOf("B", "A", "C"), names(sortGroups(g, RomSort.of(RomSortKey.Rating))))
    }

    @Test fun `a rating borrowed from a sibling counts for the game`() {
        val g = groups(
            rom(1, "Zelda (USA)", listOf("USA"), igdbId = 5),
            rom(2, "Zelda (Japan)", listOf("Japan"), igdbId = 5, rating = 95.0),
            rom(3, "Other", rating = 60.0),
        )
        assertEquals(listOf("Zelda (USA)", "Other"), names(sortGroups(g, RomSort.of(RomSortKey.Rating))))
    }

    @Test fun `released and added sort newest first by default`() {
        val g = groups(
            rom(1, "A", released = 200, added = "2024-01-02T00:00:00"),
            rom(2, "B", released = 300, added = "2024-01-01T00:00:00"),
            rom(3, "C"),
        )
        assertEquals(listOf("B", "A", "C"), names(sortGroups(g, RomSort.of(RomSortKey.Released))))
        assertEquals(listOf("A", "B", "C"), names(sortGroups(g, RomSort.of(RomSortKey.Added))))
    }

    @Test fun `an unknown size sorts last, not as the smallest file`() {
        val g = groups(rom(1, "A", size = 10), rom(2, "B", size = 0), rom(3, "C", size = 30))
        assertEquals(listOf("C", "A", "B"), names(sortGroups(g, RomSort.of(RomSortKey.Size))))
        assertEquals(listOf("A", "C", "B"), names(sortGroups(g, RomSort.of(RomSortKey.Size).reversed())))
    }
}
