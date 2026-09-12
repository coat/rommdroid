package app.rommdroid.ui.romlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.domain.displayName
import app.rommdroid.domain.regionSummary
import app.rommdroid.domain.RomGroup
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.components.RatingBadge
import app.rommdroid.util.formatSize
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.*

/** The letter a run of rows sits under. Opaque, or the list scrolling beneath
 *  the sticky header shows through it. */
@Composable
internal fun SectionHeader(label: String) {
    Surface(
        color    = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.titleSmall,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RomRow(
    group: RomGroup,
    coverUrl: String?,
    status: DownloadStatus?,
    onDevice: Boolean,
    /** Name the platform on the supporting line - a collection mixes them. */
    showPlatform: Boolean,
    queueing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val rom = group.primary
    ListItem(
        modifier = Modifier.gamepadRow(
            onClick          = onClick,
            onLongClick      = onLongClick,
            onLongClickLabel = "Download",
            focusRequester   = focusRequester,
            onFocused        = onFocused,
        ),
        headlineContent  = { Text(rom.displayName) },
        supportingContent = {
            // Flags first: when a game has several copies they are the only
            // thing that tells the rows apart, so they lead the line.
            // For a single-variant group these are just that ROM's own regions.
            val flags = regionSummary(group.regions)
            // On a collection the platform leads what is left, the way the
            // search results name it: the same game turns up under three
            // systems and the rows are otherwise identical.
            val detail = listOfNotNull(
                rom.platformDisplayName.takeIf { showPlatform && it.isNotBlank() },
                if (group.hasVariants) {
                    "${group.size} versions"
                } else {
                    "${rom.fsExtension.uppercase()}  -  ${rom.fsSizeBytes.formatSize()}"
                },
            ).joinToString("  -  ")
            // The score rides on this line rather than taking one of its own:
            // a list of these is scrolled past a screenful at a time, and a
            // third line per row costs more than the number is worth.  The
            // text yields to the pill instead of pushing it off a narrow
            // screen - the flags and size are still legible truncated.
            //
            // Both sit on one baseline because Material3 reads a supporting
            // slot whose first and last baseline differ as wrapped text, and
            // grows every row to the taller three-line item to fit it.  That
            // is also where the pill wants to be: level with the line it
            // annotates rather than centred against it.
            Row {
                Text(
                    if (flags.isEmpty()) detail else "$flags  -  $detail",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                )
                group.rating?.let { rating ->
                    Spacer(Modifier.width(8.dp))
                    RatingBadge(
                        rating,
                        compact  = true,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        },
        leadingContent = {
            if (coverUrl != null) {
                AsyncImage(
                    model              = coverUrl,
                    contentDescription = rom.name,
                    modifier           = Modifier.size(48.dp),
                )
            } else {
                Icon(
                    imageVector        = Icons.Default.VideogameAsset,
                    contentDescription = null,
                    modifier           = Modifier.size(48.dp),
                )
            }
        },
        // No per-row download button by design: the gesture is a long-press, and
        // this corner only reports back what came of it.
        trailingContent = {
            // An in-flight or failed transfer is the more urgent thing to say;
            // a plain "you have this" only shows once nothing is happening.
            val live = status?.takeIf { it != DownloadStatus.CANCELLED && it != DownloadStatus.SUCCEEDED }
            when {
                queueing       -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                live != null   -> DownloadBadge(live)
                onDevice       -> Icon(
                    imageVector        = Icons.Default.CheckCircle,
                    contentDescription = "On device",
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(20.dp),
                )
                status != null -> DownloadBadge(status)
            }
        },
    )
}

/** The download state of a row, as one glyph. */
@Composable
private fun DownloadBadge(status: DownloadStatus) {
    val (icon: ImageVector, description: String, tint) = when (status) {
        DownloadStatus.RUNNING   -> Triple(Icons.Default.Download, "Downloading", MaterialTheme.colorScheme.primary)
        DownloadStatus.QUEUED    -> Triple(Icons.Default.Schedule, "Queued", MaterialTheme.colorScheme.onSurfaceVariant)
        DownloadStatus.SUCCEEDED -> Triple(Icons.Default.CheckCircle, "Downloaded", MaterialTheme.colorScheme.primary)
        DownloadStatus.FAILED    -> Triple(Icons.Default.ErrorOutline, "Download failed", MaterialTheme.colorScheme.error)
        DownloadStatus.CANCELLED -> return
    }
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
}
