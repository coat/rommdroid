package app.rommdroid.ui.screens

import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomListPreferencesRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.common.DownloadRequester
import app.rommdroid.ui.common.QueueSnackbarEffect
import app.rommdroid.ui.common.SyncTracker
import app.rommdroid.ui.common.takeOffer
import app.rommdroid.ui.components.FastScroller
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadButton
import app.rommdroid.ui.components.GamepadHandler
import app.rommdroid.ui.components.GamepadHint
import app.rommdroid.ui.components.GamepadHintBar
import app.rommdroid.ui.components.ListGamepadScrolling
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.RatingBadge
import app.rommdroid.ui.components.RestoreFocus
import app.rommdroid.ui.components.focusOutline
import app.rommdroid.ui.components.gamepadRow
import app.rommdroid.ui.components.rememberButtonLayout
import app.rommdroid.ui.components.rememberInputFieldHandle
import app.rommdroid.ui.components.withButton
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.NO_REGION
import app.rommdroid.util.RegionCount
import app.rommdroid.util.RomGroup
import app.rommdroid.util.RomSection
import app.rommdroid.util.RomSort
import app.rommdroid.util.RomSortKey
import app.rommdroid.util.countRegions
import app.rommdroid.util.keepRegions
import app.rommdroid.util.regionFlag
import app.rommdroid.util.regionName
import app.rommdroid.util.sortGroups
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

// ViewModel

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class RomListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
    queue: DownloadQueue,
    private val platformDao: PlatformDao,
    private val downloadTargets: DownloadTargetRepository,
    private val localRoms: LocalRomIndex,
    private val listPrefs: RomListPreferencesRepository,
) : ViewModel() {

    /** What this list is a list of. The screen is identical either way; the two
     *  part company only over which ROMs, what a refresh fetches, and which
     *  folders to check. */
    private sealed interface Source {
        data class Platform(val id: Int) : Source
        data class Collection(val id: Int) : Source
    }

    private val source: Source =
        savedStateHandle.get<Int>(Route.RomList.ARG)?.let(Source::Platform)
            ?: Source.Collection(checkNotNull(savedStateHandle[Route.CollectionRoms.ARG]))

    private val roms: Flow<List<RomEntity>> =
        when (source) {
            is Source.Platform   -> repo.observeRoms(source.id)
            is Source.Collection -> repo.observeCollectionRoms(source.id)
        }
            // Shared: the fold and the region chips both read it.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which regions the list is cut down to; empty shows every ROM. Shared
     *  with every other list, and kept across launches. */
    val regionFilter: StateFlow<Set<String>> = listPrefs.regions

    val sort: StateFlow<RomSort> = listPrefs.sort

    /** The regions on offer, counted over the whole list rather than the
     *  filtered one so a chip does not vanish the moment it is picked. A
     *  region the user chose elsewhere stays on the row at zero, or there
     *  would be no way to see why the list is short, or to turn it off. */
    val regions: StateFlow<List<RegionCount>> = combine(roms, regionFilter) { roms, selected ->
        val present = countRegions(roms, repo::regionsOf)
        val missing = selected - present.map { it.region }.toSet()
        present + missing.sorted().map { RegionCount(it, 0) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Regional copies fold into one row: a No-Intro set lists three nearly
     *  identical names. The variants stay reachable from the detail screen.
     *  The region filter runs first, so a filtered row leads with, and
     *  long-press downloads, a copy from the chosen region. */
    private val groups: StateFlow<List<RomGroup>> = combine(roms, regionFilter) { roms, selected ->
        groupRoms(
            roms             = keepRegions(roms, selected, repo::regionsOf),
            preferredRegions = regionPreference(Locale.getDefault().country),
            regionsOf        = repo::regionsOf,
        )
    }
        // A few thousand ROMs, refolded every time the sync writes a page.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Text typed into the list's filter field; blank shows the whole platform. */
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    fun setFilter(text: String) { _filter.value = text }

    /** Pick a key, or flip the one already picked: a second press on "Rating"
     *  is the only way to ask for the worst-rated first. */
    fun sortBy(key: RomSortKey) {
        val current = sort.value
        listPrefs.setSort(if (current.key == key) current.reversed() else RomSort.of(key))
    }

    fun toggleRegion(region: String) {
        val current = regionFilter.value
        listPrefs.setRegions(if (region in current) current - region else current + region)
    }

    fun clearRegions() { listPrefs.setRegions(emptySet()) }

    /**
     * The rows as drawn: filtered, sorted, then cut into letter runs. A filtered
     * list comes back as one unlabelled run, since headers only earn their
     * space over hundreds of rows; so does any order but name, where a letter
     * says nothing about where a row is.
     */
    val sections: StateFlow<List<RomSection>> = combine(groups, _filter, sort) { rows, filter, sort ->
        val needle  = filter.trim()
        val matches = if (needle.isEmpty()) rows else rows.filter { it.matches(needle) }
        val ordered = sortGroups(matches, sort)
        when {
            ordered.isEmpty()                                 -> emptyList()
            needle.isEmpty() && sort.key == RomSortKey.Name  -> sectionsOf(ordered)
            else -> listOf(RomSection(label = null, groups = ordered))
        }
    }
        // Same reason as the fold above, and this reruns on every keystroke.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Download state per ROM id, so a row can show what the user already has. */
    val downloadStatus: StateFlow<Map<Int, DownloadStatus>> = queue.statusByRom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What the folders behind this list hold, keyed by platform id. A map
     *  rather than one [FolderContents] because a collection spans platforms. */
    val onDevice: StateFlow<Map<Int, FolderContents>> =
        when (source) {
            is Source.Platform -> localRoms.revision.map { listOf(source.id) }
            // Only the platforms the rows belong to: resolving the whole library
            // would read a directory per system for a dozen games.
            is Source.Collection -> combine(localRoms.revision, groups) { _, rows ->
                rows.flatMap { group -> group.variants.map { it.platformId } }.distinct()
            }
        }
            // The sync pages the ROMs in, but the platform set settles early.
            .distinctUntilChanged()
            .mapLatest { platformIds ->
                platformIds.mapNotNull { id ->
                    val platform = platformDao.getById(id) ?: return@mapNotNull null
                    val target   = downloadTargets.resolve(platform) ?: return@mapNotNull null
                    id to localRoms.listing(target)
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What the bar says this list is. Named rather than a flat "ROMs": a
     *  collection is two levels down and nothing else names it. */
    val title: StateFlow<String> = flow {
        val name = when (source) {
            is Source.Platform   -> platformDao.getById(source.id)?.displayName
            is Source.Collection -> repo.getCollection(source.id)?.name
        }
        emit(name ?: "ROMs")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "ROMs")

    /** True when a row should name its platform - only a collection mixes them. */
    val mixedPlatforms: Boolean = source is Source.Collection

    /** Long-press, or X, on a row. */
    val downloads = DownloadRequester(queue, repo::regionsOf, viewModelScope)

    val sync = SyncTracker()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            sync.run {
                when (source) {
                    is Source.Platform   -> repo.syncRoms(source.id)
                    is Source.Collection -> repo.syncCollectionRoms(source.id)
                }
            }
        }
    }

    fun coverUrl(rom: RomEntity): String? = artworkUrl(
        credentials.serverUrl,
        rom.pathCoverSmall,
        rom.pathCoverLarge,
        rom.urlCover,
    )
}

// Screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RomListScreen(
    viewModel: RomListViewModel,
    onRomClick: (Int) -> Unit,
    onDownloadsClick: () -> Unit,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val sections by viewModel.sections.collectAsState()
    val title    by viewModel.title.collectAsState()
    val filter   by viewModel.filter.collectAsState()
    val syncing  by viewModel.sync.syncing.collectAsState()
    val error    by viewModel.sync.error.collectAsState()
    val statuses by viewModel.downloadStatus.collectAsState()
    val queueing by viewModel.downloads.queueing.collectAsState()
    val onDevice by viewModel.onDevice.collectAsState()
    val sort     by viewModel.sort.collectAsState()
    val regions  by viewModel.regions.collectAsState()
    val regionFilter by viewModel.regionFilter.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val buttons = rememberButtonLayout()

    // The filter replaces the title rather than adding a row: in landscape two
    // rows of chrome is most of the list's height.
    var filtering by rememberSaveable { mutableStateOf(false) }
    val filterField = rememberInputFieldHandle()
    val listState   = rememberLazyListState()
    val scope       = rememberCoroutineScope()

    // The sort and region chips do take a row, but only while they are open:
    // a sort is picked once and a region filter rarely changes, so the row is
    // gone again before the list is read.
    var showOptions by rememberSaveable { mutableStateOf(false) }
    // With a controller the cursor moves onto the chips as they open.
    val optionsFocus = remember { FocusRequester() }
    RestoreFocus(optionsFocus, ready = showOptions)

    // Where every letter starts: the scroller's bubble reads it, the shoulder
    // buttons step through it.
    val sectionIndex = remember(sections) { sectionIndexOf(sections) }

    LaunchedEffect(filtering) { if (filtering) filterField.requestFocus() }

    // The row the controller is on: where X downloads from, and where focus
    // returns to from a detail page.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val rowFocus   = remember { FocusRequester() }
    val focusedGroup = remember(sections, focusedKey) {
        sections.firstNotNullOfOrNull { section ->
            section.groups.firstOrNull { it.key == focusedKey }
        }
    }
    // The remembered row may not have survived the last keystroke.
    val focusTarget = focusedGroup?.key ?: sections.firstOrNull()?.groups?.firstOrNull()?.key
    RestoreFocus(rowFocus, ready = !filtering && !showOptions && focusTarget != null)

    // Each keystroke narrows the list, so the old offset lands mid-matches or
    // past the end; a new sort or region does the same to the whole list. Only
    // on a real change: a rotation re-runs the effect with the same shape, and
    // the restored list state is the one worth keeping.
    val listShape = "$filter|${sort.key}|${sort.descending}|${regionFilter.sorted()}"
    var scrolledFor by rememberSaveable { mutableStateOf(listShape) }
    LaunchedEffect(listShape) {
        if (scrolledFor != listShape) {
            scrolledFor = listShape
            listState.scrollToItem(0)
        }
    }

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

    /**
     * Move the cursor to where a jump landed, or the next D-pad press snaps the
     * list back to the off-screen row and undoes the jump. Headers carry a
     * "section:" key, so the first row is the first visible key without one.
     */
    suspend fun focusTopRow() {
        withFrameNanos { }
        val landed = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { (it.key as? String)?.startsWith("section:") == false }
            ?.key as? String
        focusRow(landed)
    }

    fun closeFilter() {
        filterField.hideKeyboard()
        viewModel.setFilter("")
        filtering = false
        // Focus is in the field that is about to stop existing; left alone it
        // lands on the bar rather than the list.
        focusRow(focusedKey ?: sections.firstOrNull()?.groups?.firstOrNull()?.key)
    }

    // Same again for the chips: the cursor is on one as they go.
    fun closeOptions() {
        showOptions = false
        focusRow(focusedKey ?: sections.firstOrNull()?.groups?.firstOrNull()?.key)
    }

    // Back closes the field, or the chips, before it leaves the screen.
    BackHandler(enabled = filtering) { closeFilter() }
    BackHandler(enabled = showOptions && !filtering) { closeOptions() }

    /**
     * L1 and R1 step a letter at a time, landing on the header so the letter is
     * on screen. Consumed either way, so at the ends of the list and on a
     * filtered one the button does nothing rather than falling through.
     */
    fun jumpSection(forwards: Boolean): Boolean {
        if (sectionIndex.isEmpty) return true
        val from   = listState.firstVisibleItemIndex
        val target = if (forwards) sectionIndex.startAfter(from) else sectionIndex.startBefore(from)
        target?.let { scope.launch { listState.scrollToItem(it); focusTopRow() } }
        return true
    }

    GamepadHandler { action ->
        when (action) {
            // Y takes a standing snackbar offer. Otherwise it opens the filter,
            // or reopens the keyboard the Search key put away.
            GamepadAction.Search -> {
                when {
                    snackbarHostState.takeOffer() -> Unit
                    filtering                     -> filterField.requestFocus()
                    else                          -> filtering = true
                }
                true
            }
            // A controller cannot long-press, so X stands in for the gesture.
            GamepadAction.Download -> {
                focusedGroup?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.downloads.download(it)
                }
                true
            }
            GamepadAction.SectionPrev -> jumpSection(forwards = false)
            GamepadAction.SectionNext -> jumpSection(forwards = true)
            else                      -> false
        }
    }
    ListGamepadScrolling(listState) { focusTopRow() }

    // Sync failures share the queue's snackbar host.
    LaunchedEffect(error, buttons) {
        val message = error ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message     = message,
            actionLabel = "Retry".withButton(GamepadButton.Y, buttons),
            duration    = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.refresh()
    }

    QueueSnackbarEffect(
        messages         = viewModel.downloads.messages,
        host             = snackbarHostState,
        onUndo           = viewModel.downloads::undo,
        onFolderSettings = onFolderSettings,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (filtering) {
                        // Nothing to submit; Search only dismisses the keyboard,
                        // same as the global search field.
                        OutlinedInputField(
                            value         = filter,
                            onValueChange = viewModel::setFilter,
                            placeholder   = "Filter ROMs...",
                            imeAction     = EditorInfo.IME_ACTION_SEARCH,
                            handle        = filterField,
                            onImeAction   = { filterField.hideKeyboard() },
                            modifier      = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick  = { if (filtering) closeFilter() else onBack() },
                        modifier = Modifier.focusOutline(),
                    ) {
                        if (filtering) {
                            Icon(Icons.Default.Close, contentDescription = "Clear filter")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                // The field needs the whole bar while it is open.
                actions = {
                    if (!filtering) {
                        IconButton(
                            onClick  = { filtering = true },
                            modifier = Modifier.focusOutline(),
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Filter ROMs")
                        }
                        IconButton(
                            onClick  = { if (showOptions) closeOptions() else showOptions = true },
                            modifier = Modifier.focusOutline(),
                        ) {
                            // A dot when the chips are closed but changing the
                            // list, or a short list looks like a short library.
                            val active = regionFilter.isNotEmpty() || sort != RomSort.DEFAULT
                            BadgedBox(badge = { if (active && !showOptions) Badge() }) {
                                Icon(Icons.Default.Tune, contentDescription = "Sort and regions")
                            }
                        }
                        IconButton(onClick = onDownloadsClick, modifier = Modifier.focusOutline()) {
                            Icon(Icons.Default.Download, contentDescription = "Downloads")
                        }
                        IconButton(
                            onClick  = { viewModel.refresh() },
                            modifier = Modifier.focusOutline(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        bottomBar = {
            GamepadHintBar(
                if (showOptions) {
                    listOf(
                        GamepadHint(GamepadButton.A, "Toggle"),
                        GamepadHint(GamepadButton.B, "Close"),
                    )
                } else {
                    listOf(
                        GamepadHint(GamepadButton.A, "Open"),
                        GamepadHint(GamepadButton.X, "Download"),
                        GamepadHint(GamepadButton.Y, "Filter"),
                        GamepadHint(GamepadButton.L1, "Letter"),
                        GamepadHint(GamepadButton.B, "Back"),
                    )
                }
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Not animated out: the row has to be gone within the few frames the
            // cursor is asked back onto the list, or the bar catches it instead.
            if (showOptions) {
                ListOptions(
                    sort           = sort,
                    onSort         = viewModel::sortBy,
                    regions        = regions,
                    selected       = regionFilter,
                    onToggleRegion = viewModel::toggleRegion,
                    focusRequester = optionsFocus,
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    syncing && sections.isEmpty() && filter.isBlank() -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    sections.isEmpty() && filter.isNotBlank() -> {
                        Text(
                            text      = "No ROMs match \"${filter.trim()}\".",
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.align(Alignment.Center).padding(32.dp),
                        )
                    }
                    sections.isEmpty() && regionFilter.isNotEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        ) {
                            Text(
                                text      = "No ROMs from the selected regions.",
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(
                                onClick  = viewModel::clearRegions,
                                modifier = Modifier.focusOutline(),
                            ) { Text("Show all regions") }
                        }
                    }
                    else -> {
                        LazyColumn(state = listState) {
                            sections.forEach { section ->
                                // Sticky, so the letter stays readable through a flick.
                                section.label?.let { label ->
                                    stickyHeader(key = "section:$label") { SectionHeader(label) }
                                }
                                items(section.groups, key = { it.key }) { group ->
                                    RomRow(
                                        group       = group,
                                        coverUrl    = viewModel.coverUrl(group.primary),
                                        status      = group.downloadStatus(statuses),
                                        onDevice    = group.isOnDevice(onDevice),
                                        showPlatform = viewModel.mixedPlatforms,
                                        queueing    = group.key in queueing,
                                        onClick     = { onRomClick(group.primary.id) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.downloads.download(group)
                                        },
                                        onFocused   = { focusedKey = group.key },
                                        focusRequester = rowFocus.takeIf { group.key == focusTarget },
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
}

/** The most active state across the copies, so a row reads "downloaded"
 *  whichever variant the user took. */
private fun RomGroup.downloadStatus(statuses: Map<Int, DownloadStatus>): DownloadStatus? =
    DownloadStatus.mostActive(variants.mapNotNull { statuses[it.id] })

/**
 * True when any copy is already in its platform's folder. Independent of the
 * queue, so a library filled from a PC or kept across a reinstall still counts.
 * Each variant checks its own platform, since a collection spans folders.
 */
private fun RomGroup.isOnDevice(byPlatform: Map<Int, FolderContents>): Boolean =
    variants.any { rom ->
        val contents = byPlatform[rom.platformId] ?: return@any false
        contents.readable && contents.contains(rom.fsName)
    }

/** Matches the title or the filename: someone typing "sonic2" is naming the
 *  file, not the game. */
private fun RomGroup.matches(needle: String): Boolean =
    primary.displayName.contains(needle, ignoreCase = true) ||
        primary.fsNameNoTags.contains(needle, ignoreCase = true)

/**
 * The sort keys and the region chips, one scrolling row each. Rows that scroll
 * rather than wrap because a No-Intro set can name twenty regions, and a
 * wrapped row of them in landscape would leave no list underneath.
 *
 * The sort is single-choice and a second press on the chosen key reverses it;
 * the arrow says which way it currently runs. Regions are any-of.
 */
@Composable
private fun ListOptions(
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

/** The letter a run of rows sits under. Opaque, or the list scrolling beneath
 *  the sticky header shows through it. */
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
