package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.ui.chat.MentionCandidate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Drop-down list of sandbox files shown above the chat composer when the user
 * types `@`. Directories are flagged with a folder icon so the user knows a
 * mention will inline everything found under it.
 */
@Composable
internal fun MentionAutocomplete(
    candidates: ImmutableList<MentionCandidate>,
    query: String,
    onSelect: (MentionCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(candidates, query) {
        val q = query.lowercase()
        if (q.isEmpty()) {
            candidates
        } else {
            candidates.filter { it.displayName.lowercase().startsWith(q) || it.path.lowercase().contains(q) }
        }
    }
    if (filtered.isEmpty()) return

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .heightIn(max = 220.dp)
            .verticalScroll(scrollState),
    ) {
        for (candidate in filtered) {
            MentionRow(
                candidate = candidate,
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
private fun MentionRow(candidate: MentionCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            modifier = Modifier.size(18.dp),
            imageVector = if (candidate.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = if (candidate.isDirectory) "directory" else null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "@${candidate.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = candidate.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** No candidates placeholder for preview composition. */
@Composable
@Suppress("unused")
internal fun MentionAutocompletePreview(onSelect: (MentionCandidate) -> Unit = {}) {
    MentionAutocomplete(
        candidates = persistentListOf(
            MentionCandidate("/root/projects/todo/app.py", "app.py", isDirectory = false),
            MentionCandidate("/root/projects/todo", "todo", isDirectory = true),
        ),
        query = "",
        onSelect = onSelect,
    )
}
