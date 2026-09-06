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
 * Where a platform's ROMs get written.
 *
 * [treeUri] is a SAF tree the app holds a persisted grant on. [subfolder], when
 * non-null, is a directory *underneath* that tree which the download creates on
 * demand — that is how one grant on a base "ROMs" folder serves every platform.
 */
data class DownloadTarget(
    val treeUri: String,
    val subfolder: String?,
    /** Human-readable destination, e.g. "Roms/snes". */
    val displayPath: String,
    /** True when this came from a per-platform override rather than the base folder. */
    val isOverride: Boolean,
)

/**
 * Resolves the download destination for a platform, in precedence order:
 *
 *  1. an explicit per-platform folder override (a different directory entirely)
 *  2. the base folder + a user-renamed subfolder
 *  3. the base folder + the ES-DE convention name for the platform
 *
 * Returns null only when the user has configured nothing at all.
 *
 * Every write to a mapping goes through here so that none can skip the mirror
 * in [FolderMappingBackup] — the copy that survives a database rebuild.
 */
@Singleton
class DownloadTargetRepository @Inject constructor(
    private val baseFolderDao: BaseFolderDao,
    private val platformFolderDao: PlatformFolderDao,
    private val subfolderDao: PlatformSubfolderDao,
    private val backup: FolderMappingBackup,
) {

    // ── The configured mappings ───────────────────────────────────────────────

    fun observeBaseFolder(): Flow<BaseFolderEntity?> = baseFolderDao.observe()

    fun observeOverrides(): Flow<List<PlatformFolderEntity>> = platformFolderDao.observeAll()

    fun observeSubfolders(): Flow<List<PlatformSubfolderEntity>> = subfolderDao.observeAll()

    suspend fun resolve(platform: PlatformEntity): DownloadTarget? = resolve(
        platform  = platform,
        base      = baseFolderDao.get(),
        override  = platformFolderDao.getForPlatform(platform.id),
        customSub = subfolderDao.getForPlatform(platform.id)?.name,
    )

    /**
     * Pure form, so the settings UI can render every row from already-collected
     * flows instead of issuing a query per platform.
     */
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

    // ── Writes ────────────────────────────────────────────────────────────────

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

    /**
     * Copies the mappings out to where a database rebuild cannot reach them.
     *
     * Written whole rather than incrementally: it is three small tables, it runs
     * only when the user changes a folder, and a mirror rebuilt from the
     * database each time cannot drift from it.
     */
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
