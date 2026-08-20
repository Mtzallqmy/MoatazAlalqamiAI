package com.inspiredandroid.kai.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

object MoatazColors {
    val Carbon = Color(0xFF0D1117)
    val Graphite = Color(0xFF151B23)
    val Slate = Color(0xFF202938)
    val Mist = Color(0xFFF4F7FA)
    val Ink = Color(0xFF151A20)
    val Signal = Color(0xFF26C6A5)
    val SignalDark = Color(0xFF087F6C)
    val Horizon = Color(0xFF6EA8FE)
    val Success = Color(0xFF45D483)
    val Warning = Color(0xFFFFC857)
    val Destructive = Color(0xFFFF6B72)
    val Info = Color(0xFF69B7FF)
    val TerminalPrompt = Color(0xFF67E8C4)
    val TerminalSelection = Color(0xFF284D63)
    val AgentActivity = Color(0xFFC59BFF)
}

@Immutable
data class MoatazSemanticColors(
    val success: Color,
    val warning: Color,
    val destructive: Color,
    val info: Color,
    val terminalPrompt: Color,
    val terminalSelection: Color,
    val agentActivity: Color,
)

val MoatazSemanticDefaults = MoatazSemanticColors(
    success = MoatazColors.Success,
    warning = MoatazColors.Warning,
    destructive = MoatazColors.Destructive,
    info = MoatazColors.Info,
    terminalPrompt = MoatazColors.TerminalPrompt,
    terminalSelection = MoatazColors.TerminalSelection,
    agentActivity = MoatazColors.AgentActivity,
)

val MoatazDarkColorScheme = darkColorScheme(
    primary = MoatazColors.Signal,
    onPrimary = Color(0xFF00211A),
    secondary = MoatazColors.Horizon,
    background = MoatazColors.Carbon,
    onBackground = Color(0xFFE6EDF3),
    surface = MoatazColors.Graphite,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = MoatazColors.Slate,
    error = MoatazColors.Destructive,
)

val MoatazLightColorScheme = lightColorScheme(
    primary = MoatazColors.SignalDark,
    onPrimary = Color.White,
    secondary = Color(0xFF3267A8),
    background = MoatazColors.Mist,
    onBackground = MoatazColors.Ink,
    surface = Color.White,
    onSurface = MoatazColors.Ink,
    surfaceVariant = Color(0xFFE5EBF1),
    error = Color(0xFFB4232A),
)
