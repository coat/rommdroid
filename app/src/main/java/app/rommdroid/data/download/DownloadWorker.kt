package app.rommdroid.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import okhttp3.Request
import app.rommdroid.R
import app.rommdroid.data.repository.CredentialRepository
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a single ROM file to the SAF folder configured for its platform.
 *
 * Progress is reported as WorkManager [Progress] and as an ongoing notification.
 * The worker is resumable by WorkManager (enqueue with [ExistingWorkPolicy.KEEP]).
 *
 * Input data keys: [KEY_URL], [KEY_FILE_NAME], [KEY_DESTINATION_URI],
 *                  [KEY_ROM_ID], [KEY_EXPECTED_BYTES]
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val credentials: CredentialRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL             = "url"
        const val KEY_FILE_NAME       = "file_name"
        const val KEY_DESTINATION_URI = "destination_uri"  // SAF tree URI (content://...)
        const val KEY_ROM_ID          = "rom_id"
        const val KEY_EXPECTED_BYTES  = "expected_bytes"

        const val PROGRESS_BYTES      = "bytes_downloaded"
        const val PROGRESS_TOTAL      = "total_bytes"

        const val NOTIFICATION_CHANNEL = "downloads"

        fun buildRequest(
            url: String,
            fileName: String,
            destinationUri: String,
            romId: Int,
            expectedBytes: Long,
        ) = Data.Builder()
            .putString(KEY_URL, url)
            .putString(KEY_FILE_NAME, fileName)
            .putString(KEY_DESTINATION_URI, destinationUri)
            .putInt(KEY_ROM_ID, romId)
            .putLong(KEY_EXPECTED_BYTES, expectedBytes)
            .build()
    }

    // OkHttpClient without read timeout for streaming large files
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val url            = inputData.getString(KEY_URL)           ?: return Result.failure()
        val fileName       = inputData.getString(KEY_FILE_NAME)     ?: return Result.failure()
        val destUriString  = inputData.getString(KEY_DESTINATION_URI) ?: return Result.failure()
        val expectedBytes  = inputData.getLong(KEY_EXPECTED_BYTES, -1L)

        createNotificationChannel()
        setForeground(buildForegroundInfo(fileName, 0, expectedBytes))

        return try {
            download(url, fileName, destUriString, expectedBytes)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun download(
        url: String,
        fileName: String,
        destUriString: String,
        expectedBytes: Long,
    ) {
        val token = credentials.apiToken
        val request = Request.Builder()
            .url(url)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val body = response.body ?: throw IOException("Empty response body")
            val total = if (expectedBytes > 0) expectedBytes else body.contentLength()

            // Resolve the destination SAF folder and create/overwrite the file
            val treeUri = destUriString.toUri()
            val dir = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: throw IOException("Cannot open destination folder")

            // Delete existing file if present (resumable would be nicer, but
            // SAF doesn't support partial writes; simplicity wins here)
            dir.findFile(fileName)?.delete()
            val destFile = dir.createFile("application/octet-stream", fileName)
                ?: throw IOException("Cannot create $fileName in destination")

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
                            setForeground(buildForegroundInfo(fileName, downloaded, total))
                        }
                    }
                }
            } ?: throw IOException("Cannot open output stream")
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
        return ForegroundInfo(id.hashCode(), notification)
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
