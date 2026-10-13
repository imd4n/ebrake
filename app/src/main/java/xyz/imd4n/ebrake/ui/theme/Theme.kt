package xyz.imd4n.ebrake.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = EbrakeAccent,
    onPrimary = EbrakeText,
    secondary = EbrakeAccentDim,
    tertiary = EbrakeTerminalGreen,
    background = EbrakeBackground,
    onBackground = EbrakeText,
    surface = EbrakeSurface,
    onSurface = EbrakeText,
    surfaceVariant = EbrakeSurfaceVariant,
    onSurfaceVariant = EbrakeTextDim,
    error = EbrakeError,
)

// Светлая схема оставлена для предпросмотра/демо, по умолчанию не используется.
private val LightColorScheme = lightColorScheme(
    primary = EbrakeAccentDim,
    onPrimary = EbrakeText,
    secondary = EbrakeAccent,
    background = EbrakeText,
    onBackground = EbrakeBackground,
    surface = EbrakeText,
    onSurface = EbrakeBackground,
)

@Composable
fun EbrakeTheme(
    // Тема всегда тёмная: палитра eBrake Pro заточена под тёмный фон, а
    // попытки включить светлую давали нечитаемый лог на белом.
    darkTheme: Boolean = true,
    // ВАЖНО: Material You (dynamicColor) выключен по решению пользователя.
    // M3 используется только как toolkit со статичной палитрой eBrake Pro.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // dynamicColor намеренно не используется, чтобы тема никогда не зависела
    // от обоев системы.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
