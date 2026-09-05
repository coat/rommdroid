package app.rommdroid.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import app.rommdroid.data.db.*
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Adds the download queue table.
     *
     * Written out rather than left to the destructive fallback because the
     * folder mappings live in this database, and losing them would cost the
     * user their SAF picks — the one thing in here that is not re-syncable.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `downloads` (
                    `id` TEXT NOT NULL,
                    `romId` INTEGER NOT NULL,
                    `fileId` INTEGER NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `romName` TEXT NOT NULL,
                    `platformId` INTEGER NOT NULL,
                    `platformName` TEXT NOT NULL,
                    `sizeBytes` INTEGER NOT NULL,
                    `url` TEXT NOT NULL,
                    `treeUri` TEXT NOT NULL,
                    `subfolder` TEXT,
                    `destinationPath` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `error` TEXT,
                    `enqueuedAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_enqueuedAt` ON `downloads` (`enqueuedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_romId` ON `downloads` (`romId`)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "rommdroid.db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()   // dev builds only; add real migrations pre-release
            .build()

    @Provides fun providePlatformDao(db: AppDatabase): PlatformDao = db.platformDao()
    @Provides fun provideRomDao(db: AppDatabase): RomDao = db.romDao()
    @Provides fun providePlatformFolderDao(db: AppDatabase): PlatformFolderDao = db.platformFolderDao()
    @Provides fun provideBaseFolderDao(db: AppDatabase): BaseFolderDao = db.baseFolderDao()
    @Provides fun providePlatformSubfolderDao(db: AppDatabase): PlatformSubfolderDao = db.platformSubfolderDao()
    @Provides fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()
}
