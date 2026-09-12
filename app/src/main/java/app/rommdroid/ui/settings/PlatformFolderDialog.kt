package app.rommdroid.ui.settings

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.ui.components.OutlinedInputField
import kotlinx.coroutines.flow.*

/** Rename the subfolder, or point the platform at an unrelated directory. */
@Composable
internal fun PlatformFolderDialog(
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
                    Text("Use a different folder...")
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
