package app.rommdroid.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Auth / Setup ──────────────────────────────────────────────────────────────

@Serializable
data class HeartbeatResponse(
    val version: String,
    @SerialName("show_setup_wizard") val showSetupWizard: Boolean = false,
)

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
)

@Serializable
data class ClientTokenResponse(
    val id: Int,
    val name: String,
    /** The actual rmm_… token value; only present on create / exchange. */
    val token: String? = null,
)

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
