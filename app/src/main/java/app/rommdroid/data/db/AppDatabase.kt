package app.rommdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        PlatformFolderEntity::class,
    ],
    version = 1,
    exportSchema = false,   // set to true + configure schemaLocation before release
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun platformFolderDao(): PlatformFolderDao
}
