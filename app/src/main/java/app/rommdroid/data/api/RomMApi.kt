package app.rommdroid.data.api

import app.rommdroid.data.api.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for the RomM REST API.
 *
 * Base URL is injected by the OkHttpClient (see NetworkModule) so that the
 * user-configured server URL is applied at runtime.  All endpoints listed here
 * match the RomM OpenAPI spec; the ones the app currently uses are un-commented.
 *
 * Auth: Bearer token ("rmm_…") is attached by [AuthInterceptor].
 */
interface RomMApi {

    // ── Auth ──────────────────────────────────────────────────────────────────

    /** Basic-auth login; returns a session cookie (used only during setup). */
    @FormUrlEncoded
    @POST("api/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): Response<Unit>

    /** Verify that the server is reachable and return version/capability info. */
    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatResponse

    /** Fetch the authenticated user's profile. */
    @GET("api/users/me")
    suspend fun getMe(): UserResponse

    // ── Client API Tokens (device pairing) ───────────────────────────────────

    /** Create a new client token (returns id + name; token delivered separately). */
    @POST("api/client-tokens")
    suspend fun createClientToken(@Body req: CreateTokenRequest): ClientTokenResponse

    /** Begin device pairing — returns an 8-digit code valid for 5 minutes. */
    @POST("api/client-tokens/{id}/pair")
    suspend fun pairToken(@Path("id") id: Int): PairResponse

    /** Exchange the pairing code for the actual token. */
    @POST("api/client-tokens/exchange")
    suspend fun exchangeToken(@Body req: ExchangeTokenRequest): ClientTokenResponse

    // ── Platforms ─────────────────────────────────────────────────────────────

    /**
     * List all platforms.  Returns a flat array (not paginated).
     * Supports [updatedAfter] (ISO-8601) for incremental sync.
     */
    @GET("api/platforms")
    suspend fun getPlatforms(
        @Query("updated_after") updatedAfter: String? = null,
    ): List<PlatformSchema>

    @GET("api/platforms/{id}")
    suspend fun getPlatform(@Path("id") id: Int): PlatformSchema

    // ── ROMs ──────────────────────────────────────────────────────────────────

    /**
     * Paginated ROM list with extensive filtering.
     *
     * Pass [withFiles] = true to inline file list (costs more bandwidth).
     * Pass [groupByMetaId] = true (the default) to deduplicate regional variants.
     *
     * Disable [withCharIndex], [withRomIdIndex], [withFilterValues] for lean
     * programmatic access (they add significant response size for UI niceties
     * that we don't need in the list view).
     */
    @GET("api/roms")
    suspend fun getRoms(
        @Query("platform_id")       platformId: Int? = null,
        @Query("platform_ids")      platformIds: Int? = null,   // same value — forward-compat shim
        @Query("collection_id")     collectionId: Int? = null,
        @Query("search_term")       searchTerm: String? = null,
        @Query("limit")             limit: Int = 50,
        @Query("offset")            offset: Int = 0,
        @Query("order_by")          orderBy: String = "name",
        @Query("order_dir")         orderDir: String = "asc",
        @Query("group_by_meta_id")  groupByMetaId: Int = 1,
        @Query("with_files")        withFiles: Boolean = false,
        @Query("with_char_index")   withCharIndex: Boolean = false,
        @Query("with_rom_id_index") withRomIdIndex: Boolean = false,
        @Query("with_filter_values") withFilterValues: Boolean = false,
        @Query("updated_after")     updatedAfter: String? = null,
    ): PagedRomResponse

    @GET("api/roms/{id}")
    suspend fun getRom(@Path("id") id: Int): DetailedRomSchema

    // ── Downloads ────────────────────────────────────────────────────────────
    //
    // Download URLs are not called via Retrofit — they are passed directly to
    // WorkManager's DownloadWorker so that OkHttp handles streaming with
    // progress callbacks.  Build the URL with [RomMApiUrls.romDownloadUrl].

    // ── Firmware ─────────────────────────────────────────────────────────────

    @GET("api/firmware")
    suspend fun getFirmware(
        @Query("platform_id") platformId: Int,
    ): List<FirmwareSchema>
}
