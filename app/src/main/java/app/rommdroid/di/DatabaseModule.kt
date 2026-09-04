package app.rommdroid.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "rommdroid.db")
            .fallbackToDestructiveMigration()   // dev builds only; add real migrations pre-release
            .build()

    @Provides fun providePlatformDao(db: AppDatabase): PlatformDao = db.platformDao()
    @Provides fun provideRomDao(db: AppDatabase): RomDao = db.romDao()
    @Provides fun providePlatformFolderDao(db: AppDatabase): PlatformFolderDao = db.platformFolderDao()
    @Provides fun provideBaseFolderDao(db: AppDatabase): BaseFolderDao = db.baseFolderDao()
    @Provides fun providePlatformSubfolderDao(db: AppDatabase): PlatformSubfolderDao = db.platformSubfolderDao()
}
