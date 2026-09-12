package app.rommdroid.ui.romdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.rommdroid.domain.regionSummary
import app.rommdroid.domain.RomVariant
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.util.formatSize
import kotlinx.coroutines.flow.*

@Composable
internal fun RomVariantRow(
    variant: RomVariant,
    selected: Boolean,
    onDevice: Boolean,
    onClick: () -> Unit,
) {
    val flags = regionSummary(variant.regions)
    // A size of 0 means unknown, not a zero-byte ROM.
    val detail = buildList {
        variant.sizeBytes.takeIf { it > 0 }?.let { add(it.formatSize()) }
        if (onDevice) add("On device")
    }
    ListItem(
        modifier = Modifier.gamepadRow(onClick = onClick),
        // The filename headlines: two copies from one region are told apart
        // only by their "(Rev 1)" / "(Beta)" tags.
        headlineContent   = { Text(variant.fsName) },
        supportingContent = if (detail.isEmpty()) null else {
            { Text(detail.joinToString("  -  ")) }
        },
        leadingContent    = {
            if (flags.isNotEmpty()) {
                Text(flags, style = MaterialTheme.typography.titleMedium)
            }
        },
        trailingContent   = {
            RadioButton(selected = selected, onClick = onClick)
        },
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            ListItemDefaults.colors()
        },
    )
}
