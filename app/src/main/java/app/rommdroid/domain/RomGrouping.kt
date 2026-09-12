package app.rommdroid.domain

import app.rommdroid.data.db.RomEntity

// Variants are folded client-side rather than with the server's
// `group_by_meta_id=1`, which drops the non-primary variants from the response
// and so puts the Japanese copy out of reach entirely.

/**
 * Stable identity for "the same game on the same platform".
 *
 * Tiered: metadata id when RomM matched the ROM, slug for matches from a
 * provider other than IGDB, tag-stripped filename for ROMs it never identified.
 */
fun romGroupKey(
    platformId: Int,
    igdbId: Int?,
    slug: String?,
    fsNameNoTags: String,
): String {
    val identity = when {
        igdbId != null && igdbId != 0 -> "igdb:$igdbId"
        !slug.isNullOrBlank()         -> "slug:${slug.trim().lowercase()}"
        else                          -> "name:${fsNameNoTags.trim().lowercase()}"
    }
    return "$platformId|$identity"
}

/** One game, plus every copy of it. [variants] holds at least [primary] and is
 *  ordered preferred-region first, matching the variant picker. */
data class RomGroup(
    val key: String,
    val primary: RomEntity,
    val variants: List<RomEntity>,
    /** Canonical region codes across all variants, in [variants] order, deduped. */
    val regions: List<String>,
) {
    val size: Int get() = variants.size
    val hasVariants: Boolean get() = variants.size > 1

    /** A rating belongs to the game, not a copy, so an unidentified primary
     *  borrows the score from an identified sibling. */
    val rating: Double? get() = variants.firstNotNullOfOrNull { it.averageRating }

    /** Same again for the release date. */
    val firstReleaseDate: Long? get() = variants.firstNotNullOfOrNull { it.firstReleaseDate }

    /** When the game arrived: the earliest copy, so a re-dump of an old title
     *  does not make it "new". */
    val createdAt: String? get() = variants.mapNotNull { it.createdAt }.minOrNull()
}

/**
 * Fold [roms] into one [RomGroup] per game, preserving input order so a
 * DAO-sorted list stays sorted. [preferredRegions] decides the [RomGroup.primary].
 */
fun groupRoms(
    roms: List<RomEntity>,
    preferredRegions: List<String> = DEFAULT_REGION_PREFERENCE,
    regionsOf: (RomEntity) -> List<String>,
): List<RomGroup> {
    val buckets = LinkedHashMap<String, MutableList<RomEntity>>()
    for (rom in roms) {
        buckets.getOrPut(rom.groupKey) { mutableListOf() } += rom
    }
    return buckets.map { (key, members) ->
        val regionsByRom = members.associateWith { regionsOf(it) }
        val ordered = members.sortedWith(
            compareBy(
                { regionRank(regionsByRom.getValue(it), preferredRegions) },
                { it.fsName },  // stable across syncs; server paging order is not
            )
        )
        RomGroup(
            key      = key,
            primary  = ordered.first(),
            variants = ordered,
            regions  = ordered.flatMap { regionsByRom.getValue(it) }.distinct(),
        )
    }
}

/** Position of a ROM's best-matching region in [preferred]; unmatched sorts last. */
fun regionRank(regions: List<String>, preferred: List<String>): Int {
    var best = Int.MAX_VALUE
    for (region in regions) {
        val idx = preferred.indexOf(region)
        if (idx >= 0 && idx < best) best = idx
    }
    return best
}

/** Fallback when the locale's country is not itself a region in the library. */
val DEFAULT_REGION_PREFERENCE: List<String> = listOf("US", "WORLD", "EU", "JP")

/** Countries whose ROMs are usually the US release rather than a PAL one. */
private val NTSC_U = setOf("US", "CA", "MX")

/**
 * Region preference for a device whose locale country is [country]. The user's
 * own country wins outright; the rest only stops a PAL user defaulting to the
 * US release of everything.
 */
fun regionPreference(country: String?): List<String> {
    val home = country?.trim()?.uppercase()
        ?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
    val rest = when {
        home == "JP"                   -> listOf("JP", "WORLD", "US", "EU")
        home == null || home in NTSC_U -> DEFAULT_REGION_PREFERENCE
        else                           -> listOf("EU", "WORLD", "US", "JP")
    }
    return (listOfNotNull(home) + rest).distinct()
}

/** One downloadable copy, as the detail screen's picker shows it. [fsName] is
 *  the filename because the revision and dump tags live there, and two USA
 *  copies are otherwise indistinguishable. */
data class RomVariant(
    val id: Int,
    val fsName: String,
    val sizeBytes: Long,
    val regions: List<String>,
)

/**
 * Canonical regions for a ROM: what the server recorded, else what the filename
 * tags say. Unidentified ROMs have no other distinguishing metadata, so the
 * filename tag is all there is.
 */
fun regionsFor(recorded: List<String>, fsName: String): List<String> =
    recorded.ifEmpty { parseRegionsFromFileName(fsName) }
        .map(::normalizeRegion)
        .filter { it.isNotEmpty() }
        .distinct()
