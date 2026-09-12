package app.rommdroid.ui.navigation

import kotlinx.serialization.Serializable

/** Every destination, as the typed routes Navigation Compose 2.8 takes. A
 *  ViewModel reads its arguments back with `savedStateHandle.toRoute<T>()`. */
sealed interface Route {
    @Serializable data object Setup : Route

    @Serializable data object PlatformList : Route

    /** One screen for a platform's ROMs and a collection's: only what it
     *  lists, what a refresh fetches and which folders it checks differ. */
    @Serializable data class RomList(val source: Source, val id: Int) : Route {
        enum class Source { Platform, Collection }

        companion object {
            fun platform(id: Int) = RomList(Source.Platform, id)
            fun collection(id: Int) = RomList(Source.Collection, id)
        }
    }

    /** Reached from the row pinned above the platforms: one flat list, then
     *  straight into the ROMs. */
    @Serializable data object CollectionList : Route

    @Serializable data class RomDetail(val romId: Int) : Route

    @Serializable data object Downloads : Route
    @Serializable data object Settings : Route
    @Serializable data object FolderMapping : Route
    @Serializable data object Search : Route
}
