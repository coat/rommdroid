package app.rommdroid.data.api

import app.rommdroid.data.api.model.*
import retrofit2.http.*

/**
 * The RomM REST API. The base URL comes from [BaseUrlInterceptor] so the
 * user-configured server applies at runtime, and [AuthInterceptor] attaches the
 * bearer token.
 */
interface RomMApi {

    // Auth

    /** Reachability plus version and capability info. */
    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatResponse

    /** The authenticated user's profile. */
    @GET("api/users/me")
    suspend fun getMe(): UserResponse

    // Client API tokens

    /** Mints the token the app keeps; it arrives in `raw_token`, this once. */
    @POST("api/client-tokens")
    suspend fun createClientToken(@Body req: CreateTokenRequest): ClientTokenResponse

    // Platforms

    /** A flat array, not paginated. [updatedAfter] is ISO-8601. */
    @GET("api/platforms")
    suspend fun getPlatforms(
        @Query("updated_after") updatedAfter: String? = null,
    ): List<PlatformSchema>

    // Collections

    /** A flat array, like the platform listing. Their ROMs come from [getRoms]
     *  with `collection_id`, not from an endpoint of their own. */
    @GET("api/collections")
    suspend fun getCollections(): List<CollectionSchema>

    // ROMs

    /**
     * Paginated ROM list.
     *
     * [groupByMetaId] = 1 collapses regional variants and hides real ROMs (441
     * Game Boy ROMs become 340); 0 is the server default and what the web UI
     * shows. [withCharIndex], [withRomIdIndex] and [withFilterValues] add
     * significant response size for UI niceties this app does not use.
     */
    @GET("api/roms")
    suspend fun getRoms(
        // "platform_ids": RomM silently ignores an unknown "platform_id" and
        // returns the whole library.
        @Query("platform_ids")      platformIds: Int? = null,
        @Query("collection_id")     collectionId: Int? = null,
        @Query("search_term")       searchTerm: String? = null,
        @Query("limit")             limit: Int = 50,
        @Query("offset")            offset: Int = 0,
        @Query("order_by")          orderBy: String = "name",
        @Query("order_dir")         orderDir: String = "asc",
        @Query("group_by_meta_id")  groupByMetaId: Int = 0,
        @Query("with_files")        withFiles: Boolean = false,
        @Query("with_char_index")   withCharIndex: Boolean = false,
        @Query("with_rom_id_index") withRomIdIndex: Boolean = false,
        @Query("with_filter_values") withFilterValues: Boolean = false,
        @Query("updated_after")     updatedAfter: String? = null,
    ): PagedRomResponse

    @GET("api/roms/{id}")
    suspend fun getRom(@Path("id") id: Int): RomSchema

    // Downloads go straight to DownloadWorker rather than through Retrofit, so
    // OkHttp can stream with progress callbacks. See RomRepository.romDownloadUrl.
}
