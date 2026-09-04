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
    ],
    version = 2,
    exportSchema = false,   // set to true + configure schemaLocation before release
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun platformFolderDao(): PlatformFolderDao
    abstract fun baseFolderDao(): BaseFolderDao
    abstract fun platformSubfolderDao(): PlatformSubfolderDao
}
