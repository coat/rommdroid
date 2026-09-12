package app.rommdroid.ui.romlist

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import app.rommdroid.domain.RomGroup
import app.rommdroid.domain.RomSection
import app.rommdroid.domain.SectionIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The lazy-list key of a section header, so a landing spot can skip them. */
internal fun sectionKey(label: String) = "section:$label"

/**
 * The row the controller is on in a ROM list, and the ways of moving it: where
 * X downloads from, where focus returns to from a detail page, and where a
 * page or letter jump leaves the cursor so the next D-pad press does not snap
 * the list back to the off-screen row it was on.
 */
@Stable
class RomListCursor internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    key: MutableState<String?>,
) {
    /** Key of the focused group. Saved, so it survives the trip into a detail page. */
    var focusedKey: String? by key

    /** Attached to the row [focusTarget] names. */
    val rowFocus = FocusRequester()

    /** The focused group as it currently stands in [sections], if it survived
     *  the last keystroke or filter change. */
    fun groupIn(sections: List<RomSection>): RomGroup? =
        sections.firstNotNullOfOrNull { section -> section.groups.firstOrNull { it.key == focusedKey } }

    /** The row that should carry [rowFocus]: the remembered one, else the first. */
    fun focusTarget(sections: List<RomSection>): String? =
        groupIn(sections)?.key ?: sections.firstKey()

    /** Put the cursor on a row. Retried over a few frames: the requester has to
     *  move onto the named row, and whatever grabbed focus meanwhile (the back
     *  arrow, when the filter field is torn down) has to lose it. */
    fun focusRow(key: String?) {
        if (key == null) return
        focusedKey = key
        scope.launch {
            repeat(3) {
                withFrameNanos { }
                runCatching { rowFocus.requestFocus() }
            }
        }
    }

    /** Back onto the list from chrome that is closing - the filter field or
     *  the option chips - which would otherwise leave focus on the bar. */
    fun returnToList(sections: List<RomSection>) =
        focusRow(focusedKey ?: sections.firstKey())

    /** Move the cursor to where a jump landed: the first visible row that is
     *  not a header. */
    suspend fun focusTopRow() {
        withFrameNanos { }
        val landed = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { (it.key as? String)?.startsWith(SECTION_PREFIX) == false }
            ?.key as? String
        focusRow(landed)
    }

    /**
     * Step a letter, landing on the header so the letter is on screen. Always
     * consumed: at the ends of the list and on a filtered one the button does
     * nothing rather than falling through.
     */
    fun jumpSection(index: SectionIndex, forwards: Boolean): Boolean {
        if (index.isEmpty) return true
        val from   = listState.firstVisibleItemIndex
        val target = if (forwards) index.startAfter(from) else index.startBefore(from)
        target?.let { scope.launch { listState.scrollToItem(it); focusTopRow() } }
        return true
    }

    private companion object {
        val SECTION_PREFIX = sectionKey("")
    }
}

private fun List<RomSection>.firstKey(): String? = firstOrNull()?.groups?.firstOrNull()?.key

@Composable
fun rememberRomListCursor(listState: LazyListState): RomListCursor {
    val scope = rememberCoroutineScope()
    val key = rememberSaveable { mutableStateOf<String?>(null) }
    return remember(listState, scope) { RomListCursor(listState, scope, key) }
}
