package app.rommdroid.ui.gamepad

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A list row reachable with a controller. Material's focus overlay is a few
 * percent of a ripple, invisible on a handheld, and with a controller the
 * focused row is the cursor.
 *
 * The click handlers belong to this modifier: the highlight has to sit before
 * the focusable node in the chain to hear about focus. And it draws over the
 * row, not behind it, since a ListItem paints its own opaque container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.gamepadRow(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(onFocused)
    return this
        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
        .onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                if (it.isFocused) current()
            }
        }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            drawRect(accent.copy(alpha = 0.16f))
            drawRect(
                color = accent,
                size  = Size(width = 4.dp.toPx(), height = size.height),
            )
        }
        .combinedClickable(
            onClick          = onClick,
            onLongClick      = onLongClick,
            onLongClickLabel = onLongClickLabel,
        )
}

/** The same visible focus for anything that is not a row, mostly top-bar icons. */
@Composable
fun Modifier.focusOutline(): Modifier {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawBehind {
            if (!focused) return@drawBehind
            val inset = 2.dp.toPx()
            drawRoundRect(
                color        = accent,
                topLeft      = Offset(inset, inset),
                size         = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style        = Stroke(width = 2.dp.toPx()),
            )
        }
}

/**
 * Put focus back where the user left it. Scroll position survives a screen
 * teardown but focus does not, so without this the list looks right until the
 * first press jumps to row one. [ready] gates the request until a row is
 * composed under the requester. Controller only.
 *
 * Waiting on window focus is what makes this land on a cold start: rows compose
 * while the launch animation still runs and the window has no focus to give,
 * and a request made then is silently dropped.
 */
@Composable
fun RestoreFocus(focusRequester: FocusRequester, ready: Boolean) {
    val view          = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val wanted = ready && windowFocused && rememberHasGamepad()
    LaunchedEffect(focusRequester, wanted) {
        if (!wanted) return@LaunchedEffect
        // requestFocus() reports nothing: it throws only when no node holds the
        // requester, and does nothing at all on a node attached but not yet
        // placed. So there is no success to await, and the ask repeats until
        // the list has been laid out.
        repeat(5) {
            // requestFocusFromTouch, not requestFocus: the window comes up in
            // touch mode, where the framework refuses focus to every view, and
            // this is the one public call that leaves touch mode first.
            if (!view.hasFocus()) view.requestFocusFromTouch()
            runCatching { focusRequester.requestFocus() }
            withFrameNanos { }
        }
    }
}
