package app.rommdroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.api.model.DetailedRomSchema
import app.rommdroid.data.api.model.RomFileSchema
import app.rommdroid.data.api.model.SimpleRomSchema
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.PlatformDao
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.FolderContents
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.data.download.asMessage
import app.rommdroid.data.download.downloadableFiles
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.components.GamepadAction
import app.rommdroid.ui.components.GamepadButton
import app.rommdroid.ui.components.GamepadHandler
import app.rommdroid.ui.components.GamepadHint
import app.rommdroid.ui.components.GamepadHintBar
import app.rommdroid.ui.components.RatingBadge
import app.rommdroid.ui.components.RestoreFocus
import app.rommdroid.ui.components.StickScroll
import app.rommdroid.ui.components.focusOutline
import app.rommdroid.ui.components.gamepadRow
import app.rommdroid.ui.components.rememberHasGamepad
import app.rommdroid.ui.components.scrollPage
import app.rommdroid.ui.components.withButton
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.RomVariant
import app.rommdroid.util.artworkUrl
import app.rommdroid.util.formatSize
import app.rommdroid.util.regionPreference
import app.rommdroid.util.regionRank
import app.rommdroid.util.regionsFor
import app.rommdroid.util.regionSummary
import java.util.Locale
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed interface RomDetailState {
    data object Loading : RomDetailState
    data class  Loaded(val rom: DetailedRomSchema) : RomDetailState
    data class  Error(val message: String) : RomDetailState
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class RomDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
    private val platformDao: PlatformDao,
    private val downloadTargets: DownloadTargetRepository,
    private val queue: DownloadQueue,
    private val localRoms: LocalRomIndex,
) : ViewModel() {

    /** The variant currently being shown; changes when the user picks another. */
    private val _romId = MutableStateFlow<Int>(
        checkNotNull(savedStateHandle[Route.RomDetail.ARG])
    )
    val romId: StateFlow<Int> = _romId.asStateFlow()

    private val _state = MutableStateFlow<RomDetailState>(RomDetailState.Loading)
    val state: StateFlow<RomDetailState> = _state.asStateFlow()

    /**
     * Every regional copy of this game, preferred region first.  Holds a single
     * entry for games that only exist once, and the picker stays hidden then.
     */
    private val _variants = MutableStateFlow<List<RomVariant>>(emptyList())
    val variants: StateFlow<List<RomVariant>> = _variants.asStateFlow()

    private val _target = MutableStateFlow<DownloadTarget?>(null)
    val target: StateFlow<DownloadTarget?> = _target.asStateFlow()

    /** Live state of every download queued for this ROM, keyed by file id. */
    val downloads: StateFlow<Map<Int, DownloadItem>> =
        _romId.flatMapLatest { id ->
            queue.items.map { items -> items.filter { it.romId == id }.associateBy { it.fileId } }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * What the destination folder already holds.
     *
     * Read from the folder rather than inferred from [downloads] so a ROM the
     * user already has — copied from a PC, kept through a reinstall, fetched
     * before this app existed — is still reported as theirs.
     */
    val onDevice: StateFlow<FolderContents> =
        combine(_target, localRoms.revision) { target, _ -> target }
            .mapLatest { target -> target?.let { localRoms.listing(it) } ?: FolderContents.Unreadable }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderContents.Unreadable)

    private val _messages = MutableSharedFlow<QueueMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<QueueMessage> = _messages.asSharedFlow()

    /** True while a variant is being fetched over an already-rendered screen. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var loadJob: Job? = null

    init { load(_romId.value) }

    /** Show a different regional copy of the same game. */
    fun selectVariant(id: Int) {
        if (id == _romId.value) return
        _romId.value = id
        load(id)
    }

    private fun load(id: Int) {
        // Tapping down a long variant list should not leave earlier fetches
        // racing to overwrite the selection the user actually landed on.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Switching variants keeps the current detail on screen rather than
            // collapsing back to a spinner and losing the picker mid-tap.
            if (_state.value !is RomDetailState.Loaded) {
                _state.value = RomDetailState.Loading
            }
            _refreshing.value = true
            try {
                val rom = repo.getRomDetail(id)
                _state.value = RomDetailState.Loaded(rom)
                _target.value = platformDao.getById(rom.platformId)
                    ?.let { downloadTargets.resolve(it) }
                _variants.value = variantsOf(rom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RomDetail", "Failed to load ROM $id", e)
                _state.value = RomDetailState.Error(e.message ?: "Failed to load ROM")
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * The sibling list the server computed, or the local cache when the server
     * reported none.  The cache is the fallback rather than the primary source
     * because a ROM reached from search may belong to a platform that was never
     * synced, so Room would only know about the one ROM.
     */
    private suspend fun variantsOf(rom: DetailedRomSchema): List<RomVariant> {
        val preference = regionPreference(Locale.getDefault().country)
        // `sibling_roms` is a trimmed schema — ids and names, but no fs_name,
        // no size and no regions — so a sibling rendered straight from it is a
        // blank row reading "0 B".  The cache has all three whenever the
        // sibling's platform has been synced, which is the usual case.
        val cached = repo.getCachedRoms(rom.siblingRoms.map { it.id })
        val fromServer = buildList {
            add(RomVariant(rom.id, rom.fsName, rom.fsSizeBytes, regionsFor(rom.regions, rom.fsName)))
            rom.siblingRoms.forEach { sibling ->
                val entity = cached[sibling.id]
                add(
                    if (entity != null) entity.toVariant(repo.regionsOf(entity))
                    else sibling.toVariant()
                )
            }
        }
        val variants = if (fromServer.size > 1) {
            fromServer
        } else {
            val cached = repo.getCachedRom(rom.id)?.let { repo.cachedVariants(it) }.orEmpty()
            if (cached.size > 1) {
                cached.map { RomVariant(it.id, it.fsName, it.fsSizeBytes, repo.regionsOf(it)) }
            } else {
                fromServer
            }
        }
        return variants
            .distinctBy { it.id }
            .sortedWith(compareBy({ regionRank(it.regions, preference) }, { it.fsName }))
    }

    fun coverUrl(rom: DetailedRomSchema): String? = artworkUrl(
        credentials.serverUrl,
        rom.pathCoverLarge,
        rom.pathCoverSmall,
        rom.urlCover,
    )

    /** Queue [file]; the queue itself reports what came of it. */
    fun downloadFile(file: RomFileSchema) {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return
        viewModelScope.launch {
            _messages.emit(queue.enqueue(rom, listOf(file)).asMessage())
        }
    }

    /** Queue every file of the ROM — the whole set for a multi-disc game. */
    fun downloadAll() {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return
        viewModelScope.launch {
            _messages.emit(queue.enqueue(rom, rom.downloadableFiles()).asMessage())
        }
    }

    fun undo(ids: List<String>) {
        viewModelScope.launch { queue.undo(ids) }
    }

    fun cancel(id: String) {
        viewModelScope.launch { queue.cancel(id) }
    }

}

/** A cached copy, which knows its own filename and size. */
private fun RomEntity.toVariant(regions: List<String>) =
    RomVariant(id, fsName, fsSizeBytes, regions)

/**
 * A sibling the cache has never seen.
 *
 * The server omits `fs_name` from siblings, so the label falls back through the
 * names it does send.  `fs_name_no_ext` comes first because it still carries the
 * "(Japan)" / "(Rev 1)" tag, which is the only thing distinguishing one copy of
 * a game from another — and distinguishing them is the entire job of this row.
 * The size stays 0, meaning "unknown", and the row omits it rather than lying.
 */
private fun SimpleRomSchema.toVariant(): RomVariant {
    val label = fsName
        .ifBlank { fsNameNoExt }
        .ifBlank { fsNameNoTags }
        .ifBlank { name.orEmpty() }
        .ifBlank { "ROM #$id" }
    return RomVariant(id, label, fsSizeBytes, regionsFor(regions, label))
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomDetailScreen(
    viewModel: RomDetailViewModel,
    romId: Int,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val state          by viewModel.state.collectAsState()
    val target         by viewModel.target.collectAsState()
    val downloads      by viewModel.downloads.collectAsState()
    val variants       by viewModel.variants.collectAsState()
    val selectedRomId  by viewModel.romId.collectAsState()
    val refreshing     by viewModel.refreshing.collectAsState()
    val onDevice       by viewModel.onDevice.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    // Focus starts on the first file, which is the one thing on this page a
    // user came here to press.  Everything above it — the cover, the summary —
    // is reading matter with nothing to activate.
    val firstFile = remember { FocusRequester() }
    val loaded    = state is RomDetailState.Loaded
    RestoreFocus(firstFile, ready = loaded)

    GamepadHandler { action ->
        when (action) {
            // The whole set, which for a multi-disc game is the only sensible
            // thing a single button can mean.
            GamepadAction.Download -> {
                if (target != null) viewModel.downloadAll()
                true
            }
            // Undo and "Set folder" arrive on a snackbar, which a controller
            // cannot tap; Y takes whatever it is offering while it is up.
            GamepadAction.Search -> {
                snackbarHostState.currentSnackbarData
                    ?.takeIf { it.visuals.actionLabel != null }
                    ?.performAction()
                true
            }
            GamepadAction.PageUp   -> { scope.launch { listState.scrollPage(-1) }; true }
            GamepadAction.PageDown -> { scope.launch { listState.scrollPage(1) }; true }
            else                   -> false
        }
    }
    StickScroll(listState)

    val hasGamepad = rememberHasGamepad()
    LaunchedEffect(hasGamepad) {
        viewModel.messages.collect { message ->
            val action = when {
                message.undoIds.isNotEmpty() -> "Undo"
                message.needsFolder          -> "Set folder"
                else                         -> null
            }
            val result = snackbarHostState.showSnackbar(
                message     = message.text,
                actionLabel = action.withButton(GamepadButton.Y, hasGamepad),
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
                    val title = (state as? RomDetailState.Loaded)?.rom?.name ?: "ROM"
                    Text(title, maxLines = 1)
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
                    GamepadHint(GamepadButton.A, "Download file"),
                    GamepadHint(GamepadButton.X, "Download all"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        when (val s = state) {
            is RomDetailState.Loading -> {
                Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            is RomDetailState.Error -> {
                Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is RomDetailState.Loaded -> {
                val rom = s.rom
                Box(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        modifier       = Modifier.fillMaxSize(),
                        state          = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        // Cover art
                        item {
                            val coverUrl = viewModel.coverUrl(rom)
                            if (coverUrl != null) {
                                AsyncImage(
                                    model              = coverUrl,
                                    contentDescription = rom.name,
                                    contentScale       = ContentScale.Fit,
                                    modifier           = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                )
                            }
                        }

                        // Metadata
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    rom.name ?: rom.fsNameNoTags,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        rom.platformDisplayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    rom.metadatum.averageRating?.let { rating ->
                                        Spacer(Modifier.width(12.dp))
                                        RatingBadge(rating)
                                    }
                                }
                                if (!rom.summary.isNullOrBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(rom.summary, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        // Folder status / warning
                        item {
                            if (target == null) {
                                // The warning is also the way out of it: nothing
                                // on this screen works until a folder is chosen,
                                // so the card itself goes straight there.
                                Card(
                                    onClick  = onFolderSettings,
                                    colors   = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                ) {
                                    // One line, whatever the screen width: the
                                    // card is the button, so the label carries
                                    // no more than it has to and the chevron
                                    // says where tapping goes.
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical   = 10.dp,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            "No ROMs folder set",
                                            style    = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Set folder",
                                            style    = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .size(14.dp),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            } else {
                                Text(
                                    "Downloads to: ${target!!.displayPath}",
                                    style    = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }

                        // Regional variants.  Only shown when there is a real
                        // choice to make — a one-copy game gets no extra chrome.
                        if (variants.size > 1) {
                            item {
                                Text(
                                    "Versions",
                                    style    = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            // Prefixed keys: variants are keyed by ROM id and
                            // files by file id, and RomM hands out both from
                            // ranges that overlap — ROM 75's own file is also
                            // id 75 — so a bare id crashed the list the moment
                            // both rows were measured in the same pass.
                            items(variants, key = { "variant-${it.id}" }) { variant ->
                                RomVariantRow(
                                    variant  = variant,
                                    selected = variant.id == selectedRomId,
                                    onDevice = onDevice.contains(variant.fsName),
                                    onClick  = { viewModel.selectVariant(variant.id) },
                                )
                            }
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        }

                        // File list. The API omits it for simple single-file
                        // ROMs, so one is synthesised from fs_name — otherwise
                        // the commonest case of all has nothing to download.
                        val files = rom.downloadableFiles()
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                Text("Files", style = MaterialTheme.typography.titleMedium)
                                if (files.size > 1) {
                                    TextButton(
                                        onClick  = { viewModel.downloadAll() },
                                        enabled  = target != null,
                                        modifier = Modifier.focusOutline(),
                                    ) { Text("Download all") }
                                }
                            }
                        }
                        items(files, key = { "file-${it.id}" }) { file ->
                            RomFileRow(
                                file           = file,
                                canDownload    = target != null,
                                download       = downloads[file.id],
                                onDeviceBytes  = onDevice.sizeOf(file.fileName),
                                folderReadable = onDevice.readable,
                                onDownload     = { viewModel.downloadFile(file) },
                                onCancel       = { id -> viewModel.cancel(id) },
                                focusRequester = firstFile.takeIf { file.id == files.first().id },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (refreshing) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RomVariantRow(
    variant: RomVariant,
    selected: Boolean,
    onDevice: Boolean,
    onClick: () -> Unit,
) {
    val flags = regionSummary(variant.regions)
    // A size of 0 means the server never told us one, not a zero-byte ROM.
    val detail = buildList {
        variant.sizeBytes.takeIf { it > 0 }?.let { add(it.formatSize()) }
        if (onDevice) add("On device")
    }
    ListItem(
        modifier = Modifier.gamepadRow(onClick = onClick),
        // The filename, not the region, is the headline: two copies from the
        // same region are told apart only by their "(Rev 1)" / "(Beta)" tags.
        headlineContent   = { Text(variant.fsName) },
        supportingContent = if (detail.isEmpty()) null else {
            { Text(detail.joinToString("  ·  ")) }
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

/**
 * One downloadable file.
 *
 * [onDeviceBytes] is the size the file has in the ROMs folder right now, or
 * null when it is not there.  [folderReadable] says whether that null can be
 * trusted: with a revoked SAF grant nothing can be read, and reporting every
 * ROM as missing would be worse than saying nothing.
 */
@Composable
private fun RomFileRow(
    file: RomFileSchema,
    canDownload: Boolean,
    download: DownloadItem?,
    onDeviceBytes: Long?,
    folderReadable: Boolean,
    onDownload: () -> Unit,
    onCancel: (String) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val present = onDeviceBytes != null
    val running = download?.status?.isFinished == false
    // The row does what its trailing button does.  A controller reaches rows,
    // not the buttons inside them, and this row has exactly one action — so
    // pressing it anywhere is the action, and the button stays for the thumb.
    Column(
        Modifier.gamepadRow(
            onClick        = {
                when {
                    running     -> onCancel(download.id)
                    canDownload -> onDownload()
                }
            },
            focusRequester = focusRequester,
        )
    ) {
        ListItem(
            headlineContent   = { Text(file.fileName) },
            supportingContent = {
                // Surface what the download is actually doing. Previously a tap
                // produced no visible change whether it worked or not.
                when (download?.status) {
                    DownloadStatus.QUEUED  -> Text("Waiting…")
                    DownloadStatus.RUNNING -> Text(
                        if (download.totalBytes > 0)
                            "${download.downloadedBytes.formatSize()} / ${download.totalBytes.formatSize()}"
                        else "Downloading…"
                    )
                    DownloadStatus.FAILED -> Text(
                        download.error ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Nothing in flight, so what matters is whether the file is
                    // actually sitting in the folder — which outranks whatever
                    // the queue remembers, because the user can delete it.
                    else -> when {
                        onDeviceBytes != null ->
                            Text("On device  ·  ${onDeviceBytes.formatSize()}")
                        download?.status == DownloadStatus.SUCCEEDED && folderReadable ->
                            Text(
                                "Downloaded, but no longer in the folder",
                                color = MaterialTheme.colorScheme.error,
                            )
                        download?.status == DownloadStatus.SUCCEEDED ->
                            Text("Downloaded  ·  ${file.fileSizeBytes.formatSize()}")
                        download?.status == DownloadStatus.CANCELLED -> Text("Cancelled")
                        else -> Text(file.fileSizeBytes.formatSize())
                    }
                }
            },
            leadingContent = if (!present) null else {
                {
                    Icon(
                        imageVector        = Icons.Default.CheckCircle,
                        contentDescription = "Already on device",
                        tint               = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            trailingContent   = {
                if (running) {
                    IconButton(
                        onClick  = { onCancel(download.id) },
                        modifier = Modifier.focusOutline(),
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Close,
                            contentDescription = "Cancel ${file.fileName}",
                        )
                    }
                } else {
                    IconButton(
                        onClick  = onDownload,
                        enabled  = canDownload,
                        modifier = Modifier.focusOutline(),
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Download,
                            contentDescription = if (present) "Download ${file.fileName} again"
                                                 else "Download ${file.fileName}",
                            tint = if (canDownload) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            },
        )
        if (download?.status == DownloadStatus.RUNNING) {
            val progress = download.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
}
