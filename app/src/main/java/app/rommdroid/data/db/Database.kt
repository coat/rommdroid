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
    indices = [Index("platformId"), Index("name")],
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
)

/** Tracks per-platform folder URIs chosen by the user via SAF. */
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

    @Query("DELETE FROM platforms")
    suspend fun deleteAll()
}

@Dao
interface RomDao {
    @Query("SELECT * FROM roms WHERE platformId = :platformId ORDER BY name ASC")
    fun observeByPlatform(platformId: Int): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE platformId = :platformId ORDER BY name ASC")
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

    @Upsert
    suspend fun upsertAll(roms: List<RomEntity>)

    @Query("DELETE FROM roms WHERE platformId = :platformId")
    suspend fun deleteByPlatform(platformId: Int)

    @Query("SELECT MAX(updatedAt) FROM roms WHERE platformId = :platformId")
    suspend fun latestUpdatedAt(platformId: Int): String?
}

@Dao
interface PlatformFolderDao {
    @Query("SELECT * FROM platform_folders")
    fun observeAll(): Flow<List<PlatformFolderEntity>>

    @Query("SELECT * FROM platform_folders WHERE platformId = :platformId")
    suspend fun getForPlatform(platformId: Int): PlatformFolderEntity?

    @Upsert
    suspend fun upsert(entity: PlatformFolderEntity)

    @Query("DELETE FROM platform_folders WHERE platformId = :platformId")
    suspend fun deleteForPlatform(platformId: Int)
}
