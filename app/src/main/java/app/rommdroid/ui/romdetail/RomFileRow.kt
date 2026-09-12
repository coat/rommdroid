package app.rommdroid.ui.romdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.domain.RomFile
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.components.TransferProgress
import app.rommdroid.util.formatSize
import kotlinx.coroutines.flow.*

/**
 * One downloadable file. [onDeviceBytes] is its current size in the ROMs folder,
 * or null when absent; [folderReadable] says whether that null can be trusted,
 * since a revoked SAF grant would otherwise read as a missing library.
 */
@Composable
internal fun RomFileRow(
    file: RomFile,
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
    // A controller reaches rows, not the buttons inside them, and this row has
    // exactly one action. The button stays for the thumb.
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
                when (download?.status) {
                    DownloadStatus.QUEUED  -> Text("Waiting...")
                    DownloadStatus.RUNNING -> Text(
                        if (download.totalBytes > 0)
                            "${download.downloadedBytes.formatSize()} / ${download.totalBytes.formatSize()}"
                        else "Downloading..."
                    )
                    DownloadStatus.FAILED -> Text(
                        download.error ?: "Download failed",
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Nothing in flight, so what matters is whether the file is
                    // actually sitting in the folder - which outranks whatever
                    // the queue remembers, because the user can delete it.
                    else -> when {
                        onDeviceBytes != null ->
                            Text("On device  -  ${onDeviceBytes.formatSize()}")
                        download?.status == DownloadStatus.SUCCEEDED && folderReadable ->
                            Text(
                                "Downloaded, but no longer in the folder",
                                color = MaterialTheme.colorScheme.error,
                            )
                        download?.status == DownloadStatus.SUCCEEDED ->
                            Text("Downloaded  -  ${file.sizeBytes.formatSize()}")
                        download?.status == DownloadStatus.CANCELLED -> Text("Cancelled")
                        else -> Text(file.sizeBytes.formatSize())
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
            TransferProgress(download.progress, Modifier.padding(horizontal = 16.dp))
        }
    }
}
