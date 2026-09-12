package app.rommdroid.data.db

import androidx.room.*

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val slug: String,
    val fsSlug: String,
    val displayName: String,
    val romCount: Int,
    val urlLogo: String?,
    val updatedAt: String?,
)

@Entity(
    tableName = "roms",
    indices = [Index("platformId"), Index("name"), Index("groupKey")],
)
data class RomEntity(
    @PrimaryKey val id: Int,
    val platformId: Int,
    val platformSlug: String,
    val platformDisplayName: String,
    val fsName: String,
    val fsNameNoTags: String,
    val fsExtension: String,
    val fsSizeBytes: Long,
    val name: String?,
    val slug: String?,
    val summary: String?,
    /** As the server sent them; [app.rommdroid.domain.regionsFor] canonicalises. */
    val regions: List<String>,
    val languages: List<String>,
    val tags: List<String>,
    val urlCover: String?,
    val pathCoverSmall: String?,
    val pathCoverLarge: String?,
    val updatedAt: String?,
    /** Aggregate score out of 100; null when no provider scored the game. */
    val averageRating: Double? = null,
    /** First release, as the metadata provider stamps it; null when the game
     *  was never identified. Only ever compared, so the unit does not matter. */
    val firstReleaseDate: Long? = null,
    /** ISO-8601 stamp of when the server first saw the file. Kept as text: the
     *  server formats every stamp the same way, so text order is time order. */
    val createdAt: String? = null,
    /** [app.rommdroid.domain.romGroupKey]. Stored rather than computed on read so
     *  siblings resolve to an indexed query. */
    val groupKey: String = "",
)

/** A user-made collection. Its ROMs live in [CollectionRomEntity]. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val description: String,
    /** What the server says it holds, so the list can say "23 games" before the
     *  membership rows have ever been fetched. */
    val romCount: Int,
    val pathCoverSmall: String?,
    val pathCoverLarge: String?,
    val urlCover: String?,
    val isFavorite: Boolean,
    val isPublic: Boolean,
    val ownerUsername: String,
    val updatedAt: String?,
)

/**
 * Which ROMs are in which collection. A join table so the list screen can
 * observe an indexed query. Rows outlive the ROMs they name, since a platform
 * sync deletes and rebuilds them, so every read joins against `roms`.
 */
@Entity(
    tableName = "collection_roms",
    primaryKeys = ["collectionId", "romId"],
    indices = [Index("romId")],
)
data class CollectionRomEntity(
    val collectionId: Int,
    val romId: Int,
)
