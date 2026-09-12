package app.rommdroid.ui.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether a refresh is in flight and how the last one ended, for the list
 *  screens' progress bar and retry snackbar. */
class SyncTracker {
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Run [block] as one refresh. A thrown exception becomes [error]; the
     *  scope's own cancellation passes through untouched. */
    suspend fun run(block: suspend () -> Unit) {
        _syncing.value = true
        _error.value = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
        } finally {
            _syncing.value = false
        }
    }
}
