package app.rommdroid.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.api.model.DetailedRomSchema
import app.rommdroid.data.api.model.RomFileSchema
import app.rommdroid.data.db.PlatformFolderEntity
import app.rommdroid.data.db.PlatformFolderDao
import app.rommdroid.data.download.DownloadWorker
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.formatSize
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed interface RomDetailState {
    data object Loading : RomDetailState
    data class  Loaded(val rom: DetailedRomSchema) : RomDetailState
    data class  Error(val message: String) : RomDetailState
}

@HiltViewModel
class RomDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
    private val platformFolderDao: PlatformFolderDao,
    private val workManager: WorkManager,
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle[Route.RomDetail.ARG])

    private val _state = MutableStateFlow<RomDetailState>(RomDetailState.Loading)
    val state: StateFlow<RomDetailState> = _state.asStateFlow()

    private val _platformFolder = MutableStateFlow<PlatformFolderEntity?>(null)
    val platformFolder: StateFlow<PlatformFolderEntity?> = _platformFolder.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val rom = repo.getRomDetail(romId)
                _state.value = RomDetailState.Loaded(rom)
                _platformFolder.value = platformFolderDao.getForPlatform(rom.platformId)
            } catch (e: Exception) {
                _state.value = RomDetailState.Error(e.message ?: "Failed to load ROM")
            }
        }
    }

    fun coverUrl(rom: DetailedRomSchema): String? {
        val server = credentials.serverUrl ?: return null
        val cover  = rom.urlCover ?: return null
        return if (cover.startsWith("http")) cover else "$server/$cover"
    }

    /**
     * Enqueue a WorkManager download for [file].
     * If no folder is mapped for the platform, returns false and the caller
     * should prompt the user to map a folder first.
     */
    fun downloadFile(file: RomFileSchema): Boolean {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return false
        val folder = _platformFolder.value ?: return false
        val serverUrl = credentials.serverUrl ?: return false

        val url = repo.romDownloadUrl(serverUrl, rom.id, file.fileName, listOf(file.id))

        val inputData = DownloadWorker.buildRequest(
            url            = url,
            fileName       = file.fileName,
            destinationUri = folder.folderUri,
            romId          = rom.id,
            expectedBytes  = file.fileSizeBytes,
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("rom_${rom.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${rom.id}_${file.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return true
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomDetailScreen(
    viewModel: RomDetailViewModel,
    romId: Int,
    onBack: () -> Unit,
) {
    val state          by viewModel.state.collectAsState()
    val platformFolder by viewModel.platformFolder.collectAsState()

    var showNoFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (state as? RomDetailState.Loaded)?.rom?.name ?: "ROM"
                    Text(title, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
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
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
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
                            Text(
                                rom.platformDisplayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (!rom.summary.isNullOrBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(rom.summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Folder status / warning
                    item {
                        if (platformFolder == null) {
                            Card(
                                colors   = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "No download folder set for ${rom.platformDisplayName}. " +
                                            "Go to Settings → Folder Mapping to configure it.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        } else {
                            Text(
                                "Downloads to: ${platformFolder!!.displayPath}",
                                style    = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // File list
                    item {
                        Text(
                            "Files",
                            style    = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(rom.files) { file ->
                        RomFileRow(
                            file     = file,
                            canDownload = platformFolder != null,
                            onDownload  = {
                                val ok = viewModel.downloadFile(file)
                                if (!ok) showNoFolderDialog = true
                            }
                        )
                        HorizontalDivider()
                    }
                    // If no files yet, show the primary fs_name as a single downloadable item
                    if (rom.files.isEmpty()) {
                        item {
                            RomFileRow(
                                file = RomFileSchema(
                                    id           = 0,
                                    fileName     = rom.fsName,
                                    fileSizeBytes = rom.fsSizeBytes,
                                ),
                                canDownload = platformFolder != null,
                                onDownload  = {
                                    showNoFolderDialog = platformFolder == null
                                    // TODO: enqueue single-file download
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showNoFolderDialog) {
            AlertDialog(
                onDismissRequest = { showNoFolderDialog = false },
                title = { Text("No folder configured") },
                text  = { Text("Set a download folder for this platform in Settings → Folder Mapping.") },
                confirmButton = {
                    TextButton(onClick = { showNoFolderDialog = false }) { Text("OK") }
                },
            )
        }
    }
}

@Composable
private fun RomFileRow(
    file: RomFileSchema,
    canDownload: Boolean,
    onDownload: () -> Unit,
) {
    ListItem(
        headlineContent   = { Text(file.fileName) },
        supportingContent = { Text(file.fileSizeBytes.formatSize()) },
        trailingContent   = {
            IconButton(onClick = onDownload) {
                Icon(
                    imageVector        = Icons.Default.Download,
                    contentDescription = "Download ${file.fileName}",
                    tint = if (canDownload) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        },
    )
}
