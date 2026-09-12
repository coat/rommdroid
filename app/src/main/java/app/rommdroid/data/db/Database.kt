package app.rommdroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Entities

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
    /** Aggregate score out of 100; null when no provider scored the game. */
    val averageRating: Double? = null,
    /** First release, as the metadata provider stamps it; null when the game
     *  was never identified. Only ever compared, so the unit does not matter. */
    val firstReleaseDate: Long? = null,
    /** ISO-8601 stamp of when the server first saw the file. Kept as text: the
     *  server formats every stamp the same way, so text order is time order. */
    val createdAt: String? = null,
    /** [app.rommdroid.domain.romGroupKey]. Stored rather than computed on read so
     *  siblings resolve to an indexed query. */
    val groupKey: String = "",
)

/** A user-made collection. Its ROMs live in [CollectionRomEntity]. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val description: String,
    /** What the server says it holds, so the list can say "23 games" before the
     *  membership rows have ever been fetched. */
    val romCount: Int,
    val pathCoverSmall: String?,
    val pathCoverLarge: String?,
    val urlCover: String?,
    val isFavorite: Boolean,
    val isPublic: Boolean,
    val ownerUsername: String,
    val updatedAt: String?,
)

/**
 * Which ROMs are in which collection. A join table so the list screen can
 * observe an indexed query. Rows outlive the ROMs they name, since a platform
 * sync deletes and rebuilds them, so every read joins against `roms`.
 */
@Entity(
    tableName = "collection_roms",
    primaryKeys = ["collectionId", "romId"],
    indices = [Index("romId")],
)
data class CollectionRomEntity(
    val collectionId: Int,
    val romId: Int,
)

/** The single base "ROMs" folder: one SAF grant covering every platform. */
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

/** Override for the subfolder name, for libraries not following ES-DE naming. */
@Entity(tableName = "platform_subfolders")
data class PlatformSubfolderEntity(
    @PrimaryKey val platformId: Int,
    val name: String,
)

/** Override for a platform living outside the base folder; takes precedence. */
@Entity(tableName = "platform_folders")
data class PlatformFolderEntity(
    @PrimaryKey val platformId: Int,
    /** Persisted SAF tree URI (content://...) */
    val folderUri: String,
    /** Human-readable display path, shown in settings. */
    val displayPath: String,
)

// DAOs

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

    /** On [RomDao]'s table, but here because it has to share [reconcile]'s
     *  transaction: a crash between the two deletes strands the ROM rows. */
    @Query("DELETE FROM roms WHERE platformId NOT IN (SELECT id FROM platforms)")
    suspend fun deleteRomsWithoutPlatform()

    /** Same transaction, same reason as [deleteRomsWithoutPlatform]. */
    @Query("DELETE FROM collection_roms WHERE romId NOT IN (SELECT id FROM roms)")
    suspend fun deleteMembershipsWithoutRom()

    /**
     * Match a full server listing: anything not in [platforms] is gone, and its
     * ROMs go with it. Folder mappings are deliberately left alone, see
     * [app.rommdroid.data.repository.RomRepository.syncPlatforms].
     */
    @Transaction
    suspend fun reconcile(platforms: List<PlatformEntity>) {
        upsertAll(platforms)
        deleteMissing(platforms.map { it.id })
        deleteRomsWithoutPlatform()
        deleteMembershipsWithoutRom()
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
     * Sorted on the name the list draws: ordering on `name` alone piles every
     * unidentified ROM at the top under a NULL. NOCASE because SQLite's default
     * byte ordering files lowercase titles after all uppercase ones.
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

    /** Whichever of [ids] the cache holds. The server's `sibling_roms` carries
     *  only names and ids, so the detail screen fills the rest in from here. */
    @Query("SELECT * FROM roms WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<RomEntity>

    /** Every cached copy of one game, including the ROM the key came from. */
    @Query("SELECT * FROM roms WHERE groupKey = :groupKey ORDER BY fsName ASC")
    suspend fun getByGroupKey(groupKey: String): List<RomEntity>

    @Upsert
    suspend fun upsertAll(roms: List<RomEntity>)

    @Query("DELETE FROM roms WHERE platformId = :platformId")
    suspend fun deleteByPlatform(platformId: Int)

    /** Swap in a fresh listing. One transaction, so a reader never catches the
     *  platform empty and a crash mid-swap leaves the old listing intact. */
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
interface CollectionDao {
    /** Favourites first, then alphabetical, matching RomM's own UI. */
    @Query("SELECT * FROM collections ORDER BY isFavorite DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CollectionEntity>>

    /** Drives whether the platform list pins a Collections row at all. */
    @Query("SELECT COUNT(*) FROM collections")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Int): CollectionEntity?

    /**
     * Ordered exactly as [ROMS_BY_PLATFORM]: both feed the same letter
     * sectioning and have to agree on where the letters fall. Inner join, so a
     * membership row whose ROM was dropped falls out instead of drawing blank.
     */
    @Query("""
        SELECT roms.* FROM roms
        INNER JOIN collection_roms ON collection_roms.romId = roms.id
        WHERE collection_roms.collectionId = :collectionId
        ORDER BY COALESCE(NULLIF(roms.name, ''), roms.fsNameNoTags) COLLATE NOCASE ASC
    """)
    fun observeRoms(collectionId: Int): Flow<List<RomEntity>>

    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    @Query("DELETE FROM collections WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Int>)

    @Query("DELETE FROM collection_roms WHERE collectionId NOT IN (SELECT id FROM collections)")
    suspend fun deleteMembershipsWithoutCollection()

    /** Match a full server listing, taking the orphaned membership rows along. */
    @Transaction
    suspend fun reconcile(collections: List<CollectionEntity>) {
        upsertAll(collections)
        deleteMissing(collections.map { it.id })
        deleteMembershipsWithoutCollection()
    }

    @Query("DELETE FROM collection_roms WHERE collectionId = :collectionId")
    suspend fun deleteMembership(collectionId: Int)

    @Upsert
    suspend fun upsertMembership(rows: List<CollectionRomEntity>)

    /** Same shape and reasoning as [RomDao.replacePlatform]. */
    @Transaction
    suspend fun replaceMembership(collectionId: Int, rows: List<CollectionRomEntity>) {
        deleteMembership(collectionId)
        upsertMembership(rows)
    }

    @Query("DELETE FROM collections")
    suspend fun deleteAllCollections()

    @Query("DELETE FROM collection_roms")
    suspend fun deleteAllMemberships()

    @Transaction
    suspend fun deleteAll() {
        deleteAllMemberships()
        deleteAllCollections()
    }
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

// Download queue

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
