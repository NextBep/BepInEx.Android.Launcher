package com.bepinex.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bepinex.android.settings.AppSettings

private val LightFallbackColors = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00332E),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF80CBC4),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2F1),
    onTertiaryContainer = Color(0xFF003935),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF5FBF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C6),
    inverseSurface = Color(0xFF2E3130),
    inverseOnSurface = Color(0xFFEFF1EF),
    inversePrimary = Color(0xFF80CBC4),
    surfaceTint = Color(0xFF00897B)
)

private val DarkFallbackColors = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1B3532),
    secondaryContainer = Color(0xFF314C48),
    onSecondaryContainer = Color(0xFFCCE8E4),
    tertiary = Color(0xFFA0D0CA),
    onTertiary = Color(0xFF0B3632),
    tertiaryContainer = Color(0xFF254D49),
    onTertiaryContainer = Color(0xFFE0F2F1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1B),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF191C1B),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    inverseSurface = Color(0xFFE1E3E1),
    inverseOnSurface = Color(0xFF2E3130),
    inversePrimary = Color(0xFF00897B),
    surfaceTint = Color(0xFF80CBC4)
)

@Composable
fun BepInExTheme(
    themeMode: AppSettings.ThemeMode = AppSettings.ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        AppSettings.ThemeMode.DARK -> true
        AppSettings.ThemeMode.LIGHT -> false
        AppSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkFallbackColors
        else -> LightFallbackColors
    }

    val shapes = Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        content = content
    )
}
