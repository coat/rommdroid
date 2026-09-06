package app.rommdroid.util

import app.rommdroid.data.db.RomEntity
import java.text.Normalizer

/**
 * Splitting a platform's list into the letter runs a sticky header sits on.
 *
 * A platform can hold many hundreds of ROMs, and Compose has no fast scroller
 * of its own — so the headers are what tells the user where in the alphabet a
 * flick has landed them.  The list arrives already sorted by the DAO; nothing
 * here reorders it, it only labels the runs.
 */

/** Header for rows that do not start with a letter: numbered titles, symbols. */
const val OTHER_SECTION = "#"

/** What the list puts on a row: the matched game name, or the filename. */
val RomEntity.displayName: String
    get() = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags

/**
 * A run of consecutive rows sharing one header.
 *
 * [label] is null for a list that carries no headers at all — a filtered list,
 * where a handful of rows under a handful of letters is noise rather than an
 * index.
 */
data class RomSection(val label: String?, val groups: List<RomGroup>)

/** The letter [name] sorts under, or [OTHER_SECTION] when it starts with none. */
fun sectionLabel(name: String): String {
    val first = name.trim().firstOrNull() ?: return OTHER_SECTION
    return asciiLetter(first)?.toString() ?: OTHER_SECTION
}

/**
 * The plain A–Z letter behind [c], if there is one.
 *
 * Accents fold onto their base letter: "Ōkami" belongs under O, not under a
 * section of its own at the end of the list.  NFD splits the accent off into a
 * separate combining character, so whatever ASCII letter survives that is the
 * base one.
 */
private fun asciiLetter(c: Char): Char? {
    val upper = c.uppercaseChar()
    if (upper in 'A'..'Z') return upper
    return Normalizer.normalize(upper.toString(), Normalizer.Form.NFD)
        .firstOrNull { it in 'A'..'Z' }
}

/**
 * Section [groups] by first letter, in the order they came in.
 *
 * A letter that has been seen before joins that earlier section instead of
 * opening a second one under the same header, so a name the collation sorted
 * away from its letter — an accented title, a variant whose preferred region is
 * titled differently — still appears under the header the user would look for.
 */
fun sectionsOf(groups: List<RomGroup>): List<RomSection> =
    groups.groupBy { sectionLabel(it.primary.displayName) }
        .map { (label, rows) -> RomSection(label, rows) }

/**
 * Where each section begins once the list is laid out.
 *
 * Turns a scroll position back into a letter — what the fast scroller's bubble
 * shows — and a letter back into a scroll position, which is how the shoulder
 * buttons jump.  Item indices count the headers too, since [LazyColumn] gives
 * each one a slot of its own.
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

    /**
     * Last section starting before [itemIndex]; null at the top.
     *
     * Strictly before, so a press from the middle of a section lands on that
     * section's own header first and only then walks back — the same as a
     * "previous track" button.
     */
    fun startBefore(itemIndex: Int): Int? = starts.lastOrNull { it < itemIndex }
}

/**
 * Index [sections] as they will be laid out: a header item, then its rows.
 *
 * An unlabelled run — the filtered list — contributes no header and no entry,
 * so a filtered list indexes to nothing and the jumping affordances take
 * themselves off screen.
 */
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
