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
    /** At least one scope required by the API. Request all read scopes for a companion app. */
    val scopes: List<String> = listOf(
        "roms.read", "platforms.read", "collections.read",
        "firmware.read", "me.read", "tasks.read",
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
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
)

@Serializable
data class SimpleRomSchema(
    val id: Int,
    @SerialName("platform_id")              val platformId: Int,
    @SerialName("platform_slug")            val platformSlug: String,
    @SerialName("platform_display_name")    val platformDisplayName: String,
    @SerialName("fs_name")                  val fsName: String,
    @SerialName("fs_name_no_tags")          val fsNameNoTags: String,
    @SerialName("fs_name_no_ext")           val fsNameNoExt: String,
    @SerialName("fs_extension")             val fsExtension: String,
    @SerialName("fs_size_bytes")            val fsSizeBytes: Long = 0L,
    val name: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("url_cover")                val urlCover: String? = null,
    @SerialName("path_cover_small")         val pathCoverSmall: String? = null,
    @SerialName("path_cover_large")         val pathCoverLarge: String? = null,
    @SerialName("sibling_roms")             val siblingRoms: List<SimpleRomSchema> = emptyList(),
    val files: List<RomFileSchema> = emptyList(),
    @SerialName("updated_at")               val updatedAt: String? = null,
)

@Serializable
data class DetailedRomSchema(
    val id: Int,
    @SerialName("platform_id")              val platformId: Int,
    @SerialName("platform_slug")            val platformSlug: String,
    @SerialName("platform_display_name")    val platformDisplayName: String,
    @SerialName("fs_name")                  val fsName: String,
    @SerialName("fs_name_no_tags")          val fsNameNoTags: String,
    @SerialName("fs_name_no_ext")           val fsNameNoExt: String,
    @SerialName("fs_extension")             val fsExtension: String,
    @SerialName("fs_size_bytes")            val fsSizeBytes: Long = 0L,
    val name: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("url_cover")                val urlCover: String? = null,
    @SerialName("path_cover_small")         val pathCoverSmall: String? = null,
    @SerialName("path_cover_large")         val pathCoverLarge: String? = null,
    @SerialName("sibling_roms")             val siblingRoms: List<SimpleRomSchema> = emptyList(),
    val files: List<RomFileSchema> = emptyList(),
    val genres: List<String> = emptyList(),
    val franchises: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    @SerialName("updated_at")               val updatedAt: String? = null,
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
