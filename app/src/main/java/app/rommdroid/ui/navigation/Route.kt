package app.rommdroid.ui.navigation

/** Sealed so the nav graph is exhaustive. */
sealed class Route(val path: String) {
    data object Setup : Route("setup")

    data object PlatformList  : Route("platforms")
    data class  RomList(val platformId: Int = 0) : Route("platforms/{platformId}/roms") {
        companion object {
            const val TEMPLATE = "platforms/{platformId}/roms"
            const val ARG = "platformId"
            fun go(platformId: Int) = "platforms/$platformId/roms"
        }
    }
    /** Reached from the row pinned above the platforms: one flat list, then
     *  straight into the ROMs. */
    data object CollectionList : Route("collections")
    data class  CollectionRoms(val collectionId: Int = 0) : Route("collections/{collectionId}/roms") {
        companion object {
            const val TEMPLATE = "collections/{collectionId}/roms"
            const val ARG = "collectionId"
            fun go(collectionId: Int) = "collections/$collectionId/roms"
        }
    }
    data class  RomDetail(val romId: Int = 0) : Route("roms/{romId}") {
        companion object {
            const val TEMPLATE = "roms/{romId}"
            const val ARG = "romId"
            fun go(romId: Int) = "roms/$romId"
        }
    }
    data object Downloads     : Route("downloads")
    data object Settings      : Route("settings")
    data object FolderMapping : Route("settings/folders")
    data object Search        : Route("search")
}
