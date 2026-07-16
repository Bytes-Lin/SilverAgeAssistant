package com.example.silverageassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ElderBlueDark,
    secondary = ElderGreenDark,
    error = ColorTokens.DarkError,
    background = ElderDarkBackground,
    surface = ElderDarkSurface,
)

private val LightColorScheme = lightColorScheme(
    primary = ElderBlue,
    onPrimary = ElderSurface,
    secondary = ElderGreen,
    background = ElderBackground,
    onBackground = ElderText,
    surface = ElderSurface,
    onSurface = ElderText,
    error = ElderAlert,
    errorContainer = ElderAlertContainer,
)

private object ColorTokens {
    val DarkError = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
}

@Composable
fun SilverAgeAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = ElderTypography,
        content = content,
    )
}
