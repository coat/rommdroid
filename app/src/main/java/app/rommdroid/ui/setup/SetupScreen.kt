package app.rommdroid.ui.setup

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.ui.common.ConnectionState
import app.rommdroid.ui.gamepad.focusOutline
import app.rommdroid.ui.components.InputKind
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.rememberInputFieldHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onComplete: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var serverUrl by rememberSaveable { mutableStateOf("http://") }
    var username  by rememberSaveable { mutableStateOf("") }
    var password  by rememberSaveable { mutableStateOf("") }

    // Android's focus search does not cross the Compose/View boundary, so each
    // field is handed the next one explicitly for the keyboard's "Next".
    val usernameField = rememberInputFieldHandle()
    val passwordField = rememberInputFieldHandle()

    LaunchedEffect(state) {
        if (state is ConnectionState.Saved) onComplete()
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

            (state as? ConnectionState.Error)?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = error.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick  = { viewModel.connect(serverUrl, username, password) },
                enabled  = state !is ConnectionState.Loading,
                modifier = Modifier.fillMaxWidth().focusOutline(),
            ) {
                if (state is ConnectionState.Loading) {
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
