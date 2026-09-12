package app.rommdroid.ui.gamepad

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sign
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Fully deflected right stick, in dp per second. */
private const val StickScrollDp = 2200f

/**
 * Run [state] from the right stick. Deflection is squared so a small push
 * creeps, and the scroll runs per frame rather than per motion event because a
 * stick held still sends nothing and the list would stop with it.
 */
@Composable
fun StickScroll(state: LazyListState) {
    val dispatcher = LocalGamepad.current
    val pxPerSecond = with(LocalDensity.current) { StickScrollDp.dp.toPx() }
    LaunchedEffect(state, dispatcher) {
        var previousFrame = 0L
        while (true) {
            val deflection = dispatcher.scrollAxis.value
            if (deflection == 0f) {
                previousFrame = 0L
                dispatcher.scrollAxis.first { it != 0f }
                continue
            }
            val now = withFrameNanos { it }
            val elapsed = if (previousFrame == 0L) 0f else (now - previousFrame) / 1_000_000_000f
            previousFrame = now
            // Clamped so the first frame and a slow recomposition do not teleport.
            val step = elapsed.coerceIn(0f, 0.05f)
            state.scrollBy(sign(deflection) * deflection * deflection * pxPerSecond * step)
        }
    }
}

/** A screenful, slightly under the viewport so a row survives the jump and the
 *  reader has something to place themselves by. */
suspend fun LazyListState.scrollPage(direction: Int) {
    val viewport = layoutInfo.viewportSize.height
    if (viewport > 0) animateScrollBy(viewport * 0.85f * direction)
}

/**
 * Everything a plain list needs from the controller: the right stick scrolls
 * it, the triggers page it. [afterPage] runs once a page jump has landed, for
 * a screen that then wants the cursor moved to what is now on screen.
 */
@Composable
fun ListGamepadScrolling(state: LazyListState, afterPage: suspend () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val after by rememberUpdatedState(afterPage)
    GamepadHandler { action ->
        val direction = when (action) {
            GamepadAction.PageUp   -> -1
            GamepadAction.PageDown -> 1
            else                   -> return@GamepadHandler false
        }
        scope.launch { state.scrollPage(direction); after() }
        true
    }
    StickScroll(state)
}
