package app.rommdroid.ui.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rommdroid.domain.GamepadLayout

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

/** One legend entry. More than one button when a pair shares a job, as the
 *  shoulders do stepping letters: drawn as "[L1]/[R1] Prev / Next Letter". */
data class GamepadHint(val buttons: List<GamepadButton>, val label: String) {
    constructor(button: GamepadButton, label: String) : this(listOf(button), label)
    constructor(first: GamepadButton, second: GamepadButton, label: String) : this(listOf(first, second), label)
}

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        hint.buttons.forEachIndexed { i, button ->
                            if (i > 0) Text(
                                text  = "/",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ButtonGlyph(button)
                        }
                    }
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
