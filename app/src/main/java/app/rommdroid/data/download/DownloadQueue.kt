package app.rommdroid.data.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.rommdroid.data.api.model.DetailedRomSchema
import app.rommdroid.data.api.model.RomFileSchema
import app.rommdroid.data.db.DownloadDao
import app.rommdroid.data.db.DownloadEntity
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.PlatformDao
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened when the user asked for a ROM.
 *
 * Every case is something the UI has to say out loud: a long-press that queues
 * nothing and explains nothing is indistinguishable from a missed gesture.
 */
sealed interface EnqueueResult {
    /** [ids] are queue rows, so the caller can offer an undo. */
    data class Queued(val label: String, val ids: List<String>) : EnqueueResult
    data class AlreadyQueued(val label: String) : EnqueueResult
    /** No ROMs folder is configured yet, so there is nowhere to write. */
    data object NoFolder : EnqueueResult
    data class Failed(val message: String) : EnqueueResult
}

/** A queue outcome phrased for a snackbar, plus the one action worth offering. */
data class QueueMessage(
    val text: String,
    /** Non-empty when the action should be "Undo" for these rows. */
    val undoIds: List<String> = emptyList(),
    /** True when the user needs to go and pick a folder before this can work. */
    val needsFolder: Boolean = false,
)

/**
 * Phrase a result for the user.  [suffix] lets a caller add the region flags of
 * the copy it picked, which is the only thing distinguishing one variant of a
 * game from another when the queue happened without a visit to the detail page.
 */
fun EnqueueResult.asMessage(suffix: String = ""): QueueMessage {
    fun label(name: String) = if (suffix.isBlank()) name else "$name  $suffix"
    return when (this) {
        is EnqueueResult.Queued -> QueueMessage(
            text = if (ids.size > 1) "Queued ${label(this.label)} · ${ids.size} files"
                   else "Queued ${label(this.label)}",
            undoIds = ids,
        )
        is EnqueueResult.AlreadyQueued -> QueueMessage("${label(this.label)} is already in the queue")
        EnqueueResult.NoFolder -> QueueMessage(
            text = "No ROMs folder set yet",
            needsFolder = true,
        )
        is EnqueueResult.Failed -> QueueMessage(message)
    }
}

/** One row of the downloads screen: the stored request plus its live progress. */
data class DownloadItem(
    val id: String,
    val romId: Int,
    val fileId: Int,
    val fileName: String,
    val romName: String,
    val platformName: String,
    val destinationPath: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String?,
    val enqueuedAt: Long,
) {
    /** Null when the transfer has not reported a size yet, i.e. show it indeterminate. */
    val progress: Float?
        get() = if (totalBytes > 0 && downloadedBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else null
}

/**
 * The one place a download gets started, cancelled or retried.
 *
 * Both the ROM list (long-press) and the detail screen enqueue through here so
 * that a download begun either way lands in the same queue, with the same
 * dedup rules and the same row on the downloads screen.
 */
@Singleton
class DownloadQueue @Inject constructor(
    private val repo: RomRepository,
    private val credentials: CredentialRepository,
    private val platformDao: PlatformDao,
    private val targets: DownloadTargetRepository,
    private val downloads: DownloadDao,
    private val workManager: WorkManager,
) {

    /**
     * The queue, newest request first.
     *
     * Room holds the request and its last known outcome; WorkManager holds the
     * live progress of anything still in flight.  Neither alone is enough —
     * WorkManager prunes finished work, and Room cannot see byte counts — so
     * the two are merged here rather than in each screen.
     */
    val items: Flow<List<DownloadItem>> = combine(
        downloads.observeAll(),
        workManager.getWorkInfosByTagFlow(TAG_DOWNLOAD),
    ) { rows, infos ->
        // A retried row has both its old finished WorkInfo and the new one under
        // the same tag; the unfinished one is the interesting one.
        val live = infos
            .mapNotNull { info -> info.queueId()?.let { it to info } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, forRow) -> forRow.minBy { if (it.state.isFinished) 1 else 0 } }

        rows.map { row -> row.toItem(live[row.id]) }
    }

    /** Aggregate status per ROM id, so a list row can show what it already has. */
    val statusByRom: Flow<Map<Int, DownloadStatus>> = items.map { list ->
        list.groupBy { it.romId }
            .mapValues { (_, forRom) -> forRom.minBy { STATUS_ORDER.indexOf(it.status) }.status }
    }

    // ── Enqueueing ────────────────────────────────────────────────────────────

    /**
     * Queue every file of [romId], fetching its detail first.
     *
     * The list screen only holds a cached [app.rommdroid.data.db.RomEntity],
     * which has no file list — a multi-disc ROM would silently download only
     * one of its parts if we guessed from the filename.
     */
    suspend fun enqueueRom(romId: Int): EnqueueResult {
        val rom = try {
            repo.getRomDetail(romId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Could not load ROM $romId to queue it", e)
            return EnqueueResult.Failed(e.message ?: "Could not reach the server")
        }
        return enqueue(rom, rom.downloadableFiles())
    }

    /** Queue [files] of an already-loaded [rom]. */
    suspend fun enqueue(rom: DetailedRomSchema, files: List<RomFileSchema>): EnqueueResult {
        val serverUrl = credentials.serverUrl
            ?: return EnqueueResult.Failed("Not connected — set up your server in Settings")
        val platform = platformDao.getById(rom.platformId)
            ?: return EnqueueResult.Failed("Unknown platform — refresh the platform list")
        val target = targets.resolve(platform) ?: return EnqueueResult.NoFolder
        val label = rom.name ?: rom.fsNameNoTags

        val queued = mutableListOf<String>()
        for (file in files) {
            val id = queueId(rom.id, file.id)
            val existing = downloads.getById(id)
            if (existing != null && !existing.status.isFinished) continue

            val url = try {
                // A synthetic single-file ROM (id 0) has no file id to filter on;
                // the API serves the primary file by name in that case.
                val fileIds = if (file.id == 0) emptyList() else listOf(file.id)
                repo.romDownloadUrl(serverUrl, rom.id, file.fileName, fileIds)
            } catch (e: IllegalArgumentException) {
                android.util.Log.e(TAG, "Bad server URL: $serverUrl", e)
                return EnqueueResult.Failed("Invalid server URL — reconnect in Settings")
            }

            val now = System.currentTimeMillis()
            downloads.upsert(
                DownloadEntity(
                    id              = id,
                    romId           = rom.id,
                    fileId          = file.id,
                    fileName        = file.fileName,
                    romName         = label,
                    platformId      = rom.platformId,
                    platformName    = rom.platformDisplayName,
                    sizeBytes       = file.fileSizeBytes,
                    url             = url,
                    treeUri         = target.treeUri,
                    subfolder       = target.subfolder,
                    destinationPath = target.displayPath,
                    status          = DownloadStatus.QUEUED,
                    error           = null,
                    enqueuedAt      = now,
                    updatedAt       = now,
                )
            )
            start(
                id            = id,
                url           = url,
                fileName      = file.fileName,
                romId         = rom.id,
                fileId        = file.id,
                expectedBytes = file.fileSizeBytes,
                treeUri       = target.treeUri,
                subfolder     = target.subfolder,
            )
            queued += id
        }

        return if (queued.isEmpty()) EnqueueResult.AlreadyQueued(label)
        else EnqueueResult.Queued(label, queued)
    }

    /** Re-run a row that failed or was cancelled, without touching the network. */
    suspend fun retry(id: String) {
        val row = downloads.getById(id) ?: return
        if (!row.status.isFinished) return
        downloads.updateStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        start(
            id            = row.id,
            url           = row.url,
            fileName      = row.fileName,
            romId         = row.romId,
            fileId        = row.fileId,
            expectedBytes = row.sizeBytes,
            treeUri       = row.treeUri,
            subfolder     = row.subfolder,
        )
    }

    private fun start(
        id: String,
        url: String,
        fileName: String,
        romId: Int,
        fileId: Int,
        expectedBytes: Long,
        treeUri: String,
        subfolder: String?,
    ) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                DownloadWorker.buildRequest(
                    url            = url,
                    fileName       = fileName,
                    destinationUri = treeUri,
                    romId          = romId,
                    expectedBytes  = expectedBytes,
                    subfolder      = subfolder,
                    queueId        = id,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(TAG_DOWNLOAD)
            .addTag("rom_$romId")
            .addTag("$TAG_FILE_PREFIX$fileId")
            .addTag("$TAG_QUEUE_PREFIX$id")
            .build()

        // KEEP rather than REPLACE: a download already in flight for this exact
        // file should survive a second tap, and a finished one is not "pending"
        // so a retry still gets through.
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
    }

    // ── Cancelling / clearing ─────────────────────────────────────────────────

    /** Stop [ids] and forget them entirely — the undo of a long-press. */
    suspend fun undo(ids: List<String>) {
        ids.forEach { workManager.cancelUniqueWork(workName(it)) }
        downloads.deleteAll(ids)
    }

    /** Stop a download but keep the row, so the user can see it was cancelled. */
    suspend fun cancel(id: String) {
        workManager.cancelUniqueWork(workName(id))
        downloads.updateStatus(id, DownloadStatus.CANCELLED, null, System.currentTimeMillis())
    }

    suspend fun remove(id: String) = undo(listOf(id))

    /** Drop every finished row; anything still running is left alone. */
    suspend fun clearFinished() = downloads.deleteFinished()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun DownloadEntity.toItem(work: WorkInfo?): DownloadItem {
        // The worker writes its own status, but it cannot report its own
        // cancellation or a failure to even start, so live state wins.
        val live = work?.state?.toStatus() ?: status
        val downloaded = work?.progress?.getLong(DownloadWorker.PROGRESS_BYTES, 0L) ?: 0L
        val reportedTotal = work?.progress?.getLong(DownloadWorker.PROGRESS_TOTAL, 0L) ?: 0L
        return DownloadItem(
            id              = id,
            romId           = romId,
            fileId          = fileId,
            fileName        = fileName,
            romName         = romName,
            platformName    = platformName,
            destinationPath = destinationPath,
            status          = live,
            downloadedBytes = if (live == DownloadStatus.SUCCEEDED) sizeBytes else downloaded,
            totalBytes      = if (reportedTotal > 0) reportedTotal else sizeBytes,
            error           = work?.outputData?.getString(DownloadWorker.KEY_ERROR) ?: error,
            enqueuedAt      = enqueuedAt,
        )
    }

    private fun WorkInfo.queueId(): String? =
        tags.firstOrNull { it.startsWith(TAG_QUEUE_PREFIX) }?.removePrefix(TAG_QUEUE_PREFIX)

    companion object {
        private const val TAG = "DownloadQueue"

        /** Carried by every download, so the queue screen can find them all. */
        const val TAG_DOWNLOAD    = "rommdroid_download"
        const val TAG_FILE_PREFIX = "file_"
        const val TAG_QUEUE_PREFIX = "queue_"

        /** Most-active first; decides the badge for a ROM with several files. */
        private val STATUS_ORDER = listOf(
            DownloadStatus.RUNNING,
            DownloadStatus.QUEUED,
            DownloadStatus.FAILED,
            DownloadStatus.SUCCEEDED,
            DownloadStatus.CANCELLED,
        )

        fun queueId(romId: Int, fileId: Int) = "${romId}_$fileId"

        private fun workName(id: String) = "download_$id"

        private fun WorkInfo.State.toStatus(): DownloadStatus = when (this) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
            WorkInfo.State.RUNNING                          -> DownloadStatus.RUNNING
            WorkInfo.State.SUCCEEDED                        -> DownloadStatus.SUCCEEDED
            WorkInfo.State.FAILED                           -> DownloadStatus.FAILED
            WorkInfo.State.CANCELLED                        -> DownloadStatus.CANCELLED
        }
    }
}

/**
 * The files to fetch for this ROM.
 *
 * The API omits the list for simple single-file ROMs, so one is synthesised
 * from the filesystem name — otherwise the most common case of all would have
 * nothing to download.
 */
fun DetailedRomSchema.downloadableFiles(): List<RomFileSchema> =
    files.ifEmpty {
        listOf(
            RomFileSchema(
                id            = 0,
                romId         = id,
                fileName      = fsName,
                fileSizeBytes = fsSizeBytes,
            )
        )
    }
