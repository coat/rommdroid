package app.rommdroid.data.repository

import android.content.Context
import app.rommdroid.domain.GamepadLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton


/** Its own unencrypted preferences file: a display preference in the credential
 *  store would be tied to a keystore key that a disconnect throws away. */
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

    /** Xbox by default: what the keycodes are named, and what most handhelds now
     *  silkscreen. Anyone it is wrong for sees so in the hint bar. */
    private fun stored(): GamepadLayout {
        val name = prefs.getString(KEY_LAYOUT, null) ?: return GamepadLayout.Xbox
        return GamepadLayout.entries.firstOrNull { it.name == name } ?: GamepadLayout.Xbox
    }

    private companion object {
        const val PREFS_FILE = "rommdroid_controller"
        const val KEY_LAYOUT = "button_layout"
    }
}
