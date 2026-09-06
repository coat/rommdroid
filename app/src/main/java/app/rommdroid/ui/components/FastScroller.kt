package app.rommdroid.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.rommdroid.util.SectionIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The thumb you drag to cross a list of thousands in one gesture, with the
 * letter you are passing over shown next to it.
 *
 * Compose ships no fast scroller — LazyColumn has no scrollbar at all — so this
 * is the Android idiom rebuilt: a thumb that appears when the list moves, and a
 * bubble that names where a drag has got to.
 */

private val TrackWidth  = 40.dp
private val ThumbHeight = 56.dp
private val ThumbBar    = 6.dp
private val BubbleSize  = 64.dp
private val BubbleGap   = 8.dp

/**
 * The overlay is wide enough to hold the bubble beside the track, so nothing it
 * draws has to reach outside its own bounds.  It takes no pointer input except
 * on the thumb itself, so the extra width costs the list nothing.
 */
private val OverlayWidth = TrackWidth + BubbleGap + BubbleSize

/**
 * A draggable thumb over [state], labelled from [index].
 *
 * Position is measured in items rather than pixels: rows here are not a uniform
 * height — a two-line title is twice a one-line one — and Compose only knows
 * the height of the rows currently on screen, so there is no pixel total to
 * take a fraction of.  Counting items is exact at both ends and close enough in
 * between, which is all a thumb needs to be.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    index: SectionIndex,
    modifier: Modifier = Modifier,
) {
    if (index.isEmpty) return

    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current

    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging      by remember { mutableStateOf(false) }
    var dragOffsetPx  by remember { mutableFloatStateOf(0f) }

    val firstVisible by remember { derivedStateOf { state.firstVisibleItemIndex } }
    // How far the first visible item can travel: the last index that can still
    // fill the screen, not the item count.
    val span by remember {
        derivedStateOf {
            val info = state.layoutInfo
            (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
        }
    }

    val thumbHeightPx = with(density) { ThumbHeight.toPx() }
    val travelPx  = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val restingPx = (firstVisible.toFloat() / span).coerceIn(0f, 1f) * travelPx
    val thumbPx   = if (dragging) dragOffsetPx else restingPx

    // The gesture handler outlives the composition that set it up, so everything
    // it reads has to come through a holder rather than being captured.  Keying
    // the pointerInput on these instead would restart the detector every time
    // one changed — and `span` changes the moment a header scrolls in or out,
    // which is to say a few rows into the very drag being handled.
    val currentResting by rememberUpdatedState(restingPx)
    val currentTravel  by rememberUpdatedState(travelPx)
    val currentSpan    by rememberUpdatedState(span)

    // Shows itself when the list moves and gets out of the way when it stops,
    // the same manners as the platform's own scrollbars.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            shown = true
        } else {
            delay(1_200)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollerAlpha")

    Box(
        modifier
            .fillMaxHeight()
            .width(OverlayWidth)
            .onSizeChanged { trackHeightPx = it.height }
    ) {
        // Faded out entirely means gone, not merely invisible: an untouchable
        // 40dp strip down the edge of the list would still swallow flicks.
        if (alpha <= 0.01f) return@Box

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbPx.roundToInt()) }
                .size(width = TrackWidth, height = ThumbHeight)
                .alpha(alpha)
                // The right edge of the screen belongs to the system's back
                // gesture, which swallows a grab aimed at the outer half of the
                // thumb.  Claim this rectangle back.
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart  = { dragging = true; dragOffsetPx = currentResting },
                        onDragEnd    = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        val travel = currentTravel
                        if (travel <= 0f) return@detectVerticalDragGestures
                        dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, travel)
                        val target = ((dragOffsetPx / travel) * currentSpan)
                            .roundToInt()
                            .coerceIn(0, currentSpan)
                        scope.launch { state.scrollToItem(target) }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .width(ThumbBar)
                    .height(ThumbHeight)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            )
        }

        // Only while dragging: the letter is the answer to "where am I now",
        // which is a question only a drag asks.
        val label = if (dragging) index.labelAt(firstVisible) else null
        if (label != null) {
            Surface(
                color           = MaterialTheme.colorScheme.primaryContainer,
                shape           = CircleShape,
                shadowElevation = 3.dp,
                modifier = Modifier
                    // Left of the track, so the finger on the thumb is not
                    // covering the letter it is there to read.
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (thumbPx + thumbHeightPx / 2 - BubbleSize.toPx() / 2).roundToInt(),
                        )
                    }
                    .size(BubbleSize),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
