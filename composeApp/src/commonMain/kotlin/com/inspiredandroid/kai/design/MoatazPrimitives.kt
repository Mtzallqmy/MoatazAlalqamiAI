package com.inspiredandroid.kai.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun MoatazButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Button(onClick = onClick, modifier = modifier) { Text(text) }

@Composable fun MoatazCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Card(modifier = modifier) { content() }

@Composable
fun MoatazChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text) },
    modifier = modifier,
)

@Composable fun MoatazTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null) =
    OutlinedTextField(value, onValueChange, modifier, label = label?.let { { Text(it) } })

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MoatazTopBar(title: String, modifier: Modifier = Modifier) = TopAppBar({ Text(title) }, modifier)

@Composable fun MoatazStatusBadge(text: String, modifier: Modifier = Modifier) =
    Surface(modifier = modifier, shape = MoatazShapes.small, color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }

@Composable fun MoatazEmptyState(title: String, detail: String, modifier: Modifier = Modifier) =
    Column(modifier.padding(24.dp)) { Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge); Text(detail) }

@Composable fun MoatazErrorPanel(title: String, detail: String, modifier: Modifier = Modifier) =
    Surface(modifier, color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer, shape = MoatazShapes.medium) {
        Column(Modifier.padding(16.dp)) { Text(title, color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer); Text(detail, color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer) }
    }

@Composable fun MoatazProgress(modifier: Modifier = Modifier) = CircularProgressIndicator(modifier)

@Composable fun MoatazTerminalSurface(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(12.dp), content: @Composable () -> Unit) =
    Surface(modifier, color = MoatazTheme.terminal.background, shape = MoatazShapes.medium) {
        Column(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
    }
