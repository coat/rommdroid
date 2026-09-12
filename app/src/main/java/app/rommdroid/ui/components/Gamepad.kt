package app.rommdroid.ui.components

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.rommdroid.domain.GamepadLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/*
 * Controller support for Android gaming handhelds, where the buttons are the
 * primary input. The framework turns the left stick and D-pad into focus moves
 * and stops there: `clickable` treats only DPAD_CENTER and Enter as a press,
 * and KEYCODE_BUTTON_B is Back to nobody. So the map lives here.
 *
 * Buttons are read at the Activity rather than through `Modifier.onKeyEvent`,
 * because a focused native EditText swallows the whole key dispatch, and handed
 * to whichever screen registered last with [GamepadDispatcher].
 */

/**
 * What a button means, rather than which button it is. No Confirm: A is
 * rewritten to DPAD_CENTER upstream so it presses whatever holds focus.
 */
enum class GamepadAction(
    /** True for the ones a held button should keep firing: they move the list. */
    internal val repeatable: Boolean = false,
) {
    Back,
    Download,
    Search,
    Settings,
    Downloads,
    SectionPrev(repeatable = true),
    SectionNext(repeatable = true),
    PageUp(repeatable = true),
    PageDown(repeatable = true),
}

/** Face buttons follow the Android layout (A bottom, B right), which is what the
 *  keycodes mean however the device silkscreens them. */
private fun actionFor(keyCode: Int): GamepadAction? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_B      -> GamepadAction.Back
    KeyEvent.KEYCODE_BUTTON_X      -> GamepadAction.Download
    KeyEvent.KEYCODE_BUTTON_Y      -> GamepadAction.Search
    KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadAction.Settings
    KeyEvent.KEYCODE_BUTTON_START  -> GamepadAction.Downloads
    KeyEvent.KEYCODE_BUTTON_L1     -> GamepadAction.SectionPrev
    KeyEvent.KEYCODE_BUTTON_R1     -> GamepadAction.SectionNext
    KeyEvent.KEYCODE_BUTTON_L2     -> GamepadAction.PageUp
    KeyEvent.KEYCODE_BUTTON_R2     -> GamepadAction.PageDown
    else                           -> null
}

private const val StickDeadzone   = 0.25f
/** Analog trigger depths that latch and release a press. */
private const val TriggerPress    = 0.55f
private const val TriggerRelease  = 0.35f
/** Fully deflected right stick, in dp per second. */
private const val StickScrollDp   = 2200f

/**
 * The live button map. Handlers are consulted newest first, so a screen shadows
 * the nav host's app-wide bindings and returning false falls through to them.
 */
@Stable
class GamepadDispatcher {

    private val handlers = mutableListOf<(GamepadAction) -> Boolean>()

    /** Right stick deflection, -1 (up) to 1 (down), already past the deadzone. */
    internal val scrollAxis = MutableStateFlow(0f)

    private var leftTrigger  = false
    private var rightTrigger = false

    internal fun register(handler: (GamepadAction) -> Boolean) { handlers += handler }
    internal fun unregister(handler: (GamepadAction) -> Boolean) { handlers -= handler }

    fun dispatch(action: GamepadAction): Boolean {
        // asReversed() is a view, and a handler may register or unregister
        // during dispatch, so walk a copy.
        return handlers.toList().asReversed().any { it(action) }
    }

    /** True when the event was ours. Both halves of a press are consumed: a
     *  stray release lands on whatever the press opened, as a second press. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = actionFor(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0 && !action.repeatable) return true
        dispatch(action)
        return true
    }

    /**
     * Sticks and triggers, which arrive as axes rather than keys. L2 and R2 are
     * analog on most handhelds and send no keycode, so a pull becomes a press
     * with hysteresis so a resting trigger does not chatter.
     *
     * Must not consume the event: the framework synthesises the left stick's
     * D-pad keys only for motion events nothing handled, and that synthesis is
     * why the stick drives focus at all.
     */
    fun onMotionEvent(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return

        val vertical = event.getAxisValue(MotionEvent.AXIS_RZ)
        scrollAxis.value = if (abs(vertical) < StickDeadzone) 0f else vertical

        // AXIS_BRAKE / AXIS_GAS are the same triggers on devices that report
        // them under the driving-control names; whichever is populated wins.
        val left  = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE),
        )
        val right = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
        )
        leftTrigger  = trigger(leftTrigger,  left,  GamepadAction.PageUp)
        rightTrigger = trigger(rightTrigger, right, GamepadAction.PageDown)
    }

    /** Forget what is held. A stick deflected as the app backgrounds sends its
     *  release elsewhere, and the list would still be scrolling on return. */
    fun release() {
        scrollAxis.value = 0f
        leftTrigger  = false
        rightTrigger = false
    }

    private fun trigger(pulled: Boolean, depth: Float, action: GamepadAction): Boolean = when {
        !pulled && depth >= TriggerPress   -> { dispatch(action); true }
        pulled  && depth <= TriggerRelease -> false
        else                               -> pulled
    }
}

val LocalGamepad = staticCompositionLocalOf { GamepadDispatcher() }

/** Bind buttons while this composable is in the tree. Return true from [onAction]
 *  to take a button, false to fall through to the binding underneath. */
@Composable
fun GamepadHandler(onAction: (GamepadAction) -> Boolean) {
    val dispatcher = LocalGamepad.current
    val current by rememberUpdatedState(onAction)
    DisposableEffect(dispatcher) {
        val handler: (GamepadAction) -> Boolean = { current(it) }
        dispatcher.register(handler)
        onDispose { dispatcher.unregister(handler) }
    }
}

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

/** True while a controller is attached. Only the hint bar depends on it; the
 *  bindings are always live. */
@Composable
fun rememberHasGamepad(): Boolean {
    val context = LocalContext.current
    var attached by remember { mutableStateOf(gamepadAttached()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int)   { attached = gamepadAttached() }
            override fun onInputDeviceRemoved(deviceId: Int) { attached = gamepadAttached() }
            override fun onInputDeviceChanged(deviceId: Int) { attached = gamepadAttached() }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    return attached
}

private fun gamepadAttached(): Boolean = InputDevice.getDeviceIds().any { id ->
    val sources = InputDevice.getDevice(id)?.sources ?: 0
    sources and InputDevice.SOURCE_GAMEPAD  == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

// Hints

/**
 * A button as the bar draws it: named for its keycode, which Android names the
 * Xbox way, but printed in whichever lettering the user said their handheld
 * uses. The shoulders and small buttons letter the same in both. [GamepadLayout]
 */
enum class GamepadButton(private val xbox: String, private val nintendo: String = xbox) {
    /** Bottom. */ A("A", "B"),
    /** Right.  */ B("B", "A"),
    /** Left.   */ X("X", "Y"),
    /** Top.    */ Y("Y", "X"),
    L1("L1"), R1("R1"), L2("L2"), R2("R2"),
    Select("Sel"), Start("Start");

    fun glyph(layout: GamepadLayout): String = when (layout) {
        GamepadLayout.Xbox     -> xbox
        GamepadLayout.Nintendo -> nintendo
    }
}

data class GamepadHint(val button: GamepadButton, val label: String)

/** The lettering in force. Not `staticCompositionLocalOf`: it changes when the
 *  user picks the other style in settings. */
val LocalGamepadLayout = compositionLocalOf { GamepadLayout.Xbox }

/** The lettering to print a button in, or null when there is no controller.
 *  One call because every caller asks both questions together. */
@Composable
fun rememberButtonLayout(): GamepadLayout? {
    val layout   = LocalGamepadLayout.current
    val attached = rememberHasGamepad()
    return if (attached) layout else null
}

/** A snackbar action, named with the button that performs it. A controller
 *  cannot tap one, so the screens that raise them bind Y and say so. */
fun String?.withButton(button: GamepadButton, layout: GamepadLayout?): String? =
    if (this != null && layout != null) "${button.glyph(layout)}  -  $this" else this

/**
 * The legend along the bottom of a screen. Drawn only with a controller
 * attached, and scrolling rather than wrapping so portrait keeps it one row.
 *
 * The app is edge to edge and a Scaffold's bottom bar lays out over the system
 * bars, so the row insets itself or the gesture pill covers the last hints. The
 * colour still runs to the bottom edge.
 */
@Composable
fun GamepadHintBar(hints: List<GamepadHint>, modifier: Modifier = Modifier) {
    if (!rememberHasGamepad()) return
    Surface(
        color    = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            hints.forEach { hint ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    ButtonGlyph(hint.button)
                    Text(
                        text  = hint.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A letter in a ring for the face buttons, a rounded tab for the rest: "Start"
 *  does not fit in a circle and no shoulder button is round. */
@Composable
private fun ButtonGlyph(button: GamepadButton) {
    val glyph = button.glyph(LocalGamepadLayout.current)
    val round = glyph.length == 1
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (round) Modifier.size(18.dp) else Modifier)
            .clip(if (round) CircleShape else RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (round) Modifier else Modifier.padding(horizontal = 4.dp, vertical = 1.dp)),
    ) {
        Text(
            text  = glyph,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Focus

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
