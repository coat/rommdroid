package app.rommdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.GamepadLayoutRepository
import app.rommdroid.data.repository.RomRepository
import app.rommdroid.data.repository.ServerConnector
import app.rommdroid.domain.GamepadLayout
import app.rommdroid.ui.common.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Settings, including the server address and the signed-in account.
 *
 * Setup trades the password for a client API token and throws it away, so there
 * is nothing to pre-fill or compare against. Hence two save paths: an address
 * that moved keeps the token and re-verifies, a different account or changed
 * password signs in again for a fresh one.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentials: CredentialRepository,
    private val connector: ServerConnector,
    private val repo: RomRepository,
    private val buttonLayout: GamepadLayoutRepository,
) : ViewModel() {

    /** Which lettering the hint bars print; see [GamepadLayout]. */
    val gamepadLayout: StateFlow<GamepadLayout> = buttonLayout.layout

    fun setGamepadLayout(layout: GamepadLayout) = buttonLayout.set(layout)

    private val _savedServerUrl = MutableStateFlow(credentials.serverUrl.orEmpty())
    /** What is stored right now: seeds the field and decides whether there is
     *  anything to save. Re-read after each save, so the field ends up holding
     *  the normalized form that was actually written. */
    val savedServerUrl: StateFlow<String> = _savedServerUrl.asStateFlow()

    private val _savedUsername = MutableStateFlow(credentials.username.orEmpty())
    val savedUsername: StateFlow<String> = _savedUsername.asStateFlow()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _canSaveUnverified = MutableStateFlow(false)
    /** True after an address-only save failed to reach the server. A handheld is
     *  rarely near it while the address is being corrected, and recording an
     *  address that turns out wrong only costs a failed sync. */
    val canSaveUnverified: StateFlow<Boolean> = _canSaveUnverified.asStateFlow()

    fun save(serverUrl: String, username: String, password: String) {
        val user = username.trim()
        if (user.isBlank()) {
            _state.value = ConnectionState.Error("Enter a username")
            return
        }
        if (password.isBlank() && user != _savedUsername.value) {
            _state.value =
                ConnectionState.Error("Enter the password for $user to sign in as that account")
            return
        }

        viewModelScope.launch {
            _state.value = ConnectionState.Loading
            // No password means the token still stands and only the address moved.
            val addressOnly = password.isBlank()
            val result = if (addressOnly) {
                connector.moveTo(serverUrl)
            } else {
                connector.signIn(serverUrl, user, password)
            }
            _canSaveUnverified.value = addressOnly && result.isFailure
            _state.value = result.fold(
                onSuccess = { saved() },
                onFailure = { ConnectionState.Error(it.message ?: "Connection failed") },
            )
        }
    }

    /** Takes the address as typed, having offered [canSaveUnverified]. */
    fun saveWithoutVerifying(serverUrl: String) {
        _state.value = connector.setServerUrl(serverUrl).fold(
            onSuccess = { saved() },
            onFailure = { ConnectionState.Error(it.message ?: "That is not a URL") },
        )
    }

    /**
     * Clears a stale error once the user starts fixing the field. Leaves
     * [ConnectionState.Saved] standing on purpose: a save rewrites the URL field
     * with the normalized form, and that write arrives through onValueChange
     * like a keystroke, so clearing here would wipe the confirmation.
     */
    fun clearError() {
        if (_state.value is ConnectionState.Error) _state.value = ConnectionState.Idle
        _canSaveUnverified.value = false
    }

    /** Clears credentials and the cached library. The cache goes because
     *  disconnecting is how the app is pointed at another server, whose ids
     *  would otherwise collide with the old server's rows. */
    fun disconnect() {
        credentials.clearAll()
        viewModelScope.launch {
            // Confirming navigates to setup with `popUpTo(0)`, clearing this
            // scope mid-wipe; NonCancellable stops that leaving half a library.
            withContext(NonCancellable) { repo.clearLibraryCache() }
        }
    }

    /** Escape hatch for a cache a re-sync will not fix. Downloaded files, folder
     *  mappings and the queue are untouched. */
    fun clearLibraryCache() {
        viewModelScope.launch { repo.clearLibraryCache() }
    }

    private fun saved(): ConnectionState {
        _savedServerUrl.value = credentials.serverUrl.orEmpty()
        _savedUsername.value = credentials.username.orEmpty()
        return ConnectionState.Saved
    }
}
