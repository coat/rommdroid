package app.rommdroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
