package app.rommdroid.ui.screens

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.db.PlatformFolderDao
import app.rommdroid.data.db.PlatformFolderEntity
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.util.formatSize
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Search
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: RomRepository,
) : ViewModel() {

    val query = MutableStateFlow("")

    val results: StateFlow<List<RomEntity>> = query
        .debounce(300)
        .mapLatest { q -> if (q.length >= 2) repo.searchLocal(q) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onRomClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val query   by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value         = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder   = { Text("Search ROMs…") },
                        singleLine    = true,
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
            items(results, key = { it.id }) { rom ->
                ListItem(
                    modifier          = Modifier.clickable { onRomClick(rom.id) },
                    headlineContent   = { Text(rom.name ?: rom.fsNameNoTags) },
                    supportingContent = { Text(rom.platformDisplayName) },
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
class DownloadsViewModel @Inject constructor() : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        // TODO: observe WorkManager state for download jobs tagged "rom_*"
        Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
            Text("Download queue coming soon", style = MaterialTheme.typography.bodyLarge)
        }
    }
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
                    supportingContent = { Text("Configure per-platform download folders") },
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

@HiltViewModel
class FolderMappingViewModel @Inject constructor(
    private val repo: RomRepository,
    private val folderDao: PlatformFolderDao,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformEntity>> =
        repo.observePlatforms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<PlatformFolderEntity>> =
        folderDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Persists the SAF tree URI grant and saves the mapping.
     *
     * [uri]         — the content:// tree URI returned by ACTION_OPEN_DOCUMENT_TREE
     * [displayPath] — human-readable path derived from the URI (see [safDisplayPath])
     */
    fun saveFolder(platformId: Int, uri: String, displayPath: String) {
        viewModelScope.launch {
            folderDao.upsert(PlatformFolderEntity(platformId, uri, displayPath))
        }
    }

    fun removeFolder(platformId: Int) {
        viewModelScope.launch {
            folderDao.deleteForPlatform(platformId)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderMappingScreen(
    viewModel: FolderMappingViewModel,
    onBack: () -> Unit,
) {
    val context   = LocalContext.current
    val platforms by viewModel.platforms.collectAsState()
    val folders   by viewModel.folders.collectAsState()

    val folderMap = folders.associateBy { it.platformId }

    // Which platform ID the next picker result belongs to.
    // Stored in a mutable ref so the launcher callback always reads the latest value.
    val pendingPlatformId = remember { mutableIntStateOf(-1) }

    // SAF folder picker launcher.
    // On success: take persistent read+write permission on the URI, then save.
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val platformId = pendingPlatformId.intValue
        if (platformId == -1) return@rememberLauncherForActivityResult

        // Take persistent URI permissions so we can write here across reboots.
        // Without this the grant is revoked on the next reboot.
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        val displayPath = safDisplayPath(uri)
        viewModel.saveFolder(platformId, uri.toString(), displayPath)
        pendingPlatformId.intValue = -1
    }

    // Long-press / confirm dialog for folder removal
    var removePlatform by remember { mutableStateOf<PlatformEntity?>(null) }

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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Explanatory banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Text(
                    text = "Tap a platform to choose where its ROMs are saved. " +
                           "Long-press to remove a mapping.",
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            LazyColumn {
                items(platforms, key = { it.id }) { platform ->
                    val mapped = folderMap[platform.id]
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                pendingPlatformId.intValue = platform.id
                                // Pass the existing URI as an initial location hint (Android 8+)
                                folderPickerLauncher.launch(
                                    mapped?.folderUri?.let { Uri.parse(it) }
                                )
                            },
                            onLongClick = if (mapped != null) ({
                                removePlatform = platform
                            }) else null,
                        ),
                        headlineContent   = { Text(platform.displayName) },
                        supportingContent = {
                            Text(
                                mapped?.displayPath ?: "Tap to set download folder",
                                color = if (mapped != null)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (mapped != null) Icons.Default.FolderOpen
                                              else Icons.Default.Folder,
                                contentDescription = null,
                                tint = if (mapped != null) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            if (mapped != null) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Folder set",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // Confirm removal dialog
    removePlatform?.let { platform ->
        AlertDialog(
            onDismissRequest = { removePlatform = null },
            title   = { Text("Remove folder mapping?") },
            text    = {
                Text(
                    "The folder mapping for ${platform.displayName} will be removed. " +
                    "Files already downloaded are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeFolder(platform.id)
                        removePlatform = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removePlatform = null }) { Text("Cancel") }
            },
        )
    }
}
