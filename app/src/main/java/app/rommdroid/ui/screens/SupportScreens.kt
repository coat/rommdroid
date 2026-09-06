package app.rommdroid.ui.screens

import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.rommdroid.data.db.BaseFolderEntity
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.data.download.asMessage
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.GamepadLayout
import app.rommdroid.data.repository.GamepadLayoutRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.data.repository.ServerConnector
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadButton
import app.rommdroid.ui.components.GamepadHandler
import app.rommdroid.ui.components.GamepadHint
import app.rommdroid.ui.components.GamepadHintBar
import app.rommdroid.ui.components.InputKind
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.StickScroll
import app.rommdroid.ui.components.focusOutline
import app.rommdroid.ui.components.gamepadRow
import app.rommdroid.ui.components.rememberButtonLayout
import app.rommdroid.ui.components.rememberInputFieldHandle
import app.rommdroid.ui.components.scrollPage
import app.rommdroid.ui.components.withButton
import app.rommdroid.util.RomGroup
import app.rommdroid.util.formatSize
import app.rommdroid.util.groupRoms
import app.rommdroid.util.regionPreference
import app.rommdroid.util.regionSummary
import java.util.Locale
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Search
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: RomRepository,
    private val queue: DownloadQueue,
) : ViewModel() {

    val query = MutableStateFlow("")

    private val _offline = MutableStateFlow(false)
    /** True when the last search fell back to the (partial) local cache. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    val results: StateFlow<List<RomGroup>> = query
        .debounce(300)
        .mapLatest { q ->
            if (q.length < 2) return@mapLatest emptyList()
            // Search the server so every platform is covered, not just the ones
            // already synced into Room; drop to the cache only if it is down.
            val roms = try {
                repo.searchRemote(q).also { _offline.value = false }
            } catch (e: Exception) {
                _offline.value = true
                repo.searchLocal(q)
            }
            // Same fold as the platform list, so a search for "zelda" does not
            // return the same game five times over.  The key carries the
            // platform id, so cross-platform hits stay separate rows.
            groupRoms(
                roms             = roms,
                preferredRegions = regionPreference(Locale.getDefault().country),
                regionsOf        = repo::regionsOf,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Rows with a long-press in flight; the detail fetch takes a moment. */
    private val _queueing = MutableStateFlow<Set<String>>(emptySet())
    val queueing: StateFlow<Set<String>> = _queueing.asStateFlow()

    private val _messages = MutableSharedFlow<QueueMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<QueueMessage> = _messages.asSharedFlow()

    /** Same long-press gesture as the ROM list: queue the copy the row shows. */
    fun download(group: RomGroup) {
        if (group.key in _queueing.value) return
        viewModelScope.launch {
            _queueing.value += group.key
            try {
                val result = queue.enqueueRom(group.primary.id)
                _messages.emit(result.asMessage(regionSummary(repo.regionsOf(group.primary))))
            } finally {
                _queueing.value -= group.key
            }
        }
    }

    fun undo(ids: List<String>) {
        viewModelScope.launch { queue.undo(ids) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onRomClick: (Int) -> Unit,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val query    by viewModel.query.collectAsState()
    val results  by viewModel.results.collectAsState()
    val offline  by viewModel.offline.collectAsState()
    val queueing by viewModel.queueing.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val queryField = rememberInputFieldHandle()
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // The row a controller is on, so X can queue it.  Nothing restores focus
    // here the way the ROM list does: this screen opens on an empty query, and
    // what it opens for is the typing.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focusedGroup = results.firstOrNull { it.key == focusedKey }

    GamepadHandler { action ->
        when (action) {
            GamepadAction.Search -> {
                // A snackbar cannot be tapped with a controller, so while one is
                // offering something Y takes the offer; the label names the
                // button.  Otherwise Y goes back to the field, which is where
                // this screen starts and the one place the buttons cannot
                // otherwise reach once focus is down in the results.
                val offer = snackbarHostState.currentSnackbarData
                    ?.takeIf { it.visuals.actionLabel != null }
                if (offer != null) offer.performAction() else queryField.requestFocus()
                true
            }
            GamepadAction.Download -> {
                focusedGroup?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.download(it)
                }
                true
            }
            GamepadAction.PageUp   -> { scope.launch { listState.scrollPage(-1) }; true }
            GamepadAction.PageDown -> { scope.launch { listState.scrollPage(1) }; true }
            else                   -> false
        }
    }
    StickScroll(listState)

    // Opening search is asking to type, and on a controller there is no way to
    // tap into the field — so it takes focus itself, one frame in, once the
    // view behind it is attached to the window.  Only on a fresh query: coming
    // back from a ROM should not reopen the keyboard over the results.
    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            withFrameNanos { }
            queryField.requestFocus()
        }
    }

    val buttons = rememberButtonLayout()
    LaunchedEffect(buttons) {
        viewModel.messages.collect { message ->
            val action = when {
                message.undoIds.isNotEmpty() -> "Undo"
                message.needsFolder          -> "Set folder"
                else                         -> null
            }
            val result = snackbarHostState.showSnackbar(
                message     = message.text,
                actionLabel = action.withButton(GamepadButton.Y, buttons),
                duration    = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (message.undoIds.isNotEmpty()) viewModel.undo(message.undoIds)
                else if (message.needsFolder) onFolderSettings()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // Results follow the query as it is typed, so the Search
                    // key has nothing to submit — it just puts the keyboard
                    // away so the list underneath it is visible.  Without a
                    // handler nothing happens at all: TextView's built-in
                    // default hides the IME for Done but not for Search, and a
                    // full-screen IME re-opens over the results straight away.
                    OutlinedInputField(
                        value         = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder   = "Search ROMs…",
                        imeAction     = EditorInfo.IME_ACTION_SEARCH,
                        handle        = queryField,
                        onImeAction   = { queryField.hideKeyboard() },
                        modifier      = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Open"),
                    GamepadHint(GamepadButton.X, "Download"),
                    GamepadHint(GamepadButton.Y, "Search box"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (offline) {
                item {
                    ListItem(
                        headlineContent  = { Text("Server unreachable") },
                        supportingContent = {
                            Text("Showing downloaded platforms only.")
                        },
                        leadingContent   = { Icon(Icons.Default.CloudOff, null) },
                    )
                    HorizontalDivider()
                }
            }
            items(results, key = { it.key }) { group ->
                val rom = group.primary
                ListItem(
                    modifier = Modifier.gamepadRow(
                        onClick          = { onRomClick(rom.id) },
                        onLongClick      = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.download(group)
                        },
                        onLongClickLabel = "Download",
                        onFocused        = { focusedKey = group.key },
                    ),
                    headlineContent   = { Text(rom.name ?: rom.fsNameNoTags) },
                    supportingContent = {
                        val flags = regionSummary(group.regions)
                        val detail = if (group.hasVariants) {
                            "${rom.platformDisplayName}  ·  ${group.size} versions"
                        } else {
                            rom.platformDisplayName
                        }
                        Text(if (flags.isEmpty()) detail else "$flags  ·  $detail")
                    },
                    trailingContent = {
                        if (group.key in queueing) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Downloads
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val queue: DownloadQueue,
) : ViewModel() {

    val items: StateFlow<List<DownloadItem>> = queue.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(id: String)  { viewModelScope.launch { queue.cancel(id) } }
    fun retry(id: String)   { viewModelScope.launch { queue.retry(id) } }
    fun remove(id: String)  { viewModelScope.launch { queue.remove(id) } }
    fun clearFinished()     { viewModelScope.launch { queue.clearFinished() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onRomClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val items by viewModel.items.collectAsState()
    val (active, finished) = items.partition { !it.status.isFinished }

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // X is the row's own trailing button, whichever one this row is showing:
    // cancel while it runs, retry once it failed, remove once it is done.
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
            GamepadAction.PageUp   -> { scope.launch { listState.scrollPage(-1) }; true }
            GamepadAction.PageDown -> { scope.launch { listState.scrollPage(1) }; true }
            // Start opened this screen; pressing it again closes it rather than
            // stacking a second copy on the back stack.
            GamepadAction.Downloads -> { onBack(); true }
            else                    -> false
        }
    }
    StickScroll(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
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
                // The gesture and the button are the same action, and the one
                // the reader has no way to perform is not worth naming.  The
                // button is named in the user's own lettering, which is not
                // always the X the keycode is called.
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
                item { QueueHeader("In progress · ${active.size}") }
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
    // The whole row is the focus target, progress bar included: the trailing
    // button is reachable as a second stop, but X on the row does the same
    // thing without having to walk into it.
    Column(Modifier.gamepadRow(onClick = onClick, onFocused = onFocused)) {
        ListItem(
            headlineContent   = { Text(item.fileName, maxLines = 2) },
            supportingContent = {
                Column {
                    Text("${item.romName}  ·  ${item.platformName}")
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
                    // Cancelling is the only useful thing to do to a transfer
                    // that has not finished; everything else is after the fact.
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
            val progress = item.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** One line saying where this download has got to, and where it is going. */
private fun DownloadItem.statusLine(): String = when (status) {
    DownloadStatus.QUEUED    -> "Waiting  ·  ${totalBytes.formatSize()}  →  $destinationPath"
    DownloadStatus.RUNNING   ->
        "${downloadedBytes.formatSize()} / ${totalBytes.formatSize()}  →  $destinationPath"
    DownloadStatus.SUCCEEDED -> "Saved to $destinationPath  ·  ${totalBytes.formatSize()}"
    DownloadStatus.FAILED    -> error ?: "Download failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Where a change to the server address or the account has got to.
 *
 * Distinct from [SetupState] because saving here does not leave the screen:
 * [Saved] is a confirmation the user reads in place, not a signal to navigate.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Loading : ConnectionState
    data class Error(val message: String) : ConnectionState
    data object Saved : ConnectionState
}

/**
 * Settings, including the server address and account the app is signed in with.
 *
 * The stored password is thrown away once setup has traded it for a client API
 * token, so there is nothing to pre-fill the password field with and nothing to
 * compare a new one against.  That splits the save into two cases: a server
 * address that moved keeps the token and only re-verifies, while a different
 * account — or a password that has since changed on the server — has to sign in
 * again for a fresh token.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentials: CredentialRepository,
    private val connector: ServerConnector,
    private val repo: RomRepository,
    private val buttonLayout: GamepadLayoutRepository,
) : ViewModel() {

    /** Which lettering the hint bars print; see [GamepadLayout]. */
    val gamepadLayout: StateFlow<GamepadLayout> = buttonLayout.layout

    fun setGamepadLayout(layout: GamepadLayout) = buttonLayout.set(layout)

    private val _savedServerUrl = MutableStateFlow(credentials.serverUrl.orEmpty())
    /**
     * What is stored right now.  It seeds the field, and the field is compared
     * against it to decide whether there is anything to save — so it is re-read
     * after every save, which also replaces what was typed with the normalized
     * form that was actually written.
     */
    val savedServerUrl: StateFlow<String> = _savedServerUrl.asStateFlow()

    private val _savedUsername = MutableStateFlow(credentials.username.orEmpty())
    val savedUsername: StateFlow<String> = _savedUsername.asStateFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _canSaveUnverified = MutableStateFlow(false)
    /**
     * True after an address-only save failed to reach the server.  A handheld is
     * often nowhere near the server when its address is being corrected, so the
     * new address can still be recorded — nothing else about the sign-in changes
     * and the app will simply fail to sync until it is right.
     */
    val canSaveUnverified: StateFlow<Boolean> = _canSaveUnverified.asStateFlow()

    fun save(serverUrl: String, username: String, password: String) {
        val user = username.trim()
        if (user.isBlank()) {
            _state.value = ConnectionState.Error("Enter a username")
            return
        }
        if (password.isBlank() && user != _savedUsername.value) {
            _state.value =
                ConnectionState.Error("Enter the password for $user to sign in as that account")
            return
        }

        viewModelScope.launch {
            _state.value = ConnectionState.Loading
            // Same account means the token still stands and only the address
            // moved; a password means signing in again for a fresh one.
            val addressOnly = password.isBlank()
            val result = if (addressOnly) {
                connector.moveTo(serverUrl)
            } else {
                connector.signIn(serverUrl, user, password)
            }
            _canSaveUnverified.value = addressOnly && result.isFailure
            _state.value = result.fold(
                onSuccess = { saved() },
                onFailure = { ConnectionState.Error(it.message ?: "Connection failed") },
            )
        }
    }

    /** Takes the address as typed, having offered [canSaveUnverified]. */
    fun saveWithoutVerifying(serverUrl: String) {
        _state.value = connector.setServerUrl(serverUrl).fold(
            onSuccess = { saved() },
            onFailure = { ConnectionState.Error(it.message ?: "That is not a URL") },
        )
    }

    /**
     * Clears a stale error once the user starts fixing the offending field.
     *
     * Deliberately leaves [ConnectionState.Saved] standing.  A save rewrites the
     * URL field with the normalized form that was stored, and that write goes
     * back out through the field's onValueChange the same way a keystroke does —
     * clearing the state here would wipe the confirmation the save just earned.
     * The screen hides it once there is something to save again instead.
     */
    fun clearError() {
        if (_state.value is ConnectionState.Error) _state.value = ConnectionState.Idle
        _canSaveUnverified.value = false
    }

    /**
     * Clears the stored credentials and the cached library.
     *
     * The cache goes because disconnecting is how the app is pointed at another
     * server, and RomM's platform and ROM ids are per-server — keeping the old
     * rows would show one server's platforms, and its cached metadata, under
     * the other server's ids.
     */
    fun disconnect() {
        credentials.clearAll()
        viewModelScope.launch {
            // Confirming disconnect navigates straight to setup with
            // `popUpTo(0)`, which clears this ViewModel and its scope while the
            // wipe is still in flight.  NonCancellable lets it finish rather
            // than leaving half a library behind under new credentials.
            withContext(NonCancellable) { repo.clearLibraryCache() }
        }
    }

    /**
     * Drops the cached platforms and ROMs, keeping the connection.
     *
     * The escape hatch for a cache that has gone stale in a way a re-sync will
     * not fix on its own.  Downloaded files, folder mappings and the download
     * queue are untouched.
     */
    fun clearLibraryCache() {
        viewModelScope.launch { repo.clearLibraryCache() }
    }

    private fun saved(): ConnectionState {
        _savedServerUrl.value = credentials.serverUrl.orEmpty()
        _savedUsername.value = credentials.username.orEmpty()
        return ConnectionState.Saved
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onFolderMapping: () -> Unit,
    onResetSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val canSaveUnverified by viewModel.canSaveUnverified.collectAsState()
    val savedServerUrl by viewModel.savedServerUrl.collectAsState()
    val savedUsername by viewModel.savedUsername.collectAsState()
    val gamepadLayout by viewModel.gamepadLayout.collectAsState()

    var serverUrl by rememberSaveable { mutableStateOf(savedServerUrl) }
    var username  by rememberSaveable { mutableStateOf(savedUsername) }
    var password  by rememberSaveable { mutableStateOf("") }

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // Every field ends in Done, which puts the keyboard away and returns to this
    // screen.  Walking the fields with Next belongs to first-run setup: here the
    // usual edit is one field, and in landscape the keyboard covers the app, so
    // its action key has to be a way out rather than a way further in.
    val save = { viewModel.save(serverUrl, username, password) }
    val dirty = serverUrl.trim() != savedServerUrl ||
        username.trim() != savedUsername ||
        password.isNotEmpty()
    val editable = state != ConnectionState.Loading

    val scrollState = rememberScrollState()

    LaunchedEffect(state) {
        // A save normalizes the URL before storing it, so show what was stored
        // rather than what was typed — otherwise the field reads as unsaved.
        if (state == ConnectionState.Saved) {
            serverUrl = savedServerUrl
            username  = savedUsername
            password  = ""
        }
        // The error, and the fallback it can offer, land below the button that
        // was just tapped — on a short screen that is off the bottom edge.
        if (state is ConnectionState.Error) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val scope = rememberCoroutineScope()
    GamepadHandler { action ->
        when (action) {
            // The page is taller than the screen and its focusable stops are
            // far apart, so the triggers move it rather than the focus.
            GamepadAction.PageUp -> {
                scope.launch { scrollState.animateScrollBy(-scrollState.viewportSize * 0.85f) }
                true
            }
            GamepadAction.PageDown -> {
                scope.launch { scrollState.animateScrollBy(scrollState.viewportSize * 0.85f) }
                true
            }
            // Select opened this page; pressing it again closes it.
            GamepadAction.Settings -> { onBack(); true }
            else                   -> false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Select"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        // A plain scrolling Column, not a LazyColumn: the page is a handful of
        // items, and a lazy list is the container these fields cannot live in
        // (see the focusSearch note in OutlinedInputField).
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
        ) {
            Text(
                text     = "Server & account",
                style    = MaterialTheme.typography.titleSmall,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )

            Column(Modifier.padding(16.dp)) {
                OutlinedInputField(
                    value         = serverUrl,
                    onValueChange = { serverUrl = it; viewModel.clearError() },
                    label         = "Server URL",
                    placeholder   = "http://romm.local",
                    inputKind     = InputKind.Uri,
                    enabled       = editable,
                    imeAction     = EditorInfo.IME_ACTION_DONE,
                    modifier      = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedInputField(
                    value         = username,
                    onValueChange = { username = it; viewModel.clearError() },
                    label         = "Username",
                    enabled       = editable,
                    imeAction     = EditorInfo.IME_ACTION_DONE,
                    modifier      = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedInputField(
                    value          = password,
                    onValueChange  = { password = it; viewModel.clearError() },
                    label          = "Password",
                    inputKind      = InputKind.Password,
                    enabled        = editable,
                    imeAction      = EditorInfo.IME_ACTION_DONE,
                    supportingText = {
                        Text("Only needed to switch account or after a password change.")
                    },
                    modifier       = Modifier.fillMaxWidth(),
                )

                when (val current = state) {
                    is ConnectionState.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = current.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Only while it is still true — the moment a field differs
                    // from what is stored, there is something to save again.
                    ConnectionState.Saved -> if (!dirty) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = "Saved.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> Unit
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick  = save,
                    enabled  = dirty && editable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state == ConnectionState.Loading) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save")
                    }
                }

                if (canSaveUnverified && state is ConnectionState.Error) {
                    TextButton(
                        onClick  = { viewModel.saveWithoutVerifying(serverUrl) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Save address anyway") }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text  = "Verified before saving — if it fails, the current connection " +
                            "is kept. Downloads and folder mappings are unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                text     = "Controller",
                style    = MaterialTheme.typography.titleSmall,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )

            Text(
                // Named by what the buttons do rather than by "Xbox" and
                // "Nintendo" alone: a handheld set to its own Xbox style is
                // often still silkscreened the Nintendo way, so the vendor's
                // word for the mode is the one thing that cannot be trusted
                // here.  What the user can always check is which button just
                // opened something.
                text  = "Which letters the button hints print. Pick whichever matches " +
                        "your handheld — this only changes the hints, never what the " +
                        "buttons do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            )

            GamepadLayoutChoice(
                headline   = "A opens, B goes back",
                supporting = "Xbox and PlayStation lettering",
                selected   = gamepadLayout == GamepadLayout.Xbox,
                onSelect   = { viewModel.setGamepadLayout(GamepadLayout.Xbox) },
            )
            GamepadLayoutChoice(
                headline   = "B opens, A goes back",
                supporting = "Nintendo lettering",
                selected   = gamepadLayout == GamepadLayout.Nintendo,
                onSelect   = { viewModel.setGamepadLayout(GamepadLayout.Nintendo) },
            )

            HorizontalDivider()

            ListItem(
                modifier          = Modifier.gamepadRow(onClick = onFolderMapping),
                headlineContent   = { Text("Folder Mapping") },
                supportingContent = { Text("Set your ROMs folder and per-platform overrides") },
                leadingContent    = { Icon(Icons.Default.FolderOpen, null) },
                trailingContent   = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
            )
            HorizontalDivider()

            ListItem(
                modifier          = Modifier.gamepadRow(onClick = { showClearCacheDialog = true }),
                headlineContent   = { Text("Clear cached library") },
                supportingContent = {
                    Text("Re-fetch platforms and ROMs from the server")
                },
                leadingContent    = { Icon(Icons.Default.Refresh, null) },
            )
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))

            ListItem(
                modifier = Modifier.gamepadRow(onClick = { showDisconnectDialog = true }),
                headlineContent = {
                    Text(
                        "Disconnect / Change server",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                supportingContent = { Text("Clear credentials and return to setup") },
                leadingContent    = {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
            HorizontalDivider()
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title   = { Text("Disconnect from RomM?") },
            text    = {
                Text(
                    "This will remove your saved credentials and server URL, and " +
                    "clear the cached library so the next server starts fresh. " +
                    "Downloaded files and folder mappings are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onResetSetup()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title   = { Text("Clear cached library?") },
            text    = {
                Text(
                    "Platforms and ROMs will be fetched from the server again the " +
                    "next time you open the list. Downloaded files, folder mappings " +
                    "and your download history are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLibraryCache()
                        showClearCacheDialog = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One of the two letterings, as a row a controller can walk onto.
 *
 * A row rather than a switch because there are two named things to choose
 * between and neither is the "off" one, and because the hint bar at the bottom
 * of this very screen repaints as the selection moves — which is the check the
 * user is here to make.
 */
@Composable
private fun GamepadLayoutChoice(
    headline: String,
    supporting: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier          = Modifier.gamepadRow(onClick = onSelect),
        headlineContent   = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent    = {
            // Not clickable itself: the whole row is, and a focusable control
            // inside a focusable row gives a controller two stops for one
            // choice.
            RadioButton(selected = selected, onClick = null)
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Folder Mapping
// ─────────────────────────────────────────────────────────────────────────────

/** One row of the folder-mapping list: a platform plus where its ROMs land. */
data class PlatformFolderRow(
    val platform: PlatformEntity,
    val target: DownloadTarget?,
    /** The ES-DE name, shown when the user has renamed the subfolder. */
    val defaultSubfolder: String,
    val isRenamed: Boolean,
)

@HiltViewModel
class FolderMappingViewModel @Inject constructor(
    private val repo: RomRepository,
    private val targets: DownloadTargetRepository,
    private val localRoms: LocalRomIndex,
) : ViewModel() {

    val baseFolder: StateFlow<BaseFolderEntity?> =
        targets.observeBaseFolder()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every platform with its resolved destination. Combining the three sources
     * here keeps the resolution rules in one place rather than duplicated
     * between the download path and the settings UI.
     */
    val rows: StateFlow<List<PlatformFolderRow>> = combine(
        repo.observePlatforms(),
        targets.observeBaseFolder(),
        targets.observeOverrides(),
        targets.observeSubfolders(),
    ) { platforms, base, overrides, subfolders ->
        val overrideMap = overrides.associateBy { it.platformId }
        val subMap      = subfolders.associateBy { it.platformId }
        platforms.map { platform ->
            val custom = subMap[platform.id]?.name
            PlatformFolderRow(
                platform         = platform,
                target           = targets.resolve(platform, base, overrideMap[platform.id], custom),
                defaultSubfolder = targets.defaultSubfolder(platform),
                isRenamed        = custom != null,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBaseFolder(uri: String, displayPath: String) {
        viewModelScope.launch {
            targets.setBaseFolder(uri, displayPath)
            localRoms.invalidate()
        }
    }

    /** Point a single platform at a directory outside the base folder. */
    fun setPlatformFolder(platformId: Int, uri: String, displayPath: String) {
        viewModelScope.launch {
            targets.setPlatformFolder(platformId, uri, displayPath)
            localRoms.invalidate()
        }
    }

    fun renameSubfolder(platformId: Int, name: String) {
        viewModelScope.launch {
            targets.setSubfolder(platformId, name)
            localRoms.invalidate()
        }
    }

    /** Drop both kinds of override so the platform follows the ES-DE default again. */
    fun resetPlatform(platformId: Int) {
        viewModelScope.launch {
            targets.resetPlatform(platformId)
            localRoms.invalidate()
        }
    }
}

/**
 * Derives a human-readable path string from a SAF tree URI.
 *
 * SAF tree URIs look like:
 *   content://com.android.externalstorage.documents/tree/primary%3ARoms%2FSNES
 *
 * The last path segment after decoding is something like "primary:Roms/SNES".
 * We strip the volume prefix and return just the path part, or the full URI
 * string as a fallback for non-standard providers (MTP, cloud, etc.).
 */
fun safDisplayPath(uri: Uri): String {
    return try {
        val encoded = uri.lastPathSegment ?: return uri.toString()
        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
        // "primary:Roms/SNES" → "Roms/SNES"
        // "0000-1111:Roms/SNES" → "Roms/SNES" (SD card)
        if (decoded.contains(':')) decoded.substringAfter(':') else decoded
    } catch (_: Exception) {
        uri.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMappingScreen(
    viewModel: FolderMappingViewModel,
    onBack: () -> Unit,
) {
    val context    = LocalContext.current
    val rows       by viewModel.rows.collectAsState()
    val baseFolder by viewModel.baseFolder.collectAsState()

    // Which platform an override picker result belongs to; -1 means the picker
    // was launched for the base folder itself.
    val pendingPlatformId = remember { mutableIntStateOf(-1) }
    var editing by remember { mutableStateOf<PlatformFolderRow?>(null) }

    fun persist(uri: Uri) = context.contentResolver.takePersistableUriPermission(
        uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )

    val basePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(uri)
        viewModel.setBaseFolder(uri.toString(), safDisplayPath(uri))
    }

    val overridePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val platformId = pendingPlatformId.intValue
        if (platformId == -1) return@rememberLauncherForActivityResult
        persist(uri)
        viewModel.setPlatformFolder(platformId, uri.toString(), safDisplayPath(uri))
        pendingPlatformId.intValue = -1
    }

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

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
                title = { Text("Folder Mapping") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Edit"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Base folder ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (baseFolder == null)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("ROMs folder", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            baseFolder?.displayPath
                                ?: "Not set — choose the folder that holds your platform subfolders.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Each platform gets a subfolder here, named to the ES-DE " +
                                "convention. One permission covers them all.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick  = { basePicker.launch(baseFolder?.folderUri?.let(Uri::parse)) },
                            modifier = Modifier.focusOutline(),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (baseFolder == null) "Choose ROMs folder" else "Change")
                        }
                    }
                }
            }

            item {
                Text(
                    "Platforms",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            items(rows, key = { it.platform.id }) { row ->
                val target = row.target
                ListItem(
                    modifier = Modifier.gamepadRow(onClick = { editing = row }),
                    headlineContent   = { Text(row.platform.displayName) },
                    supportingContent = {
                        Text(
                            target?.displayPath ?: "Set a ROMs folder first",
                            color = if (target == null) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = if (target != null) Icons.Default.FolderOpen
                                          else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (target != null) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        when {
                            target?.isOverride == true -> AssistChip(
                                onClick = { editing = row },
                                label   = { Text("Custom") },
                            )
                            row.isRenamed -> AssistChip(
                                onClick = { editing = row },
                                label   = { Text("Renamed") },
                            )
                            else -> null
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { row ->
        PlatformFolderDialog(
            row       = row,
            onDismiss = { editing = null },
            onRename  = { name ->
                viewModel.renameSubfolder(row.platform.id, name)
                editing = null
            },
            onPickFolder = {
                pendingPlatformId.intValue = row.platform.id
                overridePicker.launch(row.target?.treeUri?.let(Uri::parse))
                editing = null
            },
            onReset = {
                viewModel.resetPlatform(row.platform.id)
                editing = null
            },
        )
    }
}

/**
 * Per-platform override sheet: rename the subfolder (the common case for a
 * different naming scheme) or point the platform at an unrelated directory.
 */
@Composable
private fun PlatformFolderDialog(
    row: PlatformFolderRow,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onPickFolder: () -> Unit,
    onReset: () -> Unit,
) {
    var name by remember(row.platform.id) {
        mutableStateOf(row.target?.subfolder ?: row.defaultSubfolder)
    }
    val isCustomFolder = row.target?.isOverride == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.platform.displayName) },
        text = {
            Column {
                if (isCustomFolder) {
                    Text(
                        "This platform downloads to its own folder:\n${row.target?.displayPath}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Subfolder name", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    OutlinedInputField(
                        value         = name,
                        onValueChange = { name = it },
                        imeLabel      = "Subfolder name",
                        imeAction     = EditorInfo.IME_ACTION_DONE,
                        supportingText = {
                            Text(
                                if (name.trim() == row.defaultSubfolder)
                                    "ES-DE default"
                                else
                                    "ES-DE default is \"${row.defaultSubfolder}\"",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use a different folder…")
                }
                if (isCustomFolder || row.isRenamed) {
                    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Text("Reset to ES-DE default")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = !isCustomFolder && name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
