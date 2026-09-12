package app.rommdroid.ui.common

/** Where a sign-in or server change has got to. First-run setup treats
 *  [Saved] as "done, leave"; Settings reads it in place. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Loading : ConnectionState
    data class Error(val message: String) : ConnectionState
    data object Saved : ConnectionState
}
