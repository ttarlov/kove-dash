package com.kovedash.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

@Composable
fun KoveTheme(content: @Composable () -> Unit) {
    val materialColors = darkColorScheme(
        primary = KoveColors.Mint,
        onPrimary = KoveColors.Ink,
        secondary = KoveColors.Yellow,
        onSecondary = KoveColors.Ink,
        tertiary = KoveColors.Magenta,
        onTertiary = KoveColors.Paper,
        background = KoveColors.Void,
        onBackground = KoveColors.Paper,
        surface = KoveColors.Void2,
        onSurface = KoveColors.Paper,
        error = KoveColors.Magenta,
        onError = KoveColors.Paper,
    )

    MaterialTheme(colorScheme = materialColors) {
        content()
    }
}

val LocalKoveColors = staticCompositionLocalOf { KoveColors }
