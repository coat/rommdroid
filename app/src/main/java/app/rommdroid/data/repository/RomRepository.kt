package app.rommdroid.data.repository

import app.rommdroid.data.api.RomMApi
import app.rommdroid.data.api.model.CollectionSchema
import app.rommdroid.data.api.model.DetailedRomSchema
import app.rommdroid.data.api.model.PlatformSchema
import app.rommdroid.data.api.model.SimpleRomSchema
import app.rommdroid.data.db.*
import app.rommdroid.util.decodeHtmlEntities
import app.rommdroid.util.romGroupKey
import app.rommdroid.util.romRegions
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
    private val collectionDao: CollectionDao,
    private val json: Json,
) {

    // ── Platforms ─────────────────────────────────────────────────────────────

    fun observePlatforms(): Flow<List<PlatformEntity>> = platformDao.observeAll()

    /**
     * Refresh the cached platform list.
     *
     * A full sync ([updatedAfter] null) also drops platforms the server no
     * longer has, along with their ROMs: an upsert-only sync leaves a deleted
     * platform in the list for good, since the user has no reason to open it
     * and nothing else ever prunes it.
     *
     * Two responses are deliberately *not* treated as deletions:
     *  - an incremental one, which carries only what changed and so says
     *    nothing about what is gone, and
     *  - an empty full listing, which is indistinguishable from a server that
     *    answered a misrouted or over-filtered request with `[]`.  Wrongly
     *    keeping a stale row until the next sync is cheap; wrongly wiping the
     *    whole list is not.  A library that really is empty can be cleared from
     *    Settings.
     *
     * Folder mappings survive either way.  A platform can drop out of a
     * response for reasons other than deletion, and a hand-picked SAF folder is
     * the one thing here that a re-sync cannot rebuild.
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
     * Drops the whole cached library — every platform and ROM row.
     *
     * Needed when the app is pointed at a different server: RomM ids are
     * per-server, so leaving the previous server's rows in place renders its
     * cached metadata under the new server's platforms.
     *
     * Downloaded files, folder mappings and the download queue are untouched;
     * they are the user's own and no sync can recreate them.
     */
    suspend fun clearLibraryCache() {
        platformDao.deleteAll()
        romDao.deleteAll()
        collectionDao.deleteAll()
    }

    // ── ROMs ──────────────────────────────────────────────────────────────────

    fun observeRoms(platformId: Int): Flow<List<RomEntity>> =
        romDao.observeByPlatform(platformId)

    /**
     * Fetch ROMs for [platformId] from the server, storing all pages into Room.
     *
     * Uses [updatedAfter] for incremental sync.  A full refresh (null) replaces
     * the platform's rows so ROMs deleted on the server stop being listed.
     *
     * Every page is collected before anything is written.  Deleting up front
     * instead would mean a refresh out of range of the server — which is where
     * a handheld usually is — emptying the cache it was meant to update, and a
     * connection dropped halfway through the pages leaving a part of a library
     * looking like all of it.  Holding the pages costs peak memory on the order
     * of the platform's size, which even an arcade set keeps to megabytes.
     */
    suspend fun syncRoms(platformId: Int, updatedAfter: String? = null) {
        val fetched = mutableListOf<RomEntity>()
        var offset = 0
        val pageSize = 100
        do {
            val page = api.getRoms(
                platformIds = platformId,
                limit       = pageSize,
                offset      = offset,
                withCharIndex    = false,
                withRomIdIndex   = false,
                withFilterValues = false,
                updatedAfter     = updatedAfter,
            )
            fetched += page.items.map { it.toEntity() }
            offset += pageSize
        } while (offset < page.total)

        if (updatedAfter == null) {
            romDao.replacePlatform(platformId, fetched)
        } else {
            romDao.upsertAll(fetched)
        }
    }

    suspend fun getRomDetail(id: Int): DetailedRomSchema = api.getRom(id).run {
        copy(
            name    = name?.decodeHtmlEntities(),
            summary = summary?.decodeHtmlEntities(),
        )
    }

    /**
     * Search the whole library on the server.
     *
     * Deliberately not a Room query: the cache only ever holds the platforms the
     * user has actually opened, so a local search silently returns a fraction of
     * what the web UI finds for the same term.  Callers should fall back to
     * [searchLocal] when this throws so search still works offline.
     */
    suspend fun searchRemote(query: String, limit: Int = 100): List<RomEntity> =
        api.getRoms(
            searchTerm = query,
            limit      = limit,
            withCharIndex    = false,
            withRomIdIndex   = false,
            withFilterValues = false,
        ).items.map { it.toEntity() }

    /** Offline fallback — only covers platforms that have been synced. */
    suspend fun searchLocal(query: String): List<RomEntity> = romDao.search(query)

    // ── Collections ───────────────────────────────────────────────────────────

    fun observeCollections(): Flow<List<CollectionEntity>> = collectionDao.observeAll()

    /** Non-zero is the platform list's cue to pin its Collections row. */
    fun observeCollectionCount(): Flow<Int> = collectionDao.observeCount()

    fun observeCollectionRoms(collectionId: Int): Flow<List<RomEntity>> =
        collectionDao.observeRoms(collectionId)

    suspend fun getCollection(id: Int): CollectionEntity? = collectionDao.getById(id)

    /**
     * Refresh the cached collections.
     *
     * Always a full listing — a user has a handful of collections, not the
     * thousands of ROMs that make an incremental platform sync worth it — and
     * so it prunes what the server no longer has, with the same guard
     * [syncPlatforms] documents: an empty response is treated as a server that
     * answered oddly, not as a library with nothing in it.
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
     * Fetch what is in one collection, paging exactly as [syncRoms] does and
     * for the same reasons — every page collected before anything is written.
     *
     * The ROM rows are upserted rather than swapped: they belong to their
     * platforms, not to this collection, and a platform's own sync is what
     * owns the question of which of its ROMs still exist.  Only the membership
     * is replaced, so a game taken out of the collection on the server leaves
     * the list here too.
     */
    suspend fun syncCollectionRoms(collectionId: Int) {
        val fetched = mutableListOf<RomEntity>()
        var offset = 0
        val pageSize = 100
        do {
            val page = api.getRoms(
                collectionId     = collectionId,
                limit            = pageSize,
                offset           = offset,
                withCharIndex    = false,
                withRomIdIndex   = false,
                withFilterValues = false,
            )
            fetched += page.items.map { it.toEntity() }
            offset += pageSize
        } while (offset < page.total)

        romDao.upsertAll(fetched)
        collectionDao.replaceMembership(
            collectionId = collectionId,
            rows         = fetched.map { CollectionRomEntity(collectionId, it.id) },
        )
    }

    // ── Regional variants ─────────────────────────────────────────────────────

    /**
     * Every cached copy of the same game as [rom], newest sync wins.
     *
     * Returns just [rom] when nothing else is cached — which is the normal case
     * for a ROM reached from search, since search results are not persisted.
     */
    suspend fun cachedVariants(rom: RomEntity): List<RomEntity> =
        romDao.getByGroupKey(rom.groupKey).ifEmpty { listOf(rom) }

    suspend fun getCachedRom(id: Int): RomEntity? = romDao.getById(id)

    /** Whichever of [ids] have been synced, keyed by id. */
    suspend fun getCachedRoms(ids: List<Int>): Map<Int, RomEntity> =
        if (ids.isEmpty()) emptyMap() else romDao.getByIds(ids).associateBy { it.id }

    /** Decoded region codes for [rom], falling back to its filename tags. */
    fun regionsOf(rom: RomEntity): List<String> =
        romRegions(rom) { json.decodeFromString(it) }

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

    private fun CollectionSchema.toEntity() = CollectionEntity(
        id             = id,
        // Scraped and hand-typed copy alike arrives HTML-escaped, same as a
        // ROM's name does.
        name           = name.decodeHtmlEntities(),
        description    = description.decodeHtmlEntities(),
        romCount       = romCount,
        // The member covers stand in for a collection that has none of its
        // own, which is every collection nobody uploaded artwork for.
        pathCoverSmall = pathCoverSmall ?: pathCoversSmall.firstOrNull(),
        pathCoverLarge = pathCoverLarge ?: pathCoversLarge.firstOrNull(),
        urlCover       = urlCover?.takeIf { it.isNotBlank() },
        isFavorite     = isFavorite,
        isPublic       = isPublic,
        ownerUsername  = ownerUsername,
        updatedAt      = updatedAt,
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
        // Scraped copy arrives HTML-escaped; filesystem fields never get this
        // treatment — a literal "&amp;" there is part of the real filename.
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
        groupKey              = romGroupKey(platformId, igdbId, slug, fsNameNoTags),
    )
}
