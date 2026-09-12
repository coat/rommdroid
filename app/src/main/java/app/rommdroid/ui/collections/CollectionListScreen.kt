package app.rommdroid.ui.collections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rommdroid.data.db.CollectionEntity
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.components.ConnectionError
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.ui.gamepad.RestoreFocus
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CollectionListScreen(
    viewModel: CollectionListViewModel,
    onCollectionClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val syncing     by viewModel.sync.syncing.collectAsStateWithLifecycle()
    val error       by viewModel.sync.error.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // As on the platform list: the focused row survives a trip into a collection.
    var focusedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val rowFocus  = remember { FocusRequester() }
    val focusTarget = focusedId?.takeIf { id -> collections.any { it.id == id } }
        ?: collections.firstOrNull()?.id
    RestoreFocus(rowFocus, ready = focusTarget != null)

    ListGamepadScrolling(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collections") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(
                        onClick  = { viewModel.refresh() },
                        enabled  = !syncing,
                        modifier = Modifier.focusOutline(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Open"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                syncing && collections.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error != null && collections.isEmpty() -> {
                    ConnectionError(error, onRetry = viewModel::refresh, Modifier.align(Alignment.Center))
                }
                // Reachable when the last collection is deleted while this
                // screen is open, and the row that leads here is already gone.
                collections.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Bookmarks,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No collections", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Collections you make in RomM show up here.",
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    LazyColumn(state = listState) {
                        items(collections, key = { it.id }) { collection ->
                            CollectionRow(
                                collection = collection,
                                coverUrl   = viewModel.coverUrl(collection),
                                onClick    = { onCollectionClick(collection.id) },
                                onFocused  = { focusedId = collection.id },
                                focusRequester =
                                    rowFocus.takeIf { collection.id == focusTarget },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (syncing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(
    collection: CollectionEntity,
    coverUrl: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    ListItem(
        modifier = Modifier.gamepadRow(
            onClick        = onClick,
            focusRequester = focusRequester,
            onFocused      = onFocused,
        ),
        headlineContent = {
            Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            // The server's count, so it is right before the ROMs are fetched.
            val games = if (collection.romCount == 1) "1 game" else "${collection.romCount} games"
            Text(games, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            when {
                coverUrl != null -> AsyncImage(
                    model              = coverUrl,
                    contentDescription = collection.name,
                    modifier           = Modifier.size(40.dp),
                )
                // Favourites is RomM's own, and wears a heart in the web UI.
                collection.isFavorite -> Icon(
                    imageVector        = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                )
                else -> Icon(
                    imageVector        = Icons.Default.Bookmarks,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                )
            }
        },
    )
}
