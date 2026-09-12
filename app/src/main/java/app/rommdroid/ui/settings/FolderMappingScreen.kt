package app.rommdroid.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.util.safDisplayPath
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FolderMappingScreen(
    viewModel: FolderMappingViewModel,
    onBack: () -> Unit,
) {
    val context    = LocalContext.current
    val rows       by viewModel.rows.collectAsStateWithLifecycle()
    val baseFolder by viewModel.baseFolder.collectAsStateWithLifecycle()

    // Which platform a picker result belongs to; -1 is the base folder itself.
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

    val listState = rememberLazyListState()
    ListGamepadScrolling(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folder Mapping") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Edit"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {

            // Base folder
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
                                ?: "Not set - choose the folder that holds your platform subfolders.",
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
                        Button(
                            onClick  = { basePicker.launch(baseFolder?.folderUri?.let(Uri::parse)) },
                            modifier = Modifier.focusOutline(),
                        ) {
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
                    modifier = Modifier.gamepadRow(onClick = { editing = row }),
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
