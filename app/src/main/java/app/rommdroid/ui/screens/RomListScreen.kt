package app.rommdroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.navigation.Route
import app.rommdroid.util.artworkUrl
import app.rommdroid.util.formatSize
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class RomListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
) : ViewModel() {

    private val platformId: Int = checkNotNull(savedStateHandle[Route.RomList.ARG])

    val roms: StateFlow<List<RomEntity>> =
        repo.observeRoms(platformId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun coverUrl(rom: RomEntity): String? = artworkUrl(
        credentials.serverUrl,
        rom.pathCoverSmall,
        rom.pathCoverLarge,
        rom.urlCover,
    )
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomListScreen(
    viewModel: RomListViewModel,
    platformId: Int,
    onRomClick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val roms    by viewModel.roms.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val error   by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ROMs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                syncing && roms.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn {
                        items(roms, key = { it.id }) { rom ->
                            RomRow(
                                rom      = rom,
                                coverUrl = viewModel.coverUrl(rom),
                                onClick  = { onRomClick(rom.id) },
                            )
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
            error?.let { msg ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                ) { Text(msg) }
            }
        }
    }
}

@Composable
private fun RomRow(
    rom: RomEntity,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent  = { Text(rom.name ?: rom.fsNameNoTags) },
        supportingContent = {
            Text("${rom.fsExtension.uppercase()}  ·  ${rom.fsSizeBytes.formatSize()}")
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
    )
}
