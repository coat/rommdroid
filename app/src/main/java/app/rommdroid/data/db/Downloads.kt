package app.rommdroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Mirrors the WorkManager states, but persists past them: WorkManager prunes
 *  finished work and the queue screen still has to say what happened. */
enum class DownloadStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isFinished: Boolean get() = this != QUEUED && this != RUNNING

    companion object {
        /** Most active first. Decides the one state a ROM with several files
         *  or copies reports: an in-flight transfer outranks a finished one. */
        val BY_ACTIVITY: List<DownloadStatus> = listOf(RUNNING, QUEUED, FAILED, SUCCEEDED, CANCELLED)

        /** The most active of [statuses], or null when there are none. */
        fun mostActive(statuses: Iterable<DownloadStatus>): DownloadStatus? =
            statuses.minByOrNull { BY_ACTIVITY.indexOf(it) }
    }
}

/** One requested file. URL and destination are stored rather than re-derived so
 *  a retry needs neither the detail endpoint nor the same folder mapping. */
@Entity(tableName = "downloads", indices = [Index("enqueuedAt"), Index("romId")])
data class DownloadEntity(
    /** "<romId>_<fileId>" - one row per file, so re-downloading reuses the row. */
    @PrimaryKey val id: String,
    val romId: Int,
    val fileId: Int,
    val fileName: String,
    /** Display name of the game, for the queue screen. */
    val romName: String,
    val platformId: Int,
    val platformName: String,
    val sizeBytes: Long,
    val url: String,
    /** SAF tree the app holds a grant on. */
    val treeUri: String,
    /** Directory under [treeUri], created on demand; null for an override folder. */
    val subfolder: String?,
    val destinationPath: String,
    val status: DownloadStatus,
    val error: String? = null,
    val enqueuedAt: Long,
    val updatedAt: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY enqueuedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: DownloadStatus, error: String?, now: Long)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("DELETE FROM downloads WHERE status NOT IN ('QUEUED', 'RUNNING')")
    suspend fun deleteFinished()
}
