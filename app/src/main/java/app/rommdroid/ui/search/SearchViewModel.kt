package app.rommdroid.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.domain.groupRoms
import app.rommdroid.domain.regionPreference
import app.rommdroid.domain.RomGroup
import app.rommdroid.ui.common.DownloadRequester
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.FlowPreview

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: RomRepository,
    queue: DownloadQueue,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(text: String) { _query.value = text }

    private val _offline = MutableStateFlow(false)
    /** True when the last search fell back to the (partial) local cache. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    val results: StateFlow<List<RomGroup>> = _query
        .debounce(300)
        .mapLatest { q ->
            if (q.length < 2) return@mapLatest emptyList()
            // The server covers every platform; Room covers only synced ones.
            val roms = try {
                repo.searchRemote(q).also { _offline.value = false }
            } catch (e: Exception) {
                _offline.value = true
                repo.searchLocal(q)
            }
            // Same fold as the platform list, but the key carries the platform
            // id so cross-platform hits stay separate rows.
            groupRoms(
                roms             = roms,
                preferredRegions = regionPreference(Locale.getDefault().country),
                regionsOf        = repo::regionsOf,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Same long-press gesture as the ROM list: queue the copy the row shows. */
    val downloads = DownloadRequester(queue, repo::regionsOf, viewModelScope)
}
