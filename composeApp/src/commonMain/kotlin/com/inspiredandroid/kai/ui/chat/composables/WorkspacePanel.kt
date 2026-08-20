package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.agents.OrchestratorActivityEvent
import com.inspiredandroid.kai.agents.PendingApproval
import com.inspiredandroid.kai.TerminalLine
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.agents_activity_title
import kai.composeapp.generated.resources.agents_approve
import kai.composeapp.generated.resources.agents_reject
import kai.composeapp.generated.resources.agents_no_pending
import org.jetbrains.compose.resources.stringResource

/**
 * Agentic workspace panel — tabs the user can slide through during a run:
 * the conversation, the live terminal, the sandbox file tree, and the agent
 * activity timeline with pending approvals.
 */
@Composable
fun WorkspacePanel(
    selectedTab: WorkspaceTab,
    onTabChange: (WorkspaceTab) -> Unit,
    terminalLines: List<TerminalLine>,
    terminalMinimized: Boolean,
    onTerminalToggle: () -> Unit,
    files: List<WorkspaceFileEntry>,
    activities: List<OrchestratorActivityEvent>,
    pendingApprovals: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    runRunning: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tab row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkspaceTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { onTabChange(tab) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (selectedTab) {
            WorkspaceTab.Terminal -> TerminalPanel(
                lines = terminalLines,
                minimized = terminalMinimized,
                onToggleMinimize = onTerminalToggle,
                modifier = Modifier.padding(8.dp),
            )
            WorkspaceTab.Files -> WorkspaceFilesList(files)
            WorkspaceTab.Activity -> ActivityTimeline(
                activities = activities,
                pending = pendingApprovals,
                onApprove = onApprove,
                onReject = onReject,
                runRunning = runRunning,
            )
        }
    }
}

enum class WorkspaceTab(val label: String) {
    Terminal("Terminal"),
    Files("Files"),
    Activity("Activity"),
}

data class WorkspaceFileEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
)

@Composable
private fun WorkspaceFilesList(files: List<WorkspaceFileEntry>) {
    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "The sandbox workspace is empty — files created by the agent appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(files) { entry ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = (if (entry.isDirectory) "📁 " else "📄 ") + entry.path,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun ActivityTimeline(
    activities: List<OrchestratorActivityEvent>,
    pending: List<PendingApproval>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    runRunning: Boolean,
) {
    if (!runRunning && activities.isEmpty() && pending.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "Agent activity will appear here once a run starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        pending.forEach { approval ->
            PendingApprovalRow(approval, onApprove, onReject)
            Spacer(Modifier.height(6.dp))
        }
        activities.takeLast(200).asReversed().forEach { event ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (event.type) {
                    OrchestratorActivityEvent.Type.ToolCall,
                    OrchestratorActivityEvent.Type.WaitingApproval -> {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 2.dp)
                    }
                    OrchestratorActivityEvent.Type.ToolSuccess,
                    OrchestratorActivityEvent.Type.Finished -> {
                        Text("✓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    OrchestratorActivityEvent.Type.ToolFailure,
                    OrchestratorActivityEvent.Type.LlmError -> {
                        Text("✗", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingApprovalRow(
    approval: PendingApproval,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            text = stringResource(Res.string.agents_activity_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${approval.toolName}: ${approval.argsSummary}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Text(
            text = approval.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = false, onClick = { onApprove(approval.id) }, label = { Text(stringResource(Res.string.agents_approve)) })
            FilterChip(selected = false, onClick = { onReject(approval.id) }, label = { Text(stringResource(Res.string.agents_reject)) })
        }
    }
}
