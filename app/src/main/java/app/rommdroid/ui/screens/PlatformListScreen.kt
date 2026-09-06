package app.rommdroid.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.util.artworkUrl
import app.rommdroid.util.formatSize
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class PlatformListViewModel @Inject constructor(
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformEntity>> =
        repo.observePlatforms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _syncing.value = true
            _error.value   = null
            try {
                repo.syncPlatforms()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _syncing.value = false
            }
        }
    }

    fun coverUrl(platform: PlatformEntity): String? = artworkUrl(
        credentials.serverUrl,
        platform.urlLogo,
    )
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformListScreen(
    viewModel: PlatformListViewModel,
    onPlatformClick: (Int) -> Unit,
    onSearchClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val platforms by viewModel.platforms.collectAsState()
    val syncing   by viewModel.syncing.collectAsState()
    val error     by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text("Platforms") },
                actions = {
                    // The list syncs once, when this screen is first created, so
                    // without this a platform deleted on the server — or a cache
                    // cleared from Settings — only resolves on the next launch.
                    IconButton(onClick = { viewModel.refresh() }, enabled = !syncing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onDownloadsClick) {
                        Icon(Icons.Default.Download, contentDescription = "Downloads")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                syncing && platforms.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error != null && platforms.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Could not reach server", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
                else -> {
                    LazyColumn {
                        items(platforms, key = { it.id }) { platform ->
                            PlatformRow(
                                platform    = platform,
                                coverUrl    = viewModel.coverUrl(platform),
                                onClick     = { onPlatformClick(platform.id) },
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
        }
    }
}

@Composable
private fun PlatformRow(
    platform: PlatformEntity,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(platform.displayName) },
        supportingContent = { Text("${platform.romCount} ROMs") },
        leadingContent = {
            if (coverUrl != null) {
                AsyncImage(
                    model             = coverUrl,
                    contentDescription = platform.displayName,
                    modifier          = Modifier.size(40.dp),
                )
            } else {
                Icon(
                    imageVector        = Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier           = Modifier.size(40.dp),
                )
            }
        },
    )
}
