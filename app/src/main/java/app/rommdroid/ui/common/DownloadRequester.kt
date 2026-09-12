package app.rommdroid.ui.common

import app.rommdroid.data.api.model.RomFileSchema
import app.rommdroid.data.api.model.RomSchema
import app.rommdroid.data.db.RomEntity
import app.rommdroid.data.download.DownloadQueue
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.data.download.asMessage
import app.rommdroid.util.RomGroup
import app.rommdroid.util.regionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "queue this" half of a ViewModel: fires the request, remembers which rows
 * are still being resolved, and phrases what came of it for a snackbar. Held by
 * composition rather than inherited, since a ViewModel's other state has
 * nothing to do with it.
 */
class DownloadRequester(
    private val queue: DownloadQueue,
    private val regionsOf: (RomEntity) -> List<String>,
    private val scope: CoroutineScope,
) {
    /** Keys of the rows with a request in flight; the detail fetch takes a moment. */
    private val _queueing = MutableStateFlow<Set<String>>(emptySet())
    val queueing: StateFlow<Set<String>> = _queueing.asStateFlow()

    private val _messages = MutableSharedFlow<QueueMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<QueueMessage> = _messages.asSharedFlow()

    /** Queue the preferred copy, the one the row shows. The message names its
     *  region so an unwanted pick is obvious enough to undo. */
    fun download(group: RomGroup) {
        if (group.key in _queueing.value) return
        scope.launch {
            _queueing.value += group.key
            try {
                val result = queue.enqueueRom(group.primary.id)
                _messages.emit(result.asMessage(regionSummary(regionsOf(group.primary))))
            } finally {
                _queueing.value -= group.key
            }
        }
    }

    /** Queue [files] of an already-loaded [rom]. */
    fun enqueue(rom: RomSchema, files: List<RomFileSchema>) {
        scope.launch { _messages.emit(queue.enqueue(rom, files).asMessage()) }
    }

    fun undo(ids: List<String>) {
        scope.launch { queue.undo(ids) }
    }
}
