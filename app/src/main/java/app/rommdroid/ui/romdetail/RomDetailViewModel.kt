package app.rommdroid.ui.romdetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.FolderContents
import app.rommdroid.data.download.LocalRomIndex
import app.rommdroid.data.repository.DownloadTarget
import app.rommdroid.data.repository.DownloadTargetRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.domain.regionPreference
import app.rommdroid.domain.regionRank
import app.rommdroid.domain.RomDetail
import app.rommdroid.domain.RomFile
import app.rommdroid.domain.RomVariant
import app.rommdroid.ui.common.DownloadRequester
import app.rommdroid.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface RomDetailState {
    data object Loading : RomDetailState
    data class  Loaded(val rom: RomDetail) : RomDetailState
    data class  Error(val message: String) : RomDetailState
}

@HiltViewModel
class RomDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: RomRepository,
    private val downloadTargets: DownloadTargetRepository,
    private val queue: DownloadQueue,
    private val localRoms: LocalRomIndex,
) : ViewModel() {

    /** Queues files and phrases the outcome; [cancel] stays with the queue. */
    val requests = DownloadRequester(queue, repo::regionsOf, viewModelScope)

    /** The variant currently being shown; changes when the user picks another. */
    private val _romId = MutableStateFlow(savedStateHandle.toRoute<Route.RomDetail>().romId)
    val romId: StateFlow<Int> = _romId.asStateFlow()

    private val _state = MutableStateFlow<RomDetailState>(RomDetailState.Loading)
    val state: StateFlow<RomDetailState> = _state.asStateFlow()

    /** Every regional copy, preferred region first. A single entry for a game
     *  that exists once, and the picker stays hidden then. */
    private val _variants = MutableStateFlow<List<RomVariant>>(emptyList())
    val variants: StateFlow<List<RomVariant>> = _variants.asStateFlow()

    private val _target = MutableStateFlow<DownloadTarget?>(null)
    val target: StateFlow<DownloadTarget?> = _target.asStateFlow()

    /** Live state of every download queued for this ROM, keyed by file id. */
    val downloads: StateFlow<Map<Int, DownloadItem>> =
        _romId.flatMapLatest { id ->
            queue.items.map { items -> items.filter { it.romId == id }.associateBy { it.fileId } }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** What the destination folder holds. Read from the folder rather than
     *  inferred from [downloads], so a ROM copied from a PC still counts. */
    val onDevice: StateFlow<FolderContents> =
        combine(_target, localRoms.revision) { target, _ -> target }
            .mapLatest { target -> target?.let { localRoms.listing(it) } ?: FolderContents.Unreadable }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderContents.Unreadable)

    /** True while a variant is being fetched over an already-rendered screen. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var loadJob: Job? = null

    init { load(_romId.value) }

    /** Show a different regional copy of the same game. */
    fun selectVariant(id: Int) {
        if (id == _romId.value) return
        _romId.value = id
        load(id)
    }

    private fun load(id: Int) {
        // Tapping down a long variant list must not leave earlier fetches
        // racing to overwrite where the user landed.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Keep the current detail rather than collapsing to a spinner and
            // losing the picker mid-tap.
            if (_state.value !is RomDetailState.Loaded) {
                _state.value = RomDetailState.Loading
            }
            _refreshing.value = true
            try {
                val rom = repo.getRomDetail(id)
                _state.value = RomDetailState.Loaded(rom)
                _target.value = repo.getPlatform(rom.platformId)
                    ?.let { downloadTargets.resolve(it) }
                _variants.value = variantsOf(rom)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RomDetail", "Failed to load ROM $id", e)
                _state.value = RomDetailState.Error(e.message ?: "Failed to load ROM")
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** The server's sibling list, falling back to the cache. That way round
     *  because a ROM reached from search may be from an unsynced platform. */
    private suspend fun variantsOf(rom: RomDetail): List<RomVariant> {
        val preference = regionPreference(Locale.getDefault().country)
        // `sibling_roms` is trimmed to ids and names, so a sibling rendered
        // straight from it is a blank row reading "0 B". The cache has the rest
        // whenever the sibling's platform has been synced.
        val cached = repo.getCachedRoms(rom.siblings.map { it.id })
        val fromServer = buildList {
            add(RomVariant(rom.id, rom.fsName, rom.fsSizeBytes, rom.regions))
            rom.siblings.forEach { sibling ->
                val entity = cached[sibling.id]
                add(
                    if (entity != null) RomVariant(entity.id, entity.fsName, entity.fsSizeBytes, repo.regionsOf(entity))
                    else sibling
                )
            }
        }
        val variants = if (fromServer.size > 1) {
            fromServer
        } else {
            val cached = repo.getCachedRom(rom.id)?.let { repo.cachedVariants(it) }.orEmpty()
            if (cached.size > 1) {
                cached.map { RomVariant(it.id, it.fsName, it.fsSizeBytes, repo.regionsOf(it)) }
            } else {
                fromServer
            }
        }
        return variants
            .distinctBy { it.id }
            .sortedWith(compareBy({ regionRank(it.regions, preference) }, { it.fsName }))
    }

    /** Queue [file]; the queue itself reports what came of it. */
    fun downloadFile(file: RomFile) {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return
        requests.enqueue(rom, listOf(file))
    }

    /** Queue every file of the ROM - the whole set for a multi-disc game. */
    fun downloadAll() {
        val rom = (_state.value as? RomDetailState.Loaded)?.rom ?: return
        requests.enqueue(rom, rom.files)
    }

    fun cancel(id: String) {
        viewModelScope.launch { queue.cancel(id) }
    }
}
