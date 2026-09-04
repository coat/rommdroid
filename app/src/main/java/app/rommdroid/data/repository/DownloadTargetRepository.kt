package app.rommdroid.data.repository

import app.rommdroid.data.db.BaseFolderDao
import app.rommdroid.data.db.BaseFolderEntity
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.db.PlatformFolderDao
import app.rommdroid.data.db.PlatformFolderEntity
import app.rommdroid.data.db.PlatformSubfolderDao
import app.rommdroid.data.download.EsDePlatformFolders
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
 */
@Singleton
class DownloadTargetRepository @Inject constructor(
    private val baseFolderDao: BaseFolderDao,
    private val platformFolderDao: PlatformFolderDao,
    private val subfolderDao: PlatformSubfolderDao,
) {

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

    suspend fun setBaseFolder(uri: String, displayPath: String) =
        baseFolderDao.upsert(BaseFolderEntity(folderUri = uri, displayPath = displayPath))

    suspend fun baseFolder(): BaseFolderEntity? = baseFolderDao.get()
}
