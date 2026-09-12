package app.rommdroid.data.db

import androidx.room.*

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
