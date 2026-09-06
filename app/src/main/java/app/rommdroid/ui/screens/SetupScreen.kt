package app.rommdroid.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.view.inputmethod.EditorInfo
import app.rommdroid.data.repository.ServerConnector
import app.rommdroid.ui.components.InputKind
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.rememberInputFieldHandle
import javax.inject.Inject

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed interface SetupState {
    data object Idle : SetupState
    data object Loading : SetupState
    data class Error(val message: String) : SetupState
    data object Done : SetupState
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val connector: ServerConnector,
) : ViewModel() {

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _state.value = SetupState.Loading
            _state.value = connector.signIn(serverUrl, username, password).fold(
                onSuccess = { SetupState.Done },
                onFailure = { SetupState.Error(it.message ?: "Connection failed") },
            )
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    var serverUrl by remember { mutableStateOf("http://") }
    var username  by remember { mutableStateOf("") }
    var password  by remember { mutableStateOf("") }

    // "Next" on the keyboard walks down the form.  Android's focus search does
    // not cross the Compose/View boundary between these fields, so hand each one
    // the next field explicitly.
    val usernameField = rememberInputFieldHandle()
    val passwordField = rememberInputFieldHandle()

    LaunchedEffect(state) {
        if (state is SetupState.Done) onComplete()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text  = "Connect to RomM",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(32.dp))

            OutlinedInputField(
                value         = serverUrl,
                onValueChange = { serverUrl = it },
                label         = "Server URL",
                placeholder   = "http://romm.local",
                inputKind     = InputKind.Uri,
                imeAction     = EditorInfo.IME_ACTION_NEXT,
                onImeAction   = { usernameField.requestFocus() },
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedInputField(
                value         = username,
                onValueChange = { username = it },
                label         = "Username",
                imeAction     = EditorInfo.IME_ACTION_NEXT,
                handle        = usernameField,
                onImeAction   = { passwordField.requestFocus() },
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedInputField(
                value         = password,
                onValueChange = { password = it },
                label         = "Password",
                inputKind     = InputKind.Password,
                imeAction     = EditorInfo.IME_ACTION_DONE,
                handle        = passwordField,
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
                onClick  = { viewModel.connect(serverUrl, username, password) },
                enabled  = state !is SetupState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state is SetupState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
