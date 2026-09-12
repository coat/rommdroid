package app.rommdroid.ui.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow

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
