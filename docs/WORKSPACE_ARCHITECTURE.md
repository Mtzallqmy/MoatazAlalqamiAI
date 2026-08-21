# Moataz Workspace Architecture

`/workspace` is the only project-root contract. Workspace services depend on
`WorkspaceCommandRunner`; Android supplies `ProotWorkspaceCommandRunner`, so
Git/files/build logic contains no terminal rendering or model-provider code.

## Services

- `WorkspaceImportService`: HTTPS GitHub and uploaded ZIP/TAR imports.
- `WorkspaceFileService`: explorer, bounded reads, atomic writes and `rg` search.
- `WorkspaceGitService`: status, diff, branch, stage, unstage and local commit.
- `WorkspaceBuildService`: allow-listed Gradle, Node, Python and Cargo detection/execution.
- `WorkspaceSnapshotService`: snapshot, review diff and undo.

No Workspace service exposes push or deployment. Those are external effects
and require a separate explicitly approved integration.

## Import safety

Imports use a staging directory and atomic move, never overwrite an existing
project, and reject path traversal, symlinks, hardlinks, device/FIFO entries,
encrypted ZIP entries, excessive file count, source size and expanded size.
Private GitHub credentials use `GIT_ASKPASS` through a sensitive environment
entry; the token is absent from command strings and command results are
redacted before leaving the adapter.

## Review and undo

Before important edits, the caller creates a snapshot. Completion presents the
real diff and test evidence. Undo restores from the selected snapshot with
safe-link handling. Because undo removes files created after the snapshot, the
orchestrator classifies it as destructive and requires explicit approval.
