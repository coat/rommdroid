package app.rommdroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.CollectionEntity
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadButton
import app.rommdroid.ui.components.GamepadHandler
import app.rommdroid.ui.components.GamepadHint
import app.rommdroid.ui.components.GamepadHintBar
import app.rommdroid.ui.components.RestoreFocus
import app.rommdroid.ui.components.StickScroll
import app.rommdroid.ui.components.focusOutline
import app.rommdroid.ui.components.gamepadRow
import app.rommdroid.ui.components.scrollPage
import app.rommdroid.util.artworkUrl
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CollectionListViewModel @Inject constructor(
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
) : ViewModel() {

    val collections: StateFlow<List<CollectionEntity>> =
        repo.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-fetch the list.
     *
     * The platform list already synced these on the way in, so this is for a
     * collection renamed or emptied while the app was open — and for the trip
     * back after a failure, since the cached list is all there is offline.
     */
    fun refresh() {
        viewModelScope.launch {
            _syncing.value = true
            _error.value   = null
            try {
                repo.syncCollections()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _syncing.value = false
            }
        }
    }

    fun coverUrl(collection: CollectionEntity): String? = artworkUrl(
        credentials.serverUrl,
        collection.pathCoverSmall,
        collection.pathCoverLarge,
        collection.urlCover,
    )
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionListScreen(
    viewModel: CollectionListViewModel,
    onCollectionClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val collections by viewModel.collections.collectAsState()
    val syncing     by viewModel.syncing.collectAsState()
    val error       by viewModel.error.collectAsState()

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // Same as the platform list: the row the controller is on survives a trip
    // into a collection, so coming back does not start at the top.
    var focusedId by rememberSaveable { mutableStateOf<Int?>(null) }
    val rowFocus  = remember { FocusRequester() }
    val focusTarget = focusedId?.takeIf { id -> collections.any { it.id == id } }
        ?: collections.firstOrNull()?.id
    RestoreFocus(rowFocus, ready = focusTarget != null)

    GamepadHandler { action ->
        when (action) {
            GamepadAction.PageUp   -> { scope.launch { listState.scrollPage(-1) }; true }
            GamepadAction.PageDown -> { scope.launch { listState.scrollPage(1) }; true }
            else                   -> false
        }
    }
    StickScroll(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collections") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Could not reach server", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
                // Reachable when the last collection is deleted on the server
                // while this screen is open — the row that leads here is gone
                // by then, so this is the way back rather than a dead end.
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
            // The count is the server's, so it is right before the collection
            // has ever been opened and its ROMs fetched.
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
                // Favourites is the one collection RomM makes itself, and the
                // heart is what it wears in the web UI.
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
