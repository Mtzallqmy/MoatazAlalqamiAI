package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.chat_sandbox_terminal
import kai.composeapp.generated.resources.chat_sandbox_terminal_minimize
import org.jetbrains.compose.resources.stringResource

/**
 * Inline sandbox terminal rendered inside the chat conversation. The agent's
 * commands stream here so the user watches the build as it happens, instead of
 * being bounced into a separate screen mid-task.
 *
 * Lines arrive through [lines] (each entry: timestamped text with optional
 * error styling); the panel is scrollable, minimizable, and re-expands when
 * the agent emits a new command.
 */
@Composable
fun TerminalPanel(
    lines: List<TerminalLine>,
    minimized: Boolean,
    onToggleMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleMinimize) {
                Text(
                    text = if (minimized) stringResource(Res.string.chat_sandbox_terminal) else
                        stringResource(Res.string.chat_sandbox_terminal_minimize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!minimized) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            ) {
                Column {
                    lines.forEach { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (line.isError) MaterialTheme.colorScheme.error else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (lines.isEmpty()) {
                        Text(
                            text = "Sandbox terminal ready — the agent's commands appear here.",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One visible line of sandbox terminal output. */
data class TerminalLine(
    val text: String,
    val isError: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
)
