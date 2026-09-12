package app.rommdroid.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.ui.gamepad.rememberButtonLayout
import app.rommdroid.ui.components.TransferProgress
import app.rommdroid.util.formatSize
import kotlinx.coroutines.flow.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onRomClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val (active, finished) = items.partition { !it.status.isFinished }

    val listState = rememberLazyListState()

    // X mirrors the row's trailing button, whichever it is showing.
    var focusedId by remember { mutableStateOf<String?>(null) }
    val focused = items.firstOrNull { it.id == focusedId }

    GamepadHandler { action ->
        when (action) {
            GamepadAction.Download -> {
                focused?.let {
                    when (it.status) {
                        DownloadStatus.QUEUED, DownloadStatus.RUNNING -> viewModel.cancel(it.id)
                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> viewModel.retry(it.id)
                        DownloadStatus.SUCCEEDED -> viewModel.remove(it.id)
                    }
                }
                true
            }
            // Start opened this screen; pressing it again closes rather than stacks.
            GamepadAction.Downloads -> { onBack(); true }
            else                    -> false
        }
    }
    ListGamepadScrolling(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (finished.isNotEmpty()) {
                        TextButton(
                            onClick  = { viewModel.clearFinished() },
                            modifier = Modifier.focusOutline(),
                        ) { Text("Clear") }
                    }
                },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Open"),
                    GamepadHint(GamepadButton.X, "Cancel / retry / remove"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement   = Arrangement.Center,
                horizontalAlignment   = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Nothing downloading", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                // Name only the affordance the reader can actually perform, in
                // their own lettering, which is not always the keycode's "X".
                val buttons = rememberButtonLayout()
                Text(
                    if (buttons != null) {
                        "Press ${GamepadButton.X.glyph(buttons)} on a game in " +
                            "any ROM list to add it here."
                    } else {
                        "Long-press a game in any ROM list to add it here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (active.isNotEmpty()) {
                item { QueueHeader("In progress - ${active.size}") }
                items(active, key = { it.id }) { item ->
                    DownloadRow(
                        item      = item,
                        onClick   = { onRomClick(item.romId) },
                        onCancel  = { viewModel.cancel(item.id) },
                        onRetry   = { viewModel.retry(item.id) },
                        onRemove  = { viewModel.remove(item.id) },
                        onFocused = { focusedId = item.id },
                    )
                    HorizontalDivider()
                }
            }
            if (finished.isNotEmpty()) {
                item { QueueHeader("Finished") }
                items(finished, key = { it.id }) { item ->
                    DownloadRow(
                        item      = item,
                        onClick   = { onRomClick(item.romId) },
                        onCancel  = { viewModel.cancel(item.id) },
                        onRetry   = { viewModel.retry(item.id) },
                        onRemove  = { viewModel.remove(item.id) },
                        onFocused = { focusedId = item.id },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onFocused: () -> Unit,
) {
    // The whole row is the focus target, so X does the trailing button's job
    // without walking into it.
    Column(Modifier.gamepadRow(onClick = onClick, onFocused = onFocused)) {
        ListItem(
            headlineContent   = { Text(item.fileName, maxLines = 2) },
            supportingContent = {
                Column {
                    Text("${item.romName}  -  ${item.platformName}")
                    Text(
                        item.statusLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == DownloadStatus.FAILED)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                when (item.status) {
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
                        IconButton(onClick = onCancel, modifier = Modifier.focusOutline()) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                        IconButton(onClick = onRetry, modifier = Modifier.focusOutline()) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }
                    DownloadStatus.SUCCEEDED ->
                        IconButton(onClick = onRemove, modifier = Modifier.focusOutline()) {
                            Icon(Icons.Default.Close, contentDescription = "Remove from list")
                        }
                }
            },
        )
        if (item.status == DownloadStatus.RUNNING) {
            TransferProgress(
                progress = item.progress,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Where this download has got to, and where it is going. */
private fun DownloadItem.statusLine(): String = when (status) {
    DownloadStatus.QUEUED    -> "Waiting  -  ${totalBytes.formatSize()}  ->  $destinationPath"
    DownloadStatus.RUNNING   ->
        "${downloadedBytes.formatSize()} / ${totalBytes.formatSize()}  ->  $destinationPath"
    DownloadStatus.SUCCEEDED -> "Saved to $destinationPath  -  ${totalBytes.formatSize()}"
    DownloadStatus.FAILED    -> error ?: "Download failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}
