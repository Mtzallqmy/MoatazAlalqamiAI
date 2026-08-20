package com.inspiredandroid.kai.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalSemanticColors = staticCompositionLocalOf { MoatazSemanticDefaults }
private val LocalSpacing = staticCompositionLocalOf { MoatazSpacing() }
private val LocalTerminalTheme = staticCompositionLocalOf { MoatazTerminalDark }

object MoatazTheme {
    val semanticColors: MoatazSemanticColors
        @Composable @ReadOnlyComposable get() = LocalSemanticColors.current
    val spacing: MoatazSpacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val terminal: MoatazTerminalTheme
        @Composable @ReadOnlyComposable get() = LocalTerminalTheme.current
}

@Composable
fun ProvideMoatazTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalSemanticColors provides MoatazSemanticDefaults,
        LocalSpacing provides MoatazSpacing(),
        LocalTerminalTheme provides MoatazTerminalDark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MoatazTypography,
            shapes = MoatazShapes,
            content = content,
        )
    }
}
