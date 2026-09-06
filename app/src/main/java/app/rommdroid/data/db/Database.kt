package app.rommdroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val slug: String,
    val fsSlug: String,
    val displayName: String,
    val romCount: Int,
    val urlLogo: String?,
    val updatedAt: String?,
)

@Entity(
    tableName = "roms",
    indices = [Index("platformId"), Index("name"), Index("groupKey")],
)
data class RomEntity(
    @PrimaryKey val id: Int,
    val platformId: Int,
    val platformSlug: String,
    val platformDisplayName: String,
    val fsName: String,
    val fsNameNoTags: String,
    val fsExtension: String,
    val fsSizeBytes: Long,
    val name: String?,
    val slug: String?,
    val summary: String?,
    val regions: String,        // JSON-encoded list
    val languages: String,      // JSON-encoded list
    val tags: String,           // JSON-encoded list
    val urlCover: String?,
    val pathCoverSmall: String?,
    val pathCoverLarge: String?,
    val updatedAt: String?,
    /**
     * The server's aggregate score out of 100, or null when no metadata
     * provider scored the game — see
     * [app.rommdroid.data.api.model.RomMetadataSchema.averageRating].
     */
    val averageRating: Double? = null,
    /**
     * Identity shared by every regional copy of this game — see
     * [app.rommdroid.util.romGroupKey].  Stored rather than computed on read so
     * siblings can be looked up with an indexed query instead of scanning the
     * whole platform.
     */
    val groupKey: String = "",
)

/**
 * The single base "ROMs" folder. Platform subfolders are created underneath it,
 * so one SAF grant covers every platform instead of one grant per platform.
 */
@Entity(tableName = "base_folder")
data class BaseFolderEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Persisted SAF tree URI (content://...) */
    val folderUri: String,
    /** Human-readable display path, shown in settings. */
    val displayPath: String,
) {
    companion object { const val SINGLETON_ID = 0 }
}

/**
 * Per-platform override of the subfolder name under the base folder, for users
 * whose library does not follow the ES-DE naming convention.
 */
@Entity(tableName = "platform_subfolders")
data class PlatformSubfolderEntity(
    @PrimaryKey val platformId: Int,
    val name: String,
)

/**
 * Per-platform override pointing at a completely different directory, for
 * platforms that live outside the base folder entirely. Takes precedence over
 * the base folder.
 */
@Entity(tableName = "platform_folders")
data class PlatformFolderEntity(
    @PrimaryKey val platformId: Int,
    /** Persisted SAF tree URI (content://...) */
    val folderUri: String,
    /** Human-readable display path, shown in settings. */
    val displayPath: String,
)

// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface PlatformDao {
    @Query("SELECT * FROM platforms ORDER BY displayName ASC")
    fun observeAll(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM platforms ORDER BY displayName ASC")
    suspend fun getAll(): List<PlatformEntity>

    @Query("SELECT * FROM platforms WHERE id = :id")
    suspend fun getById(id: Int): PlatformEntity?

    @Upsert
    suspend fun upsertAll(platforms: List<PlatformEntity>)

    @Query("DELETE FROM platforms WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Int>)

    /**
     * ROM rows whose platform is no longer cached.
     *
     * Lives here rather than on [RomDao] because it is the other half of
     * [reconcile] — a platform row leaving takes its ROMs with it, and the two
     * deletes have to land in the same transaction to avoid a crash in between
     * leaving hundreds of unreachable rows behind.
     */
    @Query("DELETE FROM roms WHERE platformId NOT IN (SELECT id FROM platforms)")
    suspend fun deleteRomsWithoutPlatform()

    /**
     * Makes the cache match a full listing from the server: [platforms] is
     * everything that exists, so anything else is gone and goes too, along with
     * the ROMs that belonged to it.
     *
     * Folder mappings are deliberately left alone — see
     * [app.rommdroid.data.repository.RomRepository.syncPlatforms].
     */
    @Transaction
    suspend fun reconcile(platforms: List<PlatformEntity>) {
        upsertAll(platforms)
        deleteMissing(platforms.map { it.id })
        deleteRomsWithoutPlatform()
    }

    @Query("DELETE FROM platforms")
    suspend fun deleteAll()
}

private const val ROMS_BY_PLATFORM =
    "SELECT * FROM roms WHERE platformId = :platformId " +
        "ORDER BY COALESCE(NULLIF(name, ''), fsNameNoTags) COLLATE NOCASE ASC"

@Dao
interface RomDao {
    /**
     * Sorted on the name the list actually draws, which is the filename for
     * every ROM the server never identified — ordering on `name` alone piles
     * those at the top of the list under a NULL that sorts before everything.
     * NOCASE because SQLite's default TEXT ordering is by byte, which files a
     * lowercase title after every uppercase one.
     */
    @Query(ROMS_BY_PLATFORM)
    fun observeByPlatform(platformId: Int): Flow<List<RomEntity>>

    @Query(ROMS_BY_PLATFORM)
    suspend fun getByPlatform(platformId: Int): List<RomEntity>

    @Query("""
        SELECT * FROM roms
        WHERE name LIKE '%' || :query || '%'
           OR fsNameNoTags LIKE '%' || :query || '%'
        ORDER BY name ASC
        LIMIT 100
    """)
    suspend fun search(query: String): List<RomEntity>

    @Query("SELECT * FROM roms WHERE id = :id")
    suspend fun getById(id: Int): RomEntity?

    /**
     * Whichever of [ids] the cache happens to hold.
     *
     * The server's `sibling_roms` carries only names and ids, so the detail
     * screen fills in filenames, sizes and regions from here when the sibling's
     * platform has been synced.
     */
    @Query("SELECT * FROM roms WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<RomEntity>

    /** Every cached copy of one game, including the ROM the key came from. */
    @Query("SELECT * FROM roms WHERE groupKey = :groupKey ORDER BY fsName ASC")
    suspend fun getByGroupKey(groupKey: String): List<RomEntity>

    @Upsert
    suspend fun upsertAll(roms: List<RomEntity>)

    @Query("DELETE FROM roms WHERE platformId = :platformId")
    suspend fun deleteByPlatform(platformId: Int)

    /**
     * Swaps in a freshly fetched listing for one platform.
     *
     * [roms] is everything the server has for [platformId], so the old rows go
     * and the new ones land in the same transaction — a reader never sees the
     * platform empty, and a crash mid-swap leaves the previous listing intact.
     */
    @Transaction
    suspend fun replacePlatform(platformId: Int, roms: List<RomEntity>) {
        deleteByPlatform(platformId)
        upsertAll(roms)
    }

    @Query("DELETE FROM roms")
    suspend fun deleteAll()

    @Query("SELECT MAX(updatedAt) FROM roms WHERE platformId = :platformId")
    suspend fun latestUpdatedAt(platformId: Int): String?
}

@Dao
interface BaseFolderDao {
    @Query("SELECT * FROM base_folder WHERE id = 0")
    fun observe(): Flow<BaseFolderEntity?>

    @Query("SELECT * FROM base_folder WHERE id = 0")
    suspend fun get(): BaseFolderEntity?

    @Upsert
    suspend fun upsert(entity: BaseFolderEntity)

    @Query("DELETE FROM base_folder")
    suspend fun clear()
}

@Dao
interface PlatformSubfolderDao {
    @Query("SELECT * FROM platform_subfolders")
    fun observeAll(): Flow<List<PlatformSubfolderEntity>>

    @Query("SELECT * FROM platform_subfolders")
    suspend fun getAll(): List<PlatformSubfolderEntity>

    @Query("SELECT * FROM platform_subfolders WHERE platformId = :platformId")
    suspend fun getForPlatform(platformId: Int): PlatformSubfolderEntity?

    @Upsert
    suspend fun upsert(entity: PlatformSubfolderEntity)

    @Query("DELETE FROM platform_subfolders WHERE platformId = :platformId")
    suspend fun deleteForPlatform(platformId: Int)
}

@Dao
interface PlatformFolderDao {
    @Query("SELECT * FROM platform_folders")
    fun observeAll(): Flow<List<PlatformFolderEntity>>

    @Query("SELECT * FROM platform_folders")
    suspend fun getAll(): List<PlatformFolderEntity>

    @Query("SELECT * FROM platform_folders WHERE platformId = :platformId")
    suspend fun getForPlatform(platformId: Int): PlatformFolderEntity?

    @Upsert
    suspend fun upsert(entity: PlatformFolderEntity)

    @Query("DELETE FROM platform_folders WHERE platformId = :platformId")
    suspend fun deleteForPlatform(platformId: Int)
}

// ── Download queue ────────────────────────────────────────────────────────────

/**
 * Where a queued download has got to.
 *
 * Mirrors the WorkManager states we care about, but persists past them:
 * WorkManager prunes finished work after a while, and the queue screen should
 * still be able to say what happened.
 */
enum class DownloadStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isFinished: Boolean get() = this != QUEUED && this != RUNNING
}

/**
 * One file the user asked for, with everything needed to retry it offline.
 *
 * The URL and destination are stored rather than re-derived because a retry
 * from the downloads screen should not depend on the ROM detail endpoint being
 * reachable, nor on the platform still resolving to the same folder.
 */
@Entity(tableName = "downloads", indices = [Index("enqueuedAt"), Index("romId")])
data class DownloadEntity(
    /** "<romId>_<fileId>" — one row per file, so re-downloading reuses the row. */
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
