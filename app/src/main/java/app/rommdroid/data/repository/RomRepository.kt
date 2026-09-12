package app.rommdroid.data.repository

import androidx.room.withTransaction
import app.rommdroid.data.api.RomMApi
import app.rommdroid.data.api.model.CollectionSchema
import app.rommdroid.data.api.model.PagedRomResponse
import app.rommdroid.data.api.model.PlatformSchema
import app.rommdroid.data.api.model.RomSchema
import app.rommdroid.data.db.*
import app.rommdroid.domain.RomDetail
import app.rommdroid.domain.RomFile
import app.rommdroid.domain.RomVariant
import app.rommdroid.domain.artworkUrl
import app.rommdroid.domain.regionsFor
import app.rommdroid.domain.romGroupKey
import app.rommdroid.domain.romRegions
import app.rommdroid.util.decodeHtmlEntities
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RomRepository @Inject constructor(
    private val api: RomMApi,
    private val db: AppDatabase,
    private val platformDao: PlatformDao,
    private val romDao: RomDao,
    private val collectionDao: CollectionDao,
    private val credentials: CredentialRepository,
    private val json: Json,
) {

    // Platforms

    fun observePlatforms(): Flow<List<PlatformEntity>> = platformDao.observeAll()

    suspend fun getPlatform(id: Int): PlatformEntity? = platformDao.getById(id)

    /**
     * Refresh the cached platform list. A full sync ([updatedAfter] null) also
     * prunes platforms the server no longer has, along with their ROMs.
     *
     * Neither an incremental response (says nothing about what is gone) nor an
     * empty full listing (indistinguishable from a misrouted request answered
     * with `[]`) counts as a deletion. Folder mappings survive either way; a
     * hand-picked SAF folder is the one thing a re-sync cannot rebuild.
     */
    suspend fun syncPlatforms(updatedAfter: String? = null) {
        val remote = api.getPlatforms(updatedAfter = updatedAfter).map { it.toEntity() }
        if (updatedAfter == null && remote.isNotEmpty()) {
            platformDao.reconcile(remote)
        } else {
            platformDao.upsertAll(remote)
        }
    }

    /**
     * Drop the whole cached library. Needed when the app is pointed at a
     * different server: RomM ids are per-server, so stale rows would render the
     * old server's metadata under the new server's platforms. Downloaded files,
     * folder mappings and the queue are untouched.
     */
    suspend fun clearLibraryCache() = db.withTransaction {
        platformDao.deleteAll()
        romDao.deleteAll()
        collectionDao.deleteAll()
    }

    // ROMs

    fun observeRoms(platformId: Int): Flow<List<RomEntity>> =
        romDao.observeByPlatform(platformId)

    /**
     * Fetch every page of [platformId]'s ROMs, then write. A full refresh
     * ([updatedAfter] null) replaces the platform's rows.
     */
    suspend fun syncRoms(platformId: Int, updatedAfter: String? = null) {
        val fetched = fetchAllRoms { limit, offset ->
            api.getRoms(
                platformIds  = platformId,
                limit        = limit,
                offset       = offset,
                updatedAfter = updatedAfter,
            )
        }
        if (updatedAfter == null) {
            romDao.replacePlatform(platformId, fetched)
        } else {
            romDao.upsertAll(fetched)
        }
    }

    suspend fun getRomDetail(id: Int): RomDetail = api.getRom(id).toDetail()

    /**
     * Search the whole library on the server. Not a Room query: the cache holds
     * only the platforms the user has opened, so a local search silently returns
     * a fraction of the hits. Callers fall back to [searchLocal] when this throws.
     */
    suspend fun searchRemote(query: String, limit: Int = 100): List<RomEntity> =
        api.getRoms(searchTerm = query, limit = limit).items.map { it.toEntity() }

    /** Offline fallback - only covers platforms that have been synced. */
    suspend fun searchLocal(query: String): List<RomEntity> = romDao.search(query)

    // Collections

    fun observeCollections(): Flow<List<CollectionEntity>> = collectionDao.observeAll()

    /** Non-zero is the platform list's cue to pin its Collections row. */
    fun observeCollectionCount(): Flow<Int> = collectionDao.observeCount()

    fun observeCollectionRoms(collectionId: Int): Flow<List<RomEntity>> =
        collectionDao.observeRoms(collectionId)

    suspend fun getCollection(id: Int): CollectionEntity? = collectionDao.getById(id)

    /**
     * Refresh the cached collections. Always a full listing, since a user has a
     * handful of them, so it prunes; empty responses are guarded as in
     * [syncPlatforms].
     */
    suspend fun syncCollections() {
        val remote = api.getCollections().map { it.toEntity() }
        if (remote.isNotEmpty()) {
            collectionDao.reconcile(remote)
        } else {
            collectionDao.upsertAll(remote)
        }
    }

    /**
     * Fetch one collection's ROMs. The ROM rows are upserted rather than
     * swapped, since they belong to their platforms; only the membership is
     * replaced.
     */
    suspend fun syncCollectionRoms(collectionId: Int) {
        val fetched = fetchAllRoms { limit, offset ->
            api.getRoms(collectionId = collectionId, limit = limit, offset = offset)
        }
        romDao.upsertAll(fetched)
        collectionDao.replaceMembership(
            collectionId = collectionId,
            rows         = fetched.map { CollectionRomEntity(collectionId, it.id) },
        )
    }

    /**
     * Every page of one ROM listing, mapped. Collected before anything is
     * written: peak memory on the order of the platform's size buys not
     * emptying the cache when a handheld goes out of range mid-sync.
     */
    private suspend fun fetchAllRoms(
        page: suspend (limit: Int, offset: Int) -> PagedRomResponse,
    ): List<RomEntity> {
        val fetched = mutableListOf<RomEntity>()
        var offset = 0
        do {
            val response = page(PAGE_SIZE, offset)
            fetched += response.items.map { it.toEntity() }
            offset += PAGE_SIZE
        } while (offset < response.total)
        return fetched
    }

    // Regional variants

    /** Every cached copy of the same game as [rom], or just [rom] when nothing
     *  else is cached - the normal case for a ROM reached from search. */
    suspend fun cachedVariants(rom: RomEntity): List<RomEntity> =
        romDao.getByGroupKey(rom.groupKey).ifEmpty { listOf(rom) }

    suspend fun getCachedRom(id: Int): RomEntity? = romDao.getById(id)

    /** Whichever of [ids] have been synced, keyed by id. */
    suspend fun getCachedRoms(ids: List<Int>): Map<Int, RomEntity> =
        if (ids.isEmpty()) emptyMap() else romDao.getByIds(ids).associateBy { it.id }

    /** Decoded region codes for [rom], falling back to its filename tags. */
    fun regionsOf(rom: RomEntity): List<String> =
        romRegions(rom) { json.decodeFromString(it) }

    // Artwork

    /** Absolute cover URL for a list row, or null when the server has none. */
    fun coverUrl(rom: RomEntity): String? =
        artworkUrl(credentials.serverUrl, rom.pathCoverSmall, rom.pathCoverLarge, rom.urlCover)

    fun coverUrl(platform: PlatformEntity): String? =
        artworkUrl(credentials.serverUrl, platform.urlLogo)

    fun coverUrl(collection: CollectionEntity): String? = artworkUrl(
        credentials.serverUrl,
        collection.pathCoverSmall,
        collection.pathCoverLarge,
        collection.urlCover,
    )

    // Download URL construction

    /**
     * Download URL for [fileName] of ROM [romId], optionally narrowed to
     * [fileIds] for a multi-disc set. Handed to
     * [app.rommdroid.data.download.DownloadWorker], not called via Retrofit.
     */
    fun romDownloadUrl(
        serverUrl: String,
        romId: Int,
        fileName: String,
        fileIds: List<Int> = emptyList(),
    ): String {
        // HttpUrl, not string concat: spaces, "&", "#" and "?" are common in ROM
        // filenames and a raw concat silently points at the wrong resource.
        val builder = serverUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/roms/$romId/content")
            .addPathSegment(fileName)
        if (fileIds.isNotEmpty()) {
            builder.addQueryParameter("file_ids", fileIds.joinToString(","))
        }
        return builder.build().toString()
    }

    // Mappers

    private fun PlatformSchema.toEntity() = PlatformEntity(
        id          = id,
        slug        = slug,
        fsSlug      = fsSlug,
        displayName = displayName,
        romCount    = romCount,
        urlLogo     = urlLogo,
        updatedAt   = updatedAt,
    )

    private fun CollectionSchema.toEntity() = CollectionEntity(
        id             = id,
        name           = name.decodeHtmlEntities(),
        description    = description.decodeHtmlEntities(),
        romCount       = romCount,
        // Member covers stand in for the collections nobody uploaded art for.
        pathCoverSmall = pathCoverSmall ?: pathCoversSmall.firstOrNull(),
        pathCoverLarge = pathCoverLarge ?: pathCoversLarge.firstOrNull(),
        urlCover       = urlCover?.takeIf { it.isNotBlank() },
        isFavorite     = isFavorite,
        isPublic       = isPublic,
        ownerUsername  = ownerUsername,
        updatedAt      = updatedAt,
    )

    private fun RomSchema.toDetail() = RomDetail(
        id                  = id,
        platformId          = platformId,
        platformDisplayName = platformDisplayName,
        name                = name?.decodeHtmlEntities(),
        fsName              = fsName,
        fsNameNoTags        = fsNameNoTags,
        fsSizeBytes         = fsSizeBytes,
        summary             = summary?.decodeHtmlEntities(),
        regions             = regionsFor(regions, fsName),
        // Large first: this is the one screen with room for it.
        coverUrl            = artworkUrl(credentials.serverUrl, pathCoverLarge, pathCoverSmall, urlCover),
        rating              = metadatum.averageRating,
        // The API omits the list for single-file ROMs, so one is synthesised
        // from the filesystem name; id 0 tells the queue to fetch it by name.
        files               = files.map { RomFile(it.id, it.fileName, it.fileSizeBytes) }
            .ifEmpty { listOf(RomFile(id = 0, fileName = fsName, sizeBytes = fsSizeBytes)) },
        siblings            = siblingRoms.map { it.toSiblingVariant() },
    )

    /**
     * A sibling as `sibling_roms` lists it. The server omits `fs_name`, so the
     * label falls back through what it does send, `fs_name_no_ext` first
     * because it still carries the "(Japan)" / "(Rev 1)" tag that tells copies
     * apart. Size stays 0, meaning unknown.
     */
    private fun RomSchema.toSiblingVariant(): RomVariant {
        val label = fsName
            .ifBlank { fsNameNoExt }
            .ifBlank { fsNameNoTags }
            .ifBlank { name.orEmpty() }
            .ifBlank { "ROM #$id" }
        return RomVariant(id, label, fsSizeBytes, regionsFor(regions, label))
    }

    private fun RomSchema.toEntity() = RomEntity(
        id                    = id,
        platformId            = platformId,
        platformSlug          = platformSlug,
        platformDisplayName   = platformDisplayName,
        fsName                = fsName,
        fsNameNoTags          = fsNameNoTags,
        fsExtension           = fsExtension,
        fsSizeBytes           = fsSizeBytes,
        // Scraped copy arrives HTML-escaped; the fs* fields never do, where a
        // literal "&amp;" is part of the real filename.
        name                  = name?.decodeHtmlEntities(),
        slug                  = slug,
        summary               = summary?.decodeHtmlEntities(),
        regions               = json.encodeToString(regions),
        languages             = json.encodeToString(languages),
        tags                  = json.encodeToString(tags),
        urlCover              = urlCover,
        pathCoverSmall        = pathCoverSmall,
        pathCoverLarge        = pathCoverLarge,
        updatedAt             = updatedAt,
        averageRating         = metadatum.averageRating,
        firstReleaseDate      = metadatum.firstReleaseDate,
        createdAt             = createdAt.takeIf { it.isNotBlank() },
        groupKey              = romGroupKey(platformId, igdbId, slug, fsNameNoTags),
    )

    private companion object {
        const val PAGE_SIZE = 100
    }
}
