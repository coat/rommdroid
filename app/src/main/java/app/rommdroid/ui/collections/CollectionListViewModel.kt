package app.rommdroid.ui.collections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.db.CollectionEntity
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.ui.common.SyncTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class CollectionListViewModel @Inject constructor(
    private val repo: RomRepository,
) : ViewModel() {

    val collections: StateFlow<List<CollectionEntity>> =
        repo.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sync = SyncTracker()

    init {
        refresh()
    }

    /** The platform list already synced these on the way in, so this is for a
     *  collection changed while the app was open, and for retrying a failure. */
    fun refresh() {
        viewModelScope.launch { sync.run { repo.syncCollections() } }
    }

    fun coverUrl(collection: CollectionEntity): String? = repo.coverUrl(collection)
}
