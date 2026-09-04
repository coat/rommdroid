package app.rommdroid.ui.navigation

/** All routes in the app. Sealed so the nav graph is exhaustive. */
sealed class Route(val path: String) {
    // Setup flow
    data object Setup : Route("setup")

    // Main nav
    data object PlatformList  : Route("platforms")
    data class  RomList(val platformId: Int = 0) : Route("platforms/{platformId}/roms") {
        companion object {
            const val TEMPLATE = "platforms/{platformId}/roms"
            const val ARG = "platformId"
            fun go(platformId: Int) = "platforms/$platformId/roms"
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
