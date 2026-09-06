package app.rommdroid.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Auth / Setup ──────────────────────────────────────────────────────────────

@Serializable
data class HeartbeatSystem(
    @SerialName("VERSION")          val version: String = "",
    @SerialName("SHOW_SETUP_WIZARD") val showSetupWizard: Boolean = false,
)

@Serializable
data class HeartbeatFrontend(
    @SerialName("DISABLE_USERPASS_LOGIN") val disableUserpassLogin: Boolean = false,
)

@Serializable
data class HeartbeatResponse(
    @SerialName("SYSTEM")   val system: HeartbeatSystem = HeartbeatSystem(),
    @SerialName("FRONTEND") val frontend: HeartbeatFrontend = HeartbeatFrontend(),
) {
    val version: String get() = system.version
    val showSetupWizard: Boolean get() = system.showSetupWizard
}

@Serializable
data class UserResponse(
    val id: Int,
    val username: String,
    val role: String,
    @SerialName("avatar_path") val avatarPath: String? = null,
)

@Serializable
data class CreateTokenRequest(
    val name: String,
    /**
     * At least one scope is required by the API, and every requested scope must
     * exist and be held by the user or the server answers 403.  These are all
     * from RomM's READ_SCOPES, which the lowest "viewer" role already has.
     */
    val scopes: List<String> = listOf(
        "roms.read", "platforms.read", "collections.read",
        "firmware.read", "me.read",
    ),
    @SerialName("expires_in") val expiresIn: String? = null,
)

@Serializable
data class ClientTokenResponse(
    val id: Int,
    val name: String,
    val scopes: List<String> = emptyList(),
    @SerialName("expires_at")   val expiresAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("created_at")   val createdAt: String = "",
    @SerialName("user_id")      val userId: Int = 0,
    @SerialName("device_id")    val deviceId: String? = null,
    /** Only present on token creation (ClientTokenCreateSchema). */
    @SerialName("raw_token")    val rawToken: String? = null,
) {
    /** Convenience alias — the actual token value to store. */
    val token: String? get() = rawToken
}

@Serializable
data class PairResponse(
    val code: String,
)

@Serializable
data class ExchangeTokenRequest(
    val code: String,
)

// ── Platforms ─────────────────────────────────────────────────────────────────

@Serializable
data class PlatformSchema(
    val id: Int,
    val slug: String,
    @SerialName("fs_slug")          val fsSlug: String,
    val name: String? = null,
    @SerialName("display_name")     val displayName: String,
    @SerialName("rom_count")        val romCount: Int = 0,
    @SerialName("firmware_count")   val firmwareCount: Int = 0,
    @SerialName("fs_size_bytes")    val fsSizeBytes: Long = 0L,
    @SerialName("url_logo")         val urlLogo: String? = null,
    @SerialName("created_at")       val createdAt: String? = null,
    @SerialName("updated_at")       val updatedAt: String? = null,
)

// ── Collections ───────────────────────────────────────────────────────────────

/**
 * A user-made collection: "Favourites", "To Play", whatever they named it.
 *
 * `rom_ids` is deliberately not modelled.  It arrives on every collection in
 * the listing and can run to thousands of ints for a big one, and the app has
 * no use for bare ids — the membership it stores comes from the ROM fetch,
 * which is the set it can actually draw.
 *
 * Virtual and smart collections come from sibling endpoints and are not
 * supported: the virtual ones are generated from metadata (genre, franchise,
 * company) and there are far too many to put in a flat list.
 */
@Serializable
data class CollectionSchema(
    val id: Int,
    val name: String,
    val description: String = "",
    @SerialName("rom_count")        val romCount: Int = 0,
    @SerialName("path_cover_small") val pathCoverSmall: String? = null,
    @SerialName("path_cover_large") val pathCoverLarge: String? = null,
    /**
     * Covers of the games in the collection, which is where a collection's
     * artwork actually comes from: RomM only fills the singular fields above
     * when someone uploaded a cover of their own, and leaves them null for the
     * mosaic it builds out of the members otherwise.
     */
    @SerialName("path_covers_small") val pathCoversSmall: List<String> = emptyList(),
    @SerialName("path_covers_large") val pathCoversLarge: List<String> = emptyList(),
    @SerialName("url_cover")        val urlCover: String? = null,
    @SerialName("is_favorite")      val isFavorite: Boolean = false,
    @SerialName("is_public")        val isPublic: Boolean = false,
    @SerialName("owner_username")   val ownerUsername: String = "",
    @SerialName("created_at")       val createdAt: String? = null,
    @SerialName("updated_at")       val updatedAt: String? = null,
)

// ── ROMs ──────────────────────────────────────────────────────────────────────

@Serializable
data class PagedRomResponse(
    val items: List<SimpleRomSchema>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class RomFileSchema(
    val id: Int,
    @SerialName("rom_id")           val romId: Int = 0,
    @SerialName("file_name")        val fileName: String = "",
    @SerialName("file_path")        val filePath: String = "",
    @SerialName("file_size_bytes")  val fileSizeBytes: Long = 0L,
    @SerialName("full_path")        val fullPath: String = "",
    @SerialName("is_top_level")     val isTopLevel: Boolean = true,
    @SerialName("created_at")       val createdAt: String = "",
    @SerialName("updated_at")       val updatedAt: String = "",
    @SerialName("last_modified")    val lastModified: String = "",
    @SerialName("crc_hash")         val crcHash: String? = null,
    @SerialName("md5_hash")         val md5Hash: String? = null,
    @SerialName("sha1_hash")        val sha1Hash: String? = null,
    @SerialName("ra_hash")          val raHash: String? = null,
    @SerialName("chd_sha1_hash")    val chdSha1Hash: String? = null,
)

@Serializable
data class RomMetadataSchema(
    val genres: List<String> = emptyList(),
    val franchises: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    @SerialName("game_modes")           val gameModes: List<String> = emptyList(),
    @SerialName("age_ratings")          val ageRatings: List<String> = emptyList(),
    @SerialName("player_count")         val playerCount: String = "",
    @SerialName("first_release_date")   val firstReleaseDate: Long? = null,
    /**
     * The server's aggregate score, 0-100.  It is the mean of whichever
     * providers scored the game, each normalised to that scale by RomM (IGDB
     * already is; MobyGames and ScreenScraper are x10, LaunchBox x20), and is
     * null when no provider scored it at all.
     */
    @SerialName("average_rating")       val averageRating: Double? = null,
)

@Serializable
data class SimpleRomSchema(
    val id: Int,
    // ── Platform ──────────────────────────────────────────────────────────────
    @SerialName("platform_id")              val platformId: Int = 0,
    @SerialName("platform_slug")            val platformSlug: String = "",
    @SerialName("platform_fs_slug")         val platformFsSlug: String = "",
    @SerialName("platform_custom_name")     val platformCustomName: String? = null,
    @SerialName("platform_display_name")    val platformDisplayName: String = "",
    // ── Filesystem ────────────────────────────────────────────────────────────
    @SerialName("fs_name")                  val fsName: String = "",
    @SerialName("fs_name_no_tags")          val fsNameNoTags: String = "",
    @SerialName("fs_name_no_ext")           val fsNameNoExt: String = "",
    @SerialName("fs_extension")             val fsExtension: String = "",
    @SerialName("fs_path")                  val fsPath: String = "",
    @SerialName("fs_size_bytes")            val fsSizeBytes: Long = 0L,
    // ── Metadata IDs ─────────────────────────────────────────────────────────
    @SerialName("igdb_id")                  val igdbId: Int? = null,
    @SerialName("sgdb_id")                  val sgdbId: Int? = null,
    @SerialName("moby_id")                  val mobyId: Int? = null,
    @SerialName("ss_id")                    val ssId: Int? = null,
    @SerialName("ra_id")                    val raId: Int? = null,
    @SerialName("launchbox_id")             val launchboxId: Int? = null,
    @SerialName("hasheous_id")              val hasheousId: Int? = null,
    @SerialName("tgdb_id")                  val tgdbId: Int? = null,
    @SerialName("flashpoint_id")            val flashpointId: String? = null,
    @SerialName("hltb_id")                  val hltbId: Int? = null,
    @SerialName("gamelist_id")              val gamelistId: String? = null,
    @SerialName("libretro_id")              val libretroId: String? = null,
    // ── Display ───────────────────────────────────────────────────────────────
    val name: String? = null,
    @SerialName("name_sort_key")            val nameSortKey: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    @SerialName("alternative_names")        val alternativeNames: List<String> = emptyList(),
    @SerialName("youtube_video_id")         val youtubeVideoId: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    // ── Cover / media ─────────────────────────────────────────────────────────
    @SerialName("url_cover")                val urlCover: String? = null,
    @SerialName("path_cover_small")         val pathCoverSmall: String? = null,
    @SerialName("path_cover_large")         val pathCoverLarge: String? = null,
    @SerialName("has_manual")               val hasManual: Boolean = false,
    @SerialName("has_soundtrack")           val hasSoundtrack: Boolean = false,
    @SerialName("path_manual")              val pathManual: String? = null,
    @SerialName("url_manual")              val urlManual: String? = null,
    @SerialName("path_video")               val pathVideo: String? = null,
    // ── Identification ────────────────────────────────────────────────────────
    @SerialName("is_unidentified")          val isUnidentified: Boolean = false,
    @SerialName("is_identified")            val isIdentified: Boolean = false,
    val revision: String? = null,
    // ── Hashes ────────────────────────────────────────────────────────────────
    @SerialName("crc_hash")                 val crcHash: String? = null,
    @SerialName("md5_hash")                 val md5Hash: String? = null,
    @SerialName("sha1_hash")                val sha1Hash: String? = null,
    @SerialName("ra_hash")                  val raHash: String? = null,
    // ── File structure ────────────────────────────────────────────────────────
    @SerialName("has_simple_single_file")   val hasSimpleSingleFile: Boolean = false,
    @SerialName("has_nested_single_file")   val hasNestedSingleFile: Boolean = false,
    @SerialName("has_multiple_files")       val hasMultipleFiles: Boolean = false,
    @SerialName("has_notes")                val hasNotes: Boolean = false,
    @SerialName("missing_from_fs")          val missingFromFs: Boolean = false,
    @SerialName("full_path")                val fullPath: String = "",
    // ── Timestamps ────────────────────────────────────────────────────────────
    @SerialName("created_at")               val createdAt: String = "",
    @SerialName("updated_at")               val updatedAt: String? = null,
    // ── Relations ─────────────────────────────────────────────────────────────
    @SerialName("sibling_roms")             val siblingRoms: List<SimpleRomSchema> = emptyList(),
    val files: List<RomFileSchema> = emptyList(),
    // ── Derived metadata ──────────────────────────────────────────────────────
    val metadatum: RomMetadataSchema = RomMetadataSchema(),
)

@Serializable
data class DetailedRomSchema(
    val id: Int,
    // ── Platform ──────────────────────────────────────────────────────────────
    @SerialName("platform_id")              val platformId: Int = 0,
    @SerialName("platform_slug")            val platformSlug: String = "",
    @SerialName("platform_fs_slug")         val platformFsSlug: String = "",
    @SerialName("platform_custom_name")     val platformCustomName: String? = null,
    @SerialName("platform_display_name")    val platformDisplayName: String = "",
    // ── Filesystem ────────────────────────────────────────────────────────────
    @SerialName("fs_name")                  val fsName: String = "",
    @SerialName("fs_name_no_tags")          val fsNameNoTags: String = "",
    @SerialName("fs_name_no_ext")           val fsNameNoExt: String = "",
    @SerialName("fs_extension")             val fsExtension: String = "",
    @SerialName("fs_path")                  val fsPath: String = "",
    @SerialName("fs_size_bytes")            val fsSizeBytes: Long = 0L,
    @SerialName("full_path")                val fullPath: String = "",
    @SerialName("missing_from_fs")          val missingFromFs: Boolean = false,
    // ── Metadata IDs (all nullable/defaulted — we don't use them) ────────────
    @SerialName("igdb_id")                  val igdbId: Int? = null,
    @SerialName("sgdb_id")                  val sgdbId: Int? = null,
    @SerialName("moby_id")                  val mobyId: Int? = null,
    @SerialName("ss_id")                    val ssId: Int? = null,
    @SerialName("ra_id")                    val raId: Int? = null,
    @SerialName("launchbox_id")             val launchboxId: Int? = null,
    @SerialName("hasheous_id")              val hasheousId: Int? = null,
    @SerialName("tgdb_id")                  val tgdbId: Int? = null,
    @SerialName("flashpoint_id")            val flashpointId: String? = null,
    @SerialName("hltb_id")                  val hltbId: Int? = null,
    @SerialName("gamelist_id")              val gamelistId: String? = null,
    @SerialName("libretro_id")              val libretroId: String? = null,
    // ── Display fields ────────────────────────────────────────────────────────
    val name: String? = null,
    @SerialName("name_sort_key")            val nameSortKey: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    @SerialName("alternative_names")        val alternativeNames: List<String> = emptyList(),
    @SerialName("youtube_video_id")         val youtubeVideoId: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    // ── Cover / media ─────────────────────────────────────────────────────────
    @SerialName("url_cover")                val urlCover: String? = null,
    @SerialName("path_cover_small")         val pathCoverSmall: String? = null,
    @SerialName("path_cover_large")         val pathCoverLarge: String? = null,
    @SerialName("has_manual")               val hasManual: Boolean = false,
    @SerialName("has_soundtrack")           val hasSoundtrack: Boolean = false,
    @SerialName("path_manual")              val pathManual: String? = null,
    @SerialName("url_manual")               val urlManual: String? = null,
    @SerialName("path_video")               val pathVideo: String? = null,
    // ── Identification ────────────────────────────────────────────────────────
    @SerialName("is_unidentified")          val isUnidentified: Boolean = false,
    @SerialName("is_identified")            val isIdentified: Boolean = false,
    val revision: String? = null,
    // ── Hashes ────────────────────────────────────────────────────────────────
    @SerialName("crc_hash")                 val crcHash: String? = null,
    @SerialName("md5_hash")                 val md5Hash: String? = null,
    @SerialName("sha1_hash")                val sha1Hash: String? = null,
    @SerialName("ra_hash")                  val raHash: String? = null,
    // ── File structure flags ──────────────────────────────────────────────────
    @SerialName("has_simple_single_file")   val hasSimpleSingleFile: Boolean = false,
    @SerialName("has_nested_single_file")   val hasNestedSingleFile: Boolean = false,
    @SerialName("has_multiple_files")       val hasMultipleFiles: Boolean = false,
    @SerialName("has_notes")                val hasNotes: Boolean = false,
    // ── Timestamps ────────────────────────────────────────────────────────────
    @SerialName("created_at")               val createdAt: String = "",
    @SerialName("updated_at")               val updatedAt: String? = null,
    // ── Relations (ignored for now) ───────────────────────────────────────────
    @SerialName("sibling_roms")             val siblingRoms: List<SimpleRomSchema> = emptyList(),
    val files: List<RomFileSchema> = emptyList(),
    // ── Derived metadata ──────────────────────────────────────────────────────
    /**
     * Genres, companies, the aggregate rating and the rest.  The API nests all
     * of it under "metadatum"; none of these fields exist alongside [summary],
     * so reading them from anywhere else yields nothing.
     */
    val metadatum: RomMetadataSchema = RomMetadataSchema(),
)

// ── Firmware ──────────────────────────────────────────────────────────────────

@Serializable
data class FirmwareSchema(
    val id: Int,
    @SerialName("platform_id")   val platformId: Int,
    @SerialName("file_name")     val fileName: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    val crc_hash: String? = null,
    val md5_hash: String? = null,
)
