package app.rommdroid.util

import app.rommdroid.data.db.RomEntity

// How the ROM list is cut down and ordered before it is sectioned. Both act on
// what the user can see: the region filter runs on ROMs so a game's row shows a
// copy from the chosen region, the sort runs on groups so a rating or a date
// belongs to the game rather than to whichever copy leads.

/** What the list orders by. [descendingByDefault] is the direction a fresh
 *  pick gets: nobody sorts by rating to see the worst game first. */
enum class RomSortKey(val label: String, val descendingByDefault: Boolean) {
    Name("Name", descendingByDefault = false),
    Rating("Rating", descendingByDefault = true),
    Released("Released", descendingByDefault = true),
    Added("Added", descendingByDefault = true),
    Size("Size", descendingByDefault = true),
}

data class RomSort(val key: RomSortKey, val descending: Boolean) {
    /** The same key the other way up. */
    fun reversed(): RomSort = copy(descending = !descending)

    companion object {
        val DEFAULT = RomSort(RomSortKey.Name, descending = false)

        /** A key in its natural direction. */
        fun of(key: RomSortKey): RomSort = RomSort(key, key.descendingByDefault)
    }
}

/** Stands in for "has no region" in a region filter, so unidentified ROMs can
 *  be kept or dropped on purpose rather than always dropped. Not a code
 *  [normalizeRegion] can produce: it lower-cases nothing and has no alias. */
const val NO_REGION = "-"

/**
 * The ROMs from [selected] regions, in input order. Empty [selected] keeps
 * everything. Applied before grouping so a game only folds the copies the user
 * asked for: the row then shows one of them and long-press downloads it.
 */
fun keepRegions(
    roms: List<RomEntity>,
    selected: Set<String>,
    regionsOf: (RomEntity) -> List<String>,
): List<RomEntity> {
    if (selected.isEmpty()) return roms
    return roms.filter { rom ->
        val regions = regionsOf(rom)
        if (regions.isEmpty()) NO_REGION in selected else regions.any { it in selected }
    }
}

/** A region and how many ROMs in the list carry it. */
data class RegionCount(val region: String, val count: Int)

/**
 * Every region in [roms] with its count, most common first, with [NO_REGION]
 * at the end whatever its count: it is the odd one out, and the end is where
 * the eye looks for it. Counted per ROM rather than per game, since that is
 * what the filter runs on.
 */
fun countRegions(
    roms: List<RomEntity>,
    regionsOf: (RomEntity) -> List<String>,
): List<RegionCount> {
    val counts = HashMap<String, Int>()
    for (rom in roms) {
        val regions = regionsOf(rom).ifEmpty { listOf(NO_REGION) }
        for (region in regions) counts[region] = (counts[region] ?: 0) + 1
    }
    return counts.entries
        .map { (region, count) -> RegionCount(region, count) }
        .sortedWith(
            compareBy<RegionCount> { it.region == NO_REGION }
                .thenByDescending { it.count }
                .thenBy { it.region }
        )
}

/**
 * [groups] in [sort] order. The input arrives name-sorted from the DAO, so Name
 * is the identity or its mirror, and every other key falls back to that order
 * for ties because the sort is stable. A game without the value sorts last in
 * both directions: an unrated game is not the worst-rated one.
 */
fun sortGroups(groups: List<RomGroup>, sort: RomSort): List<RomGroup> {
    if (sort.key == RomSortKey.Name) return if (sort.descending) groups.asReversed() else groups
    val comparator: Comparator<RomGroup> = when (sort.key) {
        RomSortKey.Rating   -> nullsLast(sort.descending) { it.rating }
        RomSortKey.Released -> nullsLast(sort.descending) { it.firstReleaseDate }
        RomSortKey.Added    -> nullsLast(sort.descending) { it.createdAt }
        // A size of 0 means the server did not know, not an empty file.
        RomSortKey.Size     -> nullsLast(sort.descending) { it.primary.fsSizeBytes.takeIf { b -> b > 0 } }
        RomSortKey.Name     -> error("handled above")
    }
    return groups.sortedWith(comparator)
}

private inline fun <T : Comparable<T>> nullsLast(
    descending: Boolean,
    crossinline value: (RomGroup) -> T?,
): Comparator<RomGroup> {
    val byValue = if (descending) compareByDescending(value) else compareBy(value)
    return compareBy<RomGroup> { value(it) == null }.then(byValue)
}
