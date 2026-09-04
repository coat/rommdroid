package app.rommdroid.data.repository

import app.rommdroid.data.api.RomMApi
import app.rommdroid.data.api.model.DetailedRomSchema
import app.rommdroid.data.api.model.PlatformSchema
import app.rommdroid.data.api.model.SimpleRomSchema
import app.rommdroid.data.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomRepository @Inject constructor(
    private val api: RomMApi,
    private val platformDao: PlatformDao,
    private val romDao: RomDao,
    private val json: Json,
) {

    // ── Platforms ─────────────────────────────────────────────────────────────

    fun observePlatforms(): Flow<List<PlatformEntity>> = platformDao.observeAll()

    suspend fun syncPlatforms(updatedAfter: String? = null) {
        val remote = api.getPlatforms(updatedAfter = updatedAfter)
        platformDao.upsertAll(remote.map { it.toEntity() })
    }

    // ── ROMs ──────────────────────────────────────────────────────────────────

    fun observeRoms(platformId: Int): Flow<List<RomEntity>> =
        romDao.observeByPlatform(platformId)

    /**
     * Fetch ROMs for [platformId] from the server, storing all pages into Room.
     *
     * Uses [updatedAfter] for incremental sync.  On a full refresh (null),
     * deletes existing rows first to avoid stale data from deleted ROMs.
     */
    suspend fun syncRoms(platformId: Int, updatedAfter: String? = null) {
        if (updatedAfter == null) {
            romDao.deleteByPlatform(platformId)
        }

        var offset = 0
        val pageSize = 100
        do {
            val page = api.getRoms(
                platformId  = platformId,
                platformIds = platformId,   // forward-compat shim
                limit       = pageSize,
                offset      = offset,
                withCharIndex    = false,
                withRomIdIndex   = false,
                withFilterValues = false,
                updatedAfter     = updatedAfter,
            )
            romDao.upsertAll(page.items.map { it.toEntity() })
            offset += pageSize
        } while (offset < page.total)
    }

    suspend fun getRomDetail(id: Int): DetailedRomSchema = api.getRom(id)

    suspend fun searchLocal(query: String): List<RomEntity> = romDao.search(query)

    // ── Download URL construction ─────────────────────────────────────────────

    /**
     * Returns the download URL for [fileName] belonging to ROM [romId].
     * Optionally filter to specific [fileIds] (for multi-disc ROMs).
     *
     * This URL is passed to [app.rommdroid.data.download.DownloadWorker] with
     * the auth token as a header — it is NOT called via Retrofit.
     */
    fun romDownloadUrl(
        serverUrl: String,
        romId: Int,
        fileName: String,
        fileIds: List<Int> = emptyList(),
    ): String {
        // Built through HttpUrl so ROM names survive the trip: spaces, "&",
        // "#" and "?" are common in filenames and a raw string concat produces
        // a URL that silently points at the wrong resource.
        val builder = serverUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/roms/$romId/content")
            .addPathSegment(fileName)
        if (fileIds.isNotEmpty()) {
            builder.addQueryParameter("file_ids", fileIds.joinToString(","))
        }
        return builder.build().toString()
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun PlatformSchema.toEntity() = PlatformEntity(
        id          = id,
        slug        = slug,
        fsSlug      = fsSlug,
        displayName = displayName,
        romCount    = romCount,
        urlLogo     = urlLogo,
        updatedAt   = updatedAt,
    )

    private fun SimpleRomSchema.toEntity() = RomEntity(
        id                    = id,
        platformId            = platformId,
        platformSlug          = platformSlug,
        platformDisplayName   = platformDisplayName,
        fsName                = fsName,
        fsNameNoTags          = fsNameNoTags,
        fsExtension           = fsExtension,
        fsSizeBytes           = fsSizeBytes,
        name                  = name,
        slug                  = slug,
        summary               = summary,
        regions               = json.encodeToString(regions),
        languages             = json.encodeToString(languages),
        tags                  = json.encodeToString(tags),
        urlCover              = urlCover,
        pathCoverSmall        = pathCoverSmall,
        pathCoverLarge        = pathCoverLarge,
        updatedAt             = updatedAt,
    )
}
