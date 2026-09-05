package app.rommdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        PlatformFolderEntity::class,
        BaseFolderEntity::class,
        PlatformSubfolderEntity::class,
        DownloadEntity::class,
    ],
    version = 4,
    exportSchema = false,   // set to true + configure schemaLocation before release
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun platformFolderDao(): PlatformFolderDao
    abstract fun baseFolderDao(): BaseFolderDao
    abstract fun platformSubfolderDao(): PlatformSubfolderDao
    abstract fun downloadDao(): DownloadDao
}
