package app.rommdroid.ui.screens

import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.PlatformDao
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.FolderContents
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.data.download.asMessage
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.components.FastScroller
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.rememberInputFieldHandle
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.RomGroup
import app.rommdroid.util.RomSection
import app.rommdroid.util.artworkUrl
import app.rommdroid.util.displayName
import app.rommdroid.util.formatSize
import app.rommdroid.util.groupRoms
import app.rommdroid.util.regionPreference
import app.rommdroid.util.regionSummary
import app.rommdroid.util.sectionIndexOf
import app.rommdroid.util.sectionsOf
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class RomListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
    private val queue: DownloadQueue,
    private val platformDao: PlatformDao,
    private val downloadTargets: DownloadTargetRepository,
    private val localRoms: LocalRomIndex,
) : ViewModel() {

    private val platformId: Int = checkNotNull(savedStateHandle[Route.RomList.ARG])

    /**
     * Regional copies of one game are folded into a single row.  A No-Intro set
     * lists "Game (USA)", "Game (Europe)" and "Game (Japan)" under three nearly
     * identical names, which is unreadable at list scale; the variants stay
     * reachable from the row's detail screen.
     */
    private val groups: StateFlow<List<RomGroup>> =
        repo.observeRoms(platformId)
            .map { roms ->
                groupRoms(
                    roms             = roms,
                    preferredRegions = regionPreference(Locale.getDefault().country),
                    regionsOf        = repo::regionsOf,
                )
            }
            // Folding a few thousand ROMs is too much to do on the main thread
            // every time the sync writes a page.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Text typed into the list's filter field; blank shows the whole platform. */
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    fun setFilter(text: String) { _filter.value = text }

    /**
     * The rows as the screen draws them: the filter applied, then cut into the
     * letter runs the sticky headers sit on.
     *
     * A filtered list comes back as one unlabelled run.  Headers earn their
     * space on a list of hundreds, where they are the only sign of where a
     * flick landed; over a dozen matches they would only be a row of letters
     * with a game under each.
     */
    val sections: StateFlow<List<RomSection>> = combine(groups, _filter) { rows, filter ->
        val needle  = filter.trim()
        val matches = if (needle.isEmpty()) rows else rows.filter { it.matches(needle) }
        when {
            needle.isEmpty()  -> sectionsOf(matches)
            matches.isEmpty() -> emptyList()
            else              -> listOf(RomSection(label = null, groups = matches))
        }
    }
        // Same reason as the fold above: a platform can hold a few thousand
        // rows, and this runs again on every keystroke.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Download state per ROM id, so a row can show what the user already has. */
    val downloadStatus: StateFlow<Map<Int, DownloadStatus>> = queue.statusByRom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * What this platform's folder already holds.  One directory listing covers
     * the whole screen, since every row on it belongs to the same platform.
     */
    val onDevice: StateFlow<FolderContents> = localRoms.revision
        .mapLatest {
            val platform = platformDao.getById(platformId)
            val target   = platform?.let { downloadTargets.resolve(it) }
            target?.let { localRoms.listing(it) } ?: FolderContents.Unreadable
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderContents.Unreadable)

    /** Rows with a long-press in flight; the detail fetch takes a moment. */
    private val _queueing = MutableStateFlow<Set<String>>(emptySet())
    val queueing: StateFlow<Set<String>> = _queueing.asStateFlow()

    private val _messages = MutableSharedFlow<QueueMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<QueueMessage> = _messages.asSharedFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { refresh() }

    fun refresh(fullSync: Boolean = false) {
        viewModelScope.launch {
            _syncing.value = true
            _error.value   = null
            try {
                repo.syncRoms(platformId)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * Queue the copy this row stands for.
     *
     * A group can cover several regional variants, and the one shown is the
     * preferred one — so that is what gets queued, and the message names its
     * region so an unwanted pick is obvious enough to undo.
     */
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

    fun coverUrl(rom: RomEntity): String? = artworkUrl(
        credentials.serverUrl,
        rom.pathCoverSmall,
        rom.pathCoverLarge,
        rom.urlCover,
    )
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RomListScreen(
    viewModel: RomListViewModel,
    platformId: Int,
    onRomClick: (Int) -> Unit,
    onDownloadsClick: () -> Unit,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val sections by viewModel.sections.collectAsState()
    val filter   by viewModel.filter.collectAsState()
    val syncing  by viewModel.syncing.collectAsState()
    val error    by viewModel.error.collectAsState()
    val statuses by viewModel.downloadStatus.collectAsState()
    val queueing by viewModel.queueing.collectAsState()
    val onDevice by viewModel.onDevice.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // The filter field replaces the title rather than sitting under it: on a
    // handheld in landscape the bar plus a second row of chrome is most of the
    // list's height.
    var filtering by rememberSaveable { mutableStateOf(false) }
    val filterField = rememberInputFieldHandle()
    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()

    // Where every letter starts, shared by the two ways of jumping to one: the
    // scroller's bubble reads it, the shoulder buttons step through it.
    val sectionIndex = remember(sections) { sectionIndexOf(sections) }

    // The shoulder buttons are key events, and key events go to whatever holds
    // focus — so the list has to hold it.  Not while the filter is open: the
    // field wants those keystrokes.
    val listFocus = remember { FocusRequester() }
    LaunchedEffect(filtering) {
        if (!filtering) runCatching { listFocus.requestFocus() }
    }

    LaunchedEffect(filtering) { if (filtering) filterField.requestFocus() }

    // Every keystroke narrows the list underneath; keeping the old scroll
    // offset would leave the user somewhere in the middle of the matches, or
    // past the end of them entirely.  Only on a real change, though — a
    // rotation re-runs the effect with the filter it already had, and the
    // list state it restored is the one worth keeping.
    var scrolledFor by rememberSaveable { mutableStateOf(filter) }
    LaunchedEffect(filter) {
        if (scrolledFor != filter) {
            scrolledFor = filter
            listState.scrollToItem(0)
        }
    }

    fun closeFilter() {
        filterField.hideKeyboard()
        viewModel.setFilter("")
        filtering = false
    }

    // Back closes the field before it leaves the screen — the same thing the
    // X in its place does, and what the gesture means everywhere else.
    BackHandler(enabled = filtering) { closeFilter() }

    /**
     * L1 and R1 step a letter at a time.
     *
     * This is a handheld with shoulder buttons and no second hand free for the
     * scroller — a press per letter is the cheapest jump on the device, and it
     * lands on the header so the letter is on screen when it stops.
     */
    fun onShoulderKey(key: Key): Boolean {
        val target = when (key) {
            Key.ButtonL1 -> sectionIndex.startBefore(listState.firstVisibleItemIndex)
            Key.ButtonR1 -> sectionIndex.startAfter(listState.firstVisibleItemIndex)
            else         -> return false
        }
        // Consume either way: at the ends of the list the button does nothing
        // rather than falling through to whatever else might take it.
        target?.let { scope.launch { listState.scrollToItem(it) } }
        return true
    }

    // Sync failures share the queue's snackbar host rather than stacking a
    // second one on top of it.
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = message,
            actionLabel = "Retry",
            duration    = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val action = when {
                message.undoIds.isNotEmpty() -> "Undo"
                message.needsFolder          -> "Set folder"
                else                         -> null
            }
            val result = snackbarHostState.showSnackbar(
                message     = message.text,
                actionLabel = action,
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
                    if (filtering) {
                        // Matches narrow as the text is typed, so the Search
                        // key has nothing to submit and only puts the keyboard
                        // away — same as the global search field.
                        OutlinedInputField(
                            value         = filter,
                            onValueChange = viewModel::setFilter,
                            placeholder   = "Filter ROMs…",
                            imeAction     = EditorInfo.IME_ACTION_SEARCH,
                            handle        = filterField,
                            onImeAction   = { filterField.hideKeyboard() },
                            modifier      = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("ROMs")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (filtering) closeFilter() else onBack() }) {
                        if (filtering) {
                            Icon(Icons.Default.Close, contentDescription = "Clear filter")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                // The field needs the whole bar, and neither action means
                // anything while the user is halfway through typing a name.
                actions = {
                    if (!filtering) {
                        IconButton(onClick = { filtering = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Filter ROMs")
                        }
                        IconButton(onClick = onDownloadsClick) {
                            Icon(Icons.Default.Download, contentDescription = "Downloads")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        !sectionIndex.isEmpty &&
                        onShoulderKey(event.key)
                }
                .focusRequester(listFocus)
                .focusable()
        ) {
            when {
                syncing && sections.isEmpty() && filter.isBlank() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                sections.isEmpty() && filter.isNotBlank() -> {
                    Text(
                        text      = "No ROMs match “${filter.trim()}”.",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
                else -> {
                    LazyColumn(state = listState) {
                        sections.forEach { section ->
                            // Sticky until the next letter pushes it off, so the
                            // header stays readable through a long flick.
                            section.label?.let { label ->
                                stickyHeader(key = "section:$label") { SectionHeader(label) }
                            }
                            items(section.groups, key = { it.key }) { group ->
                                RomRow(
                                    group       = group,
                                    coverUrl    = viewModel.coverUrl(group.primary),
                                    status      = group.downloadStatus(statuses),
                                    onDevice    = group.isOnDevice(onDevice),
                                    queueing    = group.key in queueing,
                                    onClick     = { onRomClick(group.primary.id) },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.download(group)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    FastScroller(
                        state    = listState,
                        index    = sectionIndex,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
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

/**
 * The most active download state across the copies of this game, so a row shows
 * "downloaded" whichever variant the user actually took.
 */
private fun RomGroup.downloadStatus(statuses: Map<Int, DownloadStatus>): DownloadStatus? =
    variants.mapNotNull { statuses[it.id] }
        .minByOrNull { STATUS_PRIORITY.indexOf(it) }

/**
 * True when any copy of this game is already sitting in the ROMs folder.
 *
 * Independent of the download queue: a library the user filled from a PC, or
 * kept across a reinstall, has no queue rows at all but is very much "already
 * downloaded" from where they are standing.
 */
private fun RomGroup.isOnDevice(contents: FolderContents): Boolean =
    contents.readable && variants.any { contents.contains(it.fsName) }

/**
 * Filter match against both names a row can be found by: the title it shows,
 * and the filename underneath it — a user hunting "sonic2" is typing what the
 * file is called, not what the game is called.
 */
private fun RomGroup.matches(needle: String): Boolean =
    primary.displayName.contains(needle, ignoreCase = true) ||
        primary.fsNameNoTags.contains(needle, ignoreCase = true)

private val STATUS_PRIORITY = listOf(
    DownloadStatus.RUNNING,
    DownloadStatus.QUEUED,
    DownloadStatus.FAILED,
    DownloadStatus.SUCCEEDED,
    DownloadStatus.CANCELLED,
)

/**
 * The letter a run of rows sits under.
 *
 * Opaque on purpose: a sticky header has the list scrolling underneath it, and
 * a transparent one would have two titles drawn over each other.
 */
@Composable
private fun SectionHeader(label: String) {
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
private fun RomRow(
    group: RomGroup,
    coverUrl: String?,
    status: DownloadStatus?,
    onDevice: Boolean,
    queueing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val rom = group.primary
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick          = onClick,
            onLongClick      = onLongClick,
            onLongClickLabel = "Download",
        ),
        headlineContent  = { Text(rom.displayName) },
        supportingContent = {
            // Flags first: when a game has several copies they are the only
            // thing that tells the rows apart, so they lead the line.
            // For a single-variant group these are just that ROM's own regions.
            val flags = regionSummary(group.regions)
            val detail = if (group.hasVariants) {
                "${group.size} versions"
            } else {
                "${rom.fsExtension.uppercase()}  ·  ${rom.fsSizeBytes.formatSize()}"
            }
            Text(if (flags.isEmpty()) detail else "$flags  ·  $detail")
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
