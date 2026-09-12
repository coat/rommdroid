package app.rommdroid.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PlatformEntity::class,
        RomEntity::class,
        CollectionEntity::class,
        CollectionRomEntity::class,
        PlatformFolderEntity::class,
        BaseFolderEntity::class,
        PlatformSubfolderEntity::class,
        DownloadEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun platformDao(): PlatformDao
    abstract fun romDao(): RomDao
    abstract fun collectionDao(): CollectionDao
    abstract fun platformFolderDao(): PlatformFolderDao
    abstract fun baseFolderDao(): BaseFolderDao
    abstract fun platformSubfolderDao(): PlatformSubfolderDao
    abstract fun downloadDao(): DownloadDao
}
