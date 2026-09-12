package app.rommdroid.ui.romlist

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.db.DownloadStatus
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.FolderContents
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomListPreferencesRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.domain.countRegions
import app.rommdroid.domain.displayName
import app.rommdroid.domain.groupRoms
import app.rommdroid.domain.keepRegions
import app.rommdroid.domain.RegionCount
import app.rommdroid.domain.regionPreference
import app.rommdroid.domain.RomGroup
import app.rommdroid.domain.RomSection
import app.rommdroid.domain.RomSort
import app.rommdroid.domain.RomSortKey
import app.rommdroid.domain.sectionsOf
import app.rommdroid.domain.sortGroups
import app.rommdroid.ui.common.DownloadRequester
import app.rommdroid.ui.common.SyncTracker
import app.rommdroid.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class RomListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    queue: DownloadQueue,
    private val downloadTargets: DownloadTargetRepository,
    private val localRoms: LocalRomIndex,
    private val listPrefs: RomListPreferencesRepository,
) : ViewModel() {

    /** What this list is a list of. The screen is identical either way; the two
     *  part company only over which ROMs, what a refresh fetches, and which
     *  folders to check. */
    private sealed interface Source {
        data class Platform(val id: Int) : Source
        data class Collection(val id: Int) : Source
    }

    private val source: Source =
        savedStateHandle.get<Int>(Route.RomList.ARG)?.let(Source::Platform)
            ?: Source.Collection(checkNotNull(savedStateHandle[Route.CollectionRoms.ARG]))

    private val roms: Flow<List<RomEntity>> =
        when (source) {
            is Source.Platform   -> repo.observeRoms(source.id)
            is Source.Collection -> repo.observeCollectionRoms(source.id)
        }
            // Shared: the fold and the region chips both read it.
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which regions the list is cut down to; empty shows every ROM. Shared
     *  with every other list, and kept across launches. */
    val regionFilter: StateFlow<Set<String>> = listPrefs.regions

    val sort: StateFlow<RomSort> = listPrefs.sort

    /** The regions on offer, counted over the whole list rather than the
     *  filtered one so a chip does not vanish the moment it is picked. A
     *  region the user chose elsewhere stays on the row at zero, or there
     *  would be no way to see why the list is short, or to turn it off. */
    val regions: StateFlow<List<RegionCount>> = combine(roms, regionFilter) { roms, selected ->
        val present = countRegions(roms, repo::regionsOf)
        val missing = selected - present.map { it.region }.toSet()
        present + missing.sorted().map { RegionCount(it, 0) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Regional copies fold into one row: a No-Intro set lists three nearly
     *  identical names. The variants stay reachable from the detail screen.
     *  The region filter runs first, so a filtered row leads with, and
     *  long-press downloads, a copy from the chosen region. */
    private val groups: StateFlow<List<RomGroup>> = combine(roms, regionFilter) { roms, selected ->
        groupRoms(
            roms             = keepRegions(roms, selected, repo::regionsOf),
            preferredRegions = regionPreference(Locale.getDefault().country),
            regionsOf        = repo::regionsOf,
        )
    }
        // A few thousand ROMs, refolded every time the sync writes a page.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Text typed into the list's filter field; blank shows the whole platform. */
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    fun setFilter(text: String) { _filter.value = text }

    /** Pick a key, or flip the one already picked: a second press on "Rating"
     *  is the only way to ask for the worst-rated first. */
    fun sortBy(key: RomSortKey) {
        val current = sort.value
        listPrefs.setSort(if (current.key == key) current.reversed() else RomSort.of(key))
    }

    fun toggleRegion(region: String) {
        val current = regionFilter.value
        listPrefs.setRegions(if (region in current) current - region else current + region)
    }

    fun clearRegions() { listPrefs.setRegions(emptySet()) }

    /**
     * The rows as drawn: filtered, sorted, then cut into letter runs. A filtered
     * list comes back as one unlabelled run, since headers only earn their
     * space over hundreds of rows; so does any order but name, where a letter
     * says nothing about where a row is.
     */
    val sections: StateFlow<List<RomSection>> = combine(groups, _filter, sort) { rows, filter, sort ->
        val needle  = filter.trim()
        val matches = if (needle.isEmpty()) rows else rows.filter { it.matches(needle) }
        val ordered = sortGroups(matches, sort)
        when {
            ordered.isEmpty()                                 -> emptyList()
            needle.isEmpty() && sort.key == RomSortKey.Name  -> sectionsOf(ordered)
            else -> listOf(RomSection(label = null, groups = ordered))
        }
    }
        // Same reason as the fold above, and this reruns on every keystroke.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Download state per ROM id, so a row can show what the user already has. */
    val downloadStatus: StateFlow<Map<Int, DownloadStatus>> = queue.statusByRom
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What the folders behind this list hold, keyed by platform id. A map
     *  rather than one [FolderContents] because a collection spans platforms. */
    val onDevice: StateFlow<Map<Int, FolderContents>> =
        when (source) {
            is Source.Platform -> localRoms.revision.map { listOf(source.id) }
            // Only the platforms the rows belong to: resolving the whole library
            // would read a directory per system for a dozen games.
            is Source.Collection -> combine(localRoms.revision, groups) { _, rows ->
                rows.flatMap { group -> group.variants.map { it.platformId } }.distinct()
            }
        }
            // The sync pages the ROMs in, but the platform set settles early.
            .distinctUntilChanged()
            .mapLatest { platformIds ->
                platformIds.mapNotNull { id ->
                    val platform = repo.getPlatform(id) ?: return@mapNotNull null
                    val target   = downloadTargets.resolve(platform) ?: return@mapNotNull null
                    id to localRoms.listing(target)
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What the bar says this list is. Named rather than a flat "ROMs": a
     *  collection is two levels down and nothing else names it. */
    val title: StateFlow<String> = flow {
        val name = when (source) {
            is Source.Platform   -> repo.getPlatform(source.id)?.displayName
            is Source.Collection -> repo.getCollection(source.id)?.name
        }
        emit(name ?: "ROMs")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "ROMs")

    /** True when a row should name its platform - only a collection mixes them. */
    val mixedPlatforms: Boolean = source is Source.Collection

    /** Long-press, or X, on a row. */
    val downloads = DownloadRequester(queue, repo::regionsOf, viewModelScope)

    val sync = SyncTracker()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            sync.run {
                when (source) {
                    is Source.Platform   -> repo.syncRoms(source.id)
                    is Source.Collection -> repo.syncCollectionRoms(source.id)
                }
            }
        }
    }

    fun coverUrl(rom: RomEntity): String? = repo.coverUrl(rom)
}



/** Matches the title or the filename: someone typing "sonic2" is naming the
 *  file, not the game. */
private fun RomGroup.matches(needle: String): Boolean =
    primary.displayName.contains(needle, ignoreCase = true) ||
        primary.fsNameNoTags.contains(needle, ignoreCase = true)
