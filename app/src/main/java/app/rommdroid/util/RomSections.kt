package app.rommdroid.util

import app.rommdroid.data.db.RomEntity
import java.text.Normalizer

// Letter runs for the ROM list's sticky headers. The list arrives sorted by the
// DAO; nothing here reorders it, it only labels the runs.

/** Header for rows that do not start with a letter: numbered titles, symbols. */
const val OTHER_SECTION = "#"

/** What the list puts on a row: the matched game name, or the filename. */
val RomEntity.displayName: String
    get() = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags

/** A run of rows sharing one header. [label] is null for a filtered list, where
 *  a handful of rows under a handful of letters is noise, not an index. */
data class RomSection(val label: String?, val groups: List<RomGroup>)

/** The letter [name] sorts under, or [OTHER_SECTION] when it starts with none. */
fun sectionLabel(name: String): String {
    val first = name.trim().firstOrNull() ?: return OTHER_SECTION
    return asciiLetter(first)?.toString() ?: OTHER_SECTION
}

/** The plain A-Z letter behind [c]. NFD splits an accent into a combining
 *  character, so "Okami" with a macron still files under O. */
private fun asciiLetter(c: Char): Char? {
    val upper = c.uppercaseChar()
    if (upper in 'A'..'Z') return upper
    return Normalizer.normalize(upper.toString(), Normalizer.Form.NFD)
        .firstOrNull { it in 'A'..'Z' }
}

/**
 * Section [groups] by first letter, in input order. A repeated letter rejoins
 * its earlier section rather than opening a second header, so a title the
 * collation sorted away from its letter still lands where the user looks.
 */
fun sectionsOf(groups: List<RomGroup>): List<RomSection> =
    groups.groupBy { sectionLabel(it.primary.displayName) }
        .map { (label, rows) -> RomSection(label, rows) }

/**
 * Maps between scroll position and letter, for the fast scroller's bubble and
 * the shoulder-button jumps. Item indices count headers, which [LazyColumn]
 * gives slots of their own.
 */
class SectionIndex internal constructor(
    private val starts: List<Int>,
    private val labels: List<String>,
) {
    val isEmpty: Boolean get() = starts.isEmpty()

    /** The section [itemIndex] falls in, or null if it sits above the first. */
    fun labelAt(itemIndex: Int): String? =
        starts.indexOfLast { it <= itemIndex }.takeIf { it >= 0 }?.let(labels::get)

    /** First section starting after [itemIndex]; null at the end of the list. */
    fun startAfter(itemIndex: Int): Int? = starts.firstOrNull { it > itemIndex }

    /** Last section starting strictly before [itemIndex], so a press mid-section
     *  lands on its own header first, like a "previous track" button. */
    fun startBefore(itemIndex: Int): Int? = starts.lastOrNull { it < itemIndex }
}

/** Index [sections] as laid out: a header item, then its rows. An unlabelled run
 *  contributes nothing, so a filtered list indexes to empty and the jump
 *  affordances hide themselves. */
fun sectionIndexOf(sections: List<RomSection>): SectionIndex {
    val starts = mutableListOf<Int>()
    val labels = mutableListOf<String>()
    var itemIndex = 0
    for (section in sections) {
        section.label?.let { label ->
            starts += itemIndex
            labels += label
            itemIndex++
        }
        itemIndex += section.groups.size
    }
    return SectionIndex(starts, labels)
}
