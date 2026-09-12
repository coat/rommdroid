package app.rommdroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
