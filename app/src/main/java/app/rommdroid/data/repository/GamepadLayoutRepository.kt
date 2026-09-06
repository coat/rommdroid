package app.rommdroid.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which of the two face-button letterings the hints should print.
 *
 * The four face buttons sit in the same four places on every controller ever
 * made; only the letters move.  Android names them the Xbox way — A at the
 * bottom, B on the right, X on the left, Y on top — and those names are what
 * arrives as a keycode.  A Nintendo-lettered pad prints the other pairing on
 * the same four positions: B at the bottom, A on the right, Y on the left, X
 * on top.
 *
 * Handhelds ship a "controller style" switch that changes which keycode each
 * position sends, and nothing in the Android API reports its state — the
 * keycodes are the whole of what an app can see, and both styles send the same
 * ten of them.  `InputDevice.getKeyCodeForKeyLocation` looks like the answer
 * and is not: it is defined against a reference QWERTY keyboard, for telling
 * W/A/S/D apart on a foreign layout, and knows nothing about gamepads.
 *
 * So it is asked rather than detected.  It costs the user one setting and it
 * is always right, which detection would not have been.
 */
enum class GamepadLayout { Xbox, Nintendo }

/**
 * Remembers the answer.
 *
 * Its own preferences file, unencrypted: this is a display preference, and
 * putting it in the credential store would tie it to a keystore key that a
 * disconnect throws away.
 */
@Singleton
class GamepadLayoutRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _layout = MutableStateFlow(stored())

    /** Read by the composition, so a change repaints every hint bar at once. */
    val layout: StateFlow<GamepadLayout> = _layout.asStateFlow()

    fun set(layout: GamepadLayout) {
        _layout.value = layout
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
    }

    /**
     * Xbox by default, because it is what the keycodes are named and what the
     * majority of handhelds now silkscreen.  Anyone it is wrong for finds out
     * on their first look at the hint bar, which is where the setting is for.
     */
    private fun stored(): GamepadLayout {
        val name = prefs.getString(KEY_LAYOUT, null) ?: return GamepadLayout.Xbox
        return GamepadLayout.entries.firstOrNull { it.name == name } ?: GamepadLayout.Xbox
    }

    private companion object {
        const val PREFS_FILE = "rommdroid_controller"
        const val KEY_LAYOUT = "button_layout"
    }
}
