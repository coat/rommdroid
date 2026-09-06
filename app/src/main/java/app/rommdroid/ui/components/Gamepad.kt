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
import app.rommdroid.data.repository.GamepadLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sign

/*
 * Controller support.
 *
 * This app is used on Android gaming handhelds, where the buttons are the
 * primary input and the touchscreen is the thing you fall back to.  Android
 * gives that almost nothing for free: the framework turns the left stick and
 * the D-pad into focus moves, and stops there.  Every face button reports its
 * own keycode that nothing in the platform interprets — Compose's `clickable`
 * treats only DPAD_CENTER and Enter as a press, and `KEYCODE_BUTTON_B` is not
 * Back to anyone.  So the map lives here.
 *
 * Key events go to whatever holds focus, and on this app that can be a native
 * EditText inside an AndroidView — which swallows the whole key dispatch, so a
 * `Modifier.onKeyEvent` anywhere in the composition stops hearing anything the
 * moment a text field is focused.  The buttons are therefore read at the
 * Activity, above focus entirely, and handed to whichever screen registered
 * last through [GamepadDispatcher].
 */

/**
 * What a button means, rather than which button it is.
 *
 * Screens bind these; the keycodes they arrive on are this file's business.
 * [Confirm] is missing on purpose — A is rewritten to DPAD_CENTER before it
 * gets here, so it presses whatever holds focus like a D-pad click would.
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

/**
 * The map itself.
 *
 * Face buttons follow the Android layout — A at the bottom, B on the right —
 * which is what the keycodes mean regardless of how the device silkscreens
 * them.  A handheld that swaps A and B in its system settings swaps them here
 * too, which is what a user who set that expects.
 */
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

/** Where a stick has to reach before it counts as pushed, and where it stops counting. */
private const val StickDeadzone   = 0.25f
/** Triggers are analog; these are the pull depths that latch and release a press. */
private const val TriggerPress    = 0.55f
private const val TriggerRelease  = 0.35f
/** How fast a fully deflected right stick runs the list, in dp per second. */
private const val StickScrollDp   = 2200f

/**
 * The live button map: screens register what they answer to, the Activity feeds
 * key events in.
 *
 * Handlers are consulted newest first, so a screen shadows the app-wide
 * bindings the nav host puts in underneath it, and returning false from one
 * passes the button along to whatever registered before it.
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

    /**
     * Returns true when the event was ours, and the caller must not pass it on.
     *
     * Both halves of a press are consumed once the down was: leaving the up to
     * the system means a button that opened a screen sends a stray release into
     * whatever opened, which on a focused button is a second press.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = actionFor(event.keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0 && !action.repeatable) return true
        dispatch(action)
        return true
    }

    /**
     * Sticks and triggers, which arrive as axes rather than keys.
     *
     * L2 and R2 are analog on most handhelds and send no keycode at all, so a
     * pull is turned into a press here, with a lower release point than press
     * point so a trigger resting near the threshold does not chatter.
     *
     * Reading this must not consume the event: the framework synthesises D-pad
     * keys from the left stick only for motion events nothing handled, and that
     * synthesis is the entire reason the stick can drive focus.
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

    /**
     * Forget what is being held.
     *
     * A stick deflected as the app goes to the background sends its release
     * event to whatever took the foreground, so without this the list would
     * still be scrolling when the user came back to it.
     */
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

/**
 * Bind buttons for as long as this composable is in the tree.
 *
 * Return true from [onAction] to take a button, false to let the binding
 * underneath have it — an unbound button on a screen still reaches the app-wide
 * ones the nav host registers.
 */
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
 * Run [state] from the right stick.
 *
 * Deflection is squared so a small push creeps and a full push crosses the
 * list, and the scroll is driven per frame rather than per motion event: a
 * stick held still sends nothing at all, and the list would stop with it.
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
            // The first frame of a push has no elapsed time to scale by, and
            // one long pause (a slow recomposition) should not teleport.
            val step = elapsed.coerceIn(0f, 0.05f)
            state.scrollBy(sign(deflection) * deflection * deflection * pxPerSecond * step)
        }
    }
}

/**
 * A screenful, which is what the triggers move.
 *
 * Slightly less than the viewport so a row stays on screen across the jump —
 * landing on an entirely new set of names gives a reader nothing to place
 * themselves by.
 */
suspend fun LazyListState.scrollPage(direction: Int) {
    val viewport = layoutInfo.viewportSize.height
    if (viewport > 0) animateScrollBy(viewport * 0.85f * direction)
}

/**
 * True while a physical controller is attached.
 *
 * The hint bar and nothing else depends on this: the bindings are always live,
 * but telling a phone user which button downloads a ROM is noise.
 */
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

// ── Hints ─────────────────────────────────────────────────────────────────────

/**
 * A button as the bar draws it.
 *
 * Named for the keycode it arrives on, which Android names the Xbox way, and
 * printed as whichever lettering the user said their handheld uses — the same
 * four positions carry different letters on a Nintendo-style pad, and a hint
 * that names a letter the device does not print there is worse than no hint.
 * The shoulders and the two small ones are the same on both, so they carry one
 * glyph.  See [GamepadLayout].
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

/**
 * The lettering in force, set once at the top of the app from the stored
 * preference.
 *
 * Not `staticCompositionLocalOf`: this one changes while the app is running —
 * the moment the user picks the other style in settings — and only the hints
 * that read it need to repaint.
 */
val LocalGamepadLayout = compositionLocalOf { GamepadLayout.Xbox }

/**
 * The lettering to print a button in, or null when there is no controller and
 * so nothing to name.
 *
 * The two questions come as one because every caller asks both: a screen either
 * names its buttons in the user's lettering or does not name them at all.
 */
@Composable
fun rememberButtonLayout(): GamepadLayout? {
    val layout   = LocalGamepadLayout.current
    val attached = rememberHasGamepad()
    return if (attached) layout else null
}

/**
 * A snackbar action, named with the button that performs it.
 *
 * "Undo" and "Set folder" are the only things in the app that appear for a few
 * seconds and then leave, and a controller cannot tap them — so the screens
 * that raise them bind Y to whatever action is on screen, and the label says
 * so.  Without the button in the text there is nothing to tell the user the
 * offer is theirs to take.
 */
fun String?.withButton(button: GamepadButton, layout: GamepadLayout?): String? =
    if (this != null && layout != null) "${button.glyph(layout)}  ·  $this" else this

/**
 * The legend along the bottom of a screen.
 *
 * A button map nobody can see is a button map nobody uses, and this is the
 * convention every handheld frontend already trained its users on.  It draws
 * only with a controller attached, and scrolls rather than wrapping so a narrow
 * screen in portrait keeps the bar one row tall.
 *
 * The app is edge to edge, and a Scaffold's bottom bar is laid out over the
 * system bars rather than above them — so the hints have to step out of the
 * navigation bar themselves, or the gesture pill sits on top of the last two
 * of them.  The colour still runs to the bottom edge; only the row moves.
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

/**
 * The button itself: a letter in a ring for the four face buttons, a rounded
 * tab for the shoulders and the two little ones, since "Start" does not fit in
 * a circle and a shoulder button is not round on any device.
 */
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

// ── Focus ─────────────────────────────────────────────────────────────────────

/**
 * A list row that can be reached with a controller.
 *
 * Material draws focus as a few percent of a ripple overlay, which is invisible
 * on a handheld screen — and with a controller the focused row is the cursor,
 * so it has to read at arm's length in daylight.  This paints the row and puts
 * a bar down its leading edge.
 *
 * The click handlers belong to this modifier rather than being applied after
 * it, because the highlight has to sit before the focusable node in the chain
 * to hear about it at all.
 *
 * It is drawn over the row rather than behind it: a ListItem paints its own
 * opaque container, so anything drawn underneath — the obvious way to tint a
 * row — is covered by the row itself and never appears.
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

/**
 * The same visible focus for anything that is not a row — the icons in a top
 * bar, mostly, which a stick can still walk into.
 */
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
 * Put focus back where the user left it.
 *
 * Without this every trip into a ROM and back starts at the top of a list of
 * thousands, because focus does not survive the screen being torn down — the
 * scroll position does, so the list even looks right until the first press
 * jumps to row one.  [ready] gates the request until there is a row composed
 * under the requester to take it; a couple of frames of slack covers the list
 * still being laid out when the screen first appears.
 *
 * Only with a controller attached.  Focus is a cursor to someone holding one
 * and nothing at all to someone holding a phone, and taking it unasked would
 * leave a highlighted row sitting on a screen that is only ever touched.
 *
 * Waiting on the window's focus is what makes this land on a cold start.  The
 * rows are composed while the launch animation is still running and the window
 * has no focus to give out yet, so a request made then is dropped — the list
 * comes up with nothing selected and the user's first press is spent picking
 * row one instead of moving to row two.
 */
@Composable
fun RestoreFocus(focusRequester: FocusRequester, ready: Boolean) {
    val view          = LocalView.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val wanted = ready && windowFocused && rememberHasGamepad()
    LaunchedEffect(focusRequester, wanted) {
        if (!wanted) return@LaunchedEffect
        // requestFocus() reports nothing.  It throws only when no node holds
        // the requester at all; on a node that is attached but not yet placed
        // — which is every row on the frame a list first composes — it quietly
        // does nothing.  So there is no success to wait for, and the ask is
        // repeated over the next few frames, by which point the list has been
        // laid out and one of them lands.
        repeat(5) {
            // requestFocusFromTouch, not requestFocus: the window comes up in
            // touch mode — it was launched by a tap — and in touch mode the
            // framework refuses focus to every view, so Compose's own request
            // lands on nothing and reports nothing.  This is the one public
            // call that leaves touch mode first, and it is the whole reason the
            // old first press only ever "woke" the list instead of moving it.
            if (!view.hasFocus()) view.requestFocusFromTouch()
            runCatching { focusRequester.requestFocus() }
            withFrameNanos { }
        }
    }
}
