package app.rommdroid.ui.romdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rommdroid.ui.common.QueueSnackbarEffect
import app.rommdroid.ui.common.takeOffer
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.ui.components.RatingBadge
import app.rommdroid.ui.gamepad.RestoreFocus
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RomDetailScreen(
    viewModel: RomDetailViewModel,
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

    // Focus starts on the first file: the cover and summary above it are
    // reading matter with nothing to activate.
    val firstFile = remember { FocusRequester() }
    val loaded    = state is RomDetailState.Loaded
    RestoreFocus(firstFile, ready = loaded)

    GamepadHandler { action ->
        when (action) {
            // The whole set: the only thing one button can mean for multi-disc.
            GamepadAction.Download -> {
                if (target != null) viewModel.downloadAll()
                true
            }
            // A controller cannot tap a snackbar, so Y takes its offer.
            GamepadAction.Search -> { snackbarHostState.takeOffer(); true }
            else                 -> false
        }
    }
    ListGamepadScrolling(listState)

    QueueSnackbarEffect(
        messages         = viewModel.requests.messages,
        host             = snackbarHostState,
        onUndo           = viewModel.requests::undo,
        onFolderSettings = onFolderSettings,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val title = (state as? RomDetailState.Loaded)?.rom?.name ?: "ROM"
                    Text(title, maxLines = 1)
                },
                navigationIcon = { BackButton(onBack) },
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
                Box(Modifier.fillMaxSize().padding(padding)) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            is RomDetailState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
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
                            rom.coverUrl?.let { coverUrl ->
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
                                Text(rom.displayName, style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        rom.platformDisplayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    rom.rating?.let { rating ->
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
                                // The warning is the way out of it: nothing here
                                // works until a folder is chosen.
                                Card(
                                    onClick  = onFolderSettings,
                                    colors   = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor   = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                ) {
                                    // One line at any width; the chevron says
                                    // where tapping goes.
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

                        // Only when there is a real choice to make.
                        if (variants.size > 1) {
                            item {
                                Text(
                                    "Versions",
                                    style    = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            // Prefixed: RomM's ROM ids and file ids overlap, and
                            // a bare id crashes the list once both kinds of row
                            // are measured in one pass.
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

                        val files = rom.files
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
