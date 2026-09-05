package app.rommdroid.util

import app.rommdroid.data.db.RomEntity

/**
 * Collapsing regional variants into one entry per game.
 *
 * The server can do this itself with `group_by_meta_id=1`, but that drops the
 * variants from the response entirely — the user can then no longer reach the
 * Japanese copy at all.  So the list is still fetched ungrouped and folded here,
 * where every variant stays addressable behind the row it was folded into.
 */

/**
 * Stable identity for "the same game on the same platform".
 *
 * Tiered on purpose: the metadata id is authoritative when RomM matched the ROM,
 * the slug covers matches that came from a provider other than IGDB, and the
 * tag-stripped filename is the last resort for ROMs the server never identified.
 * Platform is always part of the key so a cross-platform search does not merge
 * the SNES and Genesis copies of one game.
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

/**
 * One game, plus every ROM in the library that is a copy of it.
 *
 * [variants] always contains at least [primary] and is ordered so the preferred
 * region comes first — the same order the variant picker shows.
 */
data class RomGroup(
    val key: String,
    val primary: RomEntity,
    val variants: List<RomEntity>,
    /** Canonical region codes across all variants, in [variants] order, deduped. */
    val regions: List<String>,
) {
    val size: Int get() = variants.size
    /** True when this row stands for more than one downloadable copy. */
    val hasVariants: Boolean get() = variants.size > 1
}

/**
 * Fold [roms] into one [RomGroup] per game.
 *
 * Groups appear in the order their first member appeared, so a list the DAO
 * already sorted by name stays sorted.  [preferredRegions] (canonical codes,
 * most-wanted first) decides which variant becomes [RomGroup.primary].
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
                // Tie-break on the filename so the choice is stable across syncs
                // rather than following whatever order the server paged them in.
                { it.fsName },
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

/**
 * Position of a ROM's best-matching region in [preferred]; unmatched sorts last.
 */
fun regionRank(regions: List<String>, preferred: List<String>): Int {
    var best = Int.MAX_VALUE
    for (region in regions) {
        val idx = preferred.indexOf(region)
        if (idx >= 0 && idx < best) best = idx
    }
    return best
}

/**
 * Fallback preference when the device locale's country is not itself a region
 * present in the library.  Ordered by how much of a typical library it covers.
 */
val DEFAULT_REGION_PREFERENCE: List<String> = listOf("US", "WORLD", "EU", "JP")

/** Countries whose ROMs are usually the US release rather than a PAL one. */
private val NTSC_U = setOf("US", "CA", "MX")

/**
 * Region preference for a device whose locale country is [country].
 *
 * The user's own country wins outright when the library actually has a copy for
 * it; beyond that the point is only to stop a UK user's list defaulting to the
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

/**
 * One downloadable copy of a game, as shown in the detail screen's picker.
 * [fsName] is the filename because that is where the revision and dump tags
 * live — two USA copies are otherwise indistinguishable.
 */
data class RomVariant(
    val id: Int,
    val fsName: String,
    val sizeBytes: Long,
    val regions: List<String>,
)

/**
 * Canonical regions for a ROM: what the server recorded, or what the filename
 * tags say when it recorded nothing.  The fallback matters most for ROMs the
 * server could not identify — those are the ones with no other distinguishing
 * metadata, so the filename tag is all the user has to go on.
 */
fun regionsFor(recorded: List<String>, fsName: String): List<String> =
    recorded.ifEmpty { parseRegionsFromFileName(fsName) }
        .map(::normalizeRegion)
        .filter { it.isNotEmpty() }
        .distinct()

/** [regionsFor] over a cached row, whose regions are a JSON-encoded list. */
fun romRegions(rom: RomEntity, decodeJsonList: (String) -> List<String>): List<String> {
    val stored = runCatching { decodeJsonList(rom.regions) }.getOrDefault(emptyList())
    return regionsFor(stored, rom.fsName)
}
