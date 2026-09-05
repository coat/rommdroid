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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import app.rommdroid.data.api.RomMApi
import app.rommdroid.data.repository.CredentialRepository
import android.view.inputmethod.EditorInfo
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
    private val credentials: CredentialRepository,
    private val api: RomMApi,
) : ViewModel() {

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun connect(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _state.value = SetupState.Loading
            try {
                // Store server URL and basic credentials so interceptors work
                credentials.serverUrl = serverUrl.trimEnd('/')
                credentials.setBasicCredentials(username, password)

                // Verify server is reachable (Basic auth is attached by AuthInterceptor)
                api.heartbeat()

                // Create a client token — Basic auth is used for this request
                val tokenResp = api.createClientToken(
                    app.rommdroid.data.api.model.CreateTokenRequest(name = "RomMDroid")
                )
                // raw_token is present only on creation; save it and drop the password
                if (tokenResp.rawToken != null) {
                    credentials.apiToken = tokenResp.rawToken
                    credentials.clearPassword()
                }
                // Even if token creation failed (e.g. permissions), we can still proceed
                // using Basic auth for now — the token will be null and AuthInterceptor
                // falls back to Basic automatically.

                _state.value = SetupState.Done
            } catch (e: Exception) {
                // Clean up partial state so the user can retry cleanly
                credentials.serverUrl = null
                credentials.clearAll()
                _state.value = SetupState.Error(e.describe())
            }
        }
    }
}

/**
 * A bare Retrofit [HttpException] only says "HTTP 422 Unprocessable Content",
 * which hides the reason the server gave.  RomM puts that in a JSON "detail"
 * field, so pull it out when it's there.
 */
private fun Exception.describe(): String {
    val fallback = message ?: "Connection failed"
    if (this !is HttpException) return fallback
    val body = response()?.errorBody()?.string().orEmpty()
    val detail = runCatching {
        Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .jsonObject["detail"]
            ?.let { if (it is JsonPrimitive) it.content else it.toString() }
    }.getOrNull()
    return if (detail.isNullOrBlank()) fallback else "$fallback: $detail"
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
