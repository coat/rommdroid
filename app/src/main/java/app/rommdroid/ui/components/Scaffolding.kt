package app.rommdroid.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.ui.gamepad.focusOutline

// The few pieces every screen draws the same way.

/** The top bar's back arrow, focusable for a controller. */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.focusOutline()) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** What a list screen shows when the first sync failed and there is no cache
 *  to fall back on. */
@Composable
fun ConnectionError(message: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Could not reach server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.focusOutline()) { Text("Retry") }
    }
}

/** A transfer's progress bar: determinate once the size is known, else busy. */
@Composable
fun TransferProgress(progress: Float?, modifier: Modifier = Modifier) {
    val m = modifier.fillMaxWidth()
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = m)
    } else {
        LinearProgressIndicator(modifier = m)
    }
}
