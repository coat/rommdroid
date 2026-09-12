package app.rommdroid.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import app.rommdroid.data.download.QueueMessage
import app.rommdroid.ui.gamepad.GamepadButton
import app.rommdroid.ui.gamepad.rememberButtonLayout
import app.rommdroid.ui.gamepad.withButton
import kotlinx.coroutines.flow.Flow

/**
 * Shows each queue outcome as a snackbar, with "Undo" or "Set folder" as its one
 * action. A controller cannot tap a snackbar, so the label names Y and the
 * screen's Y binding calls [takeOffer].
 */
@Composable
fun QueueSnackbarEffect(
    messages: Flow<QueueMessage>,
    host: SnackbarHostState,
    onUndo: (List<String>) -> Unit,
    onFolderSettings: () -> Unit,
) {
    val buttons = rememberButtonLayout()
    val undo by rememberUpdatedState(onUndo)
    val folderSettings by rememberUpdatedState(onFolderSettings)
    LaunchedEffect(messages, buttons) {
        messages.collect { message ->
            val action = when {
                message.undoIds.isNotEmpty() -> "Undo"
                message.needsFolder          -> "Set folder"
                else                         -> null
            }
            val result = host.showSnackbar(
                message     = message.text,
                actionLabel = action.withButton(GamepadButton.Y, buttons),
                duration    = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (message.undoIds.isNotEmpty()) undo(message.undoIds)
                else if (message.needsFolder) folderSettings()
            }
        }
    }
}

/** Perform the standing snackbar's action, if one is showing and has one.
 *  True when it did, so the caller knows the button press is spent. */
fun SnackbarHostState.takeOffer(): Boolean {
    val offer = currentSnackbarData?.takeIf { it.visuals.actionLabel != null } ?: return false
    offer.performAction()
    return true
}
