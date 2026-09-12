package app.rommdroid.ui.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.download.DownloadItem
import app.rommdroid.data.download.DownloadQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val queue: DownloadQueue,
) : ViewModel() {

    val items: StateFlow<List<DownloadItem>> = queue.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(id: String)  { viewModelScope.launch { queue.cancel(id) } }
    fun retry(id: String)   { viewModelScope.launch { queue.retry(id) } }
    fun remove(id: String)  { viewModelScope.launch { queue.remove(id) } }
    fun clearFinished()     { viewModelScope.launch { queue.clearFinished() } }
}
