package app.rommdroid.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import app.rommdroid.data.db.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        folderMappingBackup: FolderMappingBackup,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "rommdroid.db")
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(KeepFolderMappings(folderMappingBackup))
            // Reached only by a version with no migration: a downgrade, or a
            // schema change that shipped without one. It drops everything; the
            // callback above is what saves the folder mappings.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun providePlatformDao(db: AppDatabase): PlatformDao = db.platformDao()
    @Provides fun provideRomDao(db: AppDatabase): RomDao = db.romDao()
    @Provides fun provideCollectionDao(db: AppDatabase): CollectionDao = db.collectionDao()
    @Provides fun providePlatformFolderDao(db: AppDatabase): PlatformFolderDao = db.platformFolderDao()
    @Provides fun provideBaseFolderDao(db: AppDatabase): BaseFolderDao = db.baseFolderDao()
    @Provides fun providePlatformSubfolderDao(db: AppDatabase): PlatformSubfolderDao = db.platformSubfolderDao()
    @Provides fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()
}

/**
 * Keeps the folder mappings across a database rebuild: an ordinary open seeds
 * the mirror, the open after a rebuild restores from it.
 *
 * Both wait for [onOpen] rather than acting where they hear the news, because
 * `dropAllTables` calls [onDestructiveMigration] between the DROP and the
 * CREATE, when there is no `base_folder` to read or write. [onOpen] still runs
 * ahead of the first query.
 *
 * [onCreate] counts as a rebuild too: a database file that went missing on its
 * own is indistinguishable from one Room dropped. On a true first run the mirror
 * is empty and the restore is a no-op.
 */
private class KeepFolderMappings(
    private val backup: FolderMappingBackup,
) : RoomDatabase.Callback() {

    private val rebuilt = AtomicBoolean(false)

    override fun onCreate(db: SupportSQLiteDatabase) {
        rebuilt.set(true)
    }

    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        rebuilt.set(true)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        if (rebuilt.getAndSet(false)) backup.restoreInto(db) else backup.seedFrom(db)
    }
}
