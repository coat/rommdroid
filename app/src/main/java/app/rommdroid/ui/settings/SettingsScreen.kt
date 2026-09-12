package app.rommdroid.ui.settings

import android.net.Uri
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.domain.GamepadLayout
import app.rommdroid.ui.common.ConnectionState
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.components.InputKind
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.gamepad.rememberHasGamepad
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onFolderMapping: () -> Unit,
    onResetSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canSaveUnverified by viewModel.canSaveUnverified.collectAsStateWithLifecycle()
    val savedServerUrl by viewModel.savedServerUrl.collectAsStateWithLifecycle()
    val savedUsername by viewModel.savedUsername.collectAsStateWithLifecycle()
    val gamepadLayout by viewModel.gamepadLayout.collectAsStateWithLifecycle()

    var serverUrl by rememberSaveable { mutableStateOf(savedServerUrl) }
    var username  by rememberSaveable { mutableStateOf(savedUsername) }
    var password  by rememberSaveable { mutableStateOf("") }

    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // Every field ends in Done, not Next: the usual edit here is one field, and
    // in landscape the keyboard covers the app, so the action key is a way out.
    val save = { viewModel.save(serverUrl, username, password) }
    val dirty = serverUrl.trim() != savedServerUrl ||
        username.trim() != savedUsername ||
        password.isNotEmpty()
    val editable = state != ConnectionState.Loading

    val scrollState = rememberScrollState()

    LaunchedEffect(state) {
        // A save normalizes the URL, so re-seed from what was stored or the
        // field reads as unsaved.
        if (state == ConnectionState.Saved) {
            serverUrl = savedServerUrl
            username  = savedUsername
            password  = ""
        }
        // The error and its fallback land below the button, off a short screen.
        if (state is ConnectionState.Error) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val scope = rememberCoroutineScope()
    GamepadHandler { action ->
        when (action) {
            // Focusable stops are far apart here, so the triggers scroll.
            GamepadAction.PageUp -> {
                scope.launch { scrollState.animateScrollBy(-scrollState.viewportSize * 0.85f) }
                true
            }
            GamepadAction.PageDown -> {
                scope.launch { scrollState.animateScrollBy(scrollState.viewportSize * 0.85f) }
                true
            }
            // Select opened this page; pressing it again closes.
            GamepadAction.Settings -> { onBack(); true }
            else                   -> false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Select"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        // Column, not LazyColumn: a lazy list is the one container these fields
        // cannot live in, see the focusSearch note in OutlinedInputField.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
        ) {
            Text(
                text     = "Server & account",
                style    = MaterialTheme.typography.titleSmall,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )

            Column(Modifier.padding(16.dp)) {
                OutlinedInputField(
                    value         = serverUrl,
                    onValueChange = { serverUrl = it; viewModel.clearError() },
                    label         = "Server URL",
                    placeholder   = "http://romm.local",
                    inputKind     = InputKind.Uri,
                    enabled       = editable,
                    imeAction     = EditorInfo.IME_ACTION_DONE,
                    modifier      = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedInputField(
                    value         = username,
                    onValueChange = { username = it; viewModel.clearError() },
                    label         = "Username",
                    enabled       = editable,
                    imeAction     = EditorInfo.IME_ACTION_DONE,
                    modifier      = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedInputField(
                    value          = password,
                    onValueChange  = { password = it; viewModel.clearError() },
                    label          = "Password",
                    inputKind      = InputKind.Password,
                    enabled        = editable,
                    imeAction      = EditorInfo.IME_ACTION_DONE,
                    supportingText = {
                        Text("Only needed to switch account or after a password change.")
                    },
                    modifier       = Modifier.fillMaxWidth(),
                )

                when (val current = state) {
                    is ConnectionState.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = current.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    // Only while still true: a differing field means unsaved work.
                    ConnectionState.Saved -> if (!dirty) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text  = "Saved.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> Unit
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick  = save,
                    enabled  = dirty && editable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state == ConnectionState.Loading) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save")
                    }
                }

                if (canSaveUnverified && state is ConnectionState.Error) {
                    TextButton(
                        onClick  = { viewModel.saveWithoutVerifying(serverUrl) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) { Text("Save address anyway") }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text  = "Verified before saving - if it fails, the current connection " +
                            "is kept. Downloads and folder mappings are unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            ListItem(
                modifier          = Modifier.gamepadRow(onClick = onFolderMapping),
                headlineContent   = { Text("Folder Mapping") },
                supportingContent = { Text("Set your ROMs folder and per-platform overrides") },
                leadingContent    = { Icon(Icons.Default.FolderOpen, null) },
                trailingContent   = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
            )
            HorizontalDivider()

            // The lettering only shows in the hint bar, which draws only with a
            // controller: on a phone this section asks about the invisible.
            if (rememberHasGamepad()) {
                Text(
                    text     = "Controller",
                    style    = MaterialTheme.typography.titleSmall,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )

                Text(
                    // Named by what the buttons do, not by "Xbox"/"Nintendo": a
                    // handheld in its own Xbox mode is often silkscreened the
                    // Nintendo way, so the vendor's word cannot be trusted.
                    text  = "Which letters the button hints print. Pick whichever matches " +
                            "your handheld - this only changes the hints, never what the " +
                            "buttons do.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )

                GamepadLayoutChoice(
                    headline   = "A opens, B goes back",
                    supporting = "Xbox and PlayStation lettering",
                    selected   = gamepadLayout == GamepadLayout.Xbox,
                    onSelect   = { viewModel.setGamepadLayout(GamepadLayout.Xbox) },
                )
                GamepadLayoutChoice(
                    headline   = "B opens, A goes back",
                    supporting = "Nintendo lettering",
                    selected   = gamepadLayout == GamepadLayout.Nintendo,
                    onSelect   = { viewModel.setGamepadLayout(GamepadLayout.Nintendo) },
                )

                HorizontalDivider()
            }

            ListItem(
                modifier          = Modifier.gamepadRow(onClick = { showClearCacheDialog = true }),
                headlineContent   = { Text("Clear cached library") },
                supportingContent = {
                    Text("Re-fetch platforms and ROMs from the server")
                },
                leadingContent    = { Icon(Icons.Default.Refresh, null) },
            )
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))

            ListItem(
                modifier = Modifier.gamepadRow(onClick = { showDisconnectDialog = true }),
                headlineContent = {
                    Text(
                        "Disconnect / Change server",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                supportingContent = { Text("Clear credentials and return to setup") },
                leadingContent    = {
                    Icon(
                        Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
            HorizontalDivider()
        }
    }

    if (showDisconnectDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title   = { Text("Disconnect from RomM?") },
            text    = {
                Text(
                    "This will remove your saved credentials and server URL, and " +
                    "clear the cached library so the next server starts fresh. " +
                    "Downloaded files and folder mappings are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.disconnect()
                        showDisconnectDialog = false
                        onResetSetup()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title   = { Text("Clear cached library?") },
            text    = {
                Text(
                    "Platforms and ROMs will be fetched from the server again the " +
                    "next time you open the list. Downloaded files, folder mappings " +
                    "and your download history are not affected."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLibraryCache()
                        showClearCacheDialog = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** One of the two letterings, as a row a controller can walk onto. A row rather
 *  than a switch because neither choice is the "off" one. */
@Composable
private fun GamepadLayoutChoice(
    headline: String,
    supporting: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier          = Modifier.gamepadRow(onClick = onSelect),
        headlineContent   = { Text(headline) },
        supportingContent = { Text(supporting) },
        leadingContent    = {
            // Not clickable: the row is, and nesting gives a controller two
            // stops for one choice.
            RadioButton(selected = selected, onClick = null)
        },
    )
}
