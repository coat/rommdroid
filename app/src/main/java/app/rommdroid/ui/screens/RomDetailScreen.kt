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
import app.rommdroid.data.db.PlatformDao
import app.rommdroid.data.download.DownloadWorker
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.artworkUrl
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
    private val platformDao: PlatformDao,
    private val downloadTargets: DownloadTargetRepository,
    private val workManager: WorkManager,
) : ViewModel() {

    private val romId: Int = checkNotNull(savedStateHandle[Route.RomDetail.ARG])

    private val _state = MutableStateFlow<RomDetailState>(RomDetailState.Loading)
    val state: StateFlow<RomDetailState> = _state.asStateFlow()

    private val _target = MutableStateFlow<DownloadTarget?>(null)
    val target: StateFlow<DownloadTarget?> = _target.asStateFlow()

    /** Live state of every download enqueued for this ROM, keyed by file id. */
    val downloads: StateFlow<Map<Int, WorkInfo>> =
        workManager.getWorkInfosByTagFlow("rom_$romId")
            .map { infos ->
                infos.mapNotNull { info ->
                    val fileId = info.tags
                        .firstOrNull { it.startsWith(TAG_FILE_PREFIX) }
                        ?.removePrefix(TAG_FILE_PREFIX)
                        ?.toIntOrNull()
                        ?: return@mapNotNull null
                    fileId to info
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                val rom = repo.getRomDetail(romId)
                _state.value = RomDetailState.Loaded(rom)
                _target.value = platformDao.getById(rom.platformId)
                    ?.let { downloadTargets.resolve(it) }
            } catch (e: Exception) {
                android.util.Log.e("RomDetail", "Failed to load ROM $romId", e)
                _state.value = RomDetailState.Error(e.message ?: "Failed to load ROM")
            }
        }
    }

    fun coverUrl(rom: DetailedRomSchema): String? = artworkUrl(
        credentials.serverUrl,
        rom.pathCoverLarge,
        rom.pathCoverSmall,
        rom.urlCover,
    )

    /**
     * Enqueue a WorkManager download for [file].
     * If no folder is mapped for the platform, returns false and the caller
     * should prompt the user to map a folder first.
     */
    fun downloadFile(file: RomFileSchema): Boolean {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return false
        val target = _target.value ?: return false
        val serverUrl = credentials.serverUrl ?: return false

        // For synthetic single-file ROMs (id=0), don't pass file_ids — the API
        // will serve the primary file by name without needing an ID filter.
        val fileIds = if (file.id == 0) emptyList() else listOf(file.id)
        val url = try {
            repo.romDownloadUrl(serverUrl, rom.id, file.fileName, fileIds)
        } catch (e: IllegalArgumentException) {
            // A stored server URL missing its scheme would otherwise throw on
            // the main thread and take the app down on tap.
            android.util.Log.e("RomDetail", "Bad server URL: $serverUrl", e)
            _messages.tryEmit("Invalid server URL — reconnect in Settings")
            return true
        }

        val inputData = DownloadWorker.buildRequest(
            url            = url,
            fileName       = file.fileName,
            destinationUri = target.treeUri,
            romId          = rom.id,
            expectedBytes  = file.fileSizeBytes,
            subfolder      = target.subfolder,
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("rom_${rom.id}")
            .addTag("$TAG_FILE_PREFIX${file.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${rom.id}_${file.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        _messages.tryEmit("Queued ${file.fileName}")
        return true
    }

    private companion object {
        const val TAG_FILE_PREFIX = "file_"
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
    val target         by viewModel.target.collectAsState()
    val downloads      by viewModel.downloads.collectAsState()

    var showNoFolderDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
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
                        if (target == null) {
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
                                        "No ROMs folder set. Go to Settings → Folder Mapping " +
                                            "and choose your ROMs directory.",
                                        style = MaterialTheme.typography.bodySmall,
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
                            file        = file,
                            canDownload = target != null,
                            workInfo    = downloads[file.id],
                            onDownload  = {
                                val ok = viewModel.downloadFile(file)
                                if (!ok) showNoFolderDialog = true
                            }
                        )
                        HorizontalDivider()
                    }
                    // If the API returned no files list, synthesize one from fs_name
                    // so there is always something to download.
                    if (rom.files.isEmpty()) {
                        item {
                            val syntheticFile = RomFileSchema(
                                id            = 0,
                                romId         = rom.id,
                                fileName      = rom.fsName,
                                fileSizeBytes = rom.fsSizeBytes,
                            )
                            RomFileRow(
                                file        = syntheticFile,
                                canDownload = target != null,
                                workInfo    = downloads[syntheticFile.id],
                                onDownload  = {
                                    val ok = viewModel.downloadFile(syntheticFile)
                                    if (!ok) showNoFolderDialog = true
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
                text  = { Text("Choose your ROMs folder in Settings → Folder Mapping.") },
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
    workInfo: WorkInfo?,
    onDownload: () -> Unit,
) {
    val downloaded = workInfo?.progress?.getLong(DownloadWorker.PROGRESS_BYTES, 0L) ?: 0L
    val total      = workInfo?.progress?.getLong(DownloadWorker.PROGRESS_TOTAL, 0L) ?: 0L

    Column {
        ListItem(
            headlineContent   = { Text(file.fileName) },
            supportingContent = {
                // Surface what the download is actually doing. Previously a tap
                // produced no visible change whether it worked or not.
                when (workInfo?.state) {
                    WorkInfo.State.ENQUEUED -> Text("Waiting for network…")
                    WorkInfo.State.RUNNING  -> Text(
                        if (total > 0) "${downloaded.formatSize()} / ${total.formatSize()}"
                        else "Downloading…"
                    )
                    WorkInfo.State.SUCCEEDED -> Text("Downloaded · ${file.fileSizeBytes.formatSize()}")
                    WorkInfo.State.FAILED -> Text(
                        workInfo.outputData.getString(DownloadWorker.KEY_ERROR)
                            ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                    )
                    WorkInfo.State.CANCELLED -> Text("Cancelled")
                    else -> Text(file.fileSizeBytes.formatSize())
                }
            },
            trailingContent   = {
                IconButton(
                    onClick = onDownload,
                    enabled = canDownload && workInfo?.state?.isFinished != false,
                ) {
                    Icon(
                        imageVector        = Icons.Default.Download,
                        contentDescription = "Download ${file.fileName}",
                        tint = if (canDownload) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            },
        )
        if (workInfo?.state == WorkInfo.State.RUNNING) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { downloaded.toFloat() / total.toFloat() },
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
