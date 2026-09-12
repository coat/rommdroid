package app.rommdroid.ui.romlist

import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.download.FolderContents
import app.rommdroid.domain.RomGroup
import app.rommdroid.domain.RomSort
import app.rommdroid.domain.sectionIndexOf
import app.rommdroid.ui.common.QueueSnackbarEffect
import app.rommdroid.ui.common.takeOffer
import app.rommdroid.ui.components.FastScroller
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.gamepad.rememberButtonLayout
import app.rommdroid.ui.components.rememberInputFieldHandle
import app.rommdroid.ui.gamepad.RestoreFocus
import app.rommdroid.ui.gamepad.withButton
import kotlinx.coroutines.flow.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RomListScreen(
    viewModel: RomListViewModel,
    onRomClick: (Int) -> Unit,
    onDownloadsClick: () -> Unit,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val title    by viewModel.title.collectAsStateWithLifecycle()
    val filter   by viewModel.filter.collectAsStateWithLifecycle()
    val syncing  by viewModel.sync.syncing.collectAsStateWithLifecycle()
    val error    by viewModel.sync.error.collectAsStateWithLifecycle()
    val statuses by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val queueing by viewModel.downloads.queueing.collectAsStateWithLifecycle()
    val onDevice by viewModel.onDevice.collectAsStateWithLifecycle()
    val sort     by viewModel.sort.collectAsStateWithLifecycle()
    val regions  by viewModel.regions.collectAsStateWithLifecycle()
    val regionFilter by viewModel.regionFilter.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val buttons = rememberButtonLayout()

    // The filter replaces the title rather than adding a row: in landscape two
    // rows of chrome is most of the list's height.
    var filtering by rememberSaveable { mutableStateOf(false) }
    val filterField = rememberInputFieldHandle()
    val listState   = rememberLazyListState()
    val cursor      = rememberRomListCursor(listState)

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

    val focusedGroup = remember(sections, cursor.focusedKey) { cursor.groupIn(sections) }
    val focusTarget  = focusedGroup?.key ?: cursor.focusTarget(sections)
    RestoreFocus(cursor.rowFocus, ready = !filtering && !showOptions && focusTarget != null)

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

    fun closeFilter() {
        filterField.hideKeyboard()
        viewModel.setFilter("")
        filtering = false
        cursor.returnToList(sections)
    }

    fun closeOptions() {
        showOptions = false
        cursor.returnToList(sections)
    }

    // Back closes the field, or the chips, before it leaves the screen.
    BackHandler(enabled = filtering) { closeFilter() }
    BackHandler(enabled = showOptions && !filtering) { closeOptions() }

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
            // L1 and R1 step a letter at a time.
            GamepadAction.SectionPrev -> cursor.jumpSection(sectionIndex, forwards = false)
            GamepadAction.SectionNext -> cursor.jumpSection(sectionIndex, forwards = true)
            else                      -> false
        }
    }
    ListGamepadScrolling(listState) { cursor.focusTopRow() }

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
                                    stickyHeader(key = sectionKey(label)) { SectionHeader(label) }
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
                                        onFocused   = { cursor.focusedKey = group.key },
                                        focusRequester = cursor.rowFocus.takeIf { group.key == focusTarget },
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
