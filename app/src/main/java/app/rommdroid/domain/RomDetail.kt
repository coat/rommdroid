package app.rommdroid.domain

/**
 * One ROM as the detail screen and the download queue see it: the server's
 * schema reduced to what they read, with the server-relative artwork path
 * already resolved and the file list already filled in.
 */
data class RomDetail(
    val id: Int,
    val platformId: Int,
    val platformDisplayName: String,
    /** The matched title; null when RomM never identified the ROM. */
    val name: String?,
    val fsName: String,
    val fsNameNoTags: String,
    val fsSizeBytes: Long,
    val summary: String?,
    /** Canonical region codes, from the server or the filename tags. */
    val regions: List<String>,
    val coverUrl: String?,
    /** Aggregate score out of 100, when any provider scored the game. */
    val rating: Double?,
    /** Never empty: the API omits the list for a single-file ROM, and then the
     *  one file is the ROM itself. */
    val files: List<RomFile>,
    /** The server's other copies of the same game. Trimmed to ids and names,
     *  so a sibling's size may be 0, meaning unknown. */
    val siblings: List<RomVariant>,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags
}

/** One downloadable file of a ROM. [id] is 0 for the synthesised single file,
 *  which the API serves by name rather than by file id. */
data class RomFile(
    val id: Int,
    val fileName: String,
    val sizeBytes: Long,
)
