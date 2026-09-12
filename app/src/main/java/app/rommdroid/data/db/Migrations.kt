package app.rommdroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/*
 * A way forward from every version of this database that has shipped. The folder
 * mappings live in here and are the one thing the app cannot re-fetch, so each
 * schema change gets a real migration; the destructive fallback in
 * [app.rommdroid.di.DatabaseModule] is the net, not the plan.
 *
 * The DDL is copied verbatim from `AppDatabase_Impl.createAllTables`, since Room
 * rejects any end state that does not match it.
 */

/** Adds the base folder and the per-platform subfolder names. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `base_folder` (" +
                "`id` INTEGER NOT NULL, `folderUri` TEXT NOT NULL, " +
                "`displayPath` TEXT NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `platform_subfolders` (" +
                "`platformId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "PRIMARY KEY(`platformId`))"
        )
    }
}

/**
 * Adds the key that folds regional copies into one row.
 *
 * Backfilled rather than left at the default: an empty key matches every other
 * empty key, so every cached ROM would be a variant of every other. The backfill
 * reaches only the lower two tiers of [app.rommdroid.util.romGroupKey], since
 * the metadata id is not a column, so grouping shifts slightly on the next sync.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `groupKey` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE `roms` SET `groupKey` = `platformId` || '|' || CASE
                WHEN `slug` IS NOT NULL AND trim(`slug`) <> ''
                    THEN 'slug:' || lower(trim(`slug`))
                ELSE 'name:' || lower(trim(`fsNameNoTags`))
            END
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_roms_groupKey` ON `roms` (`groupKey`)")
    }
}

/** Adds the download queue table. */
val MIGRATION_3_4 = object : Migration(3, 4) {
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

/** Adds the aggregate rating. Left null rather than backfilled: the value only
 *  exists on the server, and opening a platform full-syncs it anyway. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `averageRating` REAL")
    }
}

/** Adds the collections cache and its membership. Nothing to backfill; both
 *  tables fill themselves on the next sync. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collections` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `romCount` INTEGER NOT NULL,
                `pathCoverSmall` TEXT,
                `pathCoverLarge` TEXT,
                `urlCover` TEXT,
                `isFavorite` INTEGER NOT NULL,
                `isPublic` INTEGER NOT NULL,
                `ownerUsername` TEXT NOT NULL,
                `updatedAt` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collection_roms` (
                `collectionId` INTEGER NOT NULL,
                `romId` INTEGER NOT NULL,
                PRIMARY KEY(`collectionId`, `romId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_roms_romId` ON `collection_roms` (`romId`)"
        )
    }
}

/** Adds the two dates the ROM list can sort on. Left null for the same reason
 *  as the rating: opening a platform full-syncs it. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `firstReleaseDate` INTEGER")
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `createdAt` TEXT")
    }
}

/** Every migration, in the order the versions shipped. */
val ALL_MIGRATIONS = arrayOf(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
)
