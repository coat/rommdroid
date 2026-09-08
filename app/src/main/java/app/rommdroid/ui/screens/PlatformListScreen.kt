package app.rommdroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.PlatformEntity
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
import app.rommdroid.util.formatSize
import javax.inject.Inject

// ViewModel

@HiltViewModel
class PlatformListViewModel @Inject constructor(
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformEntity>> =
        repo.observePlatforms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many collections the cache holds. The pinned row draws only when this
     *  is non-zero, so a server with none gets no row to an empty screen. */
    val collectionCount: StateFlow<Int> =
        repo.observeCollectionCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _syncing.value = true
            _error.value   = null
            // One refresh, two fetches: a server too old to serve collections,
            // or a token minted before this app asked for collections.read,
            // must not take the platform list down with it. When both fail the
            // platforms' failure is the one worth reporting.
            try {
                var failure: Exception? = null
                try {
                    repo.syncPlatforms()
                } catch (e: Exception) {
                    failure = e
                }
                try {
                    repo.syncCollections()
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
                _error.value = failure?.message
            } finally {
                _syncing.value = false
            }
        }
    }

    fun coverUrl(platform: PlatformEntity): String? = artworkUrl(
        credentials.serverUrl,
        platform.urlLogo,
    )
}

// Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformListScreen(
    viewModel: PlatformListViewModel,
    onPlatformClick: (Int) -> Unit,
    onCollectionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val platforms   by viewModel.platforms.collectAsState()
    val collections by viewModel.collectionCount.collectAsState()
    val syncing     by viewModel.syncing.collectAsState()
    val error       by viewModel.error.collectAsState()

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // Which row the controller is on, kept across a trip into a platform. Keyed
    // by string because the pinned Collections row has no platform id.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val rowFocus   = remember { FocusRequester() }

    // Both kinds of row through one `items()` call, sharing the key space the
    // focus memory above remembers a position in.
    val rows = remember(platforms, collections) {
        buildList {
            if (collections > 0) add(PlatformListRow.Collections(collections))
            platforms.forEach { add(PlatformListRow.Platform(it)) }
        }
    }
    val focusTarget = focusedKey?.takeIf { key -> rows.any { it.key == key } }
        ?: rows.firstOrNull()?.key
    RestoreFocus(rowFocus, ready = focusTarget != null)

    // The collections land after the platforms, and LazyColumn holds position by
    // key, so the new top row arrives just above the viewport. Bring it into
    // view, but only for a reader still at the top.
    val pinned = rows.firstOrNull() is PlatformListRow.Collections
    LaunchedEffect(pinned) {
        if (pinned && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    GamepadHandler { action ->
        when (action) {
            GamepadAction.Search   -> { onSearchClick(); true }
            GamepadAction.PageUp   -> { scope.launch { listState.scrollPage(-1) }; true }
            GamepadAction.PageDown -> { scope.launch { listState.scrollPage(1) }; true }
            else                   -> false
        }
    }
    StickScroll(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("Platforms") },
                actions = {
                    // The list syncs once on creation, so without this a deleted
                    // platform only clears on the next launch.
                    IconButton(
                        onClick  = { viewModel.refresh() },
                        enabled  = !syncing,
                        modifier = Modifier.focusOutline(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSearchClick, modifier = Modifier.focusOutline()) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onDownloadsClick, modifier = Modifier.focusOutline()) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = onSettingsClick, modifier = Modifier.focusOutline()) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Open"),
                    GamepadHint(GamepadButton.Y, "Search"),
                    GamepadHint(GamepadButton.Start, "Downloads"),
                    GamepadHint(GamepadButton.Select, "Settings"),
                )
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                syncing && platforms.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error != null && platforms.isEmpty() -> {
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
                else -> {
                    LazyColumn(state = listState) {
                        items(rows, key = { it.key }) { row ->
                            val requester = rowFocus.takeIf { row.key == focusTarget }
                            when (row) {
                                // Pinned rather than given a level of its own: a
                                // tab or drawer would cost a press on every trip
                                // to a platform.
                                is PlatformListRow.Collections -> CollectionsRow(
                                    count          = row.count,
                                    onClick        = onCollectionsClick,
                                    onFocused      = { focusedKey = row.key },
                                    focusRequester = requester,
                                )
                                is PlatformListRow.Platform -> PlatformRow(
                                    platform       = row.platform,
                                    coverUrl       = viewModel.coverUrl(row.platform),
                                    onClick        = { onPlatformClick(row.platform.id) },
                                    onFocused      = { focusedKey = row.key },
                                    focusRequester = requester,
                                )
                            }
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
private fun PlatformRow(
    platform: PlatformEntity,
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
        headlineContent = { Text(platform.displayName) },
        supportingContent = { Text("${platform.romCount} ROMs") },
        leadingContent = {
            if (coverUrl != null) {
                AsyncImage(
                    model             = coverUrl,
                    contentDescription = platform.displayName,
                    modifier          = Modifier.size(40.dp),
                )
            } else {
                Icon(
                    imageVector        = Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                )
            }
        },
    )
}

/** One row of the platform list. The pinned Collections entry is a row like any
 *  other, so both go through one `items()` call and share a [key] space. */
private sealed interface PlatformListRow {
    val key: String

    data class Collections(val count: Int) : PlatformListRow {
        override val key get() = "collections"
    }

    data class Platform(val platform: PlatformEntity) : PlatformListRow {
        override val key get() = "platform:${platform.id}"
    }
}

/** The way into the collections. Drawn like a platform row so the D-pad reads it
 *  as one; the tint and bookmark say it leads elsewhere. */
@Composable
private fun CollectionsRow(
    count: Int,
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
            Text("Collections", color = MaterialTheme.colorScheme.primary)
        },
        supportingContent = {
            Text(if (count == 1) "1 collection" else "$count collections")
        },
        leadingContent = {
            Icon(
                imageVector        = Icons.Default.Bookmarks,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(40.dp),
            )
        },
    )
}
