package app.rommdroid.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import app.rommdroid.data.db.DownloadDao
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.repository.CredentialRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a single ROM file to the SAF folder configured for its platform.
 *
 * Progress is reported as WorkManager [Progress] and as an ongoing notification.
 * The worker is resumable by WorkManager (enqueue with [ExistingWorkPolicy.KEEP]).
 *
 * Input data keys: [KEY_URL], [KEY_FILE_NAME], [KEY_DESTINATION_URI],
 *                  [KEY_ROM_ID], [KEY_EXPECTED_BYTES], [KEY_QUEUE_ID]
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val credentials: CredentialRepository,
    private val downloads: DownloadDao,
    private val localRoms: LocalRomIndex,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_URL             = "url"
        const val KEY_FILE_NAME       = "file_name"
        const val KEY_DESTINATION_URI = "destination_uri"  // SAF tree URI (content://...)
        const val KEY_SUBFOLDER       = "subfolder"        // optional dir under the tree
        const val KEY_ROM_ID          = "rom_id"
        const val KEY_EXPECTED_BYTES  = "expected_bytes"
        const val KEY_QUEUE_ID        = "queue_id"        // row in the downloads table

        const val PROGRESS_BYTES      = "bytes_downloaded"
        const val PROGRESS_TOTAL      = "total_bytes"

        /** Present in the worker's output data when it ends in [Result.failure]. */
        const val KEY_ERROR           = "error"

        const val NOTIFICATION_CHANNEL = "downloads"

        fun buildRequest(
            url: String,
            fileName: String,
            destinationUri: String,
            romId: Int,
            expectedBytes: Long,
            subfolder: String? = null,
            queueId: String? = null,
        ) = Data.Builder()
            .putString(KEY_URL, url)
            .putString(KEY_FILE_NAME, fileName)
            .putString(KEY_DESTINATION_URI, destinationUri)
            .putString(KEY_SUBFOLDER, subfolder)
            .putInt(KEY_ROM_ID, romId)
            .putLong(KEY_EXPECTED_BYTES, expectedBytes)
            .putString(KEY_QUEUE_ID, queueId)
            .build()
    }

    // OkHttpClient without read timeout for streaming large files
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    /** The downloads-table row this work belongs to, when it was queued through it. */
    private val queueId: String? get() = inputData.getString(KEY_QUEUE_ID)

    override suspend fun doWork(): Result {
        val url            = inputData.getString(KEY_URL)             ?: return fail("Missing download URL")
        val fileName       = inputData.getString(KEY_FILE_NAME)       ?: return fail("Missing file name")
        val destUriString  = inputData.getString(KEY_DESTINATION_URI) ?: return fail("Missing destination folder")
        val expectedBytes  = inputData.getLong(KEY_EXPECTED_BYTES, -1L)
        val subfolder      = inputData.getString(KEY_SUBFOLDER)

        Log.i(TAG, "Starting download of $fileName from $url")

        createNotificationChannel()
        notifyProgress(fileName, 0, expectedBytes)
        setStatus(DownloadStatus.RUNNING, null)

        return try {
            // Copying a multi-gigabyte stream is blocking work; on the worker's
            // default dispatcher it would occupy a CPU thread the ROM list needs
            // for folding its variants, stalling the list while a download runs.
            withContext(Dispatchers.IO) {
                download(url, fileName, destUriString, subfolder, expectedBytes)
            }
            Log.i(TAG, "Finished download of $fileName")
            setStatus(DownloadStatus.SUCCEEDED, null)
            // The folder now holds a file it did not before, so any cached
            // listing showing this ROM as missing is stale.
            localRoms.invalidate()
            Result.success()
        } catch (e: CancellationException) {
            // The user cancelled from the queue screen; the row is already
            // marked there, and this scope is dead, so just get out.
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Download of $fileName failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < 3) {
                setStatus(DownloadStatus.QUEUED, "Retrying — ${e.message}")
                Result.retry()
            } else {
                fail(e.message ?: "Download failed")
            }
        } catch (e: Exception) {
            // SecurityException (revoked SAF grant), IllegalStateException
            // (foreground service refused), IllegalArgumentException (bad URL)…
            // Without this the worker died with no trace and no user feedback.
            Log.e(TAG, "Download of $fileName failed unrecoverably", e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun fail(message: String): Result {
        Log.e(TAG, "Download failed: $message")
        setStatus(DownloadStatus.FAILED, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    /**
     * Record progress in the queue table.
     *
     * Uncancellable on purpose: the interesting write is the one that happens
     * as the job ends, and a cancelled scope would otherwise drop it and leave
     * the row stuck on "Downloading" forever.
     */
    private suspend fun setStatus(status: DownloadStatus, error: String?) {
        val id = queueId ?: return
        try {
            withContext(NonCancellable) {
                downloads.updateStatus(id, status, error, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not record $status for $id: ${e.message}")
        }
    }

    private suspend fun download(
        url: String,
        fileName: String,
        destUriString: String,
        subfolder: String?,
        expectedBytes: Long,
    ) {
        // Prefer the client API token; fall back to Basic auth, which is all
        // that is stored when token creation was refused during setup. Sending
        // no credentials at all just yields a 403 from RomM.
        val authHeader = credentials.apiToken?.let { "Bearer $it" }
            ?: credentials.basicAuthHeader
        if (authHeader == null) Log.w(TAG, "No credentials stored — request will be unauthenticated")

        val request = Request.Builder()
            .url(url)
            .apply { if (authHeader != null) header("Authorization", authHeader) }
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${response.message}")

            val body = response.body ?: throw IOException("Empty response body")
            val total = if (expectedBytes > 0) expectedBytes else body.contentLength()

            // Resolve the destination SAF folder and create/overwrite the file
            val treeUri = destUriString.toUri()
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: throw IOException("Cannot open destination folder")
            if (!root.canWrite()) {
                throw IOException("No write permission for destination folder — re-select it in Settings → Folder Mapping")
            }

            // A subfolder means the grant is on the base ROMs directory, so the
            // per-platform directory is ours to create on first download.
            val dir = if (subfolder.isNullOrBlank()) root else resolveSubfolder(root, subfolder)

            // Delete existing file if present (resumable would be nicer, but
            // SAF doesn't support partial writes; simplicity wins here)
            dir.findFile(fileName)?.delete()
            val destFile = dir.createFile("application/octet-stream", fileName)
                ?: throw IOException("Cannot create $fileName in destination")

            // A half-written ROM left in the library folder is worse than no
            // ROM at all — the emulator finds it and fails obscurely — so an
            // interrupted transfer takes its file with it.
            try {
                applicationContext.contentResolver.openOutputStream(destFile.uri)?.use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastNotified = 0L

                    body.byteStream().use { stream ->
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            // Update progress ~every 500 KB to avoid hammering WorkManager
                            if (downloaded - lastNotified > 512 * 1024) {
                                lastNotified = downloaded
                                setProgress(workDataOf(PROGRESS_BYTES to downloaded, PROGRESS_TOTAL to total))
                                notifyProgress(fileName, downloaded, total)
                            }
                        }
                    }
                    out.flush()
                    setProgress(workDataOf(PROGRESS_BYTES to downloaded, PROGRESS_TOTAL to total))
                } ?: throw IOException("Cannot open output stream")
            } catch (e: Throwable) {
                runCatching { destFile.delete() }
                    .onFailure { Log.w(TAG, "Could not remove partial $fileName", it) }
                throw e
            }
        }
    }

    /**
     * Finds or creates [name] directly under [parent].
     *
     * An existing entry that is a file rather than a directory would otherwise
     * make createDirectory() silently produce a second entry with the same name,
     * so that case is reported instead.
     */
    private fun resolveSubfolder(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            if (!existing.isDirectory) throw IOException("\"$name\" exists but is not a folder")
            return existing
        }
        Log.i(TAG, "Creating subfolder $name")
        return parent.createDirectory(name)
            ?: throw IOException("Cannot create folder \"$name\" in the ROMs directory")
    }

    /**
     * Promote to a foreground service so the OS doesn't kill a long download.
     *
     * This is best-effort: the platform refuses to start a foreground service
     * while the app is backgrounded on Android 12+, and the notification is
     * silently dropped when POST_NOTIFICATIONS is denied on Android 13+. Neither
     * is a reason to abandon the transfer, so failures here are logged, not thrown.
     */
    private suspend fun notifyProgress(fileName: String, downloaded: Long, total: Long) {
        try {
            setForeground(buildForegroundInfo(fileName, downloaded, total))
        } catch (e: Exception) {
            Log.w(TAG, "Could not run download as a foreground service: ${e.message}")
        }
    }

    private fun buildForegroundInfo(
        fileName: String,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else -1
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(fileName)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Android 10+ requires the service type to be passed through, and on
        // Android 14+ omitting it throws MissingForegroundServiceTypeException.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
            (applicationContext.getSystemService(NotificationManager::class.java))
                .createNotificationChannel(channel)
        }
    }
}
