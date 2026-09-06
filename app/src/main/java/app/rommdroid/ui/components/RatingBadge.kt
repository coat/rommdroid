package app.rommdroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A game's aggregate score, as RomM scales it: one number out of 100.
 *
 * Rounded, because the value behind it is a mean of however many providers
 * happened to score the game — its decimals are an artefact of that arithmetic
 * rather than anything a rating tells you.
 *
 * [compact] is the list-row size: smaller type and no vertical padding, so the
 * pill fits inside the line the row already draws.  Without that the badge is
 * taller than the text beside it and every row in the list grows to match.
 */
@Composable
fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Text(
        "${rating.roundToInt()}/100",
        style    = if (compact) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelLarge
        },
        color    = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(50),
            )
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical   = if (compact) 0.dp else 2.dp,
            ),
    )
}
