# Moataz Agent Workflow

## Contract

The orchestrator is provider-independent and executes a bounded state machine:

`Request → Planning → AwaitingApproval → Executing → Observing → Repairing → Testing → Diffing → Delivering`

Every run persists an `AgentCheckpoint` with the current phase, completed-step
count, failure count, action fingerprints and verification evidence. An active
run restored after process death becomes `Paused`; it is never presented as
still running.

## Completion evidence

Model prose cannot complete a run. Tool observations update `RunVerification`:

- a command is successful only when its real exit code is zero;
- a streaming handle without a terminal exit code is not completion evidence;
- a workspace mutation requires a later successful test and an observed diff;
- stdout, stderr and exit code are retained in bounded, redacted summaries;
- exceeding step, duration, retry, repeated-action or cost limits fails the run.

## Approval boundaries

| Risk | Examples | Policy |
|---|---|---|
| Safe read | list/read/search/status/diff | May follow the selected mode |
| Workspace write | edit, stage, local commit | Constrained to `/workspace` |
| Network | fetch or remote read | Explicit approval unless policy permits |
| Package install | apt/npm/pip/cargo install | Always explicit |
| External effect | push, deploy, publish | Always explicit |
| Destructive | delete/reset/clean | Always explicit |

Unknown tools are never auto-approved. Cancellation propagates through the
provider call and current tool coroutine. Activity events expose planning,
model deltas, approvals, tool observations, recovery and terminal status.

## Current integration boundary

The domain contracts and tests are present. Protocol-specific live capability
adapters and a complete user-facing approval surface for cross-provider
fallback remain integration work; they must not be described as verified until
CI and device probes cover them.
