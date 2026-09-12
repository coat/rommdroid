package app.rommdroid.ui.platforms

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.db.PlatformEntity
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.common.SyncTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class PlatformListViewModel @Inject constructor(
    private val repo: RomRepository,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformEntity>> =
        repo.observePlatforms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many collections the cache holds. The pinned row draws only when this
     *  is non-zero, so a server with none gets no row to an empty screen. */
    val collectionCount: StateFlow<Int> =
        repo.observeCollectionCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val sync = SyncTracker()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            sync.run {
                // One refresh, two fetches: a server too old to serve
                // collections, or a token minted before this app asked for
                // collections.read, must not take the platform list down with
                // it. When both fail the platforms' failure is the one reported.
                val platforms = runCatching { repo.syncPlatforms() }
                val collections = runCatching { repo.syncCollections() }
                (platforms.exceptionOrNull() ?: collections.exceptionOrNull())?.let { throw it }
            }
        }
    }

    fun coverUrl(platform: PlatformEntity): String? = repo.coverUrl(platform)
}
