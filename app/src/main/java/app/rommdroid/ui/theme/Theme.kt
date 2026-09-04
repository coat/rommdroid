package app.rommdroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()

@Composable
fun RomMDroidTheme(
    useDarkTheme: Boolean = true,   // default dark — ROM library apps look better dark
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColors
        else         -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content     = content,
    )
}
