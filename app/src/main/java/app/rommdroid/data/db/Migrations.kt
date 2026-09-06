package app.rommdroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A way forward from every version of this database that has ever been on a
 * phone.
 *
 * The folder mappings live in here and they are the one thing the app cannot
 * re-fetch: dropping them costs the user their SAF grants and a walk back
 * through the document picker for the base folder and every platform they had
 * pointed elsewhere.  So each schema change gets a real migration rather than
 * leaning on the destructive fallback in
 * [app.rommdroid.di.DatabaseModule] — that fallback is the net, not the plan.
 *
 * A migration's end state has to match what `AppDatabase_Impl.createAllTables`
 * would have produced, or Room rejects it at the next open; the DDL here is
 * copied from there verbatim for that reason.
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
 * Adds the key that folds regional copies of one game into a single row.
 *
 * Existing rows are backfilled rather than left at the column default: an empty
 * key is a key like any other to `WHERE groupKey = ?`, so every cached ROM would
 * come back as a variant of every other one.  The backfill can only reach the
 * lower two tiers of [app.rommdroid.util.romGroupKey] — the metadata id it
 * prefers is not a column — so a synced platform regroups slightly when the next
 * sync recomputes the keys in full.  Wrong grouping for a while beats a variant
 * picker listing the entire library.
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

/**
 * Adds the aggregate rating a ROM list row shows.
 *
 * Left null for every existing row rather than backfilled: the value only
 * exists on the server, and opening a platform full-syncs it anyway (see
 * [app.rommdroid.ui.screens.RomListViewModel]), so the column fills itself in
 * the first time the user visits each list.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `roms` ADD COLUMN `averageRating` REAL")
    }
}

/** Every migration, in the order the versions shipped. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
