package app.rommdroid.ui.search

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.rommdroid.domain.regionSummary
import app.rommdroid.ui.common.QueueSnackbarEffect
import app.rommdroid.ui.common.takeOffer
import app.rommdroid.ui.components.BackButton
import app.rommdroid.ui.gamepad.GamepadAction
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.GamepadHandler
import app.rommdroid.ui.gamepad.GamepadHint
import app.rommdroid.ui.gamepad.GamepadHintBar
import app.rommdroid.ui.gamepad.gamepadRow
import app.rommdroid.ui.gamepad.ListGamepadScrolling
import app.rommdroid.ui.components.OutlinedInputField
import app.rommdroid.ui.components.rememberInputFieldHandle
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onRomClick: (Int) -> Unit,
    onFolderSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val query    by viewModel.query.collectAsState()
    val results  by viewModel.results.collectAsState()
    val offline  by viewModel.offline.collectAsState()
    val queueing by viewModel.downloads.queueing.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val queryField = rememberInputFieldHandle()
    val listState = rememberLazyListState()

    // The row a controller is on, so X can queue it. No focus restore: this
    // screen opens on an empty query and what it opens for is the typing.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val focusedGroup = results.firstOrNull { it.key == focusedKey }

    GamepadHandler { action ->
        when (action) {
            GamepadAction.Search -> {
                // Y takes a standing snackbar offer; otherwise it returns to
                // the field, which the buttons cannot reach from the results.
                if (!snackbarHostState.takeOffer()) queryField.requestFocus()
                true
            }
            GamepadAction.Download -> {
                focusedGroup?.let {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.downloads.download(it)
                }
                true
            }
            else -> false
        }
    }
    ListGamepadScrolling(listState)

    // A controller cannot tap into the field, so it takes focus itself one frame
    // in, once the view is attached. Only on a fresh query, so returning from a
    // ROM does not reopen the keyboard over the results.
    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            withFrameNanos { }
            queryField.requestFocus()
        }
    }

    QueueSnackbarEffect(
        messages         = viewModel.downloads.messages,
        host             = snackbarHostState,
        onUndo           = viewModel.downloads::undo,
        onFolderSettings = onFolderSettings,
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // Results follow the query as typed, so Search has nothing
                    // to submit and only dismisses the keyboard. A handler is
                    // required: TextView's default hides the IME for Done but
                    // not for Search.
                    OutlinedInputField(
                        value         = query,
                        onValueChange = viewModel::setQuery,
                        placeholder   = "Search ROMs...",
                        imeAction     = EditorInfo.IME_ACTION_SEARCH,
                        handle        = queryField,
                        onImeAction   = { queryField.hideKeyboard() },
                        modifier      = Modifier.fillMaxWidth(),
                    )
                },
                navigationIcon = { BackButton(onBack) },
            )
        },
        bottomBar = {
            GamepadHintBar(
                listOf(
                    GamepadHint(GamepadButton.A, "Open"),
                    GamepadHint(GamepadButton.X, "Download"),
                    GamepadHint(GamepadButton.Y, "Search box"),
                    GamepadHint(GamepadButton.B, "Back"),
                )
            )
        },
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
            if (offline) {
                item {
                    ListItem(
                        headlineContent  = { Text("Server unreachable") },
                        supportingContent = {
                            Text("Showing downloaded platforms only.")
                        },
                        leadingContent   = { Icon(Icons.Default.CloudOff, null) },
                    )
                    HorizontalDivider()
                }
            }
            items(results, key = { it.key }) { group ->
                val rom = group.primary
                ListItem(
                    modifier = Modifier.gamepadRow(
                        onClick          = { onRomClick(rom.id) },
                        onLongClick      = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.downloads.download(group)
                        },
                        onLongClickLabel = "Download",
                        onFocused        = { focusedKey = group.key },
                    ),
                    headlineContent   = { Text(rom.name ?: rom.fsNameNoTags) },
                    supportingContent = {
                        val flags = regionSummary(group.regions)
                        val detail = if (group.hasVariants) {
                            "${rom.platformDisplayName}  -  ${group.size} versions"
                        } else {
                            rom.platformDisplayName
                        }
                        Text(if (flags.isEmpty()) detail else "$flags  -  $detail")
                    },
                    trailingContent = {
                        if (group.key in queueing) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
