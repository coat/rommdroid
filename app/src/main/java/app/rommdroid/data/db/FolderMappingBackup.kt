package app.rommdroid.data.db

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The folder mappings, in a form that outlives the database file.
 *
 * Deliberately its own shape rather than the entities: this is a wire format
 * written to disk, and it has to keep decoding after the tables it mirrors are
 * refactored.
 */
@Serializable
data class FolderMappingSnapshot(
    val base: Folder? = null,
    val overrides: List<Override> = emptyList(),
    val subfolders: List<Subfolder> = emptyList(),
) {
    @Serializable
    data class Folder(val uri: String, val displayPath: String)

    @Serializable
    data class Override(val platformId: Int, val uri: String, val displayPath: String)

    @Serializable
    data class Subfolder(val platformId: Int, val name: String)

    val isEmpty: Boolean
        get() = base == null && overrides.isEmpty() && subfolders.isEmpty()

    companion object {
        /**
         * Lenient on purpose.  What is being decoded was written by whichever
         * build of the app the user had before this one, and a field that build
         * knew about but this one does not must not cost them the mappings the
         * rest of the file still carries.
         */
        internal val format = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Keeps a copy of the folder mappings outside SQLite, and puts them back when
 * the database is rebuilt from scratch.
 *
 * Room's destructive fallback drops every table when it meets a version it has
 * no migration for, and the mappings would go with the ROM cache — except the
 * cache is one sync away from being whole again and the mappings are not.  They
 * are re-picked by hand, folder by folder, through the document picker.  So
 * every write to them is mirrored here, into a preferences file the database
 * rebuild cannot touch, and read back in
 * [app.rommdroid.di.DatabaseModule]'s open callback.
 *
 * A mapping is only restored while the app still holds its SAF grant.  Anything
 * that takes the grant away — an uninstall, the user revoking it in system
 * settings, a restore of this file onto a different device — leaves a path the
 * app cannot write to, and showing it as configured would turn every download
 * into a failure with no hint of the cause.  Better to be honestly unset.
 */
@Singleton
class FolderMappingBackup @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    fun save(snapshot: FolderMappingSnapshot) {
        try {
            prefs.edit().putString(KEY_SNAPSHOT, FolderMappingSnapshot.format.encodeToString(snapshot)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not mirror the folder mappings: ${e.message}")
        }
    }

    fun load(): FolderMappingSnapshot {
        val stored = prefs.getString(KEY_SNAPSHOT, null) ?: return FolderMappingSnapshot()
        return try {
            FolderMappingSnapshot.format.decodeFromString(stored)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the mirrored folder mappings: ${e.message}")
            FolderMappingSnapshot()
        }
    }

    /**
     * Takes the first mirror of mappings that were configured before there was
     * anything to mirror into.
     *
     * Without this, the copy only starts existing once the user next changes a
     * folder — so someone who set theirs up months ago and has not touched it
     * since, which is everybody the mirror is for, would still lose it to the
     * next rebuild.  Reads through [SupportSQLiteDatabase] because this runs on
     * Room's open callback, before the DAOs are usable.
     */
    fun seedFrom(db: SupportSQLiteDatabase) {
        if (!load().isEmpty) return
        try {
            val base = db.query(
                "SELECT `folderUri`, `displayPath` FROM `base_folder` WHERE `id` = ?",
                arrayOf<Any>(BaseFolderEntity.SINGLETON_ID),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    FolderMappingSnapshot.Folder(cursor.getString(0), cursor.getString(1))
                } else {
                    null
                }
            }
            val overrides = db.query(
                "SELECT `platformId`, `folderUri`, `displayPath` FROM `platform_folders`"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            FolderMappingSnapshot.Override(
                                platformId = cursor.getInt(0),
                                uri = cursor.getString(1),
                                displayPath = cursor.getString(2),
                            )
                        )
                    }
                }
            }
            val subfolders = db.query(
                "SELECT `platformId`, `name` FROM `platform_subfolders`"
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(FolderMappingSnapshot.Subfolder(cursor.getInt(0), cursor.getString(1)))
                    }
                }
            }

            val snapshot = FolderMappingSnapshot(base, overrides, subfolders)
            if (!snapshot.isEmpty) save(snapshot)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mirror the folder mappings already configured: ${e.message}")
        }
    }

    /**
     * Writes the mirrored mappings into freshly created tables.
     *
     * Runs on Room's open callback, so it talks to [SupportSQLiteDatabase]
     * directly — the DAOs are not usable yet at that point.  Never throws: a
     * failure here costs the user their mappings, which is where they already
     * were, and must not also cost them a launch.
     */
    fun restoreInto(db: SupportSQLiteDatabase) {
        val snapshot = load()
        if (snapshot.isEmpty) return
        try {
            snapshot.base?.takeIf { holdsGrant(it.uri) }?.let { base ->
                db.execSQL(
                    "INSERT OR REPLACE INTO `base_folder` (`id`, `folderUri`, `displayPath`) VALUES (?, ?, ?)",
                    arrayOf<Any>(BaseFolderEntity.SINGLETON_ID, base.uri, base.displayPath),
                )
            }
            for (override in snapshot.overrides) {
                if (!holdsGrant(override.uri)) continue
                db.execSQL(
                    "INSERT OR REPLACE INTO `platform_folders` (`platformId`, `folderUri`, `displayPath`) VALUES (?, ?, ?)",
                    arrayOf<Any>(override.platformId, override.uri, override.displayPath),
                )
            }
            // Subfolder names are just names — nothing to hold a grant on, and
            // they are meaningless without the base folder they sit under, which
            // the row above has already had its say about.
            for (sub in snapshot.subfolders) {
                db.execSQL(
                    "INSERT OR REPLACE INTO `platform_subfolders` (`platformId`, `name`) VALUES (?, ?)",
                    arrayOf<Any>(sub.platformId, sub.name),
                )
            }
            Log.i(TAG, "Restored the folder mappings after a database rebuild")
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore the folder mappings: ${e.message}")
        }
    }

    /** True while the app can still write to [uri]. */
    private fun holdsGrant(uri: String): Boolean {
        val target: Uri = try {
            uri.toUri()
        } catch (_: Exception) {
            return false
        }
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == target && it.isWritePermission
        }
    }

    private companion object {
        const val TAG = "FolderMappingBackup"
        const val PREFS_FILE = "rommdroid_folder_mappings"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
