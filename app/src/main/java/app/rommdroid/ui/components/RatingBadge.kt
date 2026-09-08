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
 * A game's aggregate score out of 100, rounded: the decimals are an artefact of
 * averaging however many providers scored it.
 *
 * [compact] is the list-row size, with no vertical padding, so the pill fits the
 * line the row already draws instead of growing every row to match.
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
