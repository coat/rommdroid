package app.rommdroid.ui.screens

import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.BaseFolderDao
import app.rommdroid.data.db.BaseFolderEntity
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.db.PlatformFolderDao
import app.rommdroid.data.db.PlatformFolderEntity
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.PlatformSubfolderDao
import app.rommdroid.data.db.PlatformSubfolderEntity
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.data.download.asMessage
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.components.OutlinedInputField
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

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message     = message.text,
                actionLabel = when {
                    message.undoIds.isNotEmpty() -> "Undo"
                    message.needsFolder          -> "Set folder"
                    else                         -> null
                },
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
                    OutlinedInputField(
                        value         = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder   = "Search ROMs…",
                        imeAction     = EditorInfo.IME_ACTION_SEARCH,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
                    modifier = Modifier.combinedClickable(
                        onClick          = { onRomClick(rom.id) },
                        onLongClick      = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.download(group)
                        },
                        onLongClickLabel = "Download",
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (finished.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearFinished() }) { Text("Clear") }
                    }
                },
            )
        }
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
                Text(
                    "Long-press a game in any ROM list to add it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (active.isNotEmpty()) {
                item { QueueHeader("In progress · ${active.size}") }
                items(active, key = { it.id }) { item ->
                    DownloadRow(
                        item     = item,
                        onClick  = { onRomClick(item.romId) },
                        onCancel = { viewModel.cancel(item.id) },
                        onRetry  = { viewModel.retry(item.id) },
                        onRemove = { viewModel.remove(item.id) },
                    )
                    HorizontalDivider()
                }
            }
            if (finished.isNotEmpty()) {
                item { QueueHeader("Finished") }
                items(finished, key = { it.id }) { item ->
                    DownloadRow(
                        item     = item,
                        onClick  = { onRomClick(item.romId) },
                        onCancel = { viewModel.cancel(item.id) },
                        onRetry  = { viewModel.retry(item.id) },
                        onRemove = { viewModel.remove(item.id) },
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
) {
    Column(Modifier.clickable(onClick = onClick)) {
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
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }
                    DownloadStatus.SUCCEEDED ->
                        IconButton(onClick = onRemove) {
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentials: CredentialRepository,
) : ViewModel() {
    val serverUrl: String get() = credentials.serverUrl ?: "(not set)"
    val username: String  get() = credentials.username  ?: "(not set)"

    /** Clears all stored credentials and server config. */
    fun disconnect() {
        credentials.clearAll()
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
    var showDisconnectDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    headlineContent   = { Text("Server") },
                    supportingContent = { Text(viewModel.serverUrl) },
                    leadingContent    = { Icon(Icons.Default.Dns, null) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent   = { Text("Account") },
                    supportingContent = { Text(viewModel.username) },
                    leadingContent    = { Icon(Icons.Default.Person, null) },
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    modifier          = Modifier.clickable(onClick = onFolderMapping),
                    headlineContent   = { Text("Folder Mapping") },
                    supportingContent = { Text("Set your ROMs folder and per-platform overrides") },
                    leadingContent    = { Icon(Icons.Default.FolderOpen, null) },
                    trailingContent   = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                )
                HorizontalDivider()
            }
            item {
                Spacer(Modifier.height(16.dp))
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { showDisconnectDialog = true },
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
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title   = { Text("Disconnect from RomM?") },
            text    = {
                Text(
                    "This will remove your saved credentials and server URL. " +
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
    private val folderDao: PlatformFolderDao,
    private val baseFolderDao: BaseFolderDao,
    private val subfolderDao: PlatformSubfolderDao,
    private val targets: DownloadTargetRepository,
    private val localRoms: LocalRomIndex,
) : ViewModel() {

    val baseFolder: StateFlow<BaseFolderEntity?> =
        baseFolderDao.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every platform with its resolved destination. Combining the three sources
     * here keeps the resolution rules in one place rather than duplicated
     * between the download path and the settings UI.
     */
    val rows: StateFlow<List<PlatformFolderRow>> = combine(
        repo.observePlatforms(),
        baseFolderDao.observe(),
        folderDao.observeAll(),
        subfolderDao.observeAll(),
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
            folderDao.upsert(PlatformFolderEntity(platformId, uri, displayPath))
            localRoms.invalidate()
        }
    }

    fun renameSubfolder(platformId: Int, name: String) {
        viewModelScope.launch {
            subfolderDao.upsert(PlatformSubfolderEntity(platformId, name.trim().trim('/')))
            localRoms.invalidate()
        }
    }

    /** Drop both kinds of override so the platform follows the ES-DE default again. */
    fun resetPlatform(platformId: Int) {
        viewModelScope.launch {
            folderDao.deleteForPlatform(platformId)
            subfolderDao.deleteForPlatform(platformId)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folder Mapping") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {

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
                        Button(onClick = { basePicker.launch(baseFolder?.folderUri?.let(Uri::parse)) }) {
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
                    modifier = Modifier.clickable { editing = row },
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
