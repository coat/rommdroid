package app.rommdroid.data.repository

import app.rommdroid.data.db.BaseFolderDao
import app.rommdroid.data.db.BaseFolderEntity
import app.rommdroid.data.db.FolderMappingBackup
import app.rommdroid.data.db.FolderMappingSnapshot
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.db.PlatformFolderDao
import app.rommdroid.data.db.PlatformFolderEntity
import app.rommdroid.data.db.PlatformSubfolderDao
import app.rommdroid.data.db.PlatformSubfolderEntity
import app.rommdroid.data.download.EsDePlatformFolders
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a platform's ROMs get written. [treeUri] is a SAF tree the app holds a
 * persisted grant on; [subfolder] is a directory underneath it, created on
 * demand, which is how one grant serves every platform.
 */
data class DownloadTarget(
    val treeUri: String,
    val subfolder: String?,
    /** Human-readable destination, e.g. "Roms/snes". */
    val displayPath: String,
    /** True for a per-platform override rather than the base folder. */
    val isOverride: Boolean,
)

/**
 * Resolves a platform's download destination, in precedence order:
 *
 *  1. a per-platform folder override (a different directory entirely)
 *  2. the base folder plus a user-renamed subfolder
 *  3. the base folder plus the ES-DE convention name
 *
 * Null only when nothing is configured. Every write goes through here so none
 * can skip the [FolderMappingBackup] mirror.
 */
@Singleton
class DownloadTargetRepository @Inject constructor(
    private val baseFolderDao: BaseFolderDao,
    private val platformFolderDao: PlatformFolderDao,
    private val subfolderDao: PlatformSubfolderDao,
    private val backup: FolderMappingBackup,
) {

    // The configured mappings

    fun observeBaseFolder(): Flow<BaseFolderEntity?> = baseFolderDao.observe()

    fun observeOverrides(): Flow<List<PlatformFolderEntity>> = platformFolderDao.observeAll()

    fun observeSubfolders(): Flow<List<PlatformSubfolderEntity>> = subfolderDao.observeAll()

    suspend fun resolve(platform: PlatformEntity): DownloadTarget? = resolve(
        platform  = platform,
        base      = baseFolderDao.get(),
        override  = platformFolderDao.getForPlatform(platform.id),
        customSub = subfolderDao.getForPlatform(platform.id)?.name,
    )

    /** Pure form, so the settings UI renders from already-collected flows rather
     *  than a query per platform. */
    fun resolve(
        platform: PlatformEntity,
        base: BaseFolderEntity?,
        override: PlatformFolderEntity?,
        customSub: String?,
    ): DownloadTarget? {
        if (override != null) {
            return DownloadTarget(
                treeUri     = override.folderUri,
                subfolder   = null,
                displayPath = override.displayPath,
                isOverride  = true,
            )
        }
        if (base == null) return null

        val sub = customSub?.takeIf { it.isNotBlank() } ?: defaultSubfolder(platform)
        return DownloadTarget(
            treeUri     = base.folderUri,
            subfolder   = sub,
            displayPath = "${base.displayPath.trimEnd('/')}/$sub",
            isOverride  = false,
        )
    }

    /** The ES-DE convention folder name for [platform]. */
    fun defaultSubfolder(platform: PlatformEntity): String =
        EsDePlatformFolders.forPlatform(platform.slug, platform.fsSlug)

    // Writes

    suspend fun setBaseFolder(uri: String, displayPath: String) {
        baseFolderDao.upsert(BaseFolderEntity(folderUri = uri, displayPath = displayPath))
        mirror()
    }

    /** Point a single platform at a directory outside the base folder. */
    suspend fun setPlatformFolder(platformId: Int, uri: String, displayPath: String) {
        platformFolderDao.upsert(PlatformFolderEntity(platformId, uri, displayPath))
        mirror()
    }

    /** Rename the subfolder a platform gets under the base folder. */
    suspend fun setSubfolder(platformId: Int, name: String) {
        subfolderDao.upsert(PlatformSubfolderEntity(platformId, name.trim().trim('/')))
        mirror()
    }

    /** Drop both kinds of override so the platform follows the ES-DE default again. */
    suspend fun resetPlatform(platformId: Int) {
        platformFolderDao.deleteForPlatform(platformId)
        subfolderDao.deleteForPlatform(platformId)
        mirror()
    }

    suspend fun baseFolder(): BaseFolderEntity? = baseFolderDao.get()

    /** Copies the mappings beyond a database rebuild's reach. Written whole:
     *  three small tables, only on a folder change, and it cannot drift. */
    private suspend fun mirror() {
        val base = baseFolderDao.get()
        backup.save(
            FolderMappingSnapshot(
                base = base?.let { FolderMappingSnapshot.Folder(it.folderUri, it.displayPath) },
                overrides = platformFolderDao.getAll().map {
                    FolderMappingSnapshot.Override(it.platformId, it.folderUri, it.displayPath)
                },
                subfolders = subfolderDao.getAll().map {
                    FolderMappingSnapshot.Subfolder(it.platformId, it.name)
                },
            )
        )
    }
}
