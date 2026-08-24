package pl.trikimusic.controller.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import pl.trikimusic.controller.domain.model.ThemePreference

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7F3D0),
    onPrimary = Color(0xFF083829),
    primaryContainer = Color(0xFF155C45),
    onPrimaryContainer = Color(0xFFC4F9DF),
    secondary = Color(0xFFB8C8FF),
    onSecondary = Color(0xFF142552),
    tertiary = Color(0xFFFFC285),
    background = Color(0xFF0C1110),
    onBackground = Color(0xFFE5EDE9),
    surface = Color(0xFF111816),
    surfaceVariant = Color(0xFF1B2421),
    onSurfaceVariant = Color(0xFFB8C7C1),
    outline = Color(0xFF5F706A),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA9F3D2),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF40558C),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A4F00),
    background = Color(0xFFF7FBF8),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFFCFFFC),
    surfaceVariant = Color(0xFFE0E9E4),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    error = Color(0xFFBA1A1A),
)

@Composable
fun TrikiMusicTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
