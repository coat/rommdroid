package app.rommdroid.ui.romlist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.domain.NO_REGION
import app.rommdroid.domain.RegionCount
import app.rommdroid.domain.regionFlag
import app.rommdroid.domain.regionName
import app.rommdroid.domain.RomSort
import app.rommdroid.domain.RomSortKey
import app.rommdroid.ui.gamepad.focusOutline
import kotlinx.coroutines.flow.*

/**
 * The sort keys and the region chips, one scrolling row each. Rows that scroll
 * rather than wrap because a No-Intro set can name twenty regions, and a
 * wrapped row of them in landscape would leave no list underneath.
 *
 * The sort is single-choice and a second press on the chosen key reverses it;
 * the arrow says which way it currently runs. Regions are any-of.
 */
@Composable
internal fun ListOptions(
    sort: RomSort,
    onSort: (RomSortKey) -> Unit,
    regions: List<RegionCount>,
    selected: Set<String>,
    onToggleRegion: (String) -> Unit,
    /** Lands on the chosen sort key, the one thing the user is sure to know. */
    focusRequester: FocusRequester,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            OptionRow("Sort") {
                RomSortKey.entries.forEach { key ->
                    val chosen = key == sort.key
                    FilterChip(
                        selected = chosen,
                        onClick  = { onSort(key) },
                        label    = { Text(key.label) },
                        trailingIcon = if (!chosen) null else {
                            {
                                Icon(
                                    imageVector = if (sort.descending) {
                                        Icons.Default.ArrowDownward
                                    } else {
                                        Icons.Default.ArrowUpward
                                    },
                                    contentDescription = if (sort.descending) "Descending" else "Ascending",
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        },
                        modifier = Modifier
                            .then(if (chosen) Modifier.focusRequester(focusRequester) else Modifier)
                            .focusOutline(),
                    )
                }
            }
            if (regions.isNotEmpty()) {
                OptionRow("Region") {
                    regions.forEach { (region, count) ->
                        val label = if (region == NO_REGION) {
                            "No region"
                        } else {
                            listOfNotNull(regionFlag(region), regionName(region)).joinToString(" ")
                        }
                        FilterChip(
                            selected = region in selected,
                            onClick  = { onToggleRegion(region) },
                            // The count says how much a chip is worth pressing;
                            // a chip picked on another platform reads as zero.
                            label    = { Text("$label  $count") },
                            modifier = Modifier.focusOutline(),
                        )
                    }
                }
            }
        }
    }
}

/** A labelled, sideways-scrolling run of chips. */
@Composable
private fun OptionRow(label: String, chips: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelLarge,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        chips()
    }
}
