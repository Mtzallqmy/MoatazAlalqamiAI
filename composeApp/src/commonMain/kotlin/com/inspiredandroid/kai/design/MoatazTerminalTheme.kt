package com.inspiredandroid.kai.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class MoatazTerminalTheme(
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val prompt: Color,
    val selection: Color,
    val error: Color,
)

val MoatazTerminalDark = MoatazTerminalTheme(
    background = MoatazColors.Carbon,
    foreground = Color(0xFFDCE5EE),
    cursor = MoatazColors.Signal,
    prompt = MoatazColors.TerminalPrompt,
    selection = MoatazColors.TerminalSelection,
    error = MoatazColors.Destructive,
)
