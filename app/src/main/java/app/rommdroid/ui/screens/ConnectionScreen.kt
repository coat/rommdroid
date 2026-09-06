package app.rommdroid.ui.screens

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.rommdroid.data.repository.CredentialRepository
import app.rommdroid.data.repository.ServerConnector
import app.rommdroid.ui.components.InputKind
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.rememberInputFieldHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Editing an existing connection, as opposed to setting one up.
 *
 * The stored password is thrown away once setup has traded it for a client API
 * token, so there is nothing to pre-fill the password field with and nothing to
 * compare a new one against.  That splits the save into two cases: a server
 * address that moved keeps the token and only re-verifies, while a different
 * account — or a password that has since changed on the server — has to sign in
 * again for a fresh token.
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val credentials: CredentialRepository,
    private val connector: ServerConnector,
) : ViewModel() {

    /**
     * Read once, at construction: these seed the form, and re-reading them after
     * a save would swap what the fields are compared against mid-edit.
     */
    val initialServerUrl: String = credentials.serverUrl.orEmpty()
    val initialUsername: String  = credentials.username.orEmpty()

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private val _canSaveUnverified = MutableStateFlow(false)
    /**
     * True after an address-only save failed to reach the server.  A handheld is
     * often nowhere near the server when its address is being corrected, so the
     * new address can still be recorded — nothing else about the sign-in changes
     * and the app will simply fail to sync until it is right.
     */
    val canSaveUnverified: StateFlow<Boolean> = _canSaveUnverified.asStateFlow()

    fun save(serverUrl: String, username: String, password: String) {
        val user = username.trim()
        if (user.isBlank()) {
            _state.value = SetupState.Error("Enter a username")
            return
        }
        if (password.isBlank() && user != initialUsername) {
            _state.value = SetupState.Error("Enter the password for $user to sign in as that account")
            return
        }

        viewModelScope.launch {
            _state.value = SetupState.Loading
            // Same account means the token still stands and only the address
            // moved; a password means signing in again for a fresh one.
            val addressOnly = password.isBlank()
            val result = if (addressOnly) {
                connector.moveTo(serverUrl)
            } else {
                connector.signIn(serverUrl, user, password)
            }
            _canSaveUnverified.value = addressOnly && result.isFailure
            _state.value = result.fold(
                onSuccess = { SetupState.Done },
                onFailure = { SetupState.Error(it.message ?: "Connection failed") },
            )
        }
    }

    /** Takes the address as typed, having offered [canSaveUnverified]. */
    fun saveWithoutVerifying(serverUrl: String) {
        _state.value = connector.setServerUrl(serverUrl).fold(
            onSuccess = { SetupState.Done },
            onFailure = { SetupState.Error(it.message ?: "That is not a URL") },
        )
    }

    /** Clears a stale error once the user starts fixing the offending field. */
    fun clearError() {
        if (_state.value is SetupState.Error) _state.value = SetupState.Idle
        _canSaveUnverified.value = false
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val canSaveUnverified by viewModel.canSaveUnverified.collectAsState()

    var serverUrl by rememberSaveable { mutableStateOf(viewModel.initialServerUrl) }
    var username  by rememberSaveable { mutableStateOf(viewModel.initialUsername) }
    var password  by rememberSaveable { mutableStateOf("") }

    // "Next" on the keyboard walks down the form; focus search does not cross
    // the Compose/View boundary on its own, so each field is handed the next.
    val usernameField = rememberInputFieldHandle()
    val passwordField = rememberInputFieldHandle()

    val save = { viewModel.save(serverUrl, username, password) }
    val scrollState = rememberScrollState()

    LaunchedEffect(state) {
        if (state is SetupState.Done) onSaved()
        // The error, and the fallback it can offer, land below the button that
        // was just tapped — on a short screen that is off the bottom edge.
        if (state is SetupState.Error) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server & Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(24.dp),
        ) {
            OutlinedInputField(
                value         = serverUrl,
                onValueChange = { serverUrl = it; viewModel.clearError() },
                label         = "Server URL",
                placeholder   = "http://romm.local",
                inputKind     = InputKind.Uri,
                enabled       = state !is SetupState.Loading,
                imeAction     = EditorInfo.IME_ACTION_NEXT,
                onImeAction   = { usernameField.requestFocus() },
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedInputField(
                value         = username,
                onValueChange = { username = it; viewModel.clearError() },
                label         = "Username",
                enabled       = state !is SetupState.Loading,
                imeAction     = EditorInfo.IME_ACTION_NEXT,
                handle        = usernameField,
                onImeAction   = { passwordField.requestFocus() },
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedInputField(
                value         = password,
                onValueChange = { password = it; viewModel.clearError() },
                label         = "Password",
                inputKind     = InputKind.Password,
                enabled       = state !is SetupState.Loading,
                imeAction     = EditorInfo.IME_ACTION_DONE,
                handle        = passwordField,
                onImeAction   = { passwordField.hideKeyboard(); save() },
                supportingText = {
                    Text("Leave blank to keep the current sign-in. Enter it to sign in again — after changing your password, or to switch account.")
                },
                modifier      = Modifier.fillMaxWidth(),
            )

            if (state is SetupState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = (state as SetupState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick  = save,
                enabled  = state !is SetupState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is SetupState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Save")
                }
            }

            if (canSaveUnverified && state is SetupState.Error) {
                TextButton(
                    onClick  = { viewModel.saveWithoutVerifying(serverUrl) },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("Save address anyway") }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text  = "Verified before saving — if it fails, the current connection " +
                        "is kept. Downloads and folder mappings are unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
